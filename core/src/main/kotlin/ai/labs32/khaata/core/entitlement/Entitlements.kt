package ai.labs32.khaata.core.entitlement

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * What the user has paid for.
 *
 * Tiers are ordered and cumulative: [FAMILY] includes everything in [AI_PRO], which includes
 * everything in [PRO]. Entitlement is asked of [EntitlementManager] by feature, never by tier
 * comparison at the call site, so adding a tier or moving a feature between tiers is a change in
 * one place rather than a hunt through the UI.
 */
@Serializable
enum class Tier(val level: Int, val productId: String?) {
    FREE(0, null),
    PRO(1, "khaata_pro_yearly"),
    AI_PRO(2, "khaata_ai_pro_yearly"),
    FAMILY(3, "khaata_family_yearly"),
    ;

    fun includes(other: Tier): Boolean = level >= other.level

    companion object {
        fun fromProductId(productId: String?): Tier? =
            entries.firstOrNull { it.productId != null && it.productId == productId }
    }
}

/**
 * A gated capability.
 *
 * [minimumTier] is the single source of truth for what unlocks what. Nothing in the UI hardcodes
 * a tier check.
 */
enum class Feature(val minimumTier: Tier) {
    // ---- Free ------------------------------------------------------------------------------
    // The core loop — recording spending and seeing where it went — is never paywalled. An
    // expense tracker that will not let you track expenses has no users to convert.
    UNLIMITED_TRANSACTIONS(Tier.FREE),
    BASIC_REPORTS(Tier.FREE),
    BUDGETS(Tier.FREE),
    GOALS(Tier.FREE),
    RULE_BASED_INSIGHTS(Tier.FREE),
    CSV_EXPORT(Tier.FREE),
    JSON_BACKUP(Tier.FREE),
    NATURAL_LANGUAGE_ENTRY(Tier.FREE),
    BIOMETRIC_LOCK(Tier.FREE),

    // ---- Pro -------------------------------------------------------------------------------
    AD_FREE(Tier.PRO),
    UNLIMITED_ACCOUNTS(Tier.PRO),
    ADVANCED_REPORTS(Tier.PRO),
    CUSTOM_DATE_RANGES(Tier.PRO),
    RECEIPT_ATTACHMENTS(Tier.PRO),
    SCHEDULED_BACKUP(Tier.PRO),
    BUDGET_ROLLOVER(Tier.PRO),
    DASHBOARD_CUSTOMISATION(Tier.PRO),

    // ---- AI Pro ----------------------------------------------------------------------------
    CLOUD_AI_ASSISTANT(Tier.AI_PRO),
    AI_ENHANCED_INSIGHTS(Tier.AI_PRO),
    AI_SMART_CATEGORISATION(Tier.AI_PRO),

    // ---- Family ----------------------------------------------------------------------------
    SHARED_HOUSEHOLD(Tier.FAMILY),
    FAMILY_BUDGETS(Tier.FAMILY),
    SHARED_GOALS(Tier.FAMILY),
    ;

    /**
     * Whether anything behind this flag actually exists yet.
     *
     * The paywall builds its feature lists from [minimumTier], so a flag added here is advertised
     * for sale the moment its product is configured in the Play console — whether or not a line of
     * it has been written. Everything marked false below is named on the paywall and implemented
     * nowhere, so it is withheld rather than sold: [SHIPPED] is what the entitlement check and the
     * paywall both read.
     *
     * Delete an entry from [UNSHIPPED] in the same change that implements it.
     */
    val isShipped: Boolean get() = this !in UNSHIPPED

    companion object {
        /**
         * Sold-but-unbuilt features, kept in one place so the list cannot quietly drift.
         *
         * The whole FAMILY tier is here: sharing a household ledger needs a server, which this app
         * deliberately does not have, so these three are not close to shipping. The PRO entries are
         * nearer — each has its storage or entitlement plumbing in place and only the UI missing.
         */
        private val UNSHIPPED: Set<Feature> = setOf(
            SHARED_HOUSEHOLD,
            FAMILY_BUDGETS,
            SHARED_GOALS,
            RECEIPT_ATTACHMENTS,
            SCHEDULED_BACKUP,
            CUSTOM_DATE_RANGES,
            DASHBOARD_CUSTOMISATION,
            AI_ENHANCED_INSIGHTS,
            AI_SMART_CATEGORISATION,
        )

        /** Every feature that is actually built, in declaration order. */
        val SHIPPED: List<Feature> get() = entries.filter { it.isShipped }
    }
}

/** The user's current entitlement state. */
@Serializable
data class Entitlement(
    val tier: Tier = Tier.FREE,
    /** Null for [Tier.FREE] and for lifetime grants. */
    val expiresAt: Long? = null,
    /** True while Play reports the purchase as pending (e.g. UPI mandate awaiting approval). */
    val isPending: Boolean = false,
    /** True when the store says the subscription failed to renew. */
    val isInGracePeriod: Boolean = false,
    val purchaseToken: String? = null,
) {
    companion object {
        val FREE = Entitlement(Tier.FREE)
    }
}

/**
 * Decides what the user can do.
 *
 * Free accounts are capped by count rather than by feature, so someone with one bank account and
 * a wallet is never blocked from the thing they installed the app for.
 */
class EntitlementManager(
    private val freeAccountLimit: Int = DEFAULT_FREE_ACCOUNT_LIMIT,
) {

    /**
     * Whether [feature] is available under [entitlement] as of [now].
     *
     * An expired subscription falls back to [Tier.FREE]; a pending purchase does not yet grant
     * anything, because the money has not moved.
     */
    fun isUnlocked(feature: Feature, entitlement: Entitlement, now: Instant): Boolean =
        feature.isShipped && effectiveTier(entitlement, now).includes(feature.minimumTier)

    /**
     * The tier actually in force.
     *
     * A subscription in its grace period keeps working: the user has paid and their card simply
     * failed to renew, so locking their own financial data behind a billing hiccup would be a
     * poor trade for a few days of revenue.
     */
    fun effectiveTier(entitlement: Entitlement, now: Instant): Tier {
        if (entitlement.isPending) return Tier.FREE
        val expiry = entitlement.expiresAt ?: return entitlement.tier
        if (now.toEpochMilli() <= expiry) return entitlement.tier
        return if (entitlement.isInGracePeriod) entitlement.tier else Tier.FREE
    }

    /** Whether another account can be created. */
    fun canAddAccount(currentCount: Int, entitlement: Entitlement, now: Instant): Boolean =
        isUnlocked(Feature.UNLIMITED_ACCOUNTS, entitlement, now) || currentCount < freeAccountLimit

    /** How many more accounts are allowed, or null when unlimited. */
    fun remainingAccountSlots(currentCount: Int, entitlement: Entitlement, now: Instant): Int? =
        if (isUnlocked(Feature.UNLIMITED_ACCOUNTS, entitlement, now)) {
            null
        } else {
            (freeAccountLimit - currentCount).coerceAtLeast(0)
        }

    /** Whether ads should be shown. */
    fun shouldShowAds(entitlement: Entitlement, now: Instant): Boolean =
        !isUnlocked(Feature.AD_FREE, entitlement, now)

    /** The tier a user must reach to unlock [feature], for the paywall's headline. */
    fun requiredTier(feature: Feature): Tier = feature.minimumTier

    companion object {
        /**
         * Enough for a bank account, cash, a wallet and a credit card — the shape of most
         * users' finances. Beyond that is genuinely power-user territory.
         */
        const val DEFAULT_FREE_ACCOUNT_LIMIT = 4
    }
}
