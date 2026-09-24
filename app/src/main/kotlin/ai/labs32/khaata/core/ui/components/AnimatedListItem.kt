package ai.labs32.khaata.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A lazy-list row that moves rather than jumps.
 *
 * Without this, deleting, confirming or dismissing a row makes it vanish in one frame and snaps
 * everything below it up to fill the gap -- the single most "cheap" feeling thing a list can do.
 * With it the row fades out while its neighbours glide into place, and a new row fades in. Rows
 * need a stable key for this, which every list in the app already gives them.
 *
 * The content is stacked in a column, as a lazy item with several children already is.
 */
@Composable
fun LazyItemScope.AnimatedListItem(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.animateItem(), content = content)
}
