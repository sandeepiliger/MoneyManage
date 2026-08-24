package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.BudgetPeriod
import ai.labs32.khaata.core.model.BudgetStatus
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyMath
import ai.labs32.khaata.core.money.sumOfMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Works out where a budget stands.
 *
 * The reference apps mostly answer "how much have you spent?". The more useful question is
 * "are you going to make it?" — a user who is 60% through a budget on day 10 of the month is in
 * trouble even though nothing is red yet. [BudgetProgress.projectedSpend] and
 * [BudgetStatus.PROJECTED_OVER] exist to surface that while there is still time to act.
 *
 * Every figure here is arithmetic the user could redo on paper. Nothing is a model output.
 */
object BudgetCalculator {

    /**
     * The period of [budget] that contains [date].
     *
     * Periods are derived from the anchor date rather than stored, so a budget is correct in
     * every future month without anything having to generate rows in advance.
     */
    fun periodContaining(budget: Budget, date: LocalDate): DateRange = when (budget.period) {
        BudgetPeriod.MONTHLY -> monthlyPeriodContaining(budget, date)
        BudgetPeriod.WEEKLY -> weeklyPeriodContaining(budget, date)
    }

    private fun monthlyPeriodContaining(budget: Budget, date: LocalDate): DateRange {
        val anchorDay = budget.anchorDate.dayOfMonth
        if (anchorDay == 1) return DateRange.ofMonth(date)

        // A budget anchored mid-month runs anchor-day to anchor-day, which is how salaried users
        // who are paid on the 1st but budget from the 5th actually think about their month.
        val candidate = clampToMonth(date.year, date.monthValue, anchorDay)
        val start = if (!date.isBefore(candidate)) {
            candidate
        } else {
            val previous = date.minusMonths(1)
            clampToMonth(previous.year, previous.monthValue, anchorDay)
        }
        val nextMonth = start.plusMonths(1)
        val end = clampToMonth(nextMonth.year, nextMonth.monthValue, anchorDay).minusDays(1)
        return DateRange(start, end)
    }

    private fun weeklyPeriodContaining(budget: Budget, date: LocalDate): DateRange {
        val daysSinceAnchor = ChronoUnit.DAYS.between(budget.anchorDate, date)
        // Floor division so dates before the anchor land in the correct earlier week.
        val weeksElapsed = Math.floorDiv(daysSinceAnchor, 7L)
        val start = budget.anchorDate.plusDays(weeksElapsed * 7)
        return DateRange(start, start.plusDays(6))
    }

    /** Clamps a day-of-month to a month that may be shorter — the 31st becomes the 28th in Feb. */
    private fun clampToMonth(year: Int, month: Int, day: Int): LocalDate {
        val yearMonth = java.time.YearMonth.of(year, month)
        return yearMonth.atDay(day.coerceAtMost(yearMonth.lengthOfMonth()))
    }

    /**
     * Whether a transaction counts against [budget].
     *
     * Transfers never count. Moving ₹20,000 from savings to current is not spending, and
     * counting it as such makes the whole budget meaningless.
     */
    fun matches(
        budget: Budget,
        transaction: Transaction,
        categoryRollup: Map<String, String>,
        period: DateRange,
    ): Boolean {
        if (!transaction.countsAsSpending) return false
        if (transaction.occurredOn !in period) return false
        if (budget.accountIds.isNotEmpty() && transaction.accountId !in budget.accountIds) return false
        if (budget.isOverallLimit) return true

        val categoryId = transaction.categoryId ?: return false
        if (categoryId in budget.categoryIds) return true
        // Spend on a subcategory rolls up to the parent, so budgeting "Food" also covers "Swiggy".
        val parentId = categoryRollup[categoryId]
        return parentId != null && parentId in budget.categoryIds
    }

    /**
     * Evaluates [budget] against [transactions] as of [asOf].
     *
     * @param categoryRollup subcategory id → parent category id, so subcategory spend is counted
     *   against a parent-level budget. Build it with [buildCategoryRollup].
     */
    fun evaluate(
        budget: Budget,
        transactions: List<Transaction>,
        asOf: LocalDate,
        categoryRollup: Map<String, String> = emptyMap(),
        /** Unspent amount carried in from the previous period, when [Budget.rollsOver]. */
        carriedOver: Money = Money.zero(budget.limit.currency),
    ): BudgetProgress {
        val period = periodContaining(budget, asOf)
        val currency = budget.limit.currency
        val effectiveLimit = if (budget.rollsOver) budget.limit + carriedOver else budget.limit

        val matching = transactions.filter { matches(budget, it, categoryRollup, period) }
        val spent = matching.sumOfMoney(currency) { it.amount }
        val remaining = effectiveLimit - spent

        val elapsedDays = period.elapsedDays(asOf)
        val totalDays = period.dayCount
        val projected = projectSpend(spent, elapsedDays, totalDays, currency)

        val percentUsed = spent.percentageOf(effectiveLimit) ?: BigDecimal.ZERO
        val status = classify(
            spent = spent,
            limit = effectiveLimit,
            projected = projected,
            percentUsed = percentUsed,
            alertThreshold = budget.alertThresholdPercent,
            periodStarted = elapsedDays > 0,
        )

        return BudgetProgress(
            budget = budget,
            period = period,
            limit = effectiveLimit,
            carriedOver = carriedOver,
            spent = spent,
            remaining = remaining,
            percentUsed = percentUsed,
            projectedSpend = projected,
            status = status,
            transactionCount = matching.size,
            daysElapsed = elapsedDays,
            daysRemaining = period.remainingDays(asOf),
            safeDailySpend = safeDailySpend(remaining, period.remainingDays(asOf), currency),
        )
    }

