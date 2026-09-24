package ai.labs32.khaata

import android.app.Application
import android.content.res.Configuration as AndroidConfiguration
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.locale.AppLocales
import ai.labs32.khaata.core.locale.BuiltInCategoryNames
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.AppSettings
import ai.labs32.khaata.core.notifications.KhaataNotifier
import ai.labs32.khaata.core.notifications.NotificationChannels
import ai.labs32.khaata.core.security.AppLockManager
import ai.labs32.khaata.core.sms.SmsPermission
import ai.labs32.khaata.core.sms.SmsTransactionReceiver
import ai.labs32.khaata.core.work.WorkScheduler
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.EntitlementRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.data.repository.TransactionRepository
import ai.labs32.khaata.widget.AppShortcuts
import ai.labs32.khaata.widget.SpendWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Startup work is deliberately minimal and none of it blocks the first frame. Everything here
 * either has to happen before any screen renders (notification channels, the lock state) or runs
 * off the main thread afterwards (seeding, entitlement refresh, scheduling).
 *
 * Nothing that touches the network is started at launch: the ad SDK is initialised only when an
 * ad is actually wanted, and billing connects on demand.
 */
@HiltAndroidApp
class KhaataApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var categoryRepository: CategoryRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var entitlementRepository: EntitlementRepository
    @Inject lateinit var analyticsProvider: AnalyticsProvider
    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var notifier: KhaataNotifier
    @Inject lateinit var transactionRepository: TransactionRepository

    /**
     * Scope for startup work.
     *
     * A supervisor job so one failing task cannot cancel the others, and an exception handler so
     * a failure during startup is logged rather than crashing the process before the user has
     * seen anything.
     */
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error ->
                KhaataLog.e(TAG, "Startup task failed", error)
            },
    )

    /** A system change -- a new phone language, a font size -- resets the resources; reapply. */
    override fun onConfigurationChanged(newConfig: AndroidConfiguration) {
        super.onConfigurationChanged(newConfig)
        AppLocales.applyStored(this)
        // A new language or dark mode: the widget and the launcher shortcuts are drawn from
        // resources, so they redraw in it.
        SpendWidget.refresh(this)
        runCatching { AppShortcuts.publish(this) }
            .onFailure { KhaataLog.w(TAG, "Launcher shortcuts unavailable") }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()

        // The in-app language, before anything reads a string: notification channels below are
        // named in it.
        AppLocales.applyStored(this)
        BuiltInCategoryNames.install(this)

        // Channels must exist before any notification is posted, and creating them is cheap.
        NotificationChannels.createAll(this)

        observeAppLifecycle()
        keepWidgetCurrent()

        applicationScope.launch {
            val settings = settingsRepository.settings.first()

            // The lock is applied before the first screen so a protected app never flashes the
            // dashboard on the way to the lock screen.
            appLockManager.applyInitialState(settings.lockMode)

            // Analytics stays off until the stored consent says otherwise.
            analyticsProvider.setEnabled(settings.analyticsEnabled)
            analyticsProvider.track(AnalyticsEvent.AppOpened)

            // Adds any categories and merchant rules a new version introduced, without
            // overwriting the user's own edits.
            runCatching { categoryRepository.seedIfEmpty() }
                .onFailure { KhaataLog.e(TAG, "Category seeding failed", it) }

            // Re-read purchases so a reinstall or a new device recovers what was paid for.
            runCatching { entitlementRepository.refresh() }
                .onFailure { KhaataLog.w(TAG, "Entitlement refresh unavailable") }

            reconcileSmsReceiver(settings.smsImportEnabled)
            reconcileNotificationSettings(settings)

            workScheduler.scheduleAll(settings)

            runCatching { AppShortcuts.publish(this@KhaataApplication) }
                .onFailure { KhaataLog.w(TAG, "Launcher shortcuts unavailable") }

            // Deliberately no inbox scan here. Reading past messages is something the user asks
            // for (onboarding, or the privacy dashboard), never something a launch does to them:
            // a silent scan landed a year of history on top of a balance they had just stated.
        }
    }

    /**
     * Redraws the home-screen widget whenever this month's spending or the privacy settings
     * change: a spend added in the app, by a bank message, or deleted shows on the home screen
     * at once rather than at the widget's next half-hourly refresh. Cheap when no widget is
     * placed -- the refresh finds none and returns.
     */
    private fun keepWidgetCurrent() {
        applicationScope.launch {
            combine(
                transactionRepository.observeTotalSpend(
                    DateRange.ofMonth(java.time.LocalDate.now()),
                    CurrencyCode.DEFAULT,
                ),
                settingsRepository.settings,
            ) { spend, settings -> Triple(spend, settings.lockMode, settings.hideAmountsWhenLocked) }
                .distinctUntilChanged()
                .collectLatest {
                    // Coalesces a burst of writes (an import, a restore) into one redraw.
                    delay(WIDGET_REFRESH_DELAY_MS)
                    SpendWidget.refresh(this@KhaataApplication)
                }
        }
    }

    /**
     * Brings the SMS receiver's registration back in line with the stored setting and the
     * permission that setting depends on.
     *
     * Three things can pull them apart: the permission being revoked from Android settings while
     * the app was not running, an install whose component state starts at the manifest default of
     * disabled while the setting survives, and any earlier build that persisted the flag without
     * registering the receiver. In each case the user is left with a switch that reads "on" and a
     * feature that quietly receives nothing, so the setting is corrected here rather than trusted.
     */
    private suspend fun reconcileSmsReceiver(smsImportEnabled: Boolean) {
        val shouldReceive = smsImportEnabled && SmsPermission.isGranted(this)
        if (smsImportEnabled && !shouldReceive) {
            settingsRepository.setSmsImportEnabled(false)
        }
        runCatching { SmsTransactionReceiver.setEnabled(this, shouldReceive) }
            .onFailure { KhaataLog.e(TAG, "Could not reconcile the SMS receiver", it) }
    }

    /**
     * Stands the three notification-backed settings down if POST_NOTIFICATIONS has been revoked.
     *
     * Every place that actually posts one of these already checks [KhaataNotifier.hasPermission]
     * first, so a revoked permission was never going to cause a leaked notification -- but without
     * this, the switch in Settings would keep reading "on" for a feature that has gone silently
     * dead, which is the same failure mode as the SMS toggle above.
     */
    private suspend fun reconcileNotificationSettings(settings: AppSettings) {
        if (notifier.hasPermission()) return
        if (settings.budgetAlertsEnabled) settingsRepository.setBudgetAlertsEnabled(false)
        if (settings.billRemindersEnabled) settingsRepository.setBillRemindersEnabled(false)
        if (settings.dailyReminderEnabled) settingsRepository.setDailyReminderEnabled(false)
    }

    /**
     * Tracks foreground and background transitions for the app lock.
     *
     * Process lifecycle rather than activity lifecycle, so a configuration change or a
     * single-activity navigation does not read as the app being backgrounded.
     */
    private fun observeAppLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    appLockManager.onBackgrounded()
                }

                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch {
                        val settings = settingsRepository.settings.first()
                        appLockManager.onForegrounded(settings.lockMode, settings.lockAfterSeconds)
                    }
                }
            },
        )
    }

    private companion object {
        const val TAG = "KhaataApplication"
        const val WIDGET_REFRESH_DELAY_MS = 500L
    }
}
