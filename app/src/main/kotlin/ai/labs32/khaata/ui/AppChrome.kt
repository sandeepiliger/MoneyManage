package ai.labs32.khaata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
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
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * The global add button, optionally with a voice button above it.
 *
 * Present on every top-level screen, because recording a spend is the one thing the app exists
 * for and it should never be more than one tap away regardless of where the user happens to be.
 *
 * When [onVoiceClick] is supplied, a smaller microphone button sits above the add button rather
 * than replacing it. Dictation is the faster path when it works, but it fails in a noisy market,
 * on a cheap handset with no recognizer installed, and on accents the recognizer handles poorly —
 * so the typed path stays the primary, always-present one and voice is offered beside it.
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

    val voiceDescription = stringResource(R.string.voice_input)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SmallFloatingActionButton(
            onClick = onVoiceClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.semantics { contentDescription = voiceDescription },
        ) {
            Icon(imageVector = Icons.Default.Mic, contentDescription = null)
        }
        AddButton(onClick = onClick)
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
