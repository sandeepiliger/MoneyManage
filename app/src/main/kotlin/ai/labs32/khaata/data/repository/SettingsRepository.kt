package ai.labs32.khaata.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.model.AppSettings
import ai.labs32.khaata.core.model.DashboardCard
import ai.labs32.khaata.core.model.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "khaata_settings",
)

/**
 * Local preferences.
 *
 * These live in DataStore rather than in the database because they are written far more often
 * than financial records, are not part of a backup's financial content, and must be readable
 * before the database is opened (the theme and lock mode are needed on the very first frame).
 *
 * No preference here holds a secret. The PIN is never stored in DataStore — see
 * `AppLockManager`, which keeps only a salted hash in encrypted storage.
 *
 * Every privacy-affecting default is the private one: analytics, crash reporting, cloud AI and
 * transaction import all start off.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The current settings.
     *
     * A corrupt preferences file yields defaults rather than an exception: settings are not worth
     * failing app launch over, and the user can simply set them again.
     */
    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { it.toAppSettings() }

    suspend fun current(): AppSettings = settings.first()

    // ---- Appearance --------------------------------------------------------------------------

    suspend fun setTheme(theme: ThemePreference) = edit { it[Keys.THEME] = theme.name }

    suspend fun setDashboardCardOrder(order: List<DashboardCard>) =
        edit { it[Keys.DASHBOARD_ORDER] = order.joinToString(",") { card -> card.name } }

    suspend fun setDashboardCardHidden(card: DashboardCard, hidden: Boolean) = edit { prefs ->
        val current = prefs[Keys.HIDDEN_CARDS].orEmpty().toMutableSet()
        if (hidden) current += card.name else current -= card.name
        prefs[Keys.HIDDEN_CARDS] = current
    }

    // ---- Security ----------------------------------------------------------------------------

    suspend fun setLockMode(mode: AppLockMode) = edit { it[Keys.LOCK_MODE] = mode.name }

    suspend fun setLockAfterSeconds(seconds: Int) =
        edit { it[Keys.LOCK_AFTER_SECONDS] = seconds.coerceIn(0, 3600) }

    suspend fun setHideAmountsWhenLocked(hide: Boolean) =
        edit { it[Keys.HIDE_AMOUNTS] = hide }

    // ---- Privacy -----------------------------------------------------------------------------

    suspend fun setAnalyticsEnabled(enabled: Boolean) = edit { it[Keys.ANALYTICS] = enabled }

    suspend fun setCrashReportingEnabled(enabled: Boolean) = edit { it[Keys.CRASH_REPORTING] = enabled }

    suspend fun setCloudAiEnabled(enabled: Boolean) = edit { it[Keys.CLOUD_AI] = enabled }

    suspend fun setSmsImportEnabled(enabled: Boolean) = edit { it[Keys.SMS_IMPORT] = enabled }

    suspend fun setNotificationImportEnabled(enabled: Boolean) =
        edit { it[Keys.NOTIFICATION_IMPORT] = enabled }

    suspend fun setPrivacyDashboardSeen(seen: Boolean) = edit { it[Keys.PRIVACY_SEEN] = seen }

    suspend fun setSmsInboxScanned(scanned: Boolean) = edit { it[Keys.SMS_INBOX_SCANNED] = scanned }

    // ---- Notifications -----------------------------------------------------------------------

    suspend fun setBudgetAlertsEnabled(enabled: Boolean) = edit { it[Keys.BUDGET_ALERTS] = enabled }

    suspend fun setBillRemindersEnabled(enabled: Boolean) = edit { it[Keys.BILL_REMINDERS] = enabled }

    suspend fun setDailyReminderEnabled(enabled: Boolean) = edit { it[Keys.DAILY_REMINDER] = enabled }

    suspend fun setDailyReminderTime(minuteOfDay: Int) =
        edit { it[Keys.DAILY_REMINDER_TIME] = minuteOfDay.coerceIn(0, 1439) }

    /** Clears every preference — part of "delete all my data". */
    suspend fun resetAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val order = this[Keys.DASHBOARD_ORDER]
            ?.split(",")
            ?.mapNotNull { name -> DashboardCard.entries.firstOrNull { it.name == name } }
            ?.takeIf { it.isNotEmpty() }
            // A card added in a later version would be missing from a stored order, so anything
            // unknown to the saved list is appended rather than silently dropped.
            ?.let { stored -> stored + DashboardCard.DEFAULT_ORDER.filterNot { it in stored } }
            ?: DashboardCard.DEFAULT_ORDER

        return AppSettings(
            theme = this[Keys.THEME]
                ?.let { name -> ThemePreference.entries.firstOrNull { it.name == name } }
                ?: ThemePreference.SYSTEM,
            lockMode = this[Keys.LOCK_MODE]
                ?.let { name -> AppLockMode.entries.firstOrNull { it.name == name } }
                ?: AppLockMode.OFF,
            lockAfterSeconds = this[Keys.LOCK_AFTER_SECONDS] ?: 30,
            dashboardCardOrder = order,
            hiddenDashboardCards = this[Keys.HIDDEN_CARDS]
                .orEmpty()
                .mapNotNull { name -> DashboardCard.entries.firstOrNull { it.name == name } }
                .toSet(),
            analyticsEnabled = this[Keys.ANALYTICS] ?: false,
            crashReportingEnabled = this[Keys.CRASH_REPORTING] ?: false,
            cloudAiEnabled = this[Keys.CLOUD_AI] ?: false,
            smsImportEnabled = this[Keys.SMS_IMPORT] ?: false,
            notificationImportEnabled = this[Keys.NOTIFICATION_IMPORT] ?: false,
            budgetAlertsEnabled = this[Keys.BUDGET_ALERTS] ?: true,
            billRemindersEnabled = this[Keys.BILL_REMINDERS] ?: true,
            dailyReminderEnabled = this[Keys.DAILY_REMINDER] ?: false,
            dailyReminderMinuteOfDay = this[Keys.DAILY_REMINDER_TIME] ?: (21 * 60),
            hideAmountsWhenLocked = this[Keys.HIDE_AMOUNTS] ?: true,
            hasSeenPrivacyDashboard = this[Keys.PRIVACY_SEEN] ?: false,
            hasScannedSmsInbox = this[Keys.SMS_INBOX_SCANNED] ?: false,
        )
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val LOCK_MODE = stringPreferencesKey("lock_mode")
        val LOCK_AFTER_SECONDS = intPreferencesKey("lock_after_seconds")
        val HIDE_AMOUNTS = booleanPreferencesKey("hide_amounts_when_locked")
        val DASHBOARD_ORDER = stringPreferencesKey("dashboard_order")
        val HIDDEN_CARDS = stringSetPreferencesKey("hidden_dashboard_cards")
        val ANALYTICS = booleanPreferencesKey("analytics_enabled")
        val CRASH_REPORTING = booleanPreferencesKey("crash_reporting_enabled")
        val CLOUD_AI = booleanPreferencesKey("cloud_ai_enabled")
        val SMS_IMPORT = booleanPreferencesKey("sms_import_enabled")
        val NOTIFICATION_IMPORT = booleanPreferencesKey("notification_import_enabled")
        val BUDGET_ALERTS = booleanPreferencesKey("budget_alerts_enabled")
        val BILL_REMINDERS = booleanPreferencesKey("bill_reminders_enabled")
        val DAILY_REMINDER = booleanPreferencesKey("daily_reminder_enabled")
        val DAILY_REMINDER_TIME = intPreferencesKey("daily_reminder_minute")
        val PRIVACY_SEEN = booleanPreferencesKey("privacy_dashboard_seen")
        val SMS_INBOX_SCANNED = booleanPreferencesKey("sms_inbox_scanned")
    }
}
