package ai.labs32.khaata.core.model

import ai.labs32.khaata.core.common.InstantSerializer
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The single local user of this app.
 *
 * There is no account system and no server-side identity — a profile is a name and a set of
 * preferences held on the device. Nothing here is required to use the app.
 */
@Serializable
data class UserProfile(
    val id: String = SINGLETON_ID,
    val displayName: String? = null,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val languageTag: String = "en",
    /** Declared monthly income, used to seed budgets and compute a savings rate. */
    val monthlyIncome: Money? = null,
    /** Day the user's financial month starts — many salaried users think in "salary to salary". */
    val monthStartDay: Int = 1,
    val hasCompletedOnboarding: Boolean = false,
    val isDemoMode: Boolean = false,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant = Instant.EPOCH,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant = Instant.EPOCH,
) {
    init {
        require(monthStartDay in 1..28) {
            // Capped at 28 so every month has the day; 29-31 would silently shift some months.
            "Month start day must be 1-28, got $monthStartDay"
        }
        require(monthlyIncome == null || !monthlyIncome.isNegative) {
            "Monthly income cannot be negative"
        }
    }

    companion object {
        /** The profile row's fixed id — there is exactly one profile per install. */
        const val SINGLETON_ID = "local-user"
    }
}

/** Which theme the app renders in. */
@Serializable
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** How the app is locked when it goes to the background. */
@Serializable
enum class AppLockMode {
    /** No lock. */
    OFF,

    /** Device biometric, with the device credential as fallback. */
    BIOMETRIC,

    /** A PIN specific to this app. */
    PIN,
}

/**
 * Local, non-financial preferences.
 *
 * Kept out of [UserProfile] because these are written far more often (every toggle) and are not
 * part of a data backup's financial record.
 */
@Serializable
data class AppSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val lockMode: AppLockMode = AppLockMode.OFF,
    val lockAfterSeconds: Int = 30,
    val dashboardCardOrder: List<DashboardCard> = DashboardCard.DEFAULT_ORDER,
    val hiddenDashboardCards: Set<DashboardCard> = emptySet(),
    /** All of the following default to the privacy-preserving choice. */
    val analyticsEnabled: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val cloudAiEnabled: Boolean = false,
    val smsImportEnabled: Boolean = false,
    val notificationImportEnabled: Boolean = false,
    val budgetAlertsEnabled: Boolean = true,
    val billRemindersEnabled: Boolean = true,
    val dailyReminderEnabled: Boolean = false,
    /** Minutes past midnight for the daily "log your spends" nudge. */
    val dailyReminderMinuteOfDay: Int = 21 * 60,
    val hideAmountsWhenLocked: Boolean = true,
    val hasSeenPrivacyDashboard: Boolean = false,
    /**
     * Whether the existing SMS inbox has already been scanned once.
     *
     * The scan is a one-off catch-up, not something to repeat on every launch: it walks a year of
     * messages, and re-running it would re-parse thousands of already-imported rows to discover
     * nothing new.
     */
    val hasScannedSmsInbox: Boolean = false,
) {
    init {
        require(lockAfterSeconds in 0..3600) {
            "Auto-lock delay must be 0-3600 seconds, got $lockAfterSeconds"
        }
        require(dailyReminderMinuteOfDay in 0..1439) {
            "Reminder time must be a minute of the day, got $dailyReminderMinuteOfDay"
        }
    }
}

/**
 * The cards the dashboard can show, in the order they appear by default.
 *
 * The order is a user preference rather than a fixed layout: someone servicing three EMIs wants
 * upcoming payments at the top, while someone building a habit wants recent transactions there.
 */
@Serializable
enum class DashboardCard {
    SPENDING_OVERVIEW,
    BUDGET_PROGRESS,
    UPCOMING_PAYMENTS,
    CATEGORY_BREAKDOWN,
    RECENT_TRANSACTIONS,
    AI_INSIGHT,
    GOALS,
    ACCOUNTS,
    SUBSCRIPTIONS,
    NET_WORTH_TREND,
    ;

    companion object {
        val DEFAULT_ORDER: List<DashboardCard> = listOf(
            SPENDING_OVERVIEW,
            AI_INSIGHT,
            BUDGET_PROGRESS,
            UPCOMING_PAYMENTS,
            CATEGORY_BREAKDOWN,
            RECENT_TRANSACTIONS,
            GOALS,
            ACCOUNTS,
            SUBSCRIPTIONS,
            NET_WORTH_TREND,
        )
    }
}
