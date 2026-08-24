package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CashflowAnalyzerTest {

    private val march = DateRange.ofMonth(LocalDate.of(2026, 3, 1))
    private val food = Fixtures.category("cat-food", "Food", CategoryGroup.FOOD)
    private val swiggy = Fixtures.category("cat-swiggy", "Swiggy", CategoryGroup.FOOD, parentId = "cat-food")
    private val fuel = Fixtures.category("cat-fuel", "Fuel", CategoryGroup.TRANSPORT)
    private val categories = listOf(food, swiggy, fuel)

    private val ledger = listOf(
        Fixtures.income(amount = "80000", on = LocalDate.of(2026, 3, 1)),
        Fixtures.expense(amount = "25000", categoryId = "cat-rent", on = LocalDate.of(2026, 3, 2), merchant = "Landlord"),
        Fixtures.expense(amount = "3000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 5), merchant = "BigBasket"),
        Fixtures.expense(amount = "2000", categoryId = "cat-swiggy", on = LocalDate.of(2026, 3, 6), merchant = "Swiggy"),
        Fixtures.expense(amount = "4000", categoryId = "cat-fuel", on = LocalDate.of(2026, 3, 7), merchant = "Indian Oil"),
        // Excluded: a transfer is not spending.
        Fixtures.transfer(amount = "20000", on = LocalDate.of(2026, 3, 8)),
        // Excluded: outside the period.
        Fixtures.expense(amount = "9999", categoryId = "cat-food", on = LocalDate.of(2026, 4, 2)),
    )

    @Test
    fun `summary separates income from expense and excludes transfers`() {
        val summary = CashflowAnalyzer.summarise(ledger, march)

        assertThat(summary.income).isEqualTo(Money.of("80000"))
        assertThat(summary.expense).isEqualTo(Money.of("34000"))
        assertThat(summary.net).isEqualTo(Money.of("46000"))
        assertThat(summary.expenseCount).isEqualTo(4)
        assertThat(summary.incomeCount).isEqualTo(1)
    }

    @Test
    fun `savings rate is net over income`() {
        val summary = CashflowAnalyzer.summarise(ledger, march)
        // 46,000 / 80,000 = 57.5%
        assertThat(summary.savingsRatePercent).isEqualTo(BigDecimal("57.5000"))
    }

    @Test
    fun `savings rate is unavailable rather than infinite when there is no income`() {
        val expenseOnly = listOf(Fixtures.expense(amount = "500", on = LocalDate.of(2026, 3, 5)))
        assertThat(CashflowAnalyzer.summarise(expenseOnly, march).savingsRatePercent).isNull()
    }

    @Test
    fun `a deficit month reports a negative savings rate`() {
        val overspend = listOf(
            Fixtures.income(amount = "10000", on = LocalDate.of(2026, 3, 1)),
            Fixtures.expense(amount = "15000", on = LocalDate.of(2026, 3, 5)),
        )
        val summary = CashflowAnalyzer.summarise(overspend, march)

        assertThat(summary.isSurplus).isFalse()
        assertThat(summary.net).isEqualTo(Money.of("-5000"))
        assertThat(summary.savingsRatePercent).isEqualTo(BigDecimal("-50.0000"))
    }

    @Test
    fun `largest expense is identified`() {
        assertThat(CashflowAnalyzer.summarise(ledger, march).largestExpense?.merchant)
            .isEqualTo("Landlord")
    }

    @Test
    fun `an empty period reports no activity rather than failing`() {
        val summary = CashflowAnalyzer.summarise(emptyList(), march)

        assertThat(summary.hasActivity).isFalse()
        assertThat(summary.income).isEqualTo(Money.zero())
        assertThat(summary.averageDailySpend).isEqualTo(Money.zero())
        assertThat(summary.largestExpense).isNull()
    }

    @Test
    fun `category breakdown rolls subcategories into their parent`() {
        val breakdown = CashflowAnalyzer.categoryBreakdown(ledger, categories, march)
        val foodRow = breakdown.single { it.categoryId == "cat-food" }

        // ₹3,000 groceries + ₹2,000 Swiggy.
        assertThat(foodRow.amount).isEqualTo(Money.of("5000"))
        assertThat(foodRow.transactionCount).isEqualTo(2)
    }

    @Test
    fun `category breakdown can keep subcategories separate`() {
        val breakdown = CashflowAnalyzer.categoryBreakdown(
            ledger, categories, march, rollUpToParent = false,
        )
        assertThat(breakdown.single { it.categoryId == "cat-swiggy" }.amount)
            .isEqualTo(Money.of("2000"))
        assertThat(breakdown.single { it.categoryId == "cat-food" }.amount)
            .isEqualTo(Money.of("3000"))
    }

    @Test
    fun `category breakdown is ordered by amount and shares sum to a hundred percent`() {
        val breakdown = CashflowAnalyzer.categoryBreakdown(ledger, categories, march)

        assertThat(breakdown.first().amount).isEqualTo(Money.of("25000"))
        val totalShare = breakdown.fold(BigDecimal.ZERO) { sum, row -> sum + row.shareOfTotalPercent }
        assertThat(totalShare.setScale(2, java.math.RoundingMode.HALF_EVEN))
            .isEqualTo(BigDecimal("100.00"))
    }

    @Test
    fun `spend with an unknown category is bucketed as uncategorised`() {
        val rows = listOf(Fixtures.expense(amount = "700", categoryId = null, on = LocalDate.of(2026, 3, 5)))
        val breakdown = CashflowAnalyzer.categoryBreakdown(rows, categories, march)

        assertThat(breakdown.single().categoryId).isNull()
        assertThat(breakdown.single().amount).isEqualTo(Money.of("700"))
    }

    @Test
    fun `merchant breakdown ranks merchants by spend`() {
        val merchants = CashflowAnalyzer.merchantBreakdown(ledger, march, limit = 3)

        assertThat(merchants.map { it.merchant })
            .containsExactly("Landlord", "Indian Oil", "BigBasket").inOrder()
    }

    @Test
    fun `daily spend covers every day including days with no spending`() {
        val days = CashflowAnalyzer.dailySpend(ledger, march)

        assertThat(days).hasSize(31)
        assertThat(days.first { it.date == LocalDate.of(2026, 3, 2) }.amount)
            .isEqualTo(Money.of("25000"))
        assertThat(days.first { it.date == LocalDate.of(2026, 3, 20) }.amount)
            .isEqualTo(Money.zero())
    }

    @Test
    fun `a series produces one summary per period in order`() {
        val periods = DateRange.trailingMonths(LocalDate.of(2026, 3, 15), 3)
        val series = CashflowAnalyzer.series(ledger, periods)

        assertThat(series).hasSize(3)
        assertThat(series.last().expense).isEqualTo(Money.of("34000"))
        assertThat(series.first().expense).isEqualTo(Money.zero()) // January
    }
}

class GoalCalculatorTest {

    @Test
    fun `progress reports the remaining amount and percentage`() {
        val goal = Fixtures.goal(target = "300000", current = "120000")
        val progress = GoalCalculator.progressOf(goal, LocalDate.of(2026, 3, 15))

        assertThat(progress.remaining).isEqualTo(Money.of("180000"))
        assertThat(progress.percentCompleteClamped).isEqualTo(40)
        assertThat(progress.isAchieved).isFalse()
    }

    @Test
    fun `required monthly contribution divides what is left across the months remaining`() {
        val goal = Fixtures.goal(
            target = "300000",
            current = "120000",
            targetDate = LocalDate.of(2026, 12, 31),
        )
        val progress = GoalCalculator.progressOf(goal, LocalDate.of(2026, 3, 15))

        // ~291 days -> 10 months (rounded up); 180,000 / 10 = 18,000.
        assertThat(progress.monthsRemaining).isEqualTo(10)
        assertThat(progress.requiredMonthlyContribution).isEqualTo(Money.of("18000"))
    }

    @Test
    fun `a goal without a deadline has no monthly figure`() {
        val goal = Fixtures.goal(targetDate = null)
        val progress = GoalCalculator.progressOf(goal, LocalDate.of(2026, 3, 15))

        assertThat(progress.requiredMonthlyContribution).isNull()
        assertThat(progress.monthsRemaining).isNull()
        assertThat(progress.pace).isEqualTo(GoalPace.NO_DEADLINE)
    }

    @Test
    fun `an achieved goal reports completion and needs nothing more`() {
        val goal = Fixtures.goal(target = "100000", current = "100000")
        val progress = GoalCalculator.progressOf(goal, LocalDate.of(2026, 3, 15))

        assertThat(progress.isAchieved).isTrue()
        assertThat(progress.remaining).isEqualTo(Money.zero())
        assertThat(progress.requiredMonthlyContribution).isEqualTo(Money.zero())
        assertThat(progress.pace).isEqualTo(GoalPace.ACHIEVED)
        assertThat(progress.percentCompleteClamped).isEqualTo(100)
    }

    @Test
    fun `overshooting a goal does not push the progress bar past a hundred`() {
        val goal = Fixtures.goal(target = "100000", current = "150000")
        assertThat(GoalCalculator.progressOf(goal, LocalDate.of(2026, 3, 15)).percentCompleteClamped)
            .isEqualTo(100)
    }

    @Test
    fun `a goal keeping pace with its timeline is on track`() {
        // Two and a half months into a twelve-month goal, ~21% saved against ~20% expected.
        val goal = Fixtures.goal(
            target = "120000",
            current = "26000",
            startedOn = LocalDate.of(2026, 1, 1),
            targetDate = LocalDate.of(2026, 12, 31),
        )
        assertThat(GoalCalculator.progressOf(goal, LocalDate.of(2026, 3, 15)).pace)
            .isEqualTo(GoalPace.ON_TRACK)
    }

    @Test
    fun `a goal falling behind its timeline is flagged`() {
        val goal = Fixtures.goal(
            target = "120000",
            current = "5000",
            startedOn = LocalDate.of(2026, 1, 1),
            targetDate = LocalDate.of(2026, 12, 31),
        )
        assertThat(GoalCalculator.progressOf(goal, LocalDate.of(2026, 3, 15)).pace)
            .isEqualTo(GoalPace.BEHIND)
    }

    @Test
    fun `a missed deadline is reported and the shortfall becomes due immediately`() {
        val goal = Fixtures.goal(
            target = "100000",
            current = "60000",
            startedOn = LocalDate.of(2025, 1, 1),
            targetDate = LocalDate.of(2026, 1, 31),
        )
        val progress = GoalCalculator.progressOf(goal, LocalDate.of(2026, 3, 15))

        assertThat(progress.isOverdue).isTrue()
        assertThat(progress.pace).isEqualTo(GoalPace.MISSED_DEADLINE)
        assertThat(progress.monthsRemaining).isEqualTo(0)
        assertThat(progress.requiredMonthlyContribution).isEqualTo(Money.of("40000"))
    }

    @Test
    fun `projection needs history before it will guess a completion date`() {
        val fresh = Fixtures.goal(
            current = "0",
            startedOn = LocalDate.of(2026, 3, 1),
            targetDate = null,
        )
        assertThat(GoalCalculator.progressOf(fresh, LocalDate.of(2026, 3, 15)).projectedCompletionDate)
            .isNull()
    }

    @Test
    fun `projection extrapolates from the pace saved so far`() {
        // ₹40,000 saved over 4 months = ₹10,000/month; ₹60,000 left -> 6 more months.
        val goal = Fixtures.goal(
            target = "100000",
            current = "40000",
            startedOn = LocalDate.of(2026, 1, 1),
            targetDate = null,
        )
        assertThat(GoalCalculator.progressOf(goal, LocalDate.of(2026, 5, 1)).projectedCompletionDate)
            .isEqualTo(LocalDate.of(2026, 11, 1))
    }
}
