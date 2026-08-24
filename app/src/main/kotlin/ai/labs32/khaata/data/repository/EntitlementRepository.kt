package ai.labs32.khaata.data.repository

import ai.labs32.khaata.core.billing.BillingProvider
import ai.labs32.khaata.core.billing.BillingPurchase
import ai.labs32.khaata.core.billing.PurchaseState
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.entitlement.Entitlement
import ai.labs32.khaata.core.entitlement.EntitlementManager
import ai.labs32.khaata.core.entitlement.Feature
import ai.labs32.khaata.core.entitlement.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user is entitled to, derived from what the store reports they own.
 *
 * Entitlement is never cached to disk as a plain "isPro" flag. Anything writable is trivially
 * editable on a rooted device, and more importantly a stale flag survives a cancellation. The
 * store's current view of purchases is the source of truth, and it is re-read on every launch.
 *
 * Nothing in the UI asks "what tier is this user?" — screens ask [isUnlocked] about a specific
 * [Feature], so moving a feature between tiers is a change in one enum.
 */
@Singleton
class EntitlementRepository @Inject constructor(
    private val billingProvider: BillingProvider,
    private val entitlementManager: EntitlementManager,
    private val clock: KhaataClock,
) {

    /** The user's current entitlement, following the store's purchase list. */
    val entitlement: Flow<Entitlement> = billingProvider.purchases.map { it.toEntitlement() }

    fun observeTier(): Flow<Tier> =
        entitlement.map { entitlementManager.effectiveTier(it, clock.now()) }

    fun observeFeature(feature: Feature): Flow<Boolean> =
        entitlement.map { entitlementManager.isUnlocked(feature, it, clock.now()) }

    fun observeShouldShowAds(): Flow<Boolean> =
        entitlement.map { entitlementManager.shouldShowAds(it, clock.now()) }

    fun observeRemainingAccountSlots(currentCount: Int): Flow<Int?> =
        entitlement.map { entitlementManager.remainingAccountSlots(currentCount, it, clock.now()) }

    suspend fun current(): Entitlement = entitlement.first()

    suspend fun currentTier(): Tier = entitlementManager.effectiveTier(current(), clock.now())

    suspend fun isUnlocked(feature: Feature): Boolean =
        entitlementManager.isUnlocked(feature, current(), clock.now())

    suspend fun shouldShowAds(): Boolean =
        entitlementManager.shouldShowAds(current(), clock.now())

    suspend fun canAddAccount(currentCount: Int): Boolean =
        entitlementManager.canAddAccount(currentCount, current(), clock.now())

    /** Re-reads purchases from the store — on launch and from "Restore purchases". */
    suspend fun refresh(): Result<Unit> {
        billingProvider.connect()
        return billingProvider.restorePurchases().map { }
    }

    /**
     * Collapses the store's purchase list into a single entitlement.
     *
     * The highest owned tier wins, so someone who upgraded mid-term is never downgraded by an
     * older purchase still showing as active.
     */
    private fun List<BillingPurchase>.toEntitlement(): Entitlement {
        if (isEmpty()) return Entitlement.FREE

        val best = filter { it.tier != null }
            .maxByOrNull { purchase ->
                // Settled purchases outrank pending ones at the same tier.
                val settled = if (purchase.state == PurchaseState.PURCHASED) 1 else 0
                (purchase.tier?.level ?: 0) * 10 + settled
            } ?: return Entitlement.FREE

        return Entitlement(
            tier = best.tier ?: Tier.FREE,
            // Play does not expose an expiry through the client library; a purchase stays in the
            // list while the subscription is active and drops out when it is not, so entitlement
            // follows presence rather than a stored date. A build with a backend should set this
            // from a verified server-side expiry — see docs/BILLING.md.
            expiresAt = null,
            isPending = best.state == PurchaseState.PENDING,
            isInGracePeriod = false,
            purchaseToken = best.purchaseToken,
        )
    }
}
