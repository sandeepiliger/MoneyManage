package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.sumOfMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Income, expense and savings roll-ups over a period.
 *
 * Transfers are excluded from every total here. That single rule is what makes the numbers on the
 * dashboard trustworthy: a user who moves ₹50,000 to a fixed deposit has not spent ₹50,000, and
 * an app that says otherwise gets closed and not reopened.
 */
object CashflowAnalyzer {

    /** Income, expense, savings and savings rate over [period]. */
    fun summarise(
        transactions: List<Transaction>,
        period: DateRange,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): CashflowSummary {
        val inPeriod = transactions.filter { it.isEffective && it.occurredOn in period }
        val income = inPeriod.filter { it.countsAsIncome }.sumOfMoney(currency) { it.amount }
        val expense = inPeriod.filter { it.countsAsSpending }.sumOfMoney(currency) { it.amount }
        val net = income - expense

        return CashflowSummary(
            period = period,
            income = income,
            expense = expense,
            net = net,
            savingsRatePercent = savingsRate(income, net),
            incomeCount = inPeriod.count { it.countsAsIncome },
            expenseCount = inPeriod.count { it.countsAsSpending },
            averageDailySpend = averageDailySpend(expense, period, currency),
            largestExpense = inPeriod.filter { it.countsAsSpending }.maxByOrNull { it.amount.amount },
        )
    }

    /**
     * Savings as a percentage of income.
     *
     * Null when there was no income in the period: "you saved -∞%" of a zero income is a
     * meaningless statement, and the UI shows "no income recorded" instead.
     */
    fun savingsRate(income: Money, net: Money): BigDecimal? {
        if (!income.isPositive) return null
        return net.amount
            .multiply(BigDecimal("100"))
            .divide(income.amount, ai.labs32.khaata.core.money.MoneyMath.RATIO_SCALE, RoundingMode.HALF_EVEN)
    }

    private fun averageDailySpend(
        expense: Money,
        period: DateRange,
        currency: CurrencyCode,
    ): Money {
        if (period.dayCount <= 0) return Money.zero(currency)
        return expense / period.dayCount
    }

    /**
     * Spend per category over [period], largest first.
     *
     * Subcategory spend rolls up into its parent so the breakdown reads at the level people think
     * in ("Food ₹8,400"), with the detail available on tap.
     */
    fun categoryBreakdown(
        transactions: List<Transaction>,
        categories: List<Category>,
        period: DateRange,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
        rollUpToParent: Boolean = true,
    ): List<CategorySpend> {
        val byId = categories.associateBy { it.id }
        val spending = transactions.filter {
            it.countsAsSpending && it.occurredOn in period
        }
        if (spending.isEmpty()) return emptyList()

        val total = spending.sumOfMoney(currency) { it.amount }
        val buckets = LinkedHashMap<String?, MutableList<Transaction>>()

        for (transaction in spending) {
            val category = transaction.categoryId?.let { byId[it] }
            val key = when {
                category == null -> null // Uncategorised
                rollUpToParent && category.parentId != null -> category.parentId
                else -> category.id
            }
            buckets.getOrPut(key) { ArrayList() } += transaction
        }

        return buckets.map { (categoryId, rows) ->
            val amount = rows.sumOfMoney(currency) { it.amount }
            CategorySpend(
                categoryId = categoryId,
                category = categoryId?.let { byId[it] },
                amount = amount,
                transactionCount = rows.size,
                shareOfTotalPercent = amount.percentageOf(total) ?: BigDecimal.ZERO,
            )
        }.sortedByDescending { it.amount.amount }
    }

    /** Spend per account over [period], largest first. */
    fun accountBreakdown(
        transactions: List<Transaction>,
        period: DateRange,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): List<AccountSpend> {
        val spending = transactions.filter { it.countsAsSpending && it.occurredOn in period }
        if (spending.isEmpty()) return emptyList()
        val total = spending.sumOfMoney(currency) { it.amount }

        return spending.groupBy { it.accountId }.map { (accountId, rows) ->
            val amount = rows.sumOfMoney(currency) { it.amount }
            AccountSpend(
                accountId = accountId,
                amount = amount,
                transactionCount = rows.size,
                shareOfTotalPercent = amount.percentageOf(total) ?: BigDecimal.ZERO,
            )
        }.sortedByDescending { it.amount.amount }
    }

    /** Spend per merchant over [period], largest first, limited to [limit] entries. */
    fun merchantBreakdown(
        transactions: List<Transaction>,
        period: DateRange,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
        limit: Int = 10,
    ): List<MerchantSpend> = transactions
        .filter { it.countsAsSpending && it.occurredOn in period }
        .filter { !it.merchant.isNullOrBlank() }
        .groupBy { it.merchant!!.trim() }
        .map { (merchant, rows) ->
            MerchantSpend(
                merchant = merchant,
                amount = rows.sumOfMoney(currency) { it.amount },
                transactionCount = rows.size,
            )
        }
        .sortedByDescending { it.amount.amount }
        .take(limit)

    /** A cashflow summary per period, oldest first — the series behind the trend charts. */
    fun series(
        transactions: List<Transaction>,
        periods: List<DateRange>,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): List<CashflowSummary> = periods.map { summarise(transactions, it, currency) }

    /** Total spend in [period], the single figure the dashboard leads with. */
    fun totalSpend(
        transactions: List<Transaction>,
        period: DateRange,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): Money = transactions
        .filter { it.countsAsSpending && it.occurredOn in period }
        .sumOfMoney(currency) { it.amount }

    /** Daily spend totals across [period], including zero-spend days so charts have no gaps. */
    fun dailySpend(
        transactions: List<Transaction>,
        period: DateRange,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): List<DailySpend> {
        val byDate = transactions
            .filter { it.countsAsSpending && it.occurredOn in period }
            .groupBy { it.occurredOn }

        val days = ArrayList<DailySpend>(period.dayCount)
        var date: LocalDate = period.start
        while (!date.isAfter(period.endInclusive)) {
            val rows = byDate[date].orEmpty()
            days += DailySpend(
                date = date,
                amount = rows.sumOfMoney(currency) { it.amount },
                transactionCount = rows.size,
            )
            date = date.plusDays(1)
        }
        return days
    }
}

data class CashflowSummary(
    val period: DateRange,
    val income: Money,
    val expense: Money,
    /** Income minus expense. Negative means the period ran at a deficit. */
    val net: Money,
    /** Null when there was no income to divide by. */
    val savingsRatePercent: BigDecimal?,
    val incomeCount: Int,
    val expenseCount: Int,
    val averageDailySpend: Money,
    val largestExpense: Transaction?,
) {
    val isSurplus: Boolean get() = net.isPositive
    val hasActivity: Boolean get() = incomeCount > 0 || expenseCount > 0
}

data class CategorySpend(
    /** Null for uncategorised spending. */
    val categoryId: String?,
    val category: Category?,
    val amount: Money,
    val transactionCount: Int,
    val shareOfTotalPercent: BigDecimal,
)

data class AccountSpend(
    val accountId: String,
    val amount: Money,
    val transactionCount: Int,
    val shareOfTotalPercent: BigDecimal,
)

data class MerchantSpend(
    val merchant: String,
    val amount: Money,
    val transactionCount: Int,
)

data class DailySpend(
    val date: LocalDate,
    val amount: Money,
    val transactionCount: Int,
)
