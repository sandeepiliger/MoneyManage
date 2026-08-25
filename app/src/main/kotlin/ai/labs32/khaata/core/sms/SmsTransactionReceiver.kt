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
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // A long SMS arrives split across parts; the body has to be reassembled before parsing or
        // the amount and the reference number can land in different fragments.
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val sender = messages.first().displayOriginatingAddress

        if (body.isBlank()) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (val outcome = importer.import(body = body, sender = sender)) {
                    is SmsImportOutcome.Staged -> notifier.notifyPendingImport(outcome.parsed)

                    // Everything else is a normal, quiet outcome. A promotional SMS is not an
                    // error and the user should never be told about one.
                    SmsImportOutcome.NotATransaction,
                    SmsImportOutcome.NotEnabled,
                    SmsImportOutcome.Duplicate,
                    SmsImportOutcome.NoMatchingAccount,
                    -> Unit
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
