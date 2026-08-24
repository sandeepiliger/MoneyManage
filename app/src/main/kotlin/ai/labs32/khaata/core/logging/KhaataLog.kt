package ai.labs32.khaata.core.logging

import android.util.Log
import ai.labs32.khaata.BuildConfig

/**
 * The app's only logging entry point.
 *
 * Two rules, both enforced by this being the only way to log:
 *
 *  1. **No financial data, ever.** Amounts, balances, merchant names, account or card numbers,
 *     SMS bodies and anything a user typed never reach logcat — in debug builds either. Logs are
 *     readable by any app with the right permissions on older devices, are captured by bug report
 *     tooling, and are the single easiest way for financial data to leak somewhere nobody
 *     intended. [redact] exists for the cases where an identifier genuinely helps.
 *  2. **Verbose logging is compiled out of release.** `BuildConfig.VERBOSE_LOGGING` is false in
 *     release, and R8 strips the branch, so debug and info calls cost nothing in the shipped app.
 *
 * Errors are logged in release too, because a crash with no context is much harder to fix — but
 * with the same content restriction.
 */
object KhaataLog {

    fun d(tag: String, message: String) {
        if (BuildConfig.VERBOSE_LOGGING) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.VERBOSE_LOGGING) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    /**
     * Logs an error.
     *
     * [message] must describe what failed, not what the data was: "backup import failed" rather
     * than the contents of the file.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }

    /**
     * Reduces an identifier to something safe to log.
     *
     * Keeps the first four characters so two log lines about the same record can be correlated,
     * and drops the rest. Never use this on an amount, a merchant name or anything a user typed —
     * those do not belong in a log at any length.
     */
    fun redact(identifier: String?): String = when {
        identifier.isNullOrEmpty() -> "<none>"
        identifier.length <= 4 -> "****"
        else -> identifier.take(4) + "…"
    }
}
