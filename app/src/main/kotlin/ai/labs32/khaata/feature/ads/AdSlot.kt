package ai.labs32.khaata.feature.ads

import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            _canShow.value = adProvider.adsEnabled.first() && adProvider.canShow(placement)
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
    var adView by remember { mutableStateOf<AdView?>(null) }

    DisposableEffect(placement) {
        onDispose { adView?.destroy() }
    }

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
                    loadAd(AdRequest.Builder().build())
                    adView = this
                }
            },
        )
    }

    LaunchedEffect(placement) { viewModel.recordImpression(placement) }
}
