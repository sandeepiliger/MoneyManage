package ai.labs32.khaata.core.entitlement

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class EntitlementManagerTest {

    private val manager = EntitlementManager()
    private val now = Instant.parse("2026-03-15T00:00:00Z")
    private val nextYear = Instant.parse("2027-03-15T00:00:00Z").toEpochMilli()
    private val lastYear = Instant.parse("2025-03-15T00:00:00Z").toEpochMilli()

    @Test
    fun `the core expense tracking loop is never paywalled`() {
        val free = Entitlement.FREE
        // An expense tracker that will not let you track expenses converts nobody.
        assertThat(manager.isUnlocked(Feature.UNLIMITED_TRANSACTIONS, free, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.BUDGETS, free, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.GOALS, free, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.RULE_BASED_INSIGHTS, free, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.NATURAL_LANGUAGE_ENTRY, free, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.BIOMETRIC_LOCK, free, now)).isTrue()
        // Data portability is never held hostage either.
        assertThat(manager.isUnlocked(Feature.CSV_EXPORT, free, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.JSON_BACKUP, free, now)).isTrue()
        // A date picker is a weak paywall with a real irritation cost.
        assertThat(manager.isUnlocked(Feature.CUSTOM_DATE_RANGES, free, now)).isTrue()
    }

    @Test
    fun `the advanced reports flag actually withholds something from free`() {
        // This flag used to be named on the paywall and enforced nowhere, so a paying user got
        // the identical reports screen a free user did. It now means the merchant and account
        // breakdowns and the statement PDF, and this is what stops it drifting back.
        assertThat(manager.isUnlocked(Feature.ADVANCED_REPORTS, Entitlement.FREE, now)).isFalse()
        assertThat(Feature.ADVANCED_REPORTS.isShipped).isTrue()

        val pro = Entitlement(tier = Tier.PRO, expiresAt = nextYear)
        assertThat(manager.isUnlocked(Feature.ADVANCED_REPORTS, pro, now)).isTrue()
    }

    @Test
    fun `every shipped paid feature is withheld from free`() {
        // A paid flag that free already satisfies is a refund waiting to happen: it is advertised
        // on the paywall as a reason to upgrade and changes nothing when someone does.
        val soldToFree = Feature.SHIPPED
            .filter { it.minimumTier != Tier.FREE }
            .filter { manager.isUnlocked(it, Entitlement.FREE, now) }

        assertThat(soldToFree).isEmpty()
    }

    @Test
    fun `free users do not get paid features`() {
        val free = Entitlement.FREE
        assertThat(manager.isUnlocked(Feature.AD_FREE, free, now)).isFalse()
        assertThat(manager.isUnlocked(Feature.ADVANCED_REPORTS, free, now)).isFalse()
        assertThat(manager.isUnlocked(Feature.CLOUD_AI_ASSISTANT, free, now)).isFalse()
        assertThat(manager.isUnlocked(Feature.SHARED_HOUSEHOLD, free, now)).isFalse()
    }

    @Test
    fun `tiers are cumulative`() {
        val aiPro = Entitlement(Tier.AI_PRO, expiresAt = nextYear)

        // AI Pro includes everything Pro has.
        assertThat(manager.isUnlocked(Feature.AD_FREE, aiPro, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.ADVANCED_REPORTS, aiPro, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.CLOUD_AI_ASSISTANT, aiPro, now)).isTrue()
        // ...but not what only Family has.
        assertThat(manager.isUnlocked(Feature.SHARED_HOUSEHOLD, aiPro, now)).isFalse()
    }

    @Test
    fun `family includes every shipped tier below it`() {
        val family = Entitlement(Tier.FAMILY, expiresAt = nextYear)
        for (feature in Feature.SHIPPED) {
            assertThat(manager.isUnlocked(feature, family, now)).isTrue()
        }
    }

    @Test
    fun `a feature that is not built yet never unlocks, even on the top tier`() {
        val family = Entitlement(Tier.FAMILY, expiresAt = nextYear)

        // Paying for the highest tier must not report an unbuilt feature as available: the
        // paywall reads the same flag, so an entitlement granted here would be a feature sold
        // and never delivered.
        val unshipped = Feature.entries.filterNot { it.isShipped }
        assertThat(unshipped).isNotEmpty()

        for (feature in unshipped) {
            assertThat(manager.isUnlocked(feature, family, now)).isFalse()
        }
    }

    @Test
    fun `every shipped feature is reachable from the tier that sells it`() {
        // Guards the inverse mistake: marking something shipped that no tier can actually reach.
        for (feature in Feature.SHIPPED) {
            val entitlement = Entitlement(feature.minimumTier, expiresAt = nextYear)
            assertThat(manager.isUnlocked(feature, entitlement, now)).isTrue()
        }
    }

    @Test
    fun `an expired subscription falls back to free`() {
        val expired = Entitlement(Tier.PRO, expiresAt = lastYear)

        assertThat(manager.effectiveTier(expired, now)).isEqualTo(Tier.FREE)
        assertThat(manager.isUnlocked(Feature.AD_FREE, expired, now)).isFalse()
        // Free features keep working — an expired subscription must never lock a user out of
        // their own financial records.
        assertThat(manager.isUnlocked(Feature.UNLIMITED_TRANSACTIONS, expired, now)).isTrue()
        assertThat(manager.isUnlocked(Feature.JSON_BACKUP, expired, now)).isTrue()
    }

    @Test
    fun `a grace period keeps the subscription working`() {
        val grace = Entitlement(Tier.PRO, expiresAt = lastYear, isInGracePeriod = true)

        assertThat(manager.effectiveTier(grace, now)).isEqualTo(Tier.PRO)
        assertThat(manager.isUnlocked(Feature.AD_FREE, grace, now)).isTrue()
    }

    @Test
    fun `a pending purchase grants nothing until it settles`() {
        // UPI mandates can sit pending for a while; the money has not moved yet.
        val pending = Entitlement(Tier.PRO, expiresAt = nextYear, isPending = true)

        assertThat(manager.effectiveTier(pending, now)).isEqualTo(Tier.FREE)
        assertThat(manager.isUnlocked(Feature.AD_FREE, pending, now)).isFalse()
    }

    @Test
    fun `an entitlement without an expiry never lapses`() {
        val lifetime = Entitlement(Tier.PRO, expiresAt = null)
        assertThat(manager.effectiveTier(lifetime, now)).isEqualTo(Tier.PRO)
    }

    @Test
    fun `free accounts are capped by count`() {
        val free = Entitlement.FREE

        assertThat(manager.canAddAccount(0, free, now)).isTrue()
        assertThat(manager.canAddAccount(3, free, now)).isTrue()
        assertThat(manager.canAddAccount(4, free, now)).isFalse()
        assertThat(manager.remainingAccountSlots(2, free, now)).isEqualTo(2)
        assertThat(manager.remainingAccountSlots(9, free, now)).isEqualTo(0)
    }

    @Test
    fun `pro removes the account cap`() {
        val pro = Entitlement(Tier.PRO, expiresAt = nextYear)

        assertThat(manager.canAddAccount(99, pro, now)).isTrue()
        assertThat(manager.remainingAccountSlots(99, pro, now)).isNull()
    }

    @Test
    fun `ads follow the ad-free entitlement`() {
        assertThat(manager.shouldShowAds(Entitlement.FREE, now)).isTrue()
        assertThat(manager.shouldShowAds(Entitlement(Tier.PRO, expiresAt = nextYear), now)).isFalse()
        assertThat(manager.shouldShowAds(Entitlement(Tier.PRO, expiresAt = lastYear), now)).isTrue()
    }

    @Test
    fun `product ids map back to their tier`() {
        assertThat(Tier.fromProductId("khaata_pro_yearly")).isEqualTo(Tier.PRO)
        assertThat(Tier.fromProductId("khaata_ai_pro_yearly")).isEqualTo(Tier.AI_PRO)
        assertThat(Tier.fromProductId("khaata_family_yearly")).isEqualTo(Tier.FAMILY)
        assertThat(Tier.fromProductId("something_else")).isNull()
        assertThat(Tier.fromProductId(null)).isNull()
    }

    @Test
    fun `the paywall can name the tier a feature needs`() {
        assertThat(manager.requiredTier(Feature.AD_FREE)).isEqualTo(Tier.PRO)
        assertThat(manager.requiredTier(Feature.CLOUD_AI_ASSISTANT)).isEqualTo(Tier.AI_PRO)
        assertThat(manager.requiredTier(Feature.SHARED_GOALS)).isEqualTo(Tier.FAMILY)
    }

    // ---- What a build cannot deliver ---------------------------------------------------------

    @Test
    fun `a feature this build cannot deliver is withheld from someone who paid for it`() {
        val restricted = EntitlementManager(
            unavailableInThisBuild = setOf(Feature.CLOUD_AI_ASSISTANT),
        )

        // The tier is right, the purchase is settled, and the build still has no endpoint to
        // reach -- so the answer is no, rather than a subscription that reports it is unconfigured.
        assertThat(
            restricted.isUnlocked(Feature.CLOUD_AI_ASSISTANT, Entitlement(Tier.AI_PRO), now),
        ).isFalse()
    }

    @Test
    fun `a feature this build cannot deliver is not advertised on the paywall`() {
        val restricted = EntitlementManager(
            unavailableInThisBuild = setOf(Feature.CLOUD_AI_ASSISTANT),
        )

        // AI Pro's other two features are unshipped, so with the assistant withheld the tier has
        // nothing left to sell. The paywall drops a plan with no features, which is the point:
        // charging for a plan whose every feature is unavailable is taking money for nothing.
        assertThat(restricted.sellableFeatures(Tier.AI_PRO)).isEmpty()
    }

    @Test
    fun `restricting one feature does not withhold the others`() {
        val restricted = EntitlementManager(
            unavailableInThisBuild = setOf(Feature.CLOUD_AI_ASSISTANT),
        )

        assertThat(
            restricted.isUnlocked(Feature.RECEIPT_ATTACHMENTS, Entitlement(Tier.PRO), now),
        ).isTrue()
        assertThat(restricted.sellableFeatures(Tier.PRO)).isNotEmpty()
    }

    @Test
    fun `a fully configured build sells and unlocks the assistant as normal`() {
        // The default manager is the configured case, so this is also the guard against the
        // restriction leaking into builds that can in fact deliver.
        assertThat(manager.isUnlocked(Feature.CLOUD_AI_ASSISTANT, Entitlement(Tier.AI_PRO), now))
            .isTrue()
        assertThat(manager.sellableFeatures(Tier.AI_PRO)).contains(Feature.CLOUD_AI_ASSISTANT)
    }

    @Test
    fun `every sellable feature is one the entitlement check will actually grant`() {
        val restricted = EntitlementManager(
            unavailableInThisBuild = setOf(Feature.CLOUD_AI_ASSISTANT),
        )

        // The invariant the paywall depends on: nothing advertised for a tier may then be refused
        // to someone who buys that tier. This is the drift that let ADVANCED_REPORTS be sold while
        // gating nothing, in the other direction.
        Tier.entries.forEach { tier ->
            restricted.sellableFeatures(tier).forEach { feature ->
                assertThat(restricted.isUnlocked(feature, Entitlement(tier), now)).isTrue()
            }
        }
    }

}
