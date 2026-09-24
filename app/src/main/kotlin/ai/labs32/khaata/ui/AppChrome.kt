package ai.labs32.khaata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.navigation.TopLevelDestination

/**
 * The bottom navigation bar, with the add button in its centre.
 *
 * Add used to float over the content as a pill in the corner, where it covered the last figure
 * on every list and needed 168dp of padding under every screen to stay clear of it. Sitting in the
 * bar it covers nothing, it is in the same place on every tab, and it lands under the thumb.
 *
 * Tapping add opens the keypad; holding it opens voice entry, which is the one gesture that used to
 * need a second button. The hold is also exposed as a named accessibility action, because a
 * long-press alone is invisible to anyone using a screen reader.
 *
 * Labels are always shown rather than only on the selected item. Icon-only navigation forces
 * people to learn the glyphs before they can find anything, and it is worse for anyone using the
 * app in Hindi, where the icons carry no linguistic cue at all.
 */
@Composable
fun KhaataBottomBar(
    currentDestination: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    onAdd: () -> Unit,
    onAddByVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations = TopLevelDestination.entries
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        destinations.take(TopLevelDestination.LEADING_COUNT).forEach { destination ->
            DestinationItem(destination, destination == currentDestination, onSelect)
        }
        AddButton(onAdd = onAdd, onAddByVoice = onAddByVoice)
        destinations.drop(TopLevelDestination.LEADING_COUNT).forEach { destination ->
            DestinationItem(destination, destination == currentDestination, onSelect)
        }
    }
}

@Composable
private fun RowScope.DestinationItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        // Also on the open tab: the caller scrolls that screen back to its top.
        onClick = { onSelect(destination) },
        icon = {
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                // The label below is already read out, so the icon adds nothing.
                contentDescription = null,
            )
        },
        label = {
            Text(
                text = stringResource(destination.labelRes),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        },
        alwaysShowLabel = true,
        // primaryContainer marks "selected" everywhere in the app, not secondaryContainer --
        // brass is reserved for warning, so a selected tab and a budget nearing its limit never
        // read as the same colour.
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * The centre add button.
 *
 * A filled rounded square rather than another tab-styled item, so it reads as the one action in
 * the bar rather than as a sixth place to go.
 */
@Composable
private fun RowScope.AddButton(onAdd: () -> Unit, onAddByVoice: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val addDescription = stringResource(R.string.nav_add_transaction)
    val voiceLabel = stringResource(R.string.voice_input)
    val hint = stringResource(R.string.nav_add_hold_hint)

    // A fixed height, not fillMaxHeight: NavigationBar's row only sets a minimum height, so a
    // child asking for the maximum stretches the bar over the whole screen and leaves the
    // content above it with no room at all.
    Box(
        modifier = Modifier
            .weight(1f)
            .height(AddSlotHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(elevation = 4.dp, shape = AddButtonShape)
                .clip(AddButtonShape)
                .background(MaterialTheme.colorScheme.primary)
                .combinedClickable(
                    onClickLabel = addDescription,
                    onLongClickLabel = voiceLabel,
                    onClick = onAdd,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAddByVoice()
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "$addDescription. $hint"
                    customActions = listOf(
                        CustomAccessibilityAction(voiceLabel) {
                            onAddByVoice()
                            true
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private val AddButtonShape = RoundedCornerShape(18.dp)

/** The bar's own height, so the add button's slot lines up with the tabs either side. */
private val AddSlotHeight = 80.dp
