package ai.labs32.khaata.core.categorize

import ai.labs32.khaata.core.model.MerchantRule

/**
 * Suggests a category (and account) for a merchant.
 *
 * Three layers, strongest first:
 *  1. **The user's own rule.** An explicit correction always wins. If someone files Amazon under
 *     Groceries, that is the right answer for them, permanently.
 *  2. **What they usually do.** Rules learned from repeated confirmations, ranked by how often
 *     the pairing has held.
 *  3. **The shipped India set.** [SeedMerchantRules], so a fresh install is useful on day one.
 *
 * All of it runs on-device against the user's own history. Nothing here consults a network
 * service, and no merchant data leaves the phone.
 */
class MerchantCategorizer(
    private val seedRules: Map<String, String> = SeedMerchantRules.RULES,
) {

    /**
     * Suggests a category for [merchantText] given the user's learned [rules].
     *
     * Returns null when nothing matches, which the UI shows as an un-preselected picker rather
     * than a wrong guess the user has to notice and undo.
     */
    fun suggest(merchantText: String?, rules: List<MerchantRule>): CategorySuggestion? {
        val key = MerchantNormaliser.normalise(merchantText) ?: return null

        val candidates = rules.filter { it.merchantKey == key }
        if (candidates.isNotEmpty()) {
            // User-defined beats learned; among equals, the most-confirmed pairing wins.
            val best = candidates.maxWith(
                compareBy<MerchantRule> { if (it.isUserDefined) 1 else 0 }.thenBy { it.confidence },
            )
            return CategorySuggestion(
                categoryId = best.categoryId,
                accountId = best.accountId,
                merchantKey = key,
                displayName = MerchantNormaliser.displayName(merchantText),
                source = if (best.isUserDefined) SuggestionSource.USER_RULE else SuggestionSource.LEARNED,
                confidence = best.confidence,
            )
        }

        val seeded = seedRules[key] ?: return null
        return CategorySuggestion(
            categoryId = seeded,
            accountId = null,
            merchantKey = key,
            displayName = MerchantNormaliser.displayName(merchantText),
            source = SuggestionSource.SEEDED,
            confidence = 1,
        )
    }

    /**
     * The rule set after the user files [merchantText] under [categoryId].
     *
     * Returns the full updated list so the caller can persist it in one write. An existing rule
     * for the same merchant is strengthened when it agrees and replaced when it does not — a
     * correction should take effect immediately, not after the user has made it five times.
     */
    fun learn(
        merchantText: String?,
        categoryId: String,
        accountId: String?,
        rules: List<MerchantRule>,
        isExplicitUserChoice: Boolean,
        newRuleId: () -> String,
    ): List<MerchantRule> {
        val key = MerchantNormaliser.normalise(merchantText) ?: return rules

        val existing = rules.firstOrNull { it.merchantKey == key }
        if (existing == null) {
            return rules + MerchantRule(
                id = newRuleId(),
                merchantKey = key,
                categoryId = categoryId,
                accountId = accountId,
                confidence = 1,
                isUserDefined = isExplicitUserChoice,
            )
        }

        val updated = if (existing.categoryId == categoryId) {
            existing.copy(
                confidence = (existing.confidence + 1).coerceAtMost(MAX_CONFIDENCE),
                accountId = accountId ?: existing.accountId,
                isUserDefined = existing.isUserDefined || isExplicitUserChoice,
            )
        } else {
            // The user disagreed with the previous pairing. Reset rather than decay, so the
            // correction is honoured on the very next transaction.
            existing.copy(
                categoryId = categoryId,
                accountId = accountId ?: existing.accountId,
                confidence = 1,
                isUserDefined = isExplicitUserChoice,
            )
        }
        return rules.map { if (it.id == existing.id) updated else it }
    }

    /** True when [merchantText] looks like a recurring service worth tracking as a subscription. */
    fun looksLikeSubscription(merchantText: String?): Boolean {
        val key = MerchantNormaliser.normalise(merchantText) ?: return false
        return key in SeedMerchantRules.SUBSCRIPTION_MERCHANTS
    }

    companion object {
        /** Confidence is capped so one very frequent merchant cannot dominate ranking forever. */
        const val MAX_CONFIDENCE = 50
    }
}

data class CategorySuggestion(
    val categoryId: String,
    /** Suggested account, when the user consistently pays this merchant from one account. */
    val accountId: String?,
    val merchantKey: String,
    /** Cleaned-up merchant name to prefill the field with. */
    val displayName: String?,
    val source: SuggestionSource,
    val confidence: Int,
)

/**
 * Where a suggestion came from.
 *
 * Surfaced in the UI as a subtle hint ("usually Food") so the user understands why a category was
 * preselected rather than finding it mysteriously filled in.
 */
enum class SuggestionSource { USER_RULE, LEARNED, SEEDED }
