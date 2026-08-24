package ai.labs32.khaata.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/**
 * The screen states every list and detail view shares.
 *
 * Having these as shared components rather than per-screen improvisation is what makes "every
 * screen has a loading, empty, error and retry state" true in practice rather than aspirational:
 * a new screen gets all four by using [ScreenState], and the empty state is a required argument
 * rather than something easy to forget.
 */

/** A centred loading indicator, announced to screen readers. */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.state_loading),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * An empty state.
 *
 * Always carries an action where one makes sense. "No transactions yet" with a button that starts
 * one is useful; the same message alone is a dead end, and dead ends are where new users decide
 * an app is not for them.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(32.dp),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.default))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(KhaataTheme.spacing.large))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * An error state.
 *
 * [message] must be something a user can act on. Exception text and stack traces never reach
 * here — they say nothing useful and leak internals.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.state_error_title),
    onRetry: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    isOffline: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (isOffline) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.default))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(KhaataTheme.spacing.large))
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
        if (onSecondaryAction != null && secondaryActionLabel != null) {
            Spacer(Modifier.height(KhaataTheme.spacing.small))
            TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
        }
    }
}

/**
 * The four states a screen can be in.
 *
 * Modelled as a sealed interface so a `when` over it is exhaustive: a screen physically cannot
 * forget to handle its error case, because the compiler will not let it.
 */
sealed interface ScreenState<out T> {
    data object Loading : ScreenState<Nothing>
    data class Content<T>(val data: T) : ScreenState<T>
    data object Empty : ScreenState<Nothing>
    data class Error(val message: String, val isOffline: Boolean = false) : ScreenState<Nothing>
}

/**
 * Renders whichever state a screen is in.
 *
 * The empty-state parameters are required rather than optional, so adding a new list screen
 * forces a decision about what the user sees before they have any data.
 */
@Composable
fun <T> ScreenStateHost(
    state: ScreenState<T>,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptyDescription: String,
    modifier: Modifier = Modifier,
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is ScreenState.Loading -> LoadingState(modifier)
        is ScreenState.Empty -> EmptyState(
            icon = emptyIcon,
            title = emptyTitle,
            description = emptyDescription,
            modifier = modifier,
            actionLabel = emptyActionLabel,
            onAction = onEmptyAction,
        )
        is ScreenState.Error -> ErrorState(
            message = state.message,
            modifier = modifier,
            onRetry = onRetry,
            isOffline = state.isOffline,
        )
        is ScreenState.Content -> content(state.data)
    }
}
