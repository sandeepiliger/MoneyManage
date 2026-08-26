package ai.labs32.khaata.core.sms

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.notifications.KhaataNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives bank SMS and stages transactions for the user to confirm.
 *
 * Declared `android:enabled="false"` in the manifest and switched on only by
 * [setEnabled] after the user grants the permission and turns the feature on. A user who never
 * opts in has no SMS receiver registered at all — the component simply does not exist as far as
 * the system is concerned, which is a stronger guarantee than a runtime flag.
 *
 * The work is handed to a coroutine because `onReceive` runs on the main thread with a short
 * budget, and parsing plus three database round trips would block it. `goAsync` keeps the process
 * alive for the handful of milliseconds that takes.
 */
@AndroidEntryPoint
class SmsTransactionReceiver : BroadcastReceiver() {

    @Inject lateinit var importer: SmsTransactionImporter

    @Inject lateinit var notifier: KhaataNotifier

    override fun onReceive(context: Context, intent: Intent) {
        // TEMPORARY DIAGNOSTIC: every branch below now logs its outcome (never the message body)
        // so `adb logcat -s SmsReceiver:*` gives a definitive answer to "is this even firing" and
        // "if so, why did it not stage anything" instead of guessing. Revert once confirmed.
        KhaataLog.d(TAG, "onReceive: action=${intent.action}")
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages == null) {
            KhaataLog.d(TAG, "getMessagesFromIntent returned null")
            return
        }
        if (messages.isEmpty()) {
            KhaataLog.d(TAG, "getMessagesFromIntent returned zero messages")
            return
        }

        // A long SMS arrives split across parts; the body has to be reassembled before parsing or
        // the amount and the reference number can land in different fragments.
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val sender = messages.first().displayOriginatingAddress

        if (body.isBlank()) {
            KhaataLog.d(TAG, "Message body was blank, sender=$sender")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (val outcome = importer.import(body = body, sender = sender)) {
                    is SmsImportOutcome.Staged -> {
                        KhaataLog.d(TAG, "Staged, confidence=${outcome.parsed.confidence}")
                        notifier.notifyPendingImport(
                            parsed = outcome.parsed,
                            categoryName = outcome.categoryName,
                            accountName = outcome.accountName,
                        )
                    }

                    // Everything else is a normal, quiet outcome for the user. A promotional SMS
                    // is not an error and the user should never be told about one — but it's
                    // logged here so the reason is visible while diagnosing why nothing shows up.
                    SmsImportOutcome.NotATransaction -> KhaataLog.d(TAG, "Not a transaction")
                    SmsImportOutcome.NotEnabled -> KhaataLog.d(TAG, "SMS import is not enabled")
                    SmsImportOutcome.Duplicate -> KhaataLog.d(TAG, "Duplicate, skipped")

                    // The one quiet outcome worth breaking silence for: a real payment was
                    // recognised and then dropped because no account claimed it. Rate-limited to
                    // one a day inside the notifier.
                    SmsImportOutcome.NoMatchingAccount -> {
                        KhaataLog.d(TAG, "No account matched")
                        notifier.notifyImportNeedsAccount()
                    }
                }
            } catch (error: Exception) {
                // Never the message body — only that something went wrong handling one.
                KhaataLog.e(TAG, "SMS import failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /**
         * Registers or unregisters the receiver.
         *
         * `DONT_KILL_APP` keeps the change from restarting the process under the user, which would
         * look like a crash right after they toggled a setting.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context, SmsTransactionReceiver::class.java)
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
