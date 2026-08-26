package ai.labs32.khaata.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.AppLockMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app lock.
 *
 * Design notes that matter:
 *
 *  - **The PIN is never stored.** Only a PBKDF2 hash and its random salt are kept, in
 *    [EncryptedSharedPreferences] backed by a hardware-protected master key. Someone with the
 *    file cannot read the PIN, and cannot check candidates cheaply.
 *  - **Comparison is constant-time**, so the failure path leaks nothing about how much of a
 *    guess was right.
 *  - **Failed attempts back off.** Repeated wrong PINs lock input for a growing interval, which
 *    makes a four-digit space impractical to walk.
 *  - **Locking is a UI gate, not encryption.** The database is protected by Android's app
 *    sandbox; this stops someone picking up an unlocked phone, which is the realistic threat. It
 *    is not claimed to defend against an attacker with root or physical extraction — see
 *    docs/SECURITY.md for what is and is not in scope.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: KhaataClock,
) {

    private val _lockState = MutableStateFlow<LockState>(LockState.Unlocked)
    val lockState: StateFlow<LockState> = _lockState.asStateFlow()

    /** When the app was last backgrounded, used to decide whether the grace period has elapsed. */
    @Volatile
    private var backgroundedAt: Instant? = null

    private val preferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure {
            // Keystore failures happen on a small number of devices with damaged key material.
            KhaataLog.e(TAG, "Encrypted preferences unavailable", it)
        }.getOrNull()
    }

    val isPinSet: Boolean get() = preferences?.contains(KEY_PIN_HASH) == true

    /**
     * How many digits the configured PIN has, or null when none is set.
     *
     * Stored so the lock screen knows exactly when an entry is complete. Without it the UI has to
     * guess, and the only way to guess is to submit at every length from the minimum up -- which
     * asks [verifyPin] to check prefixes that can never match, and every one of those counts as a
     * failed attempt against the lockout backoff. A user typing their own six-digit PIN correctly
     * would burn two failures each time and lock themselves out after three normal unlocks.
     *
     * The length is far less sensitive than the PIN itself, and it lives in the same encrypted
     * store; an attacker who can read it can already read the hash and salt beside it.
     */
    val configuredPinLength: Int?
        get() = preferences?.getInt(KEY_PIN_LENGTH, 0)?.takeIf { it > 0 }

    /**
     * Sets or replaces the PIN.
     *
     * @return false when secure storage is unavailable, so the UI can tell the user rather than
     *   claiming a lock is in place when it is not.
     */
    fun setPin(pin: String): Boolean {
        require(pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits"
        }
        require(pin.all { it.isDigit() }) { "PIN must be digits only" }

        val prefs = preferences ?: return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)

        prefs.edit()
            .putString(KEY_PIN_HASH, hash.toHexString())
            .putString(KEY_PIN_SALT, salt.toHexString())
            .putInt(KEY_PIN_LENGTH, pin.length)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
        return true
    }

    fun clearPin() {
        preferences?.edit()
            ?.remove(KEY_PIN_HASH)
            ?.remove(KEY_PIN_SALT)
            ?.remove(KEY_PIN_LENGTH)
            ?.remove(KEY_FAILED_ATTEMPTS)
            ?.remove(KEY_LOCKOUT_UNTIL)
            ?.apply()
    }

    /**
     * Checks a PIN attempt.
     *
     * Applies and updates the lockout backoff, so callers cannot bypass it by not asking.
     */
    fun verifyPin(pin: String): PinVerification {
        val prefs = preferences ?: return PinVerification.StorageUnavailable

        val lockedUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val nowMillis = clock.now().toEpochMilli()
        if (lockedUntil > nowMillis) {
            return PinVerification.LockedOut(secondsRemaining = (lockedUntil - nowMillis) / 1000)
        }

        val storedHash = prefs.getString(KEY_PIN_HASH, null)?.hexToByteArray()
        val salt = prefs.getString(KEY_PIN_SALT, null)?.hexToByteArray()
        if (storedHash == null || salt == null) return PinVerification.NoPinSet

        val candidate = hashPin(pin, salt)
        // Constant-time so a wrong guess reveals nothing about how close it was.
        if (MessageDigest.isEqual(candidate, storedHash)) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).remove(KEY_LOCKOUT_UNTIL).apply()
            unlock()
            return PinVerification.Success
        }

        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
        val backoffSeconds = backoffFor(attempts)
        if (backoffSeconds > 0) {
            editor.putLong(KEY_LOCKOUT_UNTIL, nowMillis + backoffSeconds * 1000)
        }
        editor.apply()

        return PinVerification.Incorrect(
            attemptsMade = attempts,
            lockedOutSeconds = backoffSeconds,
        )
    }

    /**
     * How long input is blocked after [attempts] consecutive failures.
     *
     * Nothing for the first few, then a growing delay. Enough to make guessing impractical
     * without punishing a genuine mistyped PIN.
     */
    private fun backoffFor(attempts: Int): Long = when {
        attempts < 5 -> 0
        attempts < 8 -> 30
        attempts < 11 -> 120
        else -> 600
    }

    /** Records that the app went to the background, starting the grace period. */
    fun onBackgrounded() {
        backgroundedAt = clock.now()
    }

    /**
     * Decides whether to lock when the app returns to the foreground.
     *
     * A short grace period means switching to the banking app to check a balance and coming
     * straight back does not demand a fingerprint every time — which is what causes people to
     * turn the lock off entirely.
     */
    fun onForegrounded(mode: AppLockMode, graceSeconds: Int) {
        if (mode == AppLockMode.OFF) {
            _lockState.value = LockState.Unlocked
            return
        }
        val since = backgroundedAt ?: return
        val elapsed = clock.now().epochSecond - since.epochSecond
        if (elapsed >= graceSeconds) {
            _lockState.value = LockState.Locked(mode)
        }
    }

    /** Locks immediately, for the "Lock now" action. */
    fun lockNow(mode: AppLockMode) {
        if (mode != AppLockMode.OFF) _lockState.value = LockState.Locked(mode)
    }

    fun unlock() {
        _lockState.value = LockState.Unlocked
        backgroundedAt = null
    }

    /** Applied at launch so a protected app opens locked rather than showing data first. */
    fun applyInitialState(mode: AppLockMode) {
        _lockState.value = if (mode == AppLockMode.OFF) LockState.Unlocked else LockState.Locked(mode)
    }

    /**
     * PBKDF2-HMAC-SHA256.
     *
     * Deliberately slow. A plain SHA-256 of a four-digit PIN is exhaustively searchable in
     * microseconds; the iteration count makes each candidate cost real time.
     */
    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val TAG = "AppLockManager"
        const val PREFS_NAME = "khaata_secure"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_LENGTH = "pin_length"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"

        const val SALT_BYTES = 16
        const val KEY_LENGTH_BITS = 256

        /**
         * Chosen so verification stays under roughly 100ms on a low-end device while remaining
         * expensive in bulk. Raise it as devices get faster.
         */
        const val PBKDF2_ITERATIONS = 120_000

        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
    }
}

sealed interface LockState {
    data object Unlocked : LockState
    data class Locked(val mode: AppLockMode) : LockState
}

sealed interface PinVerification {
    data object Success : PinVerification
    data class Incorrect(val attemptsMade: Int, val lockedOutSeconds: Long) : PinVerification
    data class LockedOut(val secondsRemaining: Long) : PinVerification
    data object NoPinSet : PinVerification

    /** Secure storage is unavailable, so a PIN cannot be verified on this device. */
    data object StorageUnavailable : PinVerification
}
