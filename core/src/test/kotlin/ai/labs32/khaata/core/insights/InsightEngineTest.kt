package ai.labs32.khaata.core.insights

import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class InsightEngineTest {

    private val engine = InsightEngine()
    private val asOf = LocalDate.of(2026, 3, 15)

    private val categories = listOf(
        Fixtures.category("cat-food", "Food", CategoryGroup.FOOD),
        Fixtures.category("cat-fuel", "Fuel", CategoryGroup.TRANSPORT),
    )

    private fun generate(
        transactions: List<ai.labs32.khaata.core.model.Transaction>,
        budgets: List<ai.labs32.khaata.core.model.Budget> = emptyList(),
        subscriptions: List<ai.labs32.khaata.core.model.Subscription> = emptyList(),
    ) = engine.generate(transactions, categories, budgets, subscriptions, asOf)

    @Test
    fun `an empty ledger produces no insights rather than made-up ones`() {
        assertThat(generate(emptyList())).isEmpty()
    }

    @Test
    fun `a category rising sharply is reported with both months as evidence`() {
        val transactions = listOf(
            Fixtures.expense(amount = "6000", categoryId = "cat-food", on = LocalDate.of(2026, 2, 10)),
            Fixtures.expense(amount = "9000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 10)),
        )
        val insight = generate(transactions)
            .single { it.kind == InsightKind.CATEGORY_TREND && it.categoryId == "cat-food" }

        assertThat(insight.title).contains("Food")
        assertThat(insight.detail).contains("50")
        // Every claim must show the numbers behind it.
        assertThat(insight.evidence.map { it.label })
            .containsExactly("This month", "Last month", "Increase")
        assertThat(insight.evidence.first { it.label == "This month" }.amount)
            .isEqualTo(Money.of("9000"))
        assertThat(insight.evidence.first { it.label == "Last month" }.amount)
            .isEqualTo(Money.of("6000"))
    }

    @Test
    fun `a small change is not reported`() {
        val transactions = listOf(
            Fixtures.expense(amount = "6000", categoryId = "cat-food", on = LocalDate.of(2026, 2, 10)),
            Fixtures.expense(amount = "6200", categoryId = "cat-food", on = LocalDate.of(2026, 3, 10)),
        )
        assertThat(generate(transactions).none { it.kind == InsightKind.CATEGORY_TREND }).isTrue()
    }

    @Test
    fun `a trivially small category is not reported however much it moved`() {
        val transactions = listOf(
            Fixtures.expense(amount = "50", categoryId = "cat-food", on = LocalDate.of(2026, 2, 10)),
            Fixtures.expense(amount = "200", categoryId = "cat-food", on = LocalDate.of(2026, 3, 10)),
        )
        assertThat(generate(transactions).none { it.kind == InsightKind.CATEGORY_TREND }).isTrue()
    }

    @Test
    fun `a category with no prior month is not compared against nothing`() {
        val transactions = listOf(
            Fixtures.expense(amount = "9000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 10)),
        )
        assertThat(generate(transactions).none { it.kind == InsightKind.CATEGORY_TREND }).isTrue()
    }

    @Test
    fun `an overspent budget is the most urgent thing reported`() {
        val transactions = listOf(
            Fixtures.expense(amount = "12000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 2)),
        )
        val budgets = listOf(Fixtures.budget(limit = "10000", categoryIds = setOf("cat-food")))
        val insights = generate(transactions, budgets)

        val first = insights.first()
        assertThat(first.severity).isEqualTo(InsightSeverity.ACTION_NEEDED)
        assertThat(first.kind).isEqualTo(InsightKind.BUDGET)
        assertThat(first.warrantsNotification).isTrue()
        assertThat(first.evidence.first { it.label == "Over by" }.amount).isEqualTo(Money.of("2000"))
    }

    @Test
    fun `a budget on pace produces no budget insight`() {
        val transactions = listOf(
            Fixtures.expense(amount = "4000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 2)),
        )
        val budgets = listOf(Fixtures.budget(limit = "10000", categoryIds = setOf("cat-food")))

        assertThat(generate(transactions, budgets).none { it.kind == InsightKind.BUDGET }).isTrue()
    }

    @Test
    fun `subscriptions are summarised with monthly and yearly cost`() {
        val subscriptions = listOf(
            Fixtures.subscription(id = "s1", name = "Netflix", amount = "649"),
            Fixtures.subscription(id = "s2", name = "Spotify", amount = "119"),
        )
        val insight = generate(emptyList(), subscriptions = subscriptions)
            .single { it.id == "subscription_total" }

        assertThat(insight.detail).contains("2 active")
        assertThat(insight.evidence.first { it.label == "Per month" }.amount)
            .isEqualTo(Money.of("768"))
        assertThat(insight.evidence.first { it.label == "Per year" }.amount)
            .isEqualTo(Money.of("9216"))
    }

    @Test
    fun `subscriptions renewing within the week are flagged`() {
        val subscriptions = listOf(
            Fixtures.subscription(id = "s1", name = "Netflix", nextPaymentDate = asOf.plusDays(3)),
            Fixtures.subscription(id = "s2", name = "Prime", nextPaymentDate = asOf.plusDays(40)),
        )
        val insight = generate(emptyList(), subscriptions = subscriptions)
            .single { it.id == "subscription_due_soon" }

        assertThat(insight.detail).contains("Netflix")
        assertThat(insight.detail).doesNotContain("Prime")
    }

    @Test
    fun `a savings rate is reported when there was income`() {
        val transactions = listOf(
            Fixtures.income(amount = "100000", on = LocalDate.of(2026, 3, 1)),
            Fixtures.expense(amount = "78000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 5)),
        )
        val insight = generate(transactions).single { it.kind == InsightKind.SAVINGS }

        assertThat(insight.detail).contains("22%")
        assertThat(insight.severity).isEqualTo(InsightSeverity.INFORMATIONAL)
    }

    @Test
    fun `spending more than you earned is flagged as needing action`() {
        val transactions = listOf(
            Fixtures.income(amount = "50000", on = LocalDate.of(2026, 3, 1)),
            Fixtures.expense(amount = "62000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 5)),
        )
        val insight = generate(transactions).single { it.kind == InsightKind.SAVINGS }

        assertThat(insight.severity).isEqualTo(InsightSeverity.ACTION_NEEDED)
        assertThat(insight.title).contains("more than you earned")
    }

    @Test
    fun `the largest expense is identified with its amount`() {
        val transactions = listOf(
            Fixtures.expense(amount = "32000", merchant = "Landlord", on = LocalDate.of(2026, 3, 3)),
            Fixtures.expense(amount = "850", merchant = "Swiggy", on = LocalDate.of(2026, 3, 5)),
        )
        val insight = generate(transactions).single { it.id == "largest_expense" }

        assertThat(insight.detail).contains("Landlord")
        assertThat(insight.evidence.single().amount).isEqualTo(Money.of("32000"))
    }

    @Test
    fun `a repeatedly visited merchant becomes a pattern insight`() {
        val transactions = (1..5).map {
            Fixtures.expense(amount = "300", merchant = "Swiggy", on = LocalDate.of(2026, 3, it))
        }
        val insight = generate(transactions).single { it.kind == InsightKind.PATTERN }

        assertThat(insight.title).contains("Swiggy")
        assertThat(insight.evidence.first { it.label == "Total" }.amount).isEqualTo(Money.of("1500"))
        assertThat(insight.evidence.first { it.label == "Average" }.amount).isEqualTo(Money.of("300"))
    }

    @Test
    fun `a single visit is not a pattern`() {
        val transactions = listOf(
            Fixtures.expense(amount = "300", merchant = "Swiggy", on = LocalDate.of(2026, 3, 5)),
        )
        assertThat(generate(transactions).none { it.kind == InsightKind.PATTERN }).isTrue()
    }

    @Test
    fun `urgent insights sort above informational ones`() {
        val transactions = listOf(
            Fixtures.income(amount = "100000", on = LocalDate.of(2026, 3, 1)),
            Fixtures.expense(amount = "12000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 2)),
        )
        val budgets = listOf(Fixtures.budget(limit = "10000", categoryIds = setOf("cat-food")))
        val insights = generate(transactions, budgets)

        assertThat(insights.first().severity).isEqualTo(InsightSeverity.ACTION_NEEDED)
        val severities = insights.map { it.severity.ordinal }
        assertThat(severities).isEqualTo(severities.sortedDescending())
    }

    @Test
    fun `insight ids are stable so a dismissal sticks`() {
        val transactions = listOf(
            Fixtures.expense(amount = "12000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 2)),
        )
        val budgets = listOf(Fixtures.budget(limit = "10000", categoryIds = setOf("cat-food")))

        val first = generate(transactions, budgets).map { it.id }
        val second = generate(transactions, budgets).map { it.id }
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `the number of insights is capped`() {
        val transactions = (1..40).flatMap {
            listOf(
                Fixtures.expense(amount = "2000", categoryId = "cat-food", on = LocalDate.of(2026, 2, 10)),
                Fixtures.expense(amount = "4000", categoryId = "cat-food", on = LocalDate.of(2026, 3, 10)),
            )
        }
        assertThat(engine.generate(transactions, categories, emptyList(), emptyList(), asOf, limit = 3))
            .hasSize(3)
    }
}
