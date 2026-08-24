package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.OccurrenceKind
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.ScheduledOccurrence
import ai.labs32.khaata.core.model.Subscription
import java.time.LocalDate
import java.time.YearMonth

/**
 * Expands recurrence rules into dates.
 *
 * The whole design rests on schedules being computed rather than stored, so this is the piece
 * that has to be right. The two cases that break naive implementations are both handled here and
 * both tested:
 *
 *  - **Month-end anchoring.** A rule anchored on the 31st must fire on the 28th in February and
 *    then go back to the 31st in March. Naively adding a month and clamping permanently drags the
 *    date to the 28th, so every subsequent occurrence is wrong. Occurrences are therefore always
 *    derived from the original anchor, never from the previously clamped date.
 *  - **Intervals.** "Every 3 months" from a 15 January anchor must land on 15 April, not on the
 *    15th of every month.
 */
object RecurrenceCalculator {

    /** Occurrences of [rule] within `[from, to]`, inclusive, oldest first. */
    fun occurrencesBetween(
        rule: RecurringRule,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        if (!rule.isActive) return emptyList()
        if (to.isBefore(rule.startDate)) return emptyList()

        val hardEnd = rule.endDate?.let { if (it.isBefore(to)) it else to } ?: to
        if (hardEnd.isBefore(from)) return emptyList()

        val results = ArrayList<LocalDate>()
        var index = 0
        while (true) {
            if (rule.maxOccurrences != null && index >= rule.maxOccurrences) break
            val date = occurrenceAt(rule, index) ?: break
            if (date.isAfter(hardEnd)) break
            if (!date.isBefore(from)) results += date
            index++
            // Defensive bound: a daily rule over a decade is ~3,650 rows, so anything beyond this
            // means a corrupt interval rather than a legitimate schedule.
            if (index > MAX_OCCURRENCES) break
        }
        return results
    }

    /**
     * The [index]-th occurrence of [rule], counting the start date as index 0.
     *
     * Always derived from [RecurringRule.startDate] so month-end clamping never compounds.
     */
    fun occurrenceAt(rule: RecurringRule, index: Int): LocalDate? {
        if (index < 0) return null
        val step = rule.interval.toLong() * index
        val anchor = rule.startDate
        return when (rule.frequency) {
            Frequency.DAILY -> anchor.plusDays(step)
            Frequency.WEEKLY -> anchor.plusWeeks(step)
            Frequency.FORTNIGHTLY -> anchor.plusWeeks(step * 2)
            Frequency.MONTHLY -> addMonthsKeepingAnchorDay(anchor, step)
            Frequency.QUARTERLY -> addMonthsKeepingAnchorDay(anchor, step * 3)
            Frequency.HALF_YEARLY -> addMonthsKeepingAnchorDay(anchor, step * 6)
            Frequency.YEARLY -> addMonthsKeepingAnchorDay(anchor, step * 12)
        }
    }

