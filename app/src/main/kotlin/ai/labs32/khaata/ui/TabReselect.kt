package ai.labs32.khaata.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter

/**
 * The route of a bottom-bar tab tapped while it was already open.
 *
 * Tapping the tab you are on is how people say "take me back to the top" in every app they use;
 * doing nothing, as navigation alone does, reads as the tap being missed.
 */
val LocalTabReselect = staticCompositionLocalOf<Flow<String>> { emptyFlow() }

/**
 * Scrolls [listState] back to the top when the tab for [route] is tapped again.
 *
 * From far down a long list it jumps to a few rows from the top first and glides the rest, so the
 * motion reads as "back to the top" without animating through hundreds of rows.
 */
@Composable
fun ScrollToTopOnReselect(route: String, listState: LazyListState) {
    val reselects = LocalTabReselect.current
    LaunchedEffect(reselects, listState, route) {
        reselects.filter { it == route }.collect {
            if (listState.firstVisibleItemIndex > JUMP_THRESHOLD) listState.scrollToItem(JUMP_THRESHOLD)
            listState.animateScrollToItem(0)
        }
    }
}

private const val JUMP_THRESHOLD = 8
