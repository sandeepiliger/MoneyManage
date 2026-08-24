package ai.labs32.khaata.core.ai

import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class LocalFinancialAiServiceTest {

    private val service = LocalFinancialAiService()
    private val today = LocalDate.of(2026, 3, 15)

    private val categories = listOf(
        Fixtures.category("cat-food", "Food", CategoryGroup.FOOD),
        Fixtures.category("cat-swiggy", "Swiggy", CategoryGroup.FOOD, parentId = "cat-food"),
        Fixtures.category("cat-fuel", "Fuel", CategoryGroup.TRANSPORT),
    )

    private val transactions = listOf(
        Fixtures.income(amount = "112000", on = LocalDate.of(2026, 3, 1)),
        Fixtures.expense(amount = "32000", categoryId = "cat-rent", merchant = "Landlord", on = LocalDate.of(2026, 3, 3)),
        Fixtures.expense(amount = "3000", categoryId = "cat-food", merchant = "BigBasket", on = LocalDate.of(2026, 3, 5)),
        Fixtures.expense(amount = "2000", categoryId = "cat-swiggy", merchant = "Swiggy", on = LocalDate.of(2026, 3, 6)),
        Fixtures.expense(amount = "1500", categoryId = "cat-swiggy", merchant = "Swiggy", on = LocalDate.of(2026, 3, 9)),
        Fixtures.expense(amount = "4000", categoryId = "cat-fuel", merchant = "Indian Oil", on = LocalDate.of(2026, 3, 7)),
        // Last month, for comparisons.
        Fixtures.income(amount = "112000", on = LocalDate.of(2026, 2, 1)),
        Fixtures.expense(amount = "2500", categoryId = "cat-food", merchant = "BigBasket", on = LocalDate.of(2026, 2, 5)),
        Fixtures.expense(amount = "30000", categoryId = "cat-rent", merchant = "Landlord", on = LocalDate.of(2026, 2, 3)),
    )

    private fun context(
        budgets: List<ai.labs32.khaata.core.model.Budget> = emptyList(),
        subscriptions: List<ai.labs32.khaata.core.model.Subscription> = emptyList(),
        available: String = "60000",
    ) = AiContext(
        transactions = transactions,
        categories = categories,
        budgets = budgets,
        subscriptions = subscriptions,
        accountNames = mapOf("acc-hdfc" to "HDFC Bank"),
        today = today,
        currency = CurrencyCode.INR,
        availableBalance = Money.of(available),
        monthlyIncome = Money.of("112000"),
    )

    @Test
    fun `the on-device provider never needs the network`() {
        assertThat(service.requiresNetwork).isFalse()
    }

    @Test
    fun `answers how much was spent on a category, rolling up subcategories`() = runTest {
        val answer = service.ask("How much did I spend on food this month?", context())

        assertThat(answer).isInstanceOf(AiAnswer.Answered::class.java)
        val answered = answer as AiAnswer.Answered
        // Groceries 3,000 + Swiggy 2,000 + 1,500.
        assertThat(answered.evidence.first { it.label == "Food" }.amount).isEqualTo(Money.of("6500"))
        assertThat(answered.source).isEqualTo(AnswerSource.ON_DEVICE)
        assertThat(answered.relatedTransactionIds).isNotEmpty()
    }

    @Test
    fun `answers spending at a merchant`() = runTest {
        val answered = service.ask("How much did I spend at Swiggy this month?", context())
            as AiAnswer.Answered

        assertThat(answered.evidence.first().amount).isEqualTo(Money.of("3500"))
    }

    @Test
    fun `a category with no spending reports no data rather than zero`() = runTest {
        val answer = service.ask("How much did I spend on fitness this month?", context())
        assertThat(answer).isInstanceOf(AiAnswer.NoData::class.java)
    }

    @Test
    fun `answers total spending for the month`() = runTest {
        val answered = service.ask("How much did I spend this month?", context()) as AiAnswer.Answered

        // 32,000 + 3,000 + 2,000 + 1,500 + 4,000
        assertThat(answered.evidence.first { it.label == "Total spent" }.amount)
            .isEqualTo(Money.of("42500"))
    }

    @Test
    fun `resolves the period named in the question`() = runTest {
        val lastMonth = service.ask("How much did I spend last month?", context()) as AiAnswer.Answered
        assertThat(lastMonth.evidence.first { it.label == "Total spent" }.amount)
            .isEqualTo(Money.of("32500"))

        val thisYear = service.ask("How much did I spend this year?", context()) as AiAnswer.Answered
        assertThat(thisYear.evidence.first { it.label == "Total spent" }.amount)
            .isEqualTo(Money.of("75000"))
    }

    @Test
    fun `answers income questions`() = runTest {
        val answered = service.ask("How much did I earn this month?", context()) as AiAnswer.Answered
        assertThat(answered.evidence.first { it.label == "Income" }.amount)
            .isEqualTo(Money.of("112000"))
    }

    @Test
    fun `identifies where the user is overspending`() = runTest {
        val budgets = listOf(
            Fixtures.budget(id = "b1", name = "Food", limit = "5000", categoryIds = setOf("cat-food")),
            Fixtures.budget(id = "b2", name = "Fuel", limit = "20000", categoryIds = setOf("cat-fuel")),
        )
        val answered = service.ask("Where am I overspending?", context(budgets = budgets))
            as AiAnswer.Answered

        assertThat(answered.summary).contains("Food")
        assertThat(answered.evidence.first { it.label == "Food spent" }.amount)
            .isEqualTo(Money.of("6500"))
    }

    @Test
    fun `says budgets are fine when they are`() = runTest {
        val budgets = listOf(
            Fixtures.budget(id = "b1", name = "Food", limit = "50000", categoryIds = setOf("cat-food")),
        )
        val answered = service.ask("Where am I overspending?", context(budgets = budgets))
            as AiAnswer.Answered
        assertThat(answered.summary).contains("on track")
    }

    @Test
    fun `with no budgets set, overspending has nothing to compare against`() = runTest {
        assertThat(service.ask("Where am I overspending?", context()))
            .isInstanceOf(AiAnswer.NoData::class.java)
    }

    @Test
    fun `answers affordability against available balance`() = runTest {
        val comfortable = service.ask("Can I afford to spend 20000 this month?", context())
            as AiAnswer.Answered
        assertThat(comfortable.evidence.first { it.label == "Left after" }.amount)
            .isEqualTo(Money.of("40000"))
        // The answer is framed with a caveat rather than as a green light.
        assertThat(comfortable.summary).contains("upcoming bills")

        val tight = service.ask("Can I afford 90000?", context(available = "60000")) as AiAnswer.Answered
        assertThat(tight.summary).contains("short")
    }

    @Test
    fun `an affordability question with no amount asks for one`() = runTest {
        assertThat(service.ask("Can I afford it?", context()))
            .isInstanceOf(AiAnswer.NotUnderstood::class.java)
    }

    @Test
    fun `compares this period against the previous one`() = runTest {
        val answered = service.ask("What changed compared to last month?", context())
            as AiAnswer.Answered

        // 42,500 this month against 32,500 last month.
        assertThat(answered.evidence.first { it.label == "Difference" }.amount)
            .isEqualTo(Money.of("10000"))
        assertThat(answered.summary).contains("more")
    }

    @Test
    fun `lists the largest expenses`() = runTest {
        val answered = service.ask("Show my largest expenses", context()) as AiAnswer.Answered

        assertThat(answered.summary).contains("Landlord")
        assertThat(answered.evidence.first().amount).isEqualTo(Money.of("32000"))
        assertThat(answered.relatedTransactionIds).isNotEmpty()
    }

    @Test
    fun `reports the savings rate`() = runTest {
        val answered = service.ask("How much did I save this month?", context()) as AiAnswer.Answered

        assertThat(answered.evidence.first { it.label == "Saved" }.amount)
            .isEqualTo(Money.of("69500"))
        assertThat(answered.summary).contains("62%")
    }

    @Test
    fun `reports subscription cost`() = runTest {
        val subscriptions = listOf(
            Fixtures.subscription(id = "s1", name = "Netflix", amount = "649"),
            Fixtures.subscription(id = "s2", name = "Spotify", amount = "119"),
        )
        val answered = service.ask("What do my subscriptions cost?", context(subscriptions = subscriptions))
            as AiAnswer.Answered

        assertThat(answered.evidence.first { it.label == "Per month" }.amount)
            .isEqualTo(Money.of("768"))
        assertThat(answered.evidence.first { it.label == "Per year" }.amount)
            .isEqualTo(Money.of("9216"))
    }

    @Test
    fun `an unrelated question is not answered with a made-up figure`() = runTest {
        val answer = service.ask("What is the capital of France?", context())

        assertThat(answer).isInstanceOf(AiAnswer.NotUnderstood::class.java)
        assertThat((answer as AiAnswer.NotUnderstood).suggestions).isNotEmpty()
    }

    @Test
    fun `blank input is not understood`() = runTest {
        assertThat(service.ask("", context())).isInstanceOf(AiAnswer.NotUnderstood::class.java)
        assertThat(service.ask("   ", context())).isInstanceOf(AiAnswer.NotUnderstood::class.java)
    }

    @Test
    fun `every answer carries the figures behind it`() = runTest {
        val questions = listOf(
            "How much did I spend on food this month?",
            "How much did I spend this month?",
            "How much did I earn this month?",
            "Show my largest expenses",
            "How much did I save this month?",
            "What changed compared to last month?",
        )
        for (question in questions) {
            val answer = service.ask(question, context())
            assertThat(answer).isInstanceOf(AiAnswer.Answered::class.java)
            assertThat((answer as AiAnswer.Answered).evidence).isNotEmpty()
        }
    }

    @Test
    fun `suggested questions reflect what the data can answer`() {
        val suggestions = service.suggestedQuestions(context())

        assertThat(suggestions).isNotEmpty()
        assertThat(suggestions.size).isAtMost(6)
        // The user's actual biggest category should be offered, not a guess.
        assertThat(suggestions.any { it.contains("Rent") || it.contains("Food") }).isTrue()
    }
}

