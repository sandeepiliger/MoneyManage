package ai.labs32.khaata.core.ai

import ai.labs32.khaata.core.calc.BudgetCalculator
import ai.labs32.khaata.core.calc.CashflowAnalyzer
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.insights.Evidence
import ai.labs32.khaata.core.categorize.MerchantNormaliser
import ai.labs32.khaata.core.calc.monthlyEquivalent
import ai.labs32.khaata.core.calc.yearlyEquivalent
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.money.sumOfMoney
import java.time.LocalDate

/**
 * The on-device assistant. The default, not a fallback.
 *
 * It answers the questions people actually ask an expense tracker — "how much did I spend on food
 * this month?", "where am I overspending?", "can I afford ₹20,000?" — by classifying the question
 * into an intent, extracting a category, merchant and period, and then running the same
 * calculators the rest of the app uses.
 *
 * This is intentionally not a language model. For this class of question a deterministic query
 * engine is better on every axis that matters here: it is instant, free, works with no network,
 * and cannot invent a number. A model that occasionally hallucinates a figure about someone's own
 * money is worse than no assistant at all.
 */
class LocalFinancialAiService : FinancialAiService {

    override val requiresNetwork: Boolean = false
    override val providerName: String = "On-device"

    override suspend fun ask(question: String, context: AiContext): AiAnswer {
        if (question.isBlank()) return AiAnswer.NotUnderstood(suggestedQuestions(context))

        val lower = question.lowercase()
        val intent = classify(lower)

        // In "what changed compared to last month?", "last month" is the baseline, not the
        // period being asked about. Resolving the period from the whole sentence would shift
        // the question a month into the past and compare February with January.
        val periodText = if (intent == Intent.COMPARISON) stripComparisonClause(lower) else lower
        val period = resolvePeriod(periodText, context.today)

        return when (intent) {
            Intent.SPEND_ON_TARGET -> answerSpendOnTarget(lower, period, context)
            Intent.TOTAL_SPEND -> answerTotalSpend(period, context)
            Intent.TOTAL_INCOME -> answerTotalIncome(period, context)
            Intent.OVERSPENDING -> answerOverspending(context)
            Intent.AFFORDABILITY -> answerAffordability(lower, context)
            Intent.COMPARISON -> answerComparison(period, context)
            Intent.LARGEST_EXPENSES -> answerLargestExpenses(period, context)
            Intent.SAVINGS -> answerSavings(period, context)
            Intent.SUBSCRIPTION_COST -> answerSubscriptionCost(context)
            Intent.UNKNOWN -> AiAnswer.NotUnderstood(suggestedQuestions(context))
        }
    }

    /** Drops everything from the comparison connective onwards, so only the subject period remains. */
    private fun stripComparisonClause(lower: String): String {
        val cutAt = COMPARISON_CONNECTIVES
            .mapNotNull { connective -> lower.indexOf(connective).takeIf { it >= 0 } }
            .minOrNull()
        return if (cutAt == null) lower else lower.substring(0, cutAt)
    }

    override fun suggestedQuestions(context: AiContext): List<String> {
        val suggestions = mutableListOf<String>()
        val thisMonth = DateRange.ofMonth(context.today)

        // Lead with a category the user actually spends on; a suggestion that returns zero
        // makes the whole feature look broken.
        val topCategory = CashflowAnalyzer
            .categoryBreakdown(context.transactions, context.categories, thisMonth, context.currency)
            .firstOrNull { it.category != null }
            ?.category
        if (topCategory != null) {
            suggestions += "How much did I spend on ${topCategory.name} this month?"
        }
        suggestions += "How much did I spend this month?"

        if (context.budgets.any { it.isActive }) {
            suggestions += "Where am I overspending?"
        }
        val topMerchant = CashflowAnalyzer
            .merchantBreakdown(context.transactions, thisMonth, context.currency, limit = 1)
            .firstOrNull()
        if (topMerchant != null) {
            suggestions += "How much did I spend at ${topMerchant.merchant} this year?"
        }
        suggestions += "What changed compared to last month?"
        suggestions += "Show my largest expenses"
        if (context.subscriptions.any { it.isActive }) {
            suggestions += "What do my subscriptions cost?"
        }
        return suggestions.take(6)
    }