    /**
     * Straight-line projection of period-end spend from the run rate so far.
     *
     * Deliberately simple: the user can verify it in their head ("₹6,000 in 10 days, so about
     * ₹18,000 for the month"). A cleverer model would be harder to trust and no more accurate
     * on a single month of noisy data.
     */
    private fun projectSpend(
        spent: Money,
        elapsedDays: Int,
        totalDays: Int,
        currency: ai.labs32.khaata.core.money.CurrencyCode,
    ): Money {
        if (elapsedDays <= 0) return Money.zero(currency)
        if (elapsedDays >= totalDays) return spent
        val scale = BigDecimal(totalDays).divide(BigDecimal(elapsedDays), MoneyMath.PRECISION)
        return spent.times(scale)
    }

    /** What the user can spend per day for the rest of the period and still come in on budget. */
    private fun safeDailySpend(
        remaining: Money,
        daysRemaining: Int,
        currency: ai.labs32.khaata.core.money.CurrencyCode,
    ): Money? {
        if (daysRemaining <= 0) return null
        if (remaining.isNegative) return Money.zero(currency)
        return remaining / daysRemaining
    }

    private fun classify(
        spent: Money,
        limit: Money,
        projected: Money,
        percentUsed: BigDecimal,
        alertThreshold: Int,
        periodStarted: Boolean,
    ): BudgetStatus = when {
        spent > limit -> BudgetStatus.OVERSPENT
        spent.compareTo(limit) == 0 -> BudgetStatus.EXHAUSTED
        percentUsed >= BigDecimal(alertThreshold) -> BudgetStatus.NEARING_LIMIT
        periodStarted && projected > limit -> BudgetStatus.PROJECTED_OVER
        else -> BudgetStatus.ON_TRACK
    }

    /**
     * Unspent remainder of the period before the one containing [asOf], for rollover budgets.
     *
     * Returns zero when the budget does not roll over or the previous period overspent — debt
     * does not carry forward, because a budget that starts the month already negative is
     * demoralising rather than informative.
     */
    fun carryOverInto(
        budget: Budget,
        transactions: List<Transaction>,
        asOf: LocalDate,
        categoryRollup: Map<String, String> = emptyMap(),
    ): Money {
        val currency = budget.limit.currency
        if (!budget.rollsOver) return Money.zero(currency)

        val currentPeriod = periodContaining(budget, asOf)
        if (!currentPeriod.start.isAfter(budget.anchorDate)) return Money.zero(currency)

        val previousPeriod = periodContaining(budget, currentPeriod.start.minusDays(1))
        val spent = transactions
            .filter { matches(budget, it, categoryRollup, previousPeriod) }
            .sumOfMoney(currency) { it.amount }
        return (budget.limit - spent).floorAtZero()
    }

    /** Builds the subcategory → parent map [evaluate] expects. */
    fun buildCategoryRollup(categories: List<Category>): Map<String, String> =
        categories.mapNotNull { category ->
            category.parentId?.let { category.id to it }
        }.toMap()
}

/**
 * A budget's position at a point in time.
 *
 * Everything here is derived, and every field is something the UI can explain in one line if the
 * user taps it.
 */
data class BudgetProgress(
    val budget: Budget,
    val period: DateRange,
    /** The limit actually in force, including any rollover. */
    val limit: Money,
    val carriedOver: Money,
    val spent: Money,
    /** May be negative when overspent — the UI decides whether to clamp. */
    val remaining: Money,
    val percentUsed: BigDecimal,
    val projectedSpend: Money,
    val status: BudgetStatus,
    val transactionCount: Int,
    val daysElapsed: Int,
    val daysRemaining: Int,
    /** Null once the period is over. */
    val safeDailySpend: Money?,
) {
    /** Percentage clamped to 0-100 for progress bars, which must not overflow their track. */
    val percentUsedClamped: Int
        get() = percentUsed.setScale(0, RoundingMode.HALF_EVEN).toInt().coerceIn(0, 100)

    val isOverspent: Boolean get() = status == BudgetStatus.OVERSPENT

    /** Amount over the limit, or zero. */
    val overspentBy: Money get() = (spent - limit).floorAtZero()
}