    /**
     * Adds [months] to [anchor], re-deriving the day from the anchor each time.
     *
     * `LocalDate.plusMonths` already clamps, but chaining it drifts. Computing from the anchor
     * means a 31st rule reads 31 Jan → 28 Feb → 31 Mar rather than 31 Jan → 28 Feb → 28 Mar.
     */
    private fun addMonthsKeepingAnchorDay(anchor: LocalDate, months: Long): LocalDate {
        val targetMonth = YearMonth.from(anchor).plusMonths(months)
        return targetMonth.atDay(anchor.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
    }

    /** The first occurrence strictly after [after], or null once the rule has finished. */
    fun nextOccurrenceAfter(rule: RecurringRule, after: LocalDate): LocalDate? {
        if (!rule.isActive) return null
        var index = 0
        while (index <= MAX_OCCURRENCES) {
            if (rule.maxOccurrences != null && index >= rule.maxOccurrences) return null
            val date = occurrenceAt(rule, index) ?: return null
            if (rule.endDate != null && date.isAfter(rule.endDate)) return null
            if (date.isAfter(after)) return date
            index++
        }
        return null
    }

    /**
     * Occurrences of [rule] that are due but not yet written to the ledger.
     *
     * Anything on or before [asOf] that postdates [RecurringRule.lastPostedOn]. Keying off the
     * last posted date rather than a timestamp makes posting idempotent: running the worker twice
     * in a day cannot create duplicate rent entries.
     */
    fun duePostings(rule: RecurringRule, asOf: LocalDate): List<LocalDate> {
        val from = rule.lastPostedOn?.plusDays(1) ?: rule.startDate
        if (from.isAfter(asOf)) return emptyList()
        return occurrencesBetween(rule, from, asOf)
    }

    /** Upcoming occurrences of [rules] within the next [days], as displayable reminders. */
    fun upcomingFromRules(
        rules: List<RecurringRule>,
        asOf: LocalDate,
        days: Int = 30,
    ): List<ScheduledOccurrence> {
        require(days > 0) { "Lookahead must be positive, got $days" }
        val horizon = asOf.plusDays(days.toLong())
        return rules.filter { it.isActive }.flatMap { rule ->
            occurrencesBetween(rule, asOf, horizon).map { date ->
                ScheduledOccurrence(
                    ruleId = rule.id,
                    name = rule.name,
                    type = rule.type,
                    amount = rule.amount,
                    dueOn = date,
                    accountId = rule.accountId,
                    categoryId = rule.categoryId,
                    kind = OccurrenceKind.RECURRING,
                )
            }
        }.sortedBy { it.dueOn }
    }

    /**
     * Upcoming subscription charges within the next [days].
     *
     * Subscriptions carry their own [Subscription.nextPaymentDate] rather than a start anchor,
     * because a user editing a subscription is telling us when the next charge lands — that is
     * the fact they know, and re-deriving it from a historic start date would override them.
     */
    fun upcomingFromSubscriptions(
        subscriptions: List<Subscription>,
        asOf: LocalDate,
        days: Int = 30,
    ): List<ScheduledOccurrence> {
        require(days > 0) { "Lookahead must be positive, got $days" }
        val horizon = asOf.plusDays(days.toLong())
        return subscriptions.filter { it.isActive && it.cancelledOn == null }.flatMap { sub ->
            generateSubscriptionDates(sub, asOf, horizon).map { date ->
                ScheduledOccurrence(
                    ruleId = sub.id,
                    name = sub.name,
                    type = ai.labs32.khaata.core.model.TransactionType.EXPENSE,
                    amount = sub.amount,
                    dueOn = date,
                    accountId = sub.accountId ?: "",
                    categoryId = sub.categoryId,
                    kind = OccurrenceKind.SUBSCRIPTION,
                )
            }
        }.sortedBy { it.dueOn }
    }

    private fun generateSubscriptionDates(
        subscription: Subscription,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        val dates = ArrayList<LocalDate>()
        val anchor = subscription.nextPaymentDate
        var index = 0
        while (index <= MAX_OCCURRENCES) {
            val date = when (subscription.cycle) {
                Frequency.DAILY -> anchor.plusDays(index.toLong())
                Frequency.WEEKLY -> anchor.plusWeeks(index.toLong())
                Frequency.FORTNIGHTLY -> anchor.plusWeeks(index * 2L)
                Frequency.MONTHLY -> addMonthsKeepingAnchorDay(anchor, index.toLong())
                Frequency.QUARTERLY -> addMonthsKeepingAnchorDay(anchor, index * 3L)
                Frequency.HALF_YEARLY -> addMonthsKeepingAnchorDay(anchor, index * 6L)
                Frequency.YEARLY -> addMonthsKeepingAnchorDay(anchor, index * 12L)
            }
            if (date.isAfter(to)) break
            if (!date.isBefore(from)) dates += date
            index++
        }
        return dates
    }

    /**
     * Rolls a subscription's next payment date forward past [asOf].
     *
     * Called after a charge is recorded so the card never shows a date in the past.
     */
    fun advanceSubscription(subscription: Subscription, asOf: LocalDate): LocalDate {
        var next = subscription.nextPaymentDate
        var index = 0
        while (next.isBefore(asOf) && index < MAX_OCCURRENCES) {
            index++
            next = when (subscription.cycle) {
                Frequency.DAILY -> subscription.nextPaymentDate.plusDays(index.toLong())
                Frequency.WEEKLY -> subscription.nextPaymentDate.plusWeeks(index.toLong())
                Frequency.FORTNIGHTLY -> subscription.nextPaymentDate.plusWeeks(index * 2L)
                Frequency.MONTHLY ->
                    addMonthsKeepingAnchorDay(subscription.nextPaymentDate, index.toLong())
                Frequency.QUARTERLY ->
                    addMonthsKeepingAnchorDay(subscription.nextPaymentDate, index * 3L)
                Frequency.HALF_YEARLY ->
                    addMonthsKeepingAnchorDay(subscription.nextPaymentDate, index * 6L)
                Frequency.YEARLY ->
                    addMonthsKeepingAnchorDay(subscription.nextPaymentDate, index * 12L)
            }
        }
        return next
    }

    /** Bound on generated occurrences, to keep a corrupt rule from producing an unbounded list. */
    private const val MAX_OCCURRENCES = 5000
}
