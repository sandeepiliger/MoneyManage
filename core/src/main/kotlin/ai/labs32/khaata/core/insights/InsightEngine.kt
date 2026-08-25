package ai.labs32.khaata.core.insights

import ai.labs32.khaata.core.calc.BudgetCalculator
import ai.labs32.khaata.core.calc.BudgetProgress
import ai.labs32.khaata.core.calc.CashflowAnalyzer
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.calc.monthlyEquivalent
import ai.labs32.khaata.core.calc.yearlyEquivalent
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.BudgetStatus
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.sumOfMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Produces plain-language observations about the user's money.
 *
 * Every insight here is a deterministic calculation with its inputs attached. That is a
 * deliberate choice: an insight a user cannot verify is an insight they will not act on, and in
 * a finance app an unexplained claim about someone's spending reads as either wrong or creepy.
 * So each [Insight] carries the numbers behind it, and the UI shows them.
 *
 * Optional cloud AI (see the `ai` package) rephrases and prioritises these; it never replaces the
 * arithmetic and never invents a figure of its own.
 */
class InsightEngine(
    private val currency: CurrencyCode = CurrencyCode.DEFAULT,
) {

    /**
     * Generates insights for the period containing [asOf], newest and most severe first.
     *
     * @param limit maximum number to return; the dashboard shows one or two, the insights screen
     *   shows the rest.
     */
    fun generate(
        transactions: List<Transaction>,
        categories: List<Category>,
        budgets: List<Budget>,
        subscriptions: List<Subscription>,
        asOf: LocalDate,
        limit: Int = 12,
    ): List<Insight> {
        val thisMonth = DateRange.ofMonth(asOf)
        val lastMonth = thisMonth.previousPeriod()
        val rollup = BudgetCalculator.buildCategoryRollup(categories)

        val insights = buildList {
            addAll(categoryTrends(transactions, categories, thisMonth, lastMonth))
            addAll(budgetInsights(budgets, transactions, rollup, asOf))
            addAll(subscriptionInsights(subscriptions, asOf))
            addAll(cashflowInsights(transactions, thisMonth, lastMonth))
            addAll(spendingPatternInsights(transactions, thisMonth))
        }

        return insights
            .sortedWith(compareByDescending<Insight> { it.severity.ordinal }.thenByDescending { it.relevance })
            .take(limit)
    }

    // ---- Category trends ---------------------------------------------------------------------

    private fun categoryTrends(
        transactions: List<Transaction>,
        categories: List<Category>,
        thisMonth: DateRange,
        lastMonth: DateRange,
    ): List<Insight> {
        val current = CashflowAnalyzer.categoryBreakdown(transactions, categories, thisMonth, currency)
            .associateBy { it.categoryId }
        val previous = CashflowAnalyzer.categoryBreakdown(transactions, categories, lastMonth, currency)
            .associateBy { it.categoryId }

        // Comparing a partial month against a whole one always shows a fall, which is noise
        // rather than insight. Only compare like with like.
        if (previous.isEmpty()) return emptyList()

        return current.values.mapNotNull { row ->
            val categoryId = row.categoryId ?: return@mapNotNull null
            val before = previous[categoryId] ?: return@mapNotNull null
            if (!before.amount.isPositive) return@mapNotNull null

            val change = row.amount - before.amount
            val changePercent = change.amount
                .multiply(BigDecimal("100"))
                .divide(before.amount.amount, 1, RoundingMode.HALF_EVEN)

            // Ignore small wobbles and trivially small categories; neither is worth a card.
            if (changePercent.abs() < SIGNIFICANT_CHANGE_PERCENT) return@mapNotNull null
            if (row.amount < materialAmount) return@mapNotNull null

            val name = row.category?.name ?: return@mapNotNull null
            val rose = change.isPositive

            Insight(
                id = "category_trend_$categoryId",
                kind = InsightKind.CATEGORY_TREND,
                severity = if (rose && changePercent >= BigDecimal("40")) {
                    InsightSeverity.NOTABLE
                } else {
                    InsightSeverity.INFORMATIONAL
                },
                relevance = row.amount.amount.toDouble(),
                title = if (rose) "$name spending is up" else "$name spending is down",
                detail = buildString {
                    append(name)
                    append(if (rose) " rose " else " fell ")
                    append(changePercent.abs().toPlainString())
                    append("% versus last month")
                },
                evidence = listOf(
                    Evidence("This month", row.amount),
                    Evidence("Last month", before.amount),
                    Evidence(if (rose) "Increase" else "Decrease", change.abs()),
                ),
                categoryId = categoryId,
            )
        }
    }

    // ---- Budgets -----------------------------------------------------------------------------

    private fun budgetInsights(
        budgets: List<Budget>,
        transactions: List<Transaction>,
        rollup: Map<String, String>,
        asOf: LocalDate,
    ): List<Insight> = budgets.filter { it.isActive }.mapNotNull { budget ->
        val progress = BudgetCalculator.evaluate(budget, transactions, asOf, rollup)
        when (progress.status) {
            BudgetStatus.OVERSPENT -> overspentInsight(budget, progress)
            BudgetStatus.EXHAUSTED -> exhaustedInsight(budget, progress)
            BudgetStatus.NEARING_LIMIT -> nearingLimitInsight(budget, progress)
            BudgetStatus.PROJECTED_OVER -> projectedOverInsight(budget, progress)
            BudgetStatus.ON_TRACK -> null
        }
    }

    private fun overspentInsight(budget: Budget, progress: BudgetProgress) = Insight(
        id = "budget_over_${budget.id}",
        kind = InsightKind.BUDGET,
        severity = InsightSeverity.ACTION_NEEDED,
        relevance = progress.overspentBy.amount.toDouble(),
        title = "${budget.name} budget is overspent",
        detail = "You are over your ${budget.name} budget with " +
            "${progress.daysRemaining} day(s) left in the period",
        evidence = listOf(
            Evidence("Spent", progress.spent),
            Evidence("Budget", progress.limit),
            Evidence("Over by", progress.overspentBy),
        ),
        budgetId = budget.id,
    )

    private fun exhaustedInsight(budget: Budget, progress: BudgetProgress) = Insight(
        id = "budget_exhausted_${budget.id}",
        kind = InsightKind.BUDGET,
        severity = InsightSeverity.ACTION_NEEDED,
        relevance = progress.limit.amount.toDouble(),
        title = "${budget.name} budget is fully used",
        detail = "The whole ${budget.name} budget is spent with " +
            "${progress.daysRemaining} day(s) still to go",
        evidence = listOf(
            Evidence("Spent", progress.spent),
            Evidence("Budget", progress.limit),
        ),
        budgetId = budget.id,
    )

    private fun nearingLimitInsight(budget: Budget, progress: BudgetProgress) = Insight(
        id = "budget_nearing_${budget.id}",
        kind = InsightKind.BUDGET,
        severity = InsightSeverity.NOTABLE,
        relevance = progress.spent.amount.toDouble(),
        title = "${budget.name} budget is nearly used",
        detail = "You have used ${progress.percentUsedClamped}% of your ${budget.name} budget",
        evidence = listOf(
            Evidence("Spent", progress.spent),
            Evidence("Remaining", progress.remaining.floorAtZero()),
        ),
        budgetId = budget.id,
    )

    private fun projectedOverInsight(budget: Budget, progress: BudgetProgress) = Insight(
        id = "budget_projected_${budget.id}",
        kind = InsightKind.BUDGET,
        severity = InsightSeverity.NOTABLE,
        relevance = progress.projectedSpend.amount.toDouble(),
        title = "${budget.name} is on track to go over",
        detail = "At this rate you will finish the period above your ${budget.name} budget. " +
            progress.safeDailySpend?.let { "Staying under about ${it.toPlainString()} a day keeps it on track" }
                .orEmpty(),
        evidence = listOf(
            Evidence("Spent so far", progress.spent),
            Evidence("Projected", progress.projectedSpend),
            Evidence("Budget", progress.limit),
        ),
        budgetId = budget.id,
    )

    // ---- Subscriptions -----------------------------------------------------------------------

    private fun subscriptionInsights(
        subscriptions: List<Subscription>,
        asOf: LocalDate,
    ): List<Insight> {
        val active = subscriptions.filter { it.isActive && it.cancelledOn == null }
        if (active.isEmpty()) return emptyList()

        val monthly = active.sumOfMoney(currency) { it.monthlyEquivalent() }
        val yearly = active.sumOfMoney(currency) { it.yearlyEquivalent() }

        val insights = mutableListOf(
            Insight(
                id = "subscription_total",
                kind = InsightKind.SUBSCRIPTION,
                severity = InsightSeverity.INFORMATIONAL,
                relevance = monthly.amount.toDouble(),
                title = "Your subscriptions",
                detail = "${active.size} active subscription(s) cost about " +
                    "${monthly.toPlainString()} a month",
                evidence = listOf(
                    Evidence("Per month", monthly),
                    Evidence("Per year", yearly),
                ),
            ),
        )

        // Anything charging within the next few days is worth surfacing while it can still be
        // cancelled.
        val dueSoon = active.filter {
            val days = java.time.temporal.ChronoUnit.DAYS.between(asOf, it.nextPaymentDate)
            days in 0..RENEWAL_WINDOW_DAYS
        }
        if (dueSoon.isNotEmpty()) {
            insights += Insight(
                id = "subscription_due_soon",
                kind = InsightKind.SUBSCRIPTION,
                severity = InsightSeverity.NOTABLE,
                relevance = dueSoon.sumOfMoney(currency) { it.amount }.amount.toDouble(),
                title = "Subscriptions renewing soon",
                detail = dueSoon.joinToString(", ") { it.name } + " renew in the next week",
                evidence = dueSoon.map { Evidence(it.name, it.amount) },
            )
        }
        return insights
    }

    // ---- Cashflow ----------------------------------------------------------------------------

    private fun cashflowInsights(
        transactions: List<Transaction>,
        thisMonth: DateRange,
        lastMonth: DateRange,
    ): List<Insight> {
        val current = CashflowAnalyzer.summarise(transactions, thisMonth, currency)
        val previous = CashflowAnalyzer.summarise(transactions, lastMonth, currency)
        if (!current.hasActivity) return emptyList()

        val insights = mutableListOf<Insight>()

        current.savingsRatePercent?.let { rate ->
            insights += Insight(
                id = "savings_rate",
                kind = InsightKind.SAVINGS,
                severity = if (rate.signum() < 0) InsightSeverity.ACTION_NEEDED else InsightSeverity.INFORMATIONAL,
                relevance = current.income.amount.toDouble(),
                title = if (rate.signum() >= 0) "You saved this month" else "You spent more than you earned",
                detail = if (rate.signum() >= 0) {
                    "You kept ${rate.setScale(0, RoundingMode.HALF_EVEN)}% of what you earned this month"
                } else {
                    "Spending exceeded income this month"
                },
                evidence = listOf(
                    Evidence("Income", current.income),
                    Evidence("Expenses", current.expense),
                    Evidence("Net", current.net),
                ),
            )
        }

        if (previous.hasActivity && previous.expense.isPositive) {
            val change = current.expense - previous.expense
            if (change.abs() >= materialAmount) {
                insights += Insight(
                    id = "spend_vs_last_month",
                    kind = InsightKind.CASHFLOW,
                    severity = InsightSeverity.INFORMATIONAL,
                    relevance = change.abs().amount.toDouble(),
                    title = if (change.isPositive) "Spending is up on last month" else "Spending is down on last month",
                    detail = "You have spent ${change.abs().toPlainString()} " +
                        (if (change.isPositive) "more" else "less") + " than last month",
                    evidence = listOf(
                        Evidence("This month", current.expense),
                        Evidence("Last month", previous.expense),
                    ),
                )
            }
        }

        current.largestExpense?.let { largest ->
            insights += Insight(
                id = "largest_expense",
                kind = InsightKind.CASHFLOW,
                severity = InsightSeverity.INFORMATIONAL,
                relevance = largest.amount.amount.toDouble(),
                title = "Your largest expense this month",
                detail = largest.displayTitle("an uncategorised expense") +
                    " was your biggest single spend",
                evidence = listOf(Evidence("Amount", largest.amount)),
                transactionId = largest.id,
            )
        }
        return insights
    }

    // ---- Patterns ----------------------------------------------------------------------------

    private fun spendingPatternInsights(
        transactions: List<Transaction>,
        period: DateRange,
    ): List<Insight> {
        val merchants = CashflowAnalyzer.merchantBreakdown(transactions, period, currency, limit = 1)
        val top = merchants.firstOrNull() ?: return emptyList()
        // One visit is not a pattern.
        if (top.transactionCount < REPEAT_MERCHANT_THRESHOLD) return emptyList()

        return listOf(
            Insight(
                id = "top_merchant",
                kind = InsightKind.PATTERN,
                severity = InsightSeverity.INFORMATIONAL,
                relevance = top.amount.amount.toDouble(),
                title = "You visit ${top.merchant} often",
                detail = "${top.transactionCount} transactions at ${top.merchant} this month",
                evidence = listOf(
                    Evidence("Total", top.amount),
                    Evidence("Average", top.amount / top.transactionCount),
                ),
            ),
        )
    }

    /** Below this, a change is noise rather than something worth a card on the dashboard. */
    private val materialAmount: Money = Money.of(500, currency)

    private companion object {
        val SIGNIFICANT_CHANGE_PERCENT: BigDecimal = BigDecimal("15")
        const val RENEWAL_WINDOW_DAYS = 7L
        const val REPEAT_MERCHANT_THRESHOLD = 3
    }
}