    // ---- Intent classification ---------------------------------------------------------------

    private enum class Intent {
        SPEND_ON_TARGET,
        TOTAL_SPEND,
        TOTAL_INCOME,
        OVERSPENDING,
        AFFORDABILITY,
        COMPARISON,
        LARGEST_EXPENSES,
        SAVINGS,
        SUBSCRIPTION_COST,
        UNKNOWN,
    }

    private fun classify(lower: String): Intent = when {
        lower.contains("afford") -> Intent.AFFORDABILITY
        lower.contains("overspend") || lower.contains("over budget") ||
            lower.contains("over my budget") -> Intent.OVERSPENDING
        lower.contains("subscription") -> Intent.SUBSCRIPTION_COST
        lower.contains("largest") || lower.contains("biggest") ||
            lower.contains("top expense") -> Intent.LARGEST_EXPENSES
        lower.contains("changed") || lower.contains("compare") ||
            lower.contains("versus") || lower.contains(" vs ") -> Intent.COMPARISON
        lower.contains("save") || lower.contains("saving") -> Intent.SAVINGS
        lower.contains("earn") || lower.contains("income") ||
            lower.contains("salary") -> Intent.TOTAL_INCOME
        lower.contains("spend") || lower.contains("spent") || lower.contains("cost") -> {
            // "spend on food" is a targeted question; a bare "how much did I spend" is a total.
            if (hasTarget(lower)) Intent.SPEND_ON_TARGET else Intent.TOTAL_SPEND
        }
        else -> Intent.UNKNOWN
    }

    private fun hasTarget(lower: String): Boolean =
        TARGET_CONNECTIVES.any { connective ->
            val at = lower.indexOf(connective)
            at >= 0 && lower.substring(at + connective.length).isNotBlank()
        }

    // ---- Answers -----------------------------------------------------------------------------

    private fun answerSpendOnTarget(
        lower: String,
        period: ResolvedPeriod,
        context: AiContext,
    ): AiAnswer {
        val targetText = extractTarget(lower) ?: return AiAnswer.NotUnderstood(suggestedQuestions(context))

        val category = matchCategory(targetText, context.categories)
        val matching = if (category != null) {
            val ids = categoryAndChildren(category, context.categories)
            context.transactions.filter {
                it.countsAsSpending && it.occurredOn in period.range && it.categoryId in ids
            }
        } else {
            val key = MerchantNormaliser.normalise(targetText)
                ?: return AiAnswer.NotUnderstood(suggestedQuestions(context))
            context.transactions.filter {
                it.countsAsSpending && it.occurredOn in period.range &&
                    MerchantNormaliser.normalise(it.merchant) == key
            }
        }

        val label = category?.name ?: targetText.trim().replaceFirstChar { it.uppercase() }
        if (matching.isEmpty()) {
            return AiAnswer.NoData("No spending on $label ${period.label}.")
        }

        val total = matching.sumOfMoney(context.currency) { it.amount }
        val periodTotal = CashflowAnalyzer.totalSpend(context.transactions, period.range, context.currency)

        return AiAnswer.Answered(
            summary = "You spent ${total.toPlainString()} on $label ${period.label} " +
                "across ${matching.size} transaction(s).",
            evidence = buildList {
                add(Evidence(label, total))
                add(Evidence("All spending ${period.label}", periodTotal))
                add(Evidence("Average per transaction", total / matching.size))
            },
            relatedTransactionIds = matching.sortedByDescending { it.amount.amount }
                .take(RELATED_LIMIT).map { it.id },
            source = AnswerSource.ON_DEVICE,
        )
    }

