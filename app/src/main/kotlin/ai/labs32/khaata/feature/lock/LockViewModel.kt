package ai.labs32.khaata.feature.lock

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.security.AppLockManager
import ai.labs32.khaata.core.security.BiometricAuthenticator
import ai.labs32.khaata.core.security.BiometricResult
import ai.labs32.khaata.core.security.PinVerification
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LockUiState(
    val mode: AppLockMode = AppLockMode.OFF,
    val showPinEntry: Boolean = false,
    val pin: String = "",
    val message: String? = null,
    val isLockedOut: Boolean = false,
    val isPinConfigured: Boolean = false,
    val canUseBiometric: Boolean = false,
    val isUnlocked: Boolean = false,
    /**
     * Digits in the configured PIN, so entry can be submitted exactly once it is complete.
     * Null when no PIN is set, or when one predates the length being recorded.
     */
    val pinLength: Int? = null,
    /**
     * True while a PIN is being checked.
     *
     * Verification is deliberately slow (PBKDF2), so it runs off the main thread and the keypad
     * has to stop accepting digits while it is in flight -- otherwise a stray tap lands on the
     * next entry, or a second complete entry is submitted against the same PIN and charged as a
     * failed attempt.
     */
    val isVerifying: Boolean = false,
)

@HiltViewModel
class LockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: AppLockManager,
    private val biometricAuthenticator: BiometricAuthenticator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    /**
     * Suspends because the first read of the PIN store builds a hardware-backed master key and
     * opens `EncryptedSharedPreferences`, which is Keystore work measured in tens to hundreds of
     * milliseconds on a low-end phone. The lock screen is the first thing a protected app shows,
     * so doing that on the main thread stalls the very first frame the user sees.
     */
    suspend fun initialise(mode: AppLockMode) {
        val (pinConfigured, storedPinLength) = withContext(Dispatchers.IO) {
            appLockManager.isPinSet to appLockManager.configuredPinLength
        }
        val biometricAvailable = biometricAuthenticator.availability().canUse

        _uiState.update {
            it.copy(
                mode = mode,
                isPinConfigured = pinConfigured,
                pinLength = storedPinLength,
                canUseBiometric = biometricAvailable,
                // Fall back to PIN entry when biometrics are unavailable, so a device with a
                // broken sensor is not simply stuck on a lock screen.
                showPinEntry = mode == AppLockMode.PIN || !biometricAvailable,
            )
        }
    }

    fun authenticateBiometric(activity: FragmentActivity) {
        viewModelScope.launch {
            when (val result = biometricAuthenticator.authenticate(activity)) {
                is BiometricResult.Success -> {
                    appLockManager.unlock()
                    _uiState.update { it.copy(isUnlocked = true, message = null) }
                }
                is BiometricResult.Cancelled -> {
                    // Cancelling is not an error; the user chose not to. Offer the PIN instead
                    // of scolding them.
                    _uiState.update {
                        it.copy(showPinEntry = it.isPinConfigured, message = null)
                    }
                }
                is BiometricResult.LockedOut -> _uiState.update {
                    it.copy(
                        showPinEntry = it.isPinConfigured,
                        message = context.getString(R.string.lock_use_pin),
                    )
                }
                is BiometricResult.Error -> _uiState.update {
                    it.copy(showPinEntry = it.isPinConfigured, message = result.message)
                }
            }
        }
    }

    fun onPinDigit(digit: Int) {
        val state = _uiState.value
        if (state.isLockedOut || state.isVerifying || state.pin.length >= MAX_PIN_LENGTH) return

        val updated = state.pin + digit
        _uiState.update { it.copy(pin = updated, message = null) }

        // Submitted only when the entry is exactly as long as the stored PIN, so no confirm tap
        // is needed at any length.
        //
        // It deliberately does NOT submit at every length from the minimum up. Each submission
        // that does not match counts as a failed attempt inside AppLockManager and feeds the
        // lockout backoff, so checking prefixes would charge a user two failures for every
        // correct six-digit entry and lock them out after about three normal unlocks.
        //
        // When the length is unknown -- a PIN stored before it was recorded -- fall back to the
        // maximum rather than the minimum. Waiting too long merely needs more digits typed;
        // submitting too early silently accrues failures against a user doing nothing wrong.
        val target = state.pinLength ?: MAX_PIN_LENGTH
        if (updated.length >= target) verify(updated)
    }

    fun onPinBackspace() = _uiState.update { it.copy(pin = it.pin.dropLast(1), message = null) }

    /**
     * Checks [pin] off the main thread.
     *
     * `AppLockManager.verifyPin` runs 120,000 rounds of PBKDF2 by design -- that cost is what
     * makes a four-digit PIN impractical to walk. Paying it on the main thread froze the keypad
     * for the whole of it on every single unlock, which on a low-end device is long enough to
     * drop frames and read as the app hanging.
     */
    private fun verify(pin: String) = viewModelScope.launch {
        _uiState.update { it.copy(isVerifying = true) }
        val result = withContext(Dispatchers.Default) { appLockManager.verifyPin(pin) }
        _uiState.update { it.copy(isVerifying = false) }

        when (result) {
            is PinVerification.Success -> _uiState.update {
                it.copy(isUnlocked = true, pin = "", message = null)
            }

            // Every submission is now a complete entry rather than a prefix, so a rejection is
            // always a genuinely wrong PIN and is always worth reporting.
            is PinVerification.Incorrect -> _uiState.update {
                it.copy(
                    pin = "",
                    message = context.getString(R.string.lock_pin_incorrect),
                    isLockedOut = result.lockedOutSeconds > 0,
                )
            }

            is PinVerification.LockedOut -> _uiState.update {
                it.copy(
                    pin = "",
                    isLockedOut = true,
                    message = context.getString(
                        R.string.lock_locked_out,
                        result.secondsRemaining.toInt(),
                    ),
                )
            }

            is PinVerification.NoPinSet -> _uiState.update {
                it.copy(showPinEntry = false, isPinConfigured = false)
            }

            is PinVerification.StorageUnavailable -> _uiState.update {
                it.copy(message = context.getString(R.string.lock_storage_unavailable))
            }
        }
    }

    fun switchToPin() = _uiState.update { it.copy(showPinEntry = true, message = null) }

    fun switchToBiometric() = _uiState.update { it.copy(showPinEntry = false, message = null) }

    private companion object {
        /** Upper bound on entry length; the exact length comes from [LockUiState.pinLength]. */
        const val MAX_PIN_LENGTH = 8
    }
}
