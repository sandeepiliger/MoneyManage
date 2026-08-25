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
}
