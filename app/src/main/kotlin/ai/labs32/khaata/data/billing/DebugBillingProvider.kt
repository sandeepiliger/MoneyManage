package ai.labs32.khaata.data.billing

import android.app.Activity
import ai.labs32.khaata.core.billing.BillingConnectionState
import ai.labs32.khaata.core.billing.BillingProduct
import ai.labs32.khaata.core.billing.BillingProvider
import ai.labs32.khaata.core.billing.BillingPurchase
import ai.labs32.khaata.core.billing.PurchaseState
import ai.labs32.khaata.core.entitlement.Tier
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A billing provider that grants whatever tier the developer selected, without taking any money.
 *
 * Debug builds deliberately never touch Play, which left every paid feature unreachable: receipt
 * attachments, scheduled backup, dashboard customisation and the rest could be compiled and unit
 * tested but never actually opened on a device, because nothing could make `isUnlocked` return
 * true. That is a poor place to be for features whose whole risk is in the parts a test cannot
 * reach — a camera round-trip, an EXIF rotation, a pinch gesture.
 *
 * The selected tier is persisted, so it survives a restart the way a real purchase would, and
 * FREE remains the default: the locked state is the one most users see and it should be what a
 * developer sees too unless they ask otherwise.
 *
 * This class is only ever constructed inside the `BuildConfig.DEBUG` branch of `AppModule`, so a
 * release build resolves `PlayBillingProvider` and the override is not consulted. It is a
 * development affordance, not a bypass: it cannot be reached from a shipped APK.
 */
class DebugBillingProvider(
    private val settingsRepository: SettingsRepository,
) : BillingProvider {

    /**
     * Reported as connected rather than unavailable.
     *
     * The paywall treats UNAVAILABLE as "this device cannot buy anything" and stops before
     * loading products, which would make the screen itself untestable.
     */
    override val connectionState: Flow<BillingConnectionState> =
        flowOf(BillingConnectionState.CONNECTED)

    override val purchases: Flow<List<BillingPurchase>> =
        settingsRepository.debugTierOverride.map { name ->
            val tier = name?.let { stored -> Tier.entries.firstOrNull { it.name == stored } }
            val productId = tier?.productId ?: return@map emptyList()

            listOf(
                BillingPurchase(
                    productId = productId,
                    purchaseToken = "debug-$productId",
                    purchaseTimeMillis = System.currentTimeMillis(),
                    isAcknowledged = true,
                    isAutoRenewing = true,
                    state = PurchaseState.PURCHASED,
                ),
            )
        }

    override suspend fun connect() = Unit

    /**
     * Every real tier, priced so the figure cannot be mistaken for a live one.
     *
     * The paywall drops any tier whose features are all unshipped, so FAMILY disappears here for
     * the same reason it does in release — which makes that filter testable too.
     */
    override suspend fun loadProducts(): Result<List<BillingProduct>> = Result.success(
        Tier.entries.mapNotNull { tier ->
            val productId = tier.productId ?: return@mapNotNull null
            BillingProduct(
                productId = productId,
                tier = tier,
                title = "${tier.name} (debug)",
                description = "Granted locally. No payment is taken.",
                formattedPrice = "₹0 (debug)",
                priceMicros = 0L,
                currency = CurrencyCode.DEFAULT,
                billingPeriod = "P1Y",
                freeTrialPeriod = null,
            )
        },
    )

    /** Grants the tier immediately. The paywall picks it up through [purchases], as it would a real one. */
    override suspend fun launchPurchase(activity: Activity, productId: String): Result<Unit> {
        val tier = Tier.fromProductId(productId)
            ?: return Result.failure(IllegalArgumentException("Unknown product: $productId"))
        settingsRepository.setDebugTierOverride(tier.name)
        return Result.success(Unit)
    }

    override suspend fun restorePurchases(): Result<List<BillingPurchase>> =
        Result.success(purchases.first())

    override suspend fun acknowledge(purchaseToken: String): Result<Unit> = Result.success(Unit)

    override fun release() = Unit
}
