package ai.labs32.khaata.feature.ads

import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.BuildConfig
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ads.AdPlacement
import ai.labs32.khaata.core.ads.AdProvider
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdSlotViewModel @Inject constructor(
    private val adProvider: AdProvider,
) : ViewModel() {

    private val _canShow = MutableStateFlow(false)
    val canShow: StateFlow<Boolean> = _canShow.asStateFlow()

    fun evaluate(placement: AdPlacement) {
        viewModelScope.launch {
            // Both gates are checked: the global switch covers entitlement and remote config, and
            // canShow covers this placement's own rules. Neither is inferred from the other.
            val allowed = adProvider.adsEnabled.first() && adProvider.canShow(placement)

            // Starting the ad SDK is this app's last step before an ad is wanted, not its first
            // at process start -- which is the whole point of the OPTIMIZE_INITIALIZATION and
            // DELAY_APP_MEASUREMENT_INIT flags in the manifest, and of AdProvider.initialize
            // checking entitlement itself. Nothing called it, so MobileAds.initialize never ran
            // and the one banner in the app asked an uninitialised SDK for an ad. It is
            // idempotent, and it returns immediately for a user who is entitled to no ads.
            if (allowed) adProvider.initialize()

            _canShow.value = allowed
        }
    }

    fun recordImpression(placement: AdPlacement) {
        viewModelScope.launch { adProvider.recordImpression(placement) }
    }
}

/**
 * A banner slot.
 *
 * Renders nothing at all — not a placeholder, not reserved space — when an ad is not allowed, so a
 * paying user's layout is identical to a free user's minus the ad rather than having a gap where
 * one used to be.
 *
 * The composable takes an [AdPlacement] rather than an ad unit id, so a screen cannot invent a
 * placement the rules in [AdPlacement] have not sanctioned. Only banner placements are accepted;
 * an interstitial goes through [AdProvider.showInterstitialIfAllowed] at a navigation boundary,
 * never inside a layout.
 */
@Composable
fun AdSlot(
    placement: AdPlacement,
    modifier: Modifier = Modifier,
    viewModel: AdSlotViewModel = hiltViewModel(),
) {
    require(placement.format == ai.labs32.khaata.core.ads.AdFormat.BANNER) {
        "AdSlot renders banners only; $placement is a ${placement.format}"
    }

    val canShow by viewModel.canShow.collectAsStateWithLifecycle()
    LaunchedEffect(placement) { viewModel.evaluate(placement) }

    if (!canShow) return

    val widthDp = LocalConfiguration.current.screenWidthDp

    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = KhaataTheme.spacing.small),
    ) {
        // Labelled, always. An unlabelled banner inside a finance app can be mistaken for the
        // app's own recommendation, which is the one thing an ad here must never look like.
        androidx.compose.material3.Text(
            text = stringResource(R.string.ad_label),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                AdView(context).apply {
                    setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
                    adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    adListener = object : AdListener() {
                        // Counted when an ad actually arrives, not when this composable enters
                        // composition. Counting at composition recorded an impression for every
                        // no-fill as well, which both overstates what the user was shown and
                        // spends the frequency cap on ads that never rendered.
                        override fun onAdLoaded() = viewModel.recordImpression(placement)
                    }
                    loadAd(AdRequest.Builder().build())
                }
            },
            // Destroying the view is AndroidView's job, not a DisposableEffect's. The view used
            // to be written back into composition state from inside `factory` purely so a
            // separate effect could reach it to destroy it -- a write during composition, and one
            // that left the AdView leaked whenever that state had not been applied yet.
            onRelease = { it.destroy() },
        )
    }
}
