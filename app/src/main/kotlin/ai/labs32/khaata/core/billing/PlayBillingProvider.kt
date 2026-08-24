package ai.labs32.khaata.core.billing

import android.app.Activity
import android.content.Context
import ai.labs32.khaata.core.entitlement.Tier
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.money.CurrencyCode
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play Billing.
 *
 * Two things this deliberately does *not* do:
 *
 *  - **It does not verify purchases on a server.** There is no backend in this build, so
 *    entitlement is derived from what the Play client reports. That is adequate for a
 *    subscription whose only privilege is removing ads and unlocking local features, and it keeps
 *    the app fully offline. A build that gates anything of real value should verify server-side —
 *    see docs/BILLING.md.
 *  - **It does not invent prices.** Everything shown to the user comes from [ProductDetails],
 *    already localised by the store.
 */
@Singleton
class PlayBillingProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : BillingProvider, PurchasesUpdatedListener {

    private val _connectionState = MutableStateFlow(BillingConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BillingConnectionState> = _connectionState.asStateFlow()

    private val _purchases = MutableStateFlow<List<BillingPurchase>>(emptyList())
    override val purchases: StateFlow<List<BillingPurchase>> = _purchases.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            // Pending transactions are the norm for UPI mandates in India, so they must be
            // enabled rather than treated as an edge case.
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    override suspend fun connect() {
        if (billingClient.isReady) {
            _connectionState.value = BillingConnectionState.CONNECTED
            return
        }
        _connectionState.value = BillingConnectionState.CONNECTING

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                _connectionState.value = when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> BillingConnectionState.CONNECTED
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                    -> BillingConnectionState.UNAVAILABLE
                    else -> BillingConnectionState.ERROR
                }
            }

            override fun onBillingServiceDisconnected() {
                _connectionState.value = BillingConnectionState.DISCONNECTED
            }
        })
    }

    override suspend fun loadProducts(): Result<List<BillingProduct>> {
        if (!billingClient.isReady) return Result.failure(BillingUnavailableException())

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                Tier.entries.mapNotNull { it.productId }.map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()

        return runCatching {
            val result = billingClient.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                throw BillingUnavailableException()
            }
            result.productDetailsList.orEmpty().mapNotNull { it.toBillingProduct() }
        }.onFailure { KhaataLog.w(TAG, "Product query failed") }
    }

    override suspend fun launchPurchase(activity: Activity, productId: String): Result<Unit> {
        if (!billingClient.isReady) return Result.failure(BillingUnavailableException())

        return runCatching {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build(),
                    ),
                )
                .build()

            val details = billingClient.queryProductDetails(params)
                .productDetailsList
                ?.firstOrNull()
                ?: throw BillingUnavailableException()

            val offerToken = details.subscriptionOfferDetails
                ?.firstOrNull()
                ?.offerToken
                ?: throw BillingUnavailableException()

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .setOfferToken(offerToken)
                            .build(),
                    ),
                )
                .build()

            val result = billingClient.launchBillingFlow(activity, flowParams)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                throw BillingUnavailableException()
            }
        }
    }

    override suspend fun restorePurchases(): Result<List<BillingPurchase>> {
        if (!billingClient.isReady) return Result.failure(BillingUnavailableException())

        return runCatching {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val result = billingClient.queryPurchasesAsync(params)
            val mapped = result.purchasesList.map { it.toBillingPurchase() }
            _purchases.value = mapped
            // Anything not yet acknowledged is acknowledged now: Play auto-refunds after three
            // days, so a restore is the last chance to catch one that slipped through.
            mapped.filter { it.state == PurchaseState.PURCHASED && !it.isAcknowledged }
                .forEach { acknowledge(it.purchaseToken) }
            mapped
        }.onFailure { KhaataLog.w(TAG, "Purchase restore failed") }
    }

    override suspend fun acknowledge(purchaseToken: String): Result<Unit> = runCatching {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        val result = billingClient.acknowledgePurchase(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            throw BillingUnavailableException()
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                _purchases.value = purchases.orEmpty().map { it.toBillingPurchase() }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // Not an error; the user changed their mind.
                KhaataLog.d(TAG, "Purchase cancelled by user")
            }
            else -> KhaataLog.w(TAG, "Purchase update failed with code ${result.responseCode}")
        }
    }

    override fun release() {
        if (billingClient.isReady) billingClient.endConnection()
        _connectionState.value = BillingConnectionState.DISCONNECTED
    }

    private fun ProductDetails.toBillingProduct(): BillingProduct? {
        val tier = Tier.fromProductId(productId) ?: return null
        val offer = subscriptionOfferDetails?.firstOrNull() ?: return null
        val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return null
        // A zero-price leading phase is a free trial.
        val trial = offer.pricingPhases.pricingPhaseList
            .firstOrNull { it.priceAmountMicros == 0L }
            ?.billingPeriod

        return BillingProduct(
            productId = productId,
            tier = tier,
            title = title,
            description = description,
            formattedPrice = phase.formattedPrice,
            priceMicros = phase.priceAmountMicros,
            currency = CurrencyCode.fromCode(phase.priceCurrencyCode),
            billingPeriod = phase.billingPeriod,
            freeTrialPeriod = trial,
        )
    }

    private fun Purchase.toBillingPurchase(): BillingPurchase = BillingPurchase(
        productId = products.firstOrNull().orEmpty(),
        purchaseToken = purchaseToken,
        purchaseTimeMillis = purchaseTime,
        isAcknowledged = isAcknowledged,
        isAutoRenewing = isAutoRenewing,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.UNSPECIFIED
        },
    )

    private companion object {
        const val TAG = "PlayBillingProvider"
    }
}