class AiConsentAndConfigTest {

    @Test
    fun `cloud AI needs consent, entitlement and configuration together`() {
        assertThat(AiConsentState().canUseCloud).isFalse()
        assertThat(
            AiConsentState(cloudProcessingEnabled = true, hasEntitlement = true, isConfigured = false)
                .canUseCloud,
        ).isFalse()
        assertThat(
            AiConsentState(cloudProcessingEnabled = true, hasEntitlement = false, isConfigured = true)
                .canUseCloud,
        ).isFalse()
        assertThat(
            AiConsentState(cloudProcessingEnabled = false, hasEntitlement = true, isConfigured = true)
                .canUseCloud,
        ).isFalse()
        assertThat(
            AiConsentState(cloudProcessingEnabled = true, hasEntitlement = true, isConfigured = true)
                .canUseCloud,
        ).isTrue()
    }

    @Test
    fun `the default state is privacy preserving`() {
        val default = AiConsentState()
        assertThat(default.cloudProcessingEnabled).isFalse()
        assertThat(default.blockedReason()).isNotNull()
    }

    @Test
    fun `the blocked reason explains which condition failed`() {
        assertThat(AiConsentState(isConfigured = false).blockedReason()).contains("not configured")
        assertThat(
            AiConsentState(isConfigured = true, hasEntitlement = false).blockedReason(),
        ).contains("AI Pro")
        assertThat(
            AiConsentState(isConfigured = true, hasEntitlement = true, cloudProcessingEnabled = false)
                .blockedReason(),
        ).contains("turned off")
    }

    @Test
    fun `a cloud endpoint must use HTTPS`() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudAiConfig(endpoint = "http://example.test/v1", apiKey = "k", model = "m")
        }
    }

    @Test
    fun `a cloud config requires a key`() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudAiConfig(endpoint = "https://example.test/v1", apiKey = "", model = "m")
        }
    }

    @Test
    fun `the API key is never exposed by toString`() {
        val config = CloudAiConfig(
            endpoint = "https://example.test/v1",
            apiKey = "super-secret-value",
            model = "m",
        )
        assertThat(config.toString()).doesNotContain("super-secret-value")
        assertThat(config.toString()).contains("***")
    }
}
