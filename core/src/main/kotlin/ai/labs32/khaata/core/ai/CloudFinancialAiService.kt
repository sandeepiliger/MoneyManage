package ai.labs32.khaata.core.ai

import ai.labs32.khaata.core.calc.BudgetCalculator
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.sumOfMoney
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One HTTP POST. Supplied by the app so this module stays free of any networking stack. */
fun interface CloudAiTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): CloudAiResponse
}

data class CloudAiResponse(val status: Int, val body: String)

/**
 * The cloud assistant: an OpenAI-compatible chat-completions call, grounded in figures computed
 * on the device.
 *
 * Works against Azure OpenAI / Azure AI Foundry deployments, OpenAI itself, or -- the
 * recommended setup -- a backend the operator runs that holds the provider key and forwards the
 * request. [CloudAiConfig.endpoint] is the full URL to POST to.
 *
 * What leaves the device is [CloudAiPrompt.summarise]'s output and the question: monthly and
 * per-category totals, budget progress, subscription names, and the on-device engine's own
 * answer. Never the ledger, never an account number, never a merchant history.
 *
 * Every failure -- consent withdrawn, network, a non-2xx reply, an empty completion -- returns the
 * on-device answer instead, so a user who asked a question about their own data always gets the
 * answer the device can give.
 */
