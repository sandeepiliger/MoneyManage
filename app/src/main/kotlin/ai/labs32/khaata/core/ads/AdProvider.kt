package ai.labs32.khaata.core.ads

import kotlinx.coroutines.flow.Flow

/**
 * The seam between the app and any ad network.
 *
 * Only two implementations exist — [NoOpAdProvider] and the AdMob one — and the rest of the app
 * never imports an SDK type. Swapping networks, or dropping ads entirely, is a change to one
 * binding in a Hilt module.
 *
 * The placement rules are enforced here rather than trusted to each screen, because ad placement
 * is exactly the kind of decision that erodes one screen at a time. See [AdPlacement].
 */
interface AdProvider {

    /** Whether ads should be shown at all, given entitlement and remote configuration. */
    val adsEnabled: Flow<Boolean>

    /** Called once the user's consent and entitlement state is known — never at process start. */
    suspend fun initialize()

    /**
     * Whether [placement] may show an ad right now.
     *
     * Returns false for a paying user, for a placement disabled by remote config, and for any
     * placement the frequency rules say has been used too recently.
     */
    suspend fun canShow(placement: AdPlacement): Boolean

    /** Records that an ad was shown, so frequency capping works. */
    suspend fun recordImpression(placement: AdPlacement)

    /**
     * Shows an interstitial at a navigation boundary, if one is allowed and loaded.
     *
     * @return true if an ad was shown.
     */
    suspend fun showInterstitialIfAllowed(): Boolean

    /**
     * Offers a rewarded ad in exchange for a one-off unlock.
     *
     * @return true if the user watched it through and the reward should be granted.
     */
    suspend fun showRewarded(): Boolean

    /** Releases any cached ads. Called when the user becomes entitled to an ad-free experience. */
    fun release()
}

/**
 * Where an ad may appear.
 *
 * The list is deliberately short and every entry is somewhere the user is *between* tasks rather
 * than in the middle of one. What is absent matters as much as what is here: there is no
 * placement on the amount keypad, none after saving a transaction, none on the transaction list
 * itself, and none anywhere a balance, budget figure or account number is on screen. Ads next to
 * someone's bank balance read as an ad *about* their bank balance, and that is a trade this app
 * does not make.
 */
enum class AdPlacement(
    val format: AdFormat,
    /** Minimum seconds between impressions in this placement. */
    val minimumIntervalSeconds: Long,
) {
    /**
     * A banner at the bottom of the reports screen, below the charts.
     *
     * The minute is not cosmetic. This slot is the last item of a `LazyColumn`, so it is disposed
     * the moment it scrolls out of view and built again when it scrolls back -- and each rebuild
     * asked AdMob for a fresh ad. At zero seconds a user flicking the reports list up and down
     * generated an ad request per pass, which wastes their data, never gets long enough on screen
     * to count as seen, and is the shape of traffic ad networks treat as invalid. A minute is
     * roughly what a banner would refresh at anyway.
     */
    REPORTS_FOOTER(AdFormat.BANNER, minimumIntervalSeconds = 60),

    /** A banner at the bottom of the "More" menu. Capped for the same reason as above. */
    MORE_MENU_FOOTER(AdFormat.BANNER, minimumIntervalSeconds = 60),

    /**
     * An interstitial when returning to the dashboard from a completed flow.
     *
     * Heavily capped: at most a few times a day, and never within the first days of use.
     */
    NAVIGATION_INTERSTITIAL(AdFormat.INTERSTITIAL, minimumIntervalSeconds = 4 * 60 * 60),

    /** A rewarded ad the user chooses to watch to unlock one export or report. */
    REWARDED_UNLOCK(AdFormat.REWARDED, minimumIntervalSeconds = 0),
}

enum class AdFormat { BANNER, INTERSTITIAL, REWARDED }

/**
 * Ad behaviour, kept in configuration rather than in code.
 *
 * Sourced from a remote config service in production so placements can be turned down without an
 * app release; the defaults here apply when no remote value has been fetched.
 */
data class AdConfig(
    val bannersEnabled: Boolean = true,
    val interstitialsEnabled: Boolean = true,
    val rewardedEnabled: Boolean = true,
    /** Maximum interstitials per day. */
    val maxInterstitialsPerDay: Int = 3,
    /**
     * Days of use before any interstitial is shown.
     *
     * A new user is still deciding whether to trust the app with their finances; interrupting
     * them in the first week to sell an impression is a poor trade.
     */
    val interstitialGraceDays: Int = 7,
    /** Minimum transactions recorded before interstitials begin. */
    val interstitialMinimumTransactions: Int = 20,
) {
    fun isEnabled(placement: AdPlacement): Boolean = when (placement.format) {
        AdFormat.BANNER -> bannersEnabled
        AdFormat.INTERSTITIAL -> interstitialsEnabled
        AdFormat.REWARDED -> rewardedEnabled
    }

    companion object {
        val DEFAULT = AdConfig()
    }
}

/** Supplies [AdConfig]. Backed by remote config in production; constant in tests and debug. */
interface AdConfigProvider {
    suspend fun current(): AdConfig
}

/** Returns the built-in defaults. Used until a remote configuration is wired up. */
class StaticAdConfigProvider(private val config: AdConfig = AdConfig.DEFAULT) : AdConfigProvider {
    override suspend fun current(): AdConfig = config
}
