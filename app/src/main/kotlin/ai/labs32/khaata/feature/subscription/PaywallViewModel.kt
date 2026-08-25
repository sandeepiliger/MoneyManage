package ai.labs32.khaata.feature.subscription

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.billing.BillingConnectionState
import ai.labs32.khaata.core.common.IsoPeriod
import ai.labs32.khaata.core.billing.BillingProvider
import ai.labs32.khaata.core.billing.PurchaseState
import ai.labs32.khaata.core.entitlement.Feature
import ai.labs32.khaata.core.entitlement.Tier
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.data.repository.EntitlementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A purchasable plan, with the store's own price text and the features it unlocks. */
data class PaywallPlan(
    val productId: String,
    val tier: Tier,
    /** Exactly as the store formatted it. Never assembled by the app. */
    val formattedPrice: String,
    /** Free trial length in days, or null when the plan has none. Formatted by the UI. */
    val freeTrialDays: Int?,
    val features: List<Feature>,
)

sealed interface PaywallMessage {
    data object PurchaseCompleted : PaywallMessage
    data object PurchasePending : PaywallMessage
    data object PurchaseFailed : PaywallMessage
    data object RestoredNothing : PaywallMessage
    data object Restored : PaywallMessage
}

data class PaywallUiState(
    val isLoading: Boolean = true,
    val plans: List<PaywallPlan> = emptyList(),
    val currentTier: Tier = Tier.FREE,
    val isPending: Boolean = false,
    val message: PaywallMessage? = null,
)

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val billingProvider: BillingProvider,
    private val entitlementRepository: EntitlementRepository,
    private val analytics: AnalyticsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PaywallUiState())
    val uiState: StateFlow<PaywallUiState> = _uiState.asStateFlow()

    init {
        analytics.track(AnalyticsEvent.PaywallViewed(trigger = "settings"))

        entitlementRepository.entitlement
            .onEach { entitlement ->
                _uiState.update {
                    it.copy(currentTier = entitlement.tier, isPending = entitlement.isPending)
                }
            }
            .launchIn(viewModelScope)

        // Purchases can also arrive from outside this screen — a pending UPI mandate clearing
        // while the app is open — so the stream is observed rather than only polled on demand.
        billingProvider.purchases
            .onEach { purchases ->
                if (purchases.any { it.state == PurchaseState.PURCHASED }) {
                    entitlementRepository.refresh()
                    _uiState.update { it.copy(message = PaywallMessage.PurchaseCompleted) }
                } else if (purchases.any { it.state == PurchaseState.PENDING }) {
                    _uiState.update { it.copy(message = PaywallMessage.PurchasePending) }
                }
            }
            .launchIn(viewModelScope)

        loadPlans()
    }

    /**
     * Loads plans from the store.
     *
     * An empty list is a normal outcome, not an error: a device without Play services, or a build
     * whose products are not yet configured in the console, both land here. The screen says so and
     * every free feature keeps working.
     */
    private fun loadPlans() {
        viewModelScope.launch {
            billingProvider.connect()

            if (billingProvider.connectionState.first() == BillingConnectionState.UNAVAILABLE) {
                _uiState.update { it.copy(isLoading = false, plans = emptyList()) }
                return@launch
            }

            val products = billingProvider.loadProducts().getOrElse { error ->
                KhaataLog.e(TAG, "Product load failed", error)
                emptyList()
            }

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    plans = products
                        .sortedBy { it.tier.level }
                        .map { product ->
                            PaywallPlan(
                                productId = product.productId,
                                tier = product.tier,
                                formattedPrice = product.formattedPrice,
                                freeTrialDays = IsoPeriod.days(product.freeTrialPeriod),
                                features = featuresIntroducedBy(product.tier),
                            )
                        },
                )
            }
        }
    }

    /**
     * Features this tier adds over the one below it.
     *
     * Derived from [Feature.minimumTier] rather than listed by hand, so moving a feature between
     * tiers updates the paywall automatically and the screen can never advertise something the
     * entitlement check will then refuse.
     */
    private fun featuresIntroducedBy(tier: Tier): List<Feature> =
        Feature.entries.filter { it.minimumTier == tier }

    fun purchase(activity: Activity, productId: String) {
        analytics.track(AnalyticsEvent.PurchaseStarted(productId))
        viewModelScope.launch {
            billingProvider.launchPurchase(activity, productId)
                .onSuccess { analytics.track(AnalyticsEvent.PurchaseCompleted(productId)) }
                .onFailure { error ->
                    KhaataLog.e(TAG, "Purchase launch failed", error)
                    _uiState.update { it.copy(message = PaywallMessage.PurchaseFailed) }
                }
        }
    }

    /**
     * Re-reads purchases from the store.
     *
     * Also acknowledges anything the store still considers unacknowledged, which is what happens
     * when a purchase completes and the app is killed before it can confirm — Play refunds an
     * unacknowledged purchase after three days, so this is not merely tidiness.
     */
    fun restore() {
        viewModelScope.launch {
            val purchases = billingProvider.restorePurchases().getOrElse { error ->
                KhaataLog.e(TAG, "Restore failed", error)
                _uiState.update { it.copy(message = PaywallMessage.PurchaseFailed) }
                return@launch
            }

            purchases
                .filter { it.state == PurchaseState.PURCHASED && !it.isAcknowledged }
                .forEach { billingProvider.acknowledge(it.purchaseToken) }

            entitlementRepository.refresh()

            _uiState.update {
                it.copy(
                    message = if (purchases.isEmpty()) {
                        PaywallMessage.RestoredNothing
                    } else {
                        PaywallMessage.Restored
                    },
                )
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private companion object {
        const val TAG = "PaywallViewModel"
    }
}

/**
 * Finds the Activity behind a Compose [Context].
 *
 * `LocalContext.current` is usually a ContextWrapper rather than the Activity itself, so a bare
 * cast silently returns null and the purchase button does nothing.
 */
fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
