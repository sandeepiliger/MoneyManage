package ai.labs32.khaata.core.ads

import android.app.Activity
import android.content.Context
import ai.labs32.khaata.BuildConfig
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.data.repository.EntitlementRepository
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Ad provider that shows nothing.
 *
 * Used for paying users, for builds with ads compiled out, and in tests. It is the default
 * binding in debug so development never sees an ad.
 */
@Singleton
class NoOpAdProvider @Inject constructor() : AdProvider {
    override val adsEnabled: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    override suspend fun initialize() = Unit
    override suspend fun canShow(placement: AdPlacement): Boolean = false
    override suspend fun recordImpression(placement: AdPlacement) = Unit
    override suspend fun showInterstitialIfAllowed(): Boolean = false
    override suspend fun showRewarded(): Boolean = false
    override fun release() = Unit
}

/**
 * The AdMob-backed provider.
 *
 * All the placement policy lives in [canShow]; the SDK is only ever asked for an ad once that has
 * already said yes. The SDK is not initialised at process start — it is initialised the first
 * time an ad is actually wanted, after entitlement is known, so a paying user's device never
 * loads it at all.
 */
@Singleton
class AdMobAdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val entitlementRepository: EntitlementRepository,
    private val configProvider: AdConfigProvider,
    private val impressionStore: AdImpressionStore,
    private val clock: KhaataClock,
) : AdProvider {

    private val initialized = AtomicReference(false)
    private val interstitial = AtomicReference<InterstitialAd?>(null)
    private val rewarded = AtomicReference<RewardedAd?>(null)

    /** Set by the activity while it is resumed, so a full-screen ad has somewhere to show. */
    @Volatile
    var currentActivity: Activity? = null

    override val adsEnabled: Flow<Boolean> =
        entitlementRepository.observeShouldShowAds().map { it }

    override suspend fun initialize() {
        if (initialized.getAndSet(true)) return
        if (!entitlementRepository.shouldShowAds()) {
            // Nothing to do for an entitled user; the SDK is never started.
            initialized.set(false)
            return
        }
        withContext(Dispatchers.IO) {
            runCatching { MobileAds.initialize(context) }
                .onFailure { KhaataLog.w(TAG, "Ad SDK initialisation failed") }
        }
        preloadInterstitial()
    }

    override suspend fun canShow(placement: AdPlacement): Boolean {
        if (!entitlementRepository.shouldShowAds()) return false

        val config = configProvider.current()
        if (!config.isEnabled(placement)) return false

        if (placement.format == AdFormat.INTERSTITIAL) {
            // A user still deciding whether to trust the app is not interrupted.
            if (impressionStore.daysSinceFirstUse(clock.now()) < config.interstitialGraceDays) {
                return false
            }
            if (impressionStore.transactionCount() < config.interstitialMinimumTransactions) {
                return false
            }
            if (impressionStore.countToday(placement, clock.now()) >= config.maxInterstitialsPerDay) {
                return false
            }
        }

        val last = impressionStore.lastImpression(placement) ?: return true
        val elapsed = Duration.between(last, clock.now()).seconds
        return elapsed >= placement.minimumIntervalSeconds
    }

    override suspend fun recordImpression(placement: AdPlacement) {
        impressionStore.record(placement, clock.now())
    }

    override suspend fun showInterstitialIfAllowed(): Boolean {
        if (!canShow(AdPlacement.NAVIGATION_INTERSTITIAL)) return false
        val activity = currentActivity ?: return false
        val ad = interstitial.getAndSet(null) ?: run {
            preloadInterstitial()
            return false
        }

        val shown = suspendCancellableCoroutine { continuation ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    KhaataLog.w(TAG, "Interstitial failed to show")
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            ad.show(activity)
        }

        if (shown) recordImpression(AdPlacement.NAVIGATION_INTERSTITIAL)
        preloadInterstitial()
        return shown
    }

    override suspend fun showRewarded(): Boolean {
        if (!canShow(AdPlacement.REWARDED_UNLOCK)) return false
        val activity = currentActivity ?: return false
        val ad = rewarded.getAndSet(null) ?: run {
            preloadRewarded()
            return false
        }

        var earned = false
        val completed = suspendCancellableCoroutine { continuation ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            ad.show(activity) { earned = true }
        }

        preloadRewarded()
        return completed && earned
    }

    override fun release() {
        interstitial.set(null)
        rewarded.set(null)
        currentActivity = null
    }

    private fun preloadInterstitial() {
        if (interstitial.get() != null) return
        InterstitialAd.load(
            context,
            BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) = interstitial.set(ad)

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // No fill is routine, not an error worth surfacing.
                    interstitial.set(null)
                }
            },
        )
    }

    private fun preloadRewarded() {
        if (rewarded.get() != null) return
        RewardedAd.load(
            context,
            BuildConfig.ADMOB_REWARDED_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) = rewarded.set(ad)

                override fun onAdFailedToLoad(error: LoadAdError) = rewarded.set(null)
            },
        )
    }

    private companion object {
        const val TAG = "AdMobAdProvider"
    }
}

/**
 * Tracks impressions for frequency capping.
 *
 * Deliberately in-memory plus a small persisted counter rather than anything richer: this exists
 * to limit how often the app interrupts the user, not to build a profile of them. It records
 * counts and timestamps, never what was shown or what the user did next.
 */
interface AdImpressionStore {
    suspend fun record(placement: AdPlacement, at: Instant)
    suspend fun lastImpression(placement: AdPlacement): Instant?
    suspend fun countToday(placement: AdPlacement, now: Instant): Int
    suspend fun daysSinceFirstUse(now: Instant): Int
    suspend fun transactionCount(): Int
}
