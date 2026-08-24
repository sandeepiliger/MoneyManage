package ai.labs32.khaata.core.model

import ai.labs32.khaata.core.common.LocalDateSerializer
import ai.labs32.khaata.core.money.Money
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A transaction that repeats — salary, rent, EMI, SIP, insurance, utilities.
 *
 * Like [Budget] this is stored as a rule rather than a materialised series. Occurrences are
 * computed on demand for reminders and projections, and only actually written to the ledger when
 * they are posted (automatically if [autoPost], otherwise when the user confirms).
 */
@Serializable
data class RecurringRule(
    val id: String,
    val name: String,
    val type: TransactionType,
    val amount: Money,
    val accountId: String,
    val transferAccountId: String? = null,
    val categoryId: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    val frequency: Frequency,
    /** Every N periods: 1 = monthly, 3 = quarterly when [frequency] is MONTHLY. */
    val interval: Int = 1,
    /** First occurrence. All later occurrences are derived from it. */
    @Serializable(with = LocalDateSerializer::class) val startDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val endDate: LocalDate? = null,
    /** Stops after this many occurrences. Null means unlimited. */
    val maxOccurrences: Int? = null,
    /** The last date we generated a ledger row for, so posting is idempotent. */
    @Serializable(with = LocalDateSerializer::class) val lastPostedOn: LocalDate? = null,
    /**
     * Writes the transaction automatically on its due date.
     *
     * Off by default. Auto-posting a rent payment that did not actually leave the account
     * produces a balance the user cannot reconcile, and rebuilding their trust after that costs
     * more than the tap it saved.
     */
    val autoPost: Boolean = false,
    val reminderDaysBefore: Int = 1,
    val isActive: Boolean = true,
) {
    init {
        require(amount.isPositive) { "A recurring amount must be positive, got $amount" }
        require(interval >= 1) { "Recurrence interval must be at least 1, got $interval" }
        require(reminderDaysBefore in 0..30) {
            "Reminder lead time must be 0-30 days, got $reminderDaysBefore"
        }
        require(maxOccurrences == null || maxOccurrences > 0) {
            "maxOccurrences must be positive when set, got $maxOccurrences"
        }
        require(endDate == null || !endDate.isBefore(startDate)) {
            "Recurrence end $endDate precedes start $startDate"
        }
        if (type == TransactionType.TRANSFER) {
            require(transferAccountId != null) { "A recurring transfer needs a destination account" }
            require(transferAccountId != accountId) {
                "A recurring transfer must move money between two different accounts"
            }
        }
    }
}

/**
 * How often something repeats.
 *
 * [approximateMonthsPerOccurrence] lets subscriptions of mixed cycles be normalised to a
 * comparable monthly cost without pretending the conversion is exact.
 */
@Serializable
enum class Frequency(val approximateMonthsPerOccurrence: Double) {
    DAILY(1.0 / 30.0),
    WEEKLY(1.0 / 4.345),
    FORTNIGHTLY(0.5),
    MONTHLY(1.0),
    QUARTERLY(3.0),
    HALF_YEARLY(6.0),
    YEARLY(12.0),
    ;

    /** Occurrences per year, used to annualise a subscription cost. */
    val occurrencesPerYear: Double get() = 12.0 / approximateMonthsPerOccurrence
}

/** A computed future occurrence of a rule. Never persisted — always derived. */
data class ScheduledOccurrence(
    val ruleId: String,
    val name: String,
    val type: TransactionType,
    val amount: Money,
    val dueOn: LocalDate,
    val accountId: String,
    val categoryId: String?,
    val kind: OccurrenceKind,
) {
    fun daysUntil(today: LocalDate): Long =
        java.time.temporal.ChronoUnit.DAYS.between(today, dueOn)

    fun isOverdue(today: LocalDate): Boolean = dueOn.isBefore(today)
}

/** What produced an upcoming payment, so the UI can route the user to the right screen. */
enum class OccurrenceKind { RECURRING, SUBSCRIPTION, CREDIT_CARD_BILL, LOAN_EMI, GOAL_CONTRIBUTION }

/** Weekday helper kept next to the recurrence model rather than duplicated at call sites. */
fun LocalDate.withDayOfWeekOnOrAfter(target: DayOfWeek): LocalDate {
    var candidate = this
    while (candidate.dayOfWeek != target) candidate = candidate.plusDays(1)
    return candidate
}
