package ai.labs32.khaata.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ai.labs32.khaata.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Biometric unlock.
 *
 * Uses the device credential (PIN, pattern, password) as a fallback, so a user whose fingerprint
 * sensor fails or whose face is not recognised is never locked out of their own records. The
 * alternative — biometric only — means a wet thumb can leave someone unable to open their
 * expense tracker, which is how app locks end up switched off.
 */
@Singleton
class BiometricAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Whether this device can authenticate the user at all. */
    fun availability(): BiometricAvailability {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(ALLOWED_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricAvailability.UPDATE_REQUIRED
            else -> BiometricAvailability.UNAVAILABLE
        }
    }

    /**
     * Shows the system biometric prompt.
     *
     * Suspends until the user succeeds, fails or dismisses it. Never throws: every outcome is a
     * [BiometricResult] the caller can act on.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = activity.getString(R.string.biometric_prompt_title),
        subtitle: String = activity.getString(R.string.biometric_prompt_subtitle),
    ): BiometricResult = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(BiometricResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, message: CharSequence) {
                if (!continuation.isActive) return
                val outcome = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED,
                    -> BiometricResult.Cancelled
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                    -> BiometricResult.LockedOut
                    else -> BiometricResult.Error(message.toString())
                }
                continuation.resume(outcome)
            }

            override fun onAuthenticationFailed() {
                // A single unrecognised fingerprint. The prompt stays up for another try, so
                // this is not a terminal outcome and the continuation is left alone.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

        prompt.authenticate(promptInfo)
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    private companion object {
        /**
         * Weak biometrics are accepted alongside strong ones and the device credential.
         *
         * The lock guards a UI surface, not a cryptographic key, so requiring BIOMETRIC_STRONG
         * would exclude a large number of mid-range Indian devices for no real security gain.
         */
        const val ALLOWED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}

enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,

    /** Hardware present but nothing enrolled — the user is sent to system settings. */
    NOT_ENROLLED,

    UPDATE_REQUIRED,
    UNAVAILABLE,
    ;

    val canUse: Boolean get() = this == AVAILABLE
}

sealed interface BiometricResult {
    data object Success : BiometricResult
    data object Cancelled : BiometricResult

    /** Too many failed attempts; the system has temporarily disabled biometrics. */
    data object LockedOut : BiometricResult

    data class Error(val message: String) : BiometricResult
}
