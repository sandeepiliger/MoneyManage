package ai.labs32.khaata.feature.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/** Bounds enforced by `AppLockManager.setPin`, restated here so entry is validated before it. */
private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/**
 * Collects a new app-lock PIN.
 *
 * Asks twice and refuses to submit unless both entries match. A PIN that unlocks a user's own
 * financial history is not something to accept on a single mistyped attempt: there is no recovery
 * path short of clearing app data, which destroys the ledger the lock exists to protect.
 *
 * [onConfirm] returns false when secure storage is unavailable -- a real outcome on devices with
 * damaged Keystore material -- so the dialog stays open and says so rather than closing and
 * leaving the user believing a lock is in place when none is.
 */
@Composable
fun PinSetupDialog(
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var storageFailed by remember { mutableStateOf(false) }

    // Only digits, and never longer than the manager will accept -- filtering on input means the
    // field cannot hold something setPin would throw on.
    fun sanitise(input: String) = input.filter { it.isDigit() }.take(MAX_PIN_LENGTH)

    val tooShort = pin.length < MIN_PIN_LENGTH
    val mismatched = confirmation.isNotEmpty() && confirmation != pin
    val canSubmit = !tooShort && confirmation == pin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_set_pin)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.lock_pin_length_hint, MIN_PIN_LENGTH, MAX_PIN_LENGTH),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(KhaataTheme.spacing.default))

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = sanitise(it)
                        storageFailed = false
                    },
                    label = { Text(stringResource(R.string.settings_lock_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(KhaataTheme.spacing.small))

                OutlinedTextField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = sanitise(it)
                        storageFailed = false
                    },
                    label = { Text(stringResource(R.string.lock_confirm_pin)) },
                    singleLine = true,
                    isError = mismatched,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )

                // One message at a time, and only once the user has typed enough for it to be
                // fair -- an error under an empty second field is noise, not help.
                val error = when {
                    storageFailed -> stringResource(R.string.lock_storage_unavailable)
                    mismatched -> stringResource(R.string.lock_pin_mismatch)
                    else -> null
                }
                if (error != null) {
                    Spacer(Modifier.height(KhaataTheme.spacing.small))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { if (!onConfirm(pin)) storageFailed = true },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
