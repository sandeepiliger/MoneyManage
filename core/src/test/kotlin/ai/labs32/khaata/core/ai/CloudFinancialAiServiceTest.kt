package ai.labs32.khaata.core.ai

import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class CloudFinancialAiServiceTest {

    private val today = LocalDate.of(2026, 3, 15)
    private val local = LocalFinancialAiService()

    private val context = AiContext(
        transactions = listOf(
            Fixtures.income(amount = "112000", on = LocalDate.of(2026, 3, 1)),
            Fixtures.expense(
                amount = "3000",
                categoryId = "cat-swiggy",
                merchant = "Swiggy",
                on = LocalDate.of(2026, 3, 5),
                accountId = "acc-hdfc",
            ),
            Fixtures.expense(amount = "4000", categoryId = "cat-fuel", on = LocalDate.of(2026, 3, 7)),
            Fixtures.expense(amount = "9999", categoryId = "cat-fuel", on = LocalDate.of(2026, 3, 8), isPending = true),
            Fixtures.expense(amount = "2500", categoryId = "cat-food", on = LocalDate.of(2026, 2, 5)),
        ),
        categories = listOf(
            Fixtures.category("cat-food", "Food", CategoryGroup.FOOD),
            Fixtures.category("cat-swiggy", "Swiggy", CategoryGroup.FOOD, parentId = "cat-food"),
            Fixtures.category("cat-fuel", "Fuel", CategoryGroup.TRANSPORT),
        ),
        budgets = listOf(Fixtures.budget(name = "Food", limit = "10000")),
        subscriptions = emptyList(),
        accountNames = mapOf("acc-hdfc" to "HDFC Bank"),
        today = today,
        currency = CurrencyCode.INR,
        availableBalance = Money.of("60000"),
        monthlyIncome = Money.of("112000"),
    )

    private val config = CloudAiConfig(endpoint = "https://ai.example.test/chat", apiKey = "", model = "gpt-4o-mini")

    private fun completion(text: String) =
        """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":${Json.encodeToString(String.serializer(), text)}}}]}"""

    private class RecordingTransport(private val reply: () -> CloudAiResponse) : CloudAiTransport {
        var calls = 0
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String = ""
        override suspend fun post(url: String, headers: Map<String, String>, body: String): CloudAiResponse {
            calls++
            lastHeaders = headers
            lastBody = body
            return reply()
        }
    }

    private fun service(transport: CloudAiTransport, allowed: Boolean = true) =
        CloudFinancialAiService(config, transport, local, canUseCloud = { allowed })

    @Test
    fun `a cloud reply is shown with the device's own figures underneath`() = runTest {
        val transport = RecordingTransport { CloudAiResponse(200, completion("You spent ₹3,000 on food this month.")) }
        val answer = service(transport).ask("How much did I spend on food this month?", context)

        assertThat(answer).isInstanceOf(AiAnswer.Answered::class.java)
        answer as AiAnswer.Answered
        assertThat(answer.summary).isEqualTo("You spent ₹3,000 on food this month.")
        assertThat(answer.source).isEqualTo(AnswerSource.CLOUD_ASSISTED)
        val localAnswer = local.ask("How much did I spend on food this month?", context) as AiAnswer.Answered
        assertThat(answer.evidence).isEqualTo(localAnswer.evidence)
    }

    @Test
    fun `without consent nothing is sent and the device answers`() = runTest {
        val transport = RecordingTransport { error("must not be called") }
        val answer = service(transport, allowed = false).ask("How much did I spend on food this month?", context)

        assertThat(transport.calls).isEqualTo(0)
        assertThat((answer as AiAnswer.Answered).source).isEqualTo(AnswerSource.ON_DEVICE)
    }

    @Test
    fun `a failed call, an error status or an empty reply falls back to the device`() = runTest {
        val question = "How much did I spend on food this month?"
        val expected = local.ask(question, context)
        listOf<() -> CloudAiResponse>(
            { throw IOException("offline") },
            { CloudAiResponse(429, """{"error":"rate limited"}""") },
            { CloudAiResponse(200, """{"choices":[]}""") },
            { CloudAiResponse(200, """{"choices":[{"message":{"content":null}}]}""") },
            { CloudAiResponse(200, "not json") },
        ).forEach { reply ->
            assertThat(service(RecordingTransport(reply)).ask(question, context)).isEqualTo(expected)
        }
    }

    @Test
    fun `the key is sent in both forms only when there is one`() {
        assertThat(CloudAiPrompt.headers(config)).doesNotContainKey("api-key")
        assertThat(CloudAiPrompt.headers(config)).doesNotContainKey("Authorization")

        val keyed = CloudAiPrompt.headers(config.copy(apiKey = "k-123"))
        assertThat(keyed["api-key"]).isEqualTo("k-123")
        assertThat(keyed["Authorization"]).isEqualTo("Bearer k-123")
    }

    @Test
    fun `the request is an OpenAI-style chat completion carrying the question`() {
        val body = Json.parseToJsonElement(CloudAiPrompt.requestBody(config, "  Can I afford a phone?  ", "SUMMARY")).jsonObject
        assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo("gpt-4o-mini")
        val messages = body["messages"]!!.jsonArray
        assertThat(messages[0].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("system")
        val user = messages[1].jsonObject["content"]!!.jsonPrimitive.content
        assertThat(user).contains("SUMMARY")
        assertThat(user).endsWith("Question: Can I afford a phone?")

        val azure = Json.parseToJsonElement(CloudAiPrompt.requestBody(config.copy(model = ""), "q", "s")).jsonObject
        assertThat(azure).doesNotContainKey("model")
    }

    /** What leaves the device is totals: no merchant, no account name, no pending claim. */
    @Test
    fun `the summary carries totals and never rows`() {
        val summary = CloudAiPrompt.summarise(context, localAnswer = null)

        assertThat(summary).contains("Today: 2026-03-15")
        assertThat(summary).contains("Mar 2026: spent ₹7,000")
        assertThat(summary).contains("Food ₹3,000") // Swiggy rolled up into its parent
        assertThat(summary).contains("Food: spent ₹3,000 of ₹10,000")
        assertThat(summary).doesNotContain("Swiggy")
        assertThat(summary).doesNotContain("HDFC")
        assertThat(summary).doesNotContain("9,999")
    }
}