class CloudFinancialAiService(
    private val config: CloudAiConfig,
    private val transport: CloudAiTransport,
    private val local: FinancialAiService,
    /** Re-checked on every question, so revoking consent takes effect on the next one. */
    private val canUseCloud: suspend () -> Boolean,
) : FinancialAiService {

    override val requiresNetwork: Boolean = true

    override val providerName: String =
        config.model.takeIf { it.isNotBlank() }
            ?: runCatching { URI(config.endpoint).host }.getOrNull()
            ?: "Cloud"

    override fun suggestedQuestions(context: AiContext): List<String> = local.suggestedQuestions(context)

    override suspend fun ask(question: String, context: AiContext): AiAnswer {
        val localAnswer = local.ask(question, context)
        if (!canUseCloud()) return localAnswer

        return try {
            val response = transport.post(
                url = config.endpoint,
                headers = CloudAiPrompt.headers(config),
                body = CloudAiPrompt.requestBody(config, question, CloudAiPrompt.summarise(context, localAnswer)),
            )
            val reply = if (response.status in 200..299) CloudAiPrompt.parseReply(response.body) else null
            if (reply == null) {
                localAnswer
            } else {
                val grounded = localAnswer as? AiAnswer.Answered
                AiAnswer.Answered(
                    summary = reply,
                    // The figures shown under the answer are always the device's own, so what the
                    // user can check never depends on what a model chose to repeat.
                    evidence = grounded?.evidence.orEmpty(),
                    relatedTransactionIds = grounded?.relatedTransactionIds.orEmpty(),
                    source = AnswerSource.CLOUD_ASSISTED,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            localAnswer
        }
    }
}

/** Building the request and reading the reply, kept pure so both are testable. */
object CloudAiPrompt {

    private const val MAX_CATEGORIES = 12
    private const val MAX_SUBSCRIPTIONS = 8
    private const val MONTHS = 6
    private const val MAX_REPLY_CHARS = 1_200

    private val json = Json { ignoreUnknownKeys = true }
    private val monthLabel = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    val SYSTEM_PROMPT: String = """
        You are the assistant inside Khaata, a personal finance app used in India.
        Answer the user's question using only the figures in the summary you are given.
        If those figures cannot answer it, say plainly what is missing; never estimate or invent a number.
        Do not recommend investments, loans, insurance or any financial product, and give no tax advice.
        Reply in two to four short sentences, in the language the question was asked in, with amounts formatted as in the summary.
    """.trimIndent()

    /**
     * Headers for the request. The key is optional: a backend the operator runs authenticates the
     * app its own way and needs none. When one is set it is sent in both the Azure (`api-key`)
     * and the OpenAI (`Authorization`) form, so the same build works against either.
     */
    fun headers(config: CloudAiConfig): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) {
            put("api-key", config.apiKey)
            put("Authorization", "Bearer ${config.apiKey}")
        }
    }

    fun requestBody(config: CloudAiConfig, question: String, summary: String): String =
        buildJsonObject {
            // Azure deployment URLs name the model already; sending none keeps them happy.
            if (config.model.isNotBlank()) put("model", config.model)
            put("temperature", 0.2)
            put("max_tokens", 400)
            put(
                "messages",
                buildJsonArray {
                    add(message("system", SYSTEM_PROMPT))
                    add(message("user", "Summary of my finances:\n$summary\n\nQuestion: ${question.trim()}"))
                },
            )
        }.toString()

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** The completion's text, or null for anything that is not a usable reply. */
    fun parseReply(body: String): String? = runCatching {
        val choices = json.parseToJsonElement(body).jsonObject["choices"] as? JsonArray ?: return null
        // A JsonNull's `content` is the string "null", so only a real string counts.
        val content = (choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        content?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REPLY_CHARS)
    }.getOrNull()

    /**
     * The figures a cloud model is allowed to see: totals, never rows.
     *
     * Bounded in size whatever the ledger holds, and built from the same effective-transaction
     * rules every screen uses, so the model is answering from the numbers the user sees.
     */
    fun summarise(context: AiContext, localAnswer: AiAnswer?): String = buildString {
        val currency = context.currency
        fun money(value: Money) = MoneyFormatter.plain(value)

        appendLine("Today: ${context.today}")
        appendLine("Available to spend now: ${money(context.availableBalance)}")
        context.monthlyIncome?.let { appendLine("Stated monthly income: ${money(it)}") }

        val effective = context.transactions.filter { it.isEffective }
        val months = (MONTHS - 1 downTo 0).map { YearMonth.from(context.today).minusMonths(it.toLong()) }
        appendLine("Spending and income by month (transfers between own accounts excluded):")
        months.forEach { month ->
            val range = DateRange.ofMonth(month)
            val inMonth = effective.filter { it.occurredOn in range }
            val spent = inMonth.filter { it.countsAsSpending }.sumOfMoney(currency) { it.amount }
            val earned = inMonth.filter { it.countsAsIncome }.sumOfMoney(currency) { it.amount }
            appendLine("- ${month.format(monthLabel)}: spent ${money(spent)}, income ${money(earned)}")
        }

        val rollup = BudgetCalculator.buildCategoryRollup(context.categories)
        val names = context.categories.associate { it.id to it.name }
        fun byCategory(month: YearMonth): List<Pair<String, Money>> {
            val range = DateRange.ofMonth(month)
            return effective
                .filter { it.countsAsSpending && it.occurredOn in range }
                .groupBy { transaction ->
                    val id = transaction.categoryId?.let { rollup[it] ?: it }
                    id?.let { names[it] } ?: "Uncategorised"
                }
                .map { (name, rows) -> name to rows.sumOfMoney(currency) { it.amount } }
                .sortedByDescending { it.second.amount }
                .take(MAX_CATEGORIES)
        }
        listOf("This month" to months.last(), "Last month" to months[months.size - 2]).forEach { (label, month) ->
            val rows = byCategory(month)
            if (rows.isNotEmpty()) {
                appendLine("$label by category: " + rows.joinToString(", ") { "${it.first} ${money(it.second)}" })
            }
        }

        val budgets = context.budgets.filter { it.isActive }
        if (budgets.isNotEmpty()) {
            appendLine("Budgets this period:")
            budgets.forEach { budget ->
                val progress = BudgetCalculator.evaluateWithCarryOver(budget, effective, context.today, rollup)
                appendLine(
                    "- ${budget.name}: spent ${money(progress.spent)} of ${money(progress.limit)}, " +
                        "${progress.daysRemaining} days left",
                )
            }
        }

        val subscriptions = context.subscriptions.filter { it.isActive && it.cancelledOn == null }
        if (subscriptions.isNotEmpty()) {
            appendLine(
                "Active subscriptions: " + subscriptions.take(MAX_SUBSCRIPTIONS).joinToString(", ") {
                    "${it.name} ${money(it.amount)} ${it.cycle.name.lowercase()}"
                },
            )
        }

        when (localAnswer) {
            is AiAnswer.Answered -> {
                appendLine("The app's own calculation for this question: ${localAnswer.summary}")
                localAnswer.evidence.forEach { appendLine("- ${it.label}: ${money(it.amount)}") }
            }
            is AiAnswer.NoData -> appendLine("The app's own calculation found: ${localAnswer.summary}")
            else -> Unit
        }
    }.trimEnd()

}
