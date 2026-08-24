package ai.labs32.khaata.core.insights

import ai.labs32.khaata.core.money.Money

/**
 * One observation about the user's money, with the numbers that produced it.
 *
 * [evidence] is not decoration. A user who taps an insight should be able to see exactly which
 * figures it came from and check the arithmetic themselves. That is what separates a useful
 * observation from a claim they have to take on faith.
 */
data class Insight(
    /**
     * Stable across regenerations for the same underlying observation, so the UI can dismiss one
     * and have it stay dismissed rather than reappearing under a new identity.
     */
    val id: String,
    val kind: InsightKind,
    val severity: InsightSeverity,
    /** Ranking weight within a severity band, typically the rupee magnitude involved. */
    val relevance: Double,
    val title: String,
    val detail: String,
    val evidence: List<Evidence>,
    val categoryId: String? = null,
    val budgetId: String? = null,
    val transactionId: String? = null,
) {
    /** True when this insight is worth interrupting the user for with a notification. */
    val warrantsNotification: Boolean get() = severity == InsightSeverity.ACTION_NEEDED
}

/** One labelled figure behind an insight. */
data class Evidence(val label: String, val amount: Money)

enum class InsightKind {
    CATEGORY_TREND,
    BUDGET,
    SUBSCRIPTION,
    CASHFLOW,
    SAVINGS,
    PATTERN,
}

/**
 * How much attention an insight deserves.
 *
 * Ordered least to most severe so `ordinal` can drive sorting. Each level maps to a distinct
 * icon and label in the UI, never to colour alone.
 */
enum class InsightSeverity {
    INFORMATIONAL,
    NOTABLE,
    ACTION_NEEDED,
}