    private fun answerTotalSpend(period: ResolvedPeriod, context: AiContext): AiAnswer {
        val summary = CashflowAnalyzer.summarise(context.transactions, period.range, context.currency)
        if (summary.expenseCount == 0) {
            return AiAnswer.NoData("No spending recorded ${period.label}.")
        }
        val topCategory = CashflowAnalyzer
            .categoryBreakdown(context.transactions, context.categories, period.range, context.currency)
            .firstOrNull()

        return AiAnswer.Answered(
            summary = "You spent ${summary.expense.toPlainString()} ${period.label} " +
                "across ${summary.expenseCount} transaction(s)." +
                (topCategory?.category?.let { " Most of it went on ${it.name}." } ?: ""),
            evidence = buildList {
                add(Evidence("Total spent", summary.expense))
                add(Evidence("Daily average", summary.averageDailySpend))
                topCategory?.let { add(Evidence(it.category?.name ?: "Uncategorised", it.amount)) }
            },
            source = AnswerSource.ON_DEVICE,
        )
    }

    private fun answerTotalIncome(period: ResolvedPeriod, context: AiContext): AiAnswer {
        val summary = CashflowAnalyzer.summarise(context.transactions, period.range, context.currency)
        if (summary.incomeCount == 0) {
            return AiAnswer.NoData("No income recorded ${period.label}.")
        }
        return AiAnswer.Answered(
            summary = "You received ${summary.income.toPlainString()} ${period.label}.",
            evidence = listOf(
                Evidence("Income", summary.income),
                Evidence("Expenses", summary.expense),
                Evidence("Net", summary.net),
            ),
            source = AnswerSource.ON_DEVICE,
        )
    }

    private fun answerOverspending(context: AiContext): AiAnswer {
        val rollup = BudgetCalculator.buildCategoryRollup(context.categories)
        val active = context.budgets.filter { it.isActive }
        if (active.isEmpty()) {
            return AiAnswer.NoData("You have not set any budgets yet, so there is nothing to compare against.")
        }

        val struggling = active
            .map { BudgetCalculator.evaluate(it, context.transactions, context.today, rollup) }
            .filter { it.status.needsAttention }
            .sortedByDescending { it.percentUsed }

        if (struggling.isEmpty()) {
            return AiAnswer.Answered(
                summary = "Every budget is on track this period.",
                evidence = active.map { budget ->
                    val progress = BudgetCalculator.evaluate(budget, context.transactions, context.today, rollup)
                    Evidence(budget.name, progress.spent)
                },
                source = AnswerSource.ON_DEVICE,
            )
        }

        val worst = struggling.first()
        return AiAnswer.Answered(
            summary = "${worst.budget.name} needs attention — " +
                "${worst.percentUsedClamped}% of ${worst.limit.toPlainString()} is used" +
                if (struggling.size > 1) ", and ${struggling.size - 1} other budget(s) too." else ".",
            evidence = struggling.take(EVIDENCE_LIMIT).flatMap { progress ->
                listOf(
                    Evidence("${progress.budget.name} spent", progress.spent),
                    Evidence("${progress.budget.name} limit", progress.limit),
                )
            },
            source = AnswerSource.ON_DEVICE,
        )
    }

    private fun answerAffordability(lower: String, context: AiContext): AiAnswer {
        val amount = extractAmount(lower, context)
            ?: return AiAnswer.NotUnderstood(listOf("Can I afford to spend 20000 this month?"))

        val thisMonth = DateRange.ofMonth(context.today)
        val alreadySpent = CashflowAnalyzer.totalSpend(context.transactions, thisMonth, context.currency)
        val remainingAfter = context.availableBalance - amount

        // Committed spending still to come this month — a balance that looks comfortable today
        // is not comfortable if rent has not gone out yet.
        val summary = if (remainingAfter.isNegative) {
            "Spending ${amount.toPlainString()} would leave you short — you have " +
                "${context.availableBalance.toPlainString()} available."
        } else {
            "Spending ${amount.toPlainString()} would leave ${remainingAfter.toPlainString()} " +
                "available. Check upcoming bills before deciding."
        }

        return AiAnswer.Answered(
            summary = summary,
            evidence = buildList {
                add(Evidence("Available now", context.availableBalance))
                add(Evidence("You asked about", amount))
                add(Evidence("Left after", remainingAfter))
                add(Evidence("Spent so far this month", alreadySpent))
                context.monthlyIncome?.let { add(Evidence("Monthly income", it)) }
            },
            source = AnswerSource.ON_DEVICE,
        )
    }

