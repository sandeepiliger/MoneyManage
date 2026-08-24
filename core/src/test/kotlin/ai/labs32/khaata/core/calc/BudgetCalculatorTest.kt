package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.BudgetPeriod
import ai.labs32.khaata.core.model.BudgetStatus
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class BudgetCalculatorTest {

    private val food = Fixtures.category("cat-food", "Food", CategoryGroup.FOOD)
    private val swiggy = Fixtures.category("cat-swiggy", "Swiggy", CategoryGroup.FOOD, parentId = "cat-food")
    private val fuel = Fixtures.category("cat-fuel", "Fuel", CategoryGroup.TRANSPORT)
    private val categories = listOf(food, swiggy, fuel)
    private val rollup = BudgetCalculator.buildCategoryRollup(categories)

    // ---- Period derivation -------------------------------------------------------------------

    @Test
    fun `a month-anchored budget runs the calendar month`() {
        val budget = Fixtures.budget(anchorDate = LocalDate.of(2026, 1, 1))
        val period = BudgetCalculator.periodContaining(budget, LocalDate.of(2026, 3, 15))

        assertThat(period.start).isEqualTo(LocalDate.of(2026, 3, 1))
        assertThat(period.endInclusive).isEqualTo(LocalDate.of(2026, 3, 31))
    }

    @Test
    fun `a mid-month anchored budget runs anchor day to anchor day`() {
        val budget = Fixtures.budget(anchorDate = LocalDate.of(2026, 1, 5))

        val early = BudgetCalculator.periodContaining(budget, LocalDate.of(2026, 3, 3))
        assertThat(early.start).isEqualTo(LocalDate.of(2026, 2, 5))
        assertThat(early.endInclusive).isEqualTo(LocalDate.of(2026, 3, 4))

        val late = BudgetCalculator.periodContaining(budget, LocalDate.of(2026, 3, 20))
        assertThat(late.start).isEqualTo(LocalDate.of(2026, 3, 5))
        assertThat(late.endInclusive).isEqualTo(LocalDate.of(2026, 4, 4))
    }

    @Test
    fun `a budget anchored on the 31st clamps into short months without drifting`() {
        val budget = Fixtures.budget(anchorDate = LocalDate.of(2026, 1, 31))

        val february = BudgetCalculator.periodContaining(budget, LocalDate.of(2026, 2, 10))
        assertThat(february.start).isEqualTo(LocalDate.of(2026, 1, 31))
        assertThat(february.endInclusive).isEqualTo(LocalDate.of(2026, 2, 27))

        // March must return to the 31st rather than staying stuck on the 28th.
        val april = BudgetCalculator.periodContaining(budget, LocalDate.of(2026, 4, 10))
        assertThat(april.start).isEqualTo(LocalDate.of(2026, 3, 31))
    }

    @Test
    fun `weekly periods align to the anchor in both directions`() {
        val budget = Fixtures.budget(
            period = BudgetPeriod.WEEKLY,
            anchorDate = LocalDate.of(2026, 3, 2), // a Monday
            limit = "3000",
        )
        val current = BudgetCalculator.periodContaining(budget, LocalDate.of(2026, 3, 18))
        assertThat(current.start).isEqualTo(LocalDate.of(2026, 3, 16))
        assertThat(current.endInclusive).isEqualTo(LocalDate.of(2026, 3, 22))

        // Dates before the anchor must fall into an earlier week, not the anchor week.
        val past = BudgetCalculator.periodContaining(budget, LocalDate.of(2026, 2, 25))
        assertThat(past.start).isEqualTo(LocalDate.of(2026, 2, 23))
    }

    // ---- Matching ----------------------------------------------------------------------------

    @Test
    fun `subcategory spend rolls up into a parent category budget`() {
        val budget = Fixtures.budget(categoryIds = setOf("cat-food"))
        val transactions = listOf(
            Fixtures.expense(amount = "500", categoryId = "cat-food", on = LocalDate.of(2026, 3, 2)),
            Fixtures.expense(amount = "850", categoryId = "cat-swiggy", on = LocalDate.of(2026, 3, 3)),
            Fixtures.expense(amount = "2000", categoryId = "cat-fuel", on = LocalDate.of(2026, 3, 4)),
        )
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 15), rollup)

        assertThat(progress.spent).isEqualTo(Money.of("1350"))
        assertThat(progress.transactionCount).isEqualTo(2)
    }

    @Test
    fun `transfers never count against a budget`() {
        val budget = Fixtures.budget(categoryIds = emptySet()) // overall limit
        val transactions = listOf(
            Fixtures.expense(amount = "1000", on = LocalDate.of(2026, 3, 2)),
            Fixtures.transfer(amount = "50000", on = LocalDate.of(2026, 3, 3)),
        )
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 15), rollup)

        assertThat(progress.spent).isEqualTo(Money.of("1000"))
    }

    @Test
    fun `income never counts against a budget`() {
        val budget = Fixtures.budget(categoryIds = emptySet())
        val transactions = listOf(
            Fixtures.expense(amount = "1000", on = LocalDate.of(2026, 3, 2)),
            Fixtures.income(amount = "80000", on = LocalDate.of(2026, 3, 3)),
        )
        assertThat(
            BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 15), rollup).spent,
        ).isEqualTo(Money.of("1000"))
    }

    @Test
    fun `spend outside the period is excluded`() {
        val budget = Fixtures.budget()
        val transactions = listOf(
            Fixtures.expense(amount = "5000", on = LocalDate.of(2026, 2, 27)),
            Fixtures.expense(amount = "1000", on = LocalDate.of(2026, 3, 2)),
            Fixtures.expense(amount = "9000", on = LocalDate.of(2026, 4, 2)),
        )
        assertThat(
            BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 15), rollup).spent,
        ).isEqualTo(Money.of("1000"))
    }

    @Test
    fun `account-scoped budgets ignore other accounts`() {
        val budget = Fixtures.budget(categoryIds = emptySet(), accountIds = setOf("acc-cash"))
        val transactions = listOf(
            Fixtures.expense(amount = "300", accountId = "acc-cash", on = LocalDate.of(2026, 3, 2)),
            Fixtures.expense(amount = "7000", accountId = "acc-hdfc", on = LocalDate.of(2026, 3, 2)),
        )
        assertThat(
            BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 15), rollup).spent,
        ).isEqualTo(Money.of("300"))
    }

    @Test
    fun `uncategorised spend is excluded from a category budget but counted by an overall limit`() {
        val transactions = listOf(Fixtures.expense(amount = "700", categoryId = null, on = LocalDate.of(2026, 3, 2)))

        val categoryBudget = Fixtures.budget(categoryIds = setOf("cat-food"))
        assertThat(
            BudgetCalculator.evaluate(categoryBudget, transactions, LocalDate.of(2026, 3, 15), rollup).spent,
        ).isEqualTo(Money.zero())

        val overallBudget = Fixtures.budget(id = "bud-all", categoryIds = emptySet())
        assertThat(
            BudgetCalculator.evaluate(overallBudget, transactions, LocalDate.of(2026, 3, 15), rollup).spent,
        ).isEqualTo(Money.of("700"))
    }

    // ---- Status ------------------------------------------------------------------------------

    @Test
    fun `a steady spender at the halfway mark is on track`() {
        val budget = Fixtures.budget(limit = "10000")
        // Day 15 of 31, ₹4,500 spent -> projects to ~₹9,300.
        val transactions = listOf(Fixtures.expense(amount = "4500", on = LocalDate.of(2026, 3, 5)))
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 15), rollup)

        assertThat(progress.status).isEqualTo(BudgetStatus.ON_TRACK)
        assertThat(progress.remaining).isEqualTo(Money.of("5500"))
    }

    @Test
    fun `spending fast early is flagged before the limit is reached`() {
        val budget = Fixtures.budget(limit = "10000")
        // Day 5 of 31, ₹4,000 spent -> projects to ~₹24,800, well over the limit.
        val transactions = listOf(Fixtures.expense(amount = "4000", on = LocalDate.of(2026, 3, 2)))
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 5), rollup)

        assertThat(progress.status).isEqualTo(BudgetStatus.PROJECTED_OVER)
        assertThat(progress.projectedSpend).isGreaterThan(budget.limit)
        // Still under the limit — this is a warning, not a failure.
        assertThat(progress.spent).isLessThan(budget.limit)
    }

    @Test
    fun `crossing the alert threshold reports nearing limit`() {
        val budget = Fixtures.budget(limit = "10000", alertThresholdPercent = 85)
        val transactions = listOf(Fixtures.expense(amount = "8500", on = LocalDate.of(2026, 3, 2)))
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 28), rollup)

        assertThat(progress.status).isEqualTo(BudgetStatus.NEARING_LIMIT)
        assertThat(progress.percentUsedClamped).isEqualTo(85)
    }

    @Test
    fun `spending exactly the limit is exhausted, not overspent`() {
        val budget = Fixtures.budget(limit = "10000")
        val transactions = listOf(Fixtures.expense(amount = "10000", on = LocalDate.of(2026, 3, 2)))
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 15), rollup)

        assertThat(progress.status).isEqualTo(BudgetStatus.EXHAUSTED)
        assertThat(progress.remaining).isEqualTo(Money.zero())
        assertThat(progress.overspentBy).isEqualTo(Money.zero())
    }

    @Test
    fun `going over reports the overspend amount`() {
        val budget = Fixtures.budget(limit = "10000")
        val transactions = listOf(Fixtures.expense(amount = "11250", on = LocalDate.of(2026, 3, 2)))
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 15), rollup)

        assertThat(progress.status).isEqualTo(BudgetStatus.OVERSPENT)
        assertThat(progress.isOverspent).isTrue()
        assertThat(progress.overspentBy).isEqualTo(Money.of("1250"))
        assertThat(progress.remaining).isEqualTo(Money.of("-1250"))
        // The progress bar must not overflow its track.
        assertThat(progress.percentUsedClamped).isEqualTo(100)
    }

    @Test
    fun `an untouched budget is on track rather than projected over`() {
        val budget = Fixtures.budget(limit = "10000")
        val progress = BudgetCalculator.evaluate(budget, emptyList(), LocalDate.of(2026, 3, 15), rollup)

        assertThat(progress.status).isEqualTo(BudgetStatus.ON_TRACK)
        assertThat(progress.spent).isEqualTo(Money.zero())
        assertThat(progress.projectedSpend).isEqualTo(Money.zero())
    }

    // ---- Pacing ------------------------------------------------------------------------------

    @Test
    fun `safe daily spend divides what is left across the days that remain`() {
        val budget = Fixtures.budget(limit = "10000")
        val transactions = listOf(Fixtures.expense(amount = "4000", on = LocalDate.of(2026, 3, 5)))
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 21), rollup)

        // 31 - 21 = 10 days left, ₹6,000 remaining.
        assertThat(progress.daysRemaining).isEqualTo(10)
        assertThat(progress.safeDailySpend).isEqualTo(Money.of("600"))
    }

    @Test
    fun `safe daily spend is zero rather than negative when overspent`() {
        val budget = Fixtures.budget(limit = "10000")
        val transactions = listOf(Fixtures.expense(amount = "12000", on = LocalDate.of(2026, 3, 5)))
        val progress = BudgetCalculator.evaluate(budget, transactions, LocalDate.of(2026, 3, 21), rollup)

        assertThat(progress.safeDailySpend).isEqualTo(Money.zero())
    }

    @Test
    fun `safe daily spend is unavailable once the period is over`() {
        val budget = Fixtures.budget(limit = "10000")
        val progress = BudgetCalculator.evaluate(budget, emptyList(), LocalDate.of(2026, 3, 31), rollup)

        assertThat(progress.daysRemaining).isEqualTo(0)
        assertThat(progress.safeDailySpend).isNull()
    }

    // ---- Rollover ----------------------------------------------------------------------------

    @Test
    fun `an unspent remainder carries into the next period when rollover is on`() {
        val budget = Fixtures.budget(limit = "10000", rollsOver = true)
        val transactions = listOf(Fixtures.expense(amount = "6000", on = LocalDate.of(2026, 2, 10)))

        val carried = BudgetCalculator.carryOverInto(
            budget, transactions, LocalDate.of(2026, 3, 15), rollup,
        )
        assertThat(carried).isEqualTo(Money.of("4000"))

        val progress = BudgetCalculator.evaluate(
            budget, transactions, LocalDate.of(2026, 3, 15), rollup, carried,
        )
        assertThat(progress.limit).isEqualTo(Money.of("14000"))
        assertThat(progress.carriedOver).isEqualTo(Money.of("4000"))
    }

    @Test
    fun `an overspent period does not carry debt forward`() {
        val budget = Fixtures.budget(limit = "10000", rollsOver = true)
        val transactions = listOf(Fixtures.expense(amount = "13000", on = LocalDate.of(2026, 2, 10)))

        assertThat(
            BudgetCalculator.carryOverInto(budget, transactions, LocalDate.of(2026, 3, 15), rollup),
        ).isEqualTo(Money.zero())
    }

    @Test
    fun `rollover is ignored when the budget does not opt in`() {
        val budget = Fixtures.budget(limit = "10000", rollsOver = false)
        val transactions = listOf(Fixtures.expense(amount = "2000", on = LocalDate.of(2026, 2, 10)))

        assertThat(
            BudgetCalculator.carryOverInto(budget, transactions, LocalDate.of(2026, 3, 15), rollup),
        ).isEqualTo(Money.zero())
    }
}
