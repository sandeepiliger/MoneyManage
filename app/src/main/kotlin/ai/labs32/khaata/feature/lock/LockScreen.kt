package ai.labs32.khaata.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/**
 * The lock screen.
 *
 * Drawn over the app rather than as a navigation destination, so unlocking returns the user
 * exactly where they were. Biometric prompts automatically on arrival — making someone tap
 * "unlock" before the fingerprint prompt appears is a pointless extra step — with a PIN always
 * available as a fallback so a failed sensor never locks them out of their own records.
 */
@Composable
fun LockScreen(
    mode: AppLockMode,
    onUnlocked: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.isUnlocked) { if (state.isUnlocked) onUnlocked() }

    LaunchedEffect(mode) {
        viewModel.initialise(mode)
        if (mode == AppLockMode.BIOMETRIC) {
            (context as? FragmentActivity)?.let { viewModel.authenticateBiometric(it) }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = KhaataTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(KhaataTheme.spacing.xxlarge))

            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(KhaataTheme.spacing.default))
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(Modifier.height(KhaataTheme.spacing.xlarge))

            if (state.showPinEntry) {
                PinEntry(state = state, viewModel = viewModel)
            } else {
                BiometricPrompt(
                    message = state.message,
                    onRetry = {
                        (context as? FragmentActivity)?.let { viewModel.authenticateBiometric(it) }
                    },
                    onUsePin = viewModel::switchToPin,
                    pinAvailable = state.isPinConfigured,
                )
            }
        }
    }
}

@Composable
private fun BiometricPrompt(
    message: String?,
    onRetry: () -> Unit,
    onUsePin: () -> Unit,
    pinAvailable: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.default))

        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(KhaataTheme.spacing.default))
        }

        TextButton(onClick = onRetry) { Text(stringResource(R.string.lock_use_biometric)) }

        if (pinAvailable) {
            TextButton(onClick = onUsePin) { Text(stringResource(R.string.lock_use_pin)) }
        }
    }
}

@Composable
private fun PinEntry(state: LockUiState, viewModel: LockViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.lock_enter_pin),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(KhaataTheme.spacing.large))

        // Dots rather than digits: the PIN is never shown, even to the person entering it, since
        // the realistic threat here is someone standing nearby.
        // The dot row grows with what has been entered rather than implying a fixed length,
        // because a PIN here may be four to eight digits.
        val dotCount = maxOf(MIN_PIN_DOTS, state.pin.length)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(dotCount) { index ->
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < state.pin.length) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                )
            }
        }

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.large))

        PinKeypad(
            enabled = !state.isLockedOut,
            onDigit = viewModel::onPinDigit,
            onBackspace = viewModel::onPinBackspace,
        )

        if (state.canUseBiometric) {
            Spacer(Modifier.height(KhaataTheme.spacing.default))
            TextButton(onClick = viewModel::switchToBiometric) {
                Text(stringResource(R.string.lock_use_biometric))
            }
        }
    }
}

@Composable
private fun PinKeypad(
    enabled: Boolean,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf(1, 2, 3),
        listOf(4, 5, 6),
        listOf(7, 8, 9),
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit ->
                    PinKey(
                        label = digit.toString(),
                        enabled = enabled,
                        onClick = { onDigit(digit) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.size(KEY_SIZE))
            PinKey(label = "0", enabled = enabled, onClick = { onDigit(0) })
            PinKey(label = null, enabled = enabled, onClick = onBackspace)
        }
    }
}

@Composable
private fun PinKey(label: String?, enabled: Boolean, onClick: () -> Unit) {
    val description = label ?: "backspace"
    Box(
        Modifier
            .size(KEY_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Icon(
                Icons.Default.Backspace,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val KEY_SIZE = 68.dp
private const val MIN_PIN_DOTS = 4