    private fun answerComparison(period: ResolvedPeriod, context: AiContext): AiAnswer {
        val current = CashflowAnalyzer.summarise(context.transactions, period.range, context.currency)
        val previousRange = period.range.previousPeriod()
        val previous = CashflowAnalyzer.summarise(context.transactions, previousRange, context.currency)

        if (!previous.hasActivity) {
            return AiAnswer.NoData("There is not enough history yet to compare periods.")
        }

        val change = current.expense - previous.expense
        val direction = if (change.isPositive) "more" else "less"

        val currentByCategory = CashflowAnalyzer
            .categoryBreakdown(context.transactions, context.categories, period.range, context.currency)
            .associateBy { it.categoryId }
        val previousByCategory = CashflowAnalyzer
            .categoryBreakdown(context.transactions, context.categories, previousRange, context.currency)
            .associateBy { it.categoryId }

        val biggestMover = currentByCategory.values
            .filter { it.category != null }
            .maxByOrNull { row ->
                val before = previousByCategory[row.categoryId]?.amount
                    ?: Money.zero(context.currency)
                (row.amount - before).abs().amount
            }

        return AiAnswer.Answered(
            summary = "You spent ${change.abs().toPlainString()} $direction ${period.label} " +
                "than the period before." +
                (biggestMover?.category?.let { " The biggest change was ${it.name}." } ?: ""),
            evidence = buildList {
                add(Evidence("This period", current.expense))
                add(Evidence("Previous period", previous.expense))
                add(Evidence("Difference", change.abs()))
                biggestMover?.let { mover ->
                    add(Evidence("${mover.category?.name} now", mover.amount))
                    add(
                        Evidence(
                            "${mover.category?.name} before",
                            previousByCategory[mover.categoryId]?.amount ?: Money.zero(context.currency),
                        ),
                    )
                }
            },
            source = AnswerSource.ON_DEVICE,
        )
    }

    private fun answerLargestExpenses(period: ResolvedPeriod, context: AiContext): AiAnswer {
        val largest = context.transactions
            .filter { it.countsAsSpending && it.occurredOn in period.range }
            .sortedByDescending { it.amount.amount }
            .take(EVIDENCE_LIMIT)

        if (largest.isEmpty()) {
            return AiAnswer.NoData("No spending recorded ${period.label}.")
        }
        return AiAnswer.Answered(
            summary = "Your largest expense ${period.label} was " +
                "${largest.first().displayTitle("an uncategorised expense")} at " +
                "${largest.first().amount.toPlainString()}.",
            evidence = largest.map { Evidence(it.displayTitle("Uncategorised"), it.amount) },
            relatedTransactionIds = largest.map { it.id },
            source = AnswerSource.ON_DEVICE,
        )
    }

    private fun answerSavings(period: ResolvedPeriod, context: AiContext): AiAnswer {
        val summary = CashflowAnalyzer.summarise(context.transactions, period.range, context.currency)
        val rate = summary.savingsRatePercent
            ?: return AiAnswer.NoData("No income was recorded ${period.label}, so a savings rate cannot be worked out.")

        return AiAnswer.Answered(
            summary = if (summary.net.isPositive) {
                "You saved ${summary.net.toPlainString()} ${period.label}, " +
                    "which is ${rate.setScale(0, java.math.RoundingMode.HALF_EVEN)}% of your income."
            } else {
                "You spent ${summary.net.abs().toPlainString()} more than you earned ${period.label}."
            },
            evidence = listOf(
                Evidence("Income", summary.income),
                Evidence("Expenses", summary.expense),
                Evidence("Saved", summary.net),
            ),
            source = AnswerSource.ON_DEVICE,
        )
    }

