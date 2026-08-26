package ai.labs32.khaata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.navigation.TopLevelDestination

/**
 * The bottom navigation bar.
 *
 * Labels are always shown rather than only on the selected item. Icon-only navigation forces
 * people to learn five glyphs before they can find anything, and it is worse for anyone using
 * the app in Hindi, where the icons carry no linguistic cue at all.
 */
@Composable
fun KhaataBottomBar(
    currentDestination: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination == currentDestination
            val label = stringResource(destination.labelRes)

            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        // The label below is already read out, so the icon adds nothing.
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                alwaysShowLabel = true,
                // primaryContainer marks "selected" everywhere in the app now, not
                // secondaryContainer -- brass is reserved for warning, so a selected tab and a
                // budget nearing its limit no longer read as the same colour.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * The global add button, optionally with a voice section beside it.
 *
 * Present on every top-level screen, because recording a spend is the one thing the app exists
 * for and it should never be more than one tap away regardless of where the user happens to be.
 *
 * When [onVoiceClick] is supplied, the button becomes one wide pill with the microphone as a
 * distinct trailing region — `[ + Add | mic ]` — rather than two separate floating buttons stacked
 * in the same corner. Two stacked FABs covered real figures on the screen behind them (a
 * dashboard amount, a card total) and, on Insights, collided with that screen's own assistant FAB
 * at the same anchor. One pill fixes both: it is a single shape at a single anchor, and the mic
 * still gets its own tap target and its own description rather than being folded into "Add"'s
 * click, so dictation stays beside the typed path rather than replacing it -- which is what this
 * comment protected before the layout changed.
 */
@Composable
fun KhaataFloatingAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onVoiceClick: (() -> Unit)? = null,
) {
    if (onVoiceClick == null) {
        AddButton(onClick = onClick, modifier = modifier)
        return
    }

    val addDescription = stringResource(R.string.nav_add_transaction)
    val voiceDescription = stringResource(R.string.voice_input)
    val addLabel = stringResource(R.string.action_add)

    Row(
        modifier = modifier
            .height(56.dp)
            // A hand-built pill doesn't get FloatingActionButton's built-in shadow for free --
            // without this it reads as a flat chip sitting on the screen rather than the raised
            // control every other FAB in the app is.
            .shadow(elevation = 6.dp, shape = KhaataShapeTokens.card)
            .clip(KhaataShapeTokens.card)
            .background(MaterialTheme.colorScheme.primary),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = addDescription }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = addLabel,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(48.dp)
                .clickable(role = Role.Button, onClick = onVoiceClick)
                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f))
                .semantics { contentDescription = voiceDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.nav_add_transaction)
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
    }
}
