package ai.labs32.khaata

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Channels must exist before any notification is posted, and creating them is cheap.
        NotificationChannels.createAll(this)

        observeAppLifecycle()

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
    }
}