    private fun answerSubscriptionCost(context: AiContext): AiAnswer {
        val active = context.subscriptions.filter { it.isActive && it.cancelledOn == null }
        if (active.isEmpty()) {
            return AiAnswer.NoData("You are not tracking any subscriptions yet.")
        }
        val monthly = active.sumOfMoney(context.currency) { it.monthlyEquivalent() }
        val yearly = active.sumOfMoney(context.currency) { it.yearlyEquivalent() }

        return AiAnswer.Answered(
            summary = "Your ${active.size} subscription(s) cost about ${monthly.toPlainString()} " +
                "a month, or ${yearly.toPlainString()} a year.",
            evidence = buildList {
                add(Evidence("Per month", monthly))
                add(Evidence("Per year", yearly))
                addAll(
                    active.sortedByDescending { it.monthlyEquivalent().amount }
                        .take(EVIDENCE_LIMIT)
                        .map { Evidence(it.name, it.amount) },
                )
            },
            source = AnswerSource.ON_DEVICE,
        )
    }

    // ---- Extraction --------------------------------------------------------------------------

    private data class ResolvedPeriod(val range: DateRange, val label: String)

    private fun resolvePeriod(lower: String, today: LocalDate): ResolvedPeriod = when {
        lower.contains("last month") ->
            ResolvedPeriod(DateRange.ofMonth(today.minusMonths(1)), "last month")
        lower.contains("this year") || lower.contains("the year") ->
            ResolvedPeriod(DateRange.ofYear(today), "this year")
        lower.contains("last year") ->
            ResolvedPeriod(DateRange.ofYear(today.minusYears(1)), "last year")
        lower.contains("financial year") || lower.contains("fy") ->
            ResolvedPeriod(DateRange.ofFinancialYear(today), "this financial year")
        lower.contains("last week") ->
            ResolvedPeriod(DateRange.ofWeek(today.minusWeeks(1)), "last week")
        lower.contains("this week") ->
            ResolvedPeriod(DateRange.ofWeek(today), "this week")
        lower.contains("today") ->
            ResolvedPeriod(DateRange(today, today), "today")
        // Defaulting to the current month matches what people mean when they leave the period
        // out, which is most of the time.
        else -> ResolvedPeriod(DateRange.ofMonth(today), "this month")
    }

    private fun extractTarget(lower: String): String? {
        for (connective in TARGET_CONNECTIVES) {
            val at = lower.indexOf(connective)
            if (at < 0) continue
            var tail = lower.substring(at + connective.length).trim()
            // Trim the period phrase and trailing punctuation so "food this month?" becomes "food".
            for (phrase in PERIOD_PHRASES) {
                val phraseAt = tail.indexOf(phrase)
                if (phraseAt >= 0) tail = tail.substring(0, phraseAt).trim()
            }
            tail = tail.trimEnd('?', '.', '!', ',').trim()
            if (tail.isNotBlank()) return tail
        }
        return null
    }

    private fun matchCategory(target: String, categories: List<Category>): Category? {
        val normalised = target.trim().lowercase()
        return categories.firstOrNull { it.name.lowercase() == normalised }
            ?: categories.firstOrNull { it.name.lowercase().contains(normalised) && normalised.length >= 3 }
    }

    private fun categoryAndChildren(category: Category, all: List<Category>): Set<String> =
        buildSet {
            add(category.id)
            all.filter { it.parentId == category.id }.forEach { add(it.id) }
        }

    private fun extractAmount(lower: String, context: AiContext): Money? {
        val match = AMOUNT_IN_QUESTION.find(lower) ?: return null
        val decimal = MoneyParser.parseDecimal(match.value) ?: return null
        return runCatching { Money.of(decimal, context.currency) }.getOrNull()
    }

    private companion object {
        val TARGET_CONNECTIVES = listOf(" on ", " at ", " for ", " in ")

        /** Phrases after which a period phrase names the baseline rather than the subject. */
        val COMPARISON_CONNECTIVES = listOf(
            "compared to", "compared with", "compare to", "compare with",
            " versus ", " vs ", " than ",
        )

        val PERIOD_PHRASES = listOf(
            "this month", "last month", "this year", "last year", "this week", "last week",
            "financial year", "today", "so far",
        )

        val AMOUNT_IN_QUESTION = Regex(
            """(?:(?:rs\.?|inr|₹)\s*)?\d[\d,]*(?:\.\d{1,2})?\s*(?:k|l|cr|lakh|lakhs|crore|crores)?""",
            RegexOption.IGNORE_CASE,
        )

        const val EVIDENCE_LIMIT = 5
        const val RELATED_LIMIT = 20
    }
}
