package ai.labs32.khaata.core.ai

import ai.labs32.khaata.core.insights.Evidence
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.Money
import java.time.LocalDate

/**
 * The app's financial assistant.
 *
 * Two implementations sit behind this interface: [LocalFinancialAiService], which runs entirely
 * on-device and is the default, and a cloud provider the user can opt into. Swapping providers —
 * or losing one — never changes what a caller has to do.
 *
 * Three rules hold for every implementation:
 *
 *  1. **The assistant answers questions; it never moves money.** There is no path from a
 *     conversation to a written transaction. Anything that would change the ledger goes through
 *     the normal confirmation UI, with the user tapping save.
 *  2. **Answers are grounded in the user's own data.** Every figure in an [AiAnswer] comes from
 *     the [AiContext] handed in, and is returned as [Evidence] the UI can display.
 *  3. **Nothing leaves the device without explicit consent.** [requiresNetwork] tells the caller
 *     whether a provider would transmit anything, and the cloud provider is unreachable unless
 *     the user has turned cloud processing on.
 */
interface FinancialAiService {

    /** Whether this provider sends data off the device. */
    val requiresNetwork: Boolean

    /** Identifier shown in the privacy dashboard, e.g. "On-device" or a provider name. */
    val providerName: String

    /**
     * Answers [question] against [context].
     *
     * Implementations must not throw: a provider that is unavailable, rate-limited or confused
     * returns [AiAnswer.Unavailable] or [AiAnswer.NotUnderstood] so the UI can fall back
     * gracefully rather than showing a crash.
     */
    suspend fun ask(question: String, context: AiContext): AiAnswer

    /**
     * Suggested questions to show before the user has typed anything.
     *
     * Tailored to what their data can actually answer — offering "how much did you spend on
     * fuel?" to someone with no fuel transactions wastes a tap and makes the feature look thin.
     */
    fun suggestedQuestions(context: AiContext): List<String>
}

/**
 * The user's financial data, scoped to what a question needs.
 *
 * Assembled by the caller and passed in explicitly rather than letting a provider reach into the
 * database. That makes exactly what a cloud provider would see auditable at the call site, and
 * lets the privacy dashboard describe it honestly.
 */
data class AiContext(
    val transactions: List<Transaction>,
    val categories: List<Category>,
    val budgets: List<Budget>,
    val subscriptions: List<Subscription>,
    val accountNames: Map<String, String>,
    val today: LocalDate,
    val currency: ai.labs32.khaata.core.money.CurrencyCode,
    /** Available-to-spend, for affordability questions. */
    val availableBalance: Money,
    val monthlyIncome: Money?,
)

/** The result of asking a question. */
sealed interface AiAnswer {

    /**
     * A question that was answered.
     *
     * @param summary one or two plain sentences.
     * @param evidence the figures behind [summary], always shown alongside it.
     * @param relatedTransactionIds rows the user can tap through to, so an answer is checkable.
     */
    data class Answered(
        val summary: String,
        val evidence: List<Evidence>,
        val relatedTransactionIds: List<String> = emptyList(),
        val source: AnswerSource,
    ) : AiAnswer

    /** Understood as a question, but the data cannot answer it. */
    data class NoData(val summary: String) : AiAnswer

    /** Not understood. [suggestions] offers phrasings that would work. */
    data class NotUnderstood(val suggestions: List<String>) : AiAnswer

    /** The provider could not be reached or refused. Never a crash. */
    data class Unavailable(val reason: String) : AiAnswer
}

/** Where an answer's numbers came from, surfaced in the UI so the user knows. */
enum class AnswerSource {
    /** Computed on-device from the user's ledger. */
    ON_DEVICE,

    /** Phrased by a cloud model, but computed from figures supplied on-device. */
    CLOUD_ASSISTED,
}

/**
 * Configuration for a cloud AI provider.
 *
 * There is no default endpoint and no bundled key. A build without configuration runs the
 * on-device provider and the cloud option stays disabled in settings — the app is fully
 * functional with no credentials at all.
 */
data class CloudAiConfig(
    val endpoint: String,
    /**
     * Supplied at runtime from secure storage or a build-time secret, never checked into the
     * repository and never written to logs.
     */
    val apiKey: String,
    val model: String,
    val timeoutMillis: Long = 20_000,
) {
    init {
        require(endpoint.startsWith("https://")) {
            // Financial data must never travel in the clear.
            "Cloud AI endpoint must use HTTPS"
        }
        require(apiKey.isNotBlank()) { "Cloud AI requires an API key" }
    }

    /** Redacted so a config can be logged or shown in diagnostics without leaking the key. */
    override fun toString(): String = "CloudAiConfig(endpoint=$endpoint, model=$model, apiKey=***)"
}

/**
 * Whether cloud AI may be used right now.
 *
 * All three must hold, and the default for the first two is off.
 */
data class AiConsentState(
    /** The user turned cloud processing on in settings. */
    val cloudProcessingEnabled: Boolean = false,
    /** The user has an entitlement that includes the cloud assistant. */
    val hasEntitlement: Boolean = false,
    /** A provider is actually configured in this build. */
    val isConfigured: Boolean = false,
) {
    val canUseCloud: Boolean get() = cloudProcessingEnabled && hasEntitlement && isConfigured

    /** Why cloud AI is unavailable, for the UI to explain rather than silently degrade. */
    fun blockedReason(): String? = when {
        canUseCloud -> null
        !isConfigured -> "Cloud AI is not configured in this build."
        !hasEntitlement -> "Cloud AI is part of the AI Pro plan."
        else -> "Cloud AI processing is turned off in Privacy settings."
    }
}
