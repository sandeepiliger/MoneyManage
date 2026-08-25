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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

@HiltViewModel
class LockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: AppLockManager,
    private val biometricAuthenticator: BiometricAuthenticator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    fun initialise(mode: AppLockMode) {
        val pinConfigured = appLockManager.isPinSet
        val biometricAvailable = biometricAuthenticator.availability().canUse

        _uiState.update {
            it.copy(
                mode = mode,
                isPinConfigured = pinConfigured,
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
        if (state.isLockedOut || state.pin.length >= MAX_PIN_LENGTH) return

        val updated = state.pin + digit
        _uiState.update { it.copy(pin = updated, message = null) }

        // Verified once the PIN reaches the minimum length, so a four-digit PIN needs no extra
        // confirm tap. Longer PINs are checked at each further digit.
        if (updated.length >= MIN_PIN_LENGTH) verify(updated)
    }

    fun onPinBackspace() = _uiState.update { it.copy(pin = it.pin.dropLast(1), message = null) }

    private fun verify(pin: String) {
        when (val result = appLockManager.verifyPin(pin)) {
            is PinVerification.Success -> _uiState.update {
                it.copy(isUnlocked = true, pin = "", message = null)
            }

            is PinVerification.Incorrect -> {
                // Only clear and complain once the entry is as long as it could usefully be;
                // otherwise a five-digit PIN would be rejected after four digits.
                if (pin.length >= MAX_PIN_LENGTH) {
                    _uiState.update {
                        it.copy(
                            pin = "",
                            message = context.getString(R.string.lock_pin_incorrect),
                            isLockedOut = result.lockedOutSeconds > 0,
                        )
                    }
                }
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
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
    }
}
