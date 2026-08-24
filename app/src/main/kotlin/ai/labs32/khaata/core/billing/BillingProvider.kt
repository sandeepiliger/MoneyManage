package ai.labs32.khaata.core.billing

import android.app.Activity
import ai.labs32.khaata.core.entitlement.Tier
import ai.labs32.khaata.core.money.CurrencyCode
import kotlinx.coroutines.flow.Flow

/**
 * The seam between the app and any billing platform.
 *
 * Play Billing is the only implementation today, but nothing above this interface imports a Play
 * type. That matters for more than portability: it means the whole entitlement path can be tested
 * without the Play services stack, and a build with no billing configured still runs.
 */
interface BillingProvider {

    /** Connection state, so the paywall can show a real error rather than an empty screen. */
    val connectionState: Flow<BillingConnectionState>

    /** Purchases the platform currently reports as owned. */
    val purchases: Flow<List<BillingPurchase>>

    /** Connects to the billing service. Safe to call repeatedly. */
    suspend fun connect()

    /**
     * Fetches product details, including localised prices.
     *
     * Prices come from the store, never from constants in the app: a hardcoded "₹199/year" is
     * wrong the moment pricing changes or the user is in another country, and showing a price
     * that differs from what is charged is both a trust problem and a policy violation.
     */
    suspend fun loadProducts(): Result<List<BillingProduct>>

    /** Launches the purchase flow. The result arrives through [purchases]. */
    suspend fun launchPurchase(activity: Activity, productId: String): Result<Unit>

    /**
     * Re-reads entitlements from the store.
     *
     * Called on launch and from "Restore purchases", so a reinstall or a new device recovers
     * what the user has already paid for.
     */
    suspend fun restorePurchases(): Result<List<BillingPurchase>>

    /**
     * Acknowledges a purchase.
     *
     * Play refunds anything not acknowledged within three days, so this is not optional
     * bookkeeping — an unacknowledged purchase becomes a refund and a support ticket.
     */
    suspend fun acknowledge(purchaseToken: String): Result<Unit>

    fun release()
}

enum class BillingConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,

    /** Play services missing or too old — common on devices without Play. */
    UNAVAILABLE,

    /** Connected, but the store rejected the request. */
    ERROR,
}

/** A purchasable plan, with the store's own localised price text. */
data class BillingProduct(
    val productId: String,
    val tier: Tier,
    val title: String,
    val description: String,
    /** Exactly as the store formats it, e.g. "₹199.00". Never reformatted by the app. */
    val formattedPrice: String,
    val priceMicros: Long,
    val currency: CurrencyCode?,
    val billingPeriod: String,
    /** Free trial period in ISO-8601 duration form, when the plan offers one. */
    val freeTrialPeriod: String? = null,
)

/** A purchase as the store reports it. */
data class BillingPurchase(
    val productId: String,
    val purchaseToken: String,
    val purchaseTimeMillis: Long,
    val isAcknowledged: Boolean,
    val isAutoRenewing: Boolean,
    val state: PurchaseState,
) {
    val tier: Tier? get() = Tier.fromProductId(productId)
}

enum class PurchaseState {
    PURCHASED,

    /**
     * Payment is still being processed.
     *
     * Common in India, where UPI mandates and net banking can take hours or days to settle. A
     * pending purchase grants nothing until it completes — the money has not moved — and the UI
     * says so plainly rather than leaving the user wondering why they paid and got nothing.
     */
    PENDING,

    UNSPECIFIED,
}

/**
 * A billing provider that owns nothing and sells nothing.
 *
 * Used in debug, in tests, and on devices without Play services. Every screen behaves as it would
 * for a free user, and the paywall reports that purchases are unavailable rather than failing.
 */
class NoOpBillingProvider : BillingProvider {

    override val connectionState: Flow<BillingConnectionState> =
        kotlinx.coroutines.flow.flowOf(BillingConnectionState.UNAVAILABLE)

    override val purchases: Flow<List<BillingPurchase>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun connect() = Unit

    override suspend fun loadProducts(): Result<List<BillingProduct>> = Result.success(emptyList())

    override suspend fun launchPurchase(activity: Activity, productId: String): Result<Unit> =
        Result.failure(BillingUnavailableException())

    override suspend fun restorePurchases(): Result<List<BillingPurchase>> =
        Result.success(emptyList())

    override suspend fun acknowledge(purchaseToken: String): Result<Unit> = Result.success(Unit)

    override fun release() = Unit
}

class BillingUnavailableException :
    Exception("In-app purchases are not available on this device.")
