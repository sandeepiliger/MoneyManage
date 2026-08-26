package ai.labs32.khaata.core.sms

import android.content.Context
import android.provider.Telephony
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What one inbox scan found. Counts only — never a merchant, an amount or a message. */
data class SmsScanResult(
    val messagesRead: Int = 0,
    val staged: Int = 0,
    val accountsCreated: Int = 0,
) {
    val foundSomething: Boolean get() = staged > 0
}

/**
 * Reads the SMS messages already on the phone and imports the transactions in them.
 *
 * This is what makes the feature useful on the day it is switched on. [SmsTransactionReceiver]
 * only ever sees messages that arrive *after* the user grants permission, so without this the app
 * sits empty until the next time they happen to spend something — while the bank messages
 * explaining the last year of their spending are already sitting in the inbox, unread. Competing
 * India-first trackers all do this scan, and it is the single reason they can show a populated
 * ledger before the user has entered anything.
 *
 * Deliberate limits:
 *
 *  - **One year.** Long enough to fill the reports this app draws (which top out at a financial
 *    year) without walking a decade of messages on a low-end phone.
 *  - **Once.** Guarded by [AppSettings.hasScannedSmsInbox][ai.labs32.khaata.core.model.AppSettings.hasScannedSmsInbox];
 *    re-running would re-parse thousands of rows that [SmsTransactionImporter] would then
 *    correctly reject as duplicates, which is all cost and no benefit.
 *  - **Silent.** It stages transactions and posts no notifications, because the receiver — not the
 *    importer — is what notifies. A scan that announced each of several hundred finds would be
 *    unusable, and the pending-imports badge already says how many are waiting.
 *  - **Never retains a message.** Bodies are read into memory, parsed, and dropped. Nothing here
 *    logs, stores or transmits message text, exactly as the live path already guarantees.
 */
@Singleton
class SmsInboxScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: SmsTransactionImporter,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Scans the inbox if it has not been scanned before.
     *
     * @return the scan's result, or null when it was skipped (already done, feature off, or the
     *   permission is not granted).
     */
    suspend fun scanIfNeeded(): SmsScanResult? {
        val settings = settingsRepository.current()
        if (!settings.smsImportEnabled) return null
        if (settings.hasScannedSmsInbox) return null
        if (!SmsPermission.isGranted(context)) return null
        return scanNow()
    }

    /**
     * Scans regardless of whether it has run before.
     *
     * The completion flag is set even when the scan finds nothing, so an inbox with no bank
     * messages in it is not re-walked on every launch.
     */
    suspend fun scanNow(): SmsScanResult = withContext(Dispatchers.IO) {
        val since = LocalDate.now().minusYears(SCAN_YEARS)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        var messagesRead = 0
        var staged = 0
        var accountsCreated = 0

        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { cursor ->
                val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (cursor.moveToNext()) {
                    // A scan of a large inbox is long enough to outlive the reason it was started,
                    // so it must stop when its scope is cancelled rather than run to completion in
                    // the background.
                    ensureActive()

                    val body = cursor.getString(bodyColumn).orEmpty()
                    if (body.isBlank()) continue
                    messagesRead++

                    // Oldest first, and each message keeps its own date, so the imported history
                    // reads as the history it actually was rather than everything landing today.
                    val receivedOn = Instant.ofEpochMilli(cursor.getLong(dateColumn))
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()

                    val outcome = runCatching {
                        importer.import(
                            body = body,
                            sender = cursor.getString(addressColumn),
                            receivedOn = receivedOn,
                        )
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        // One malformed message must not abandon the rest of the inbox.
                        KhaataLog.e(TAG, "Failed to import a message during the inbox scan", error)
                        null
                    }

                    if (outcome is SmsImportOutcome.Staged) {
                        staged++
                        if (outcome.isNewAccount) accountsCreated++
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A SecurityException here means the permission was revoked mid-scan; anything else is
            // a content-provider failure. Neither is worth losing what was already imported.
            KhaataLog.e(TAG, "Inbox scan stopped early", error)
        }

        settingsRepository.setSmsInboxScanned(true)
        KhaataLog.d(TAG, "Inbox scan: read=$messagesRead staged=$staged accounts=$accountsCreated")

        SmsScanResult(
            messagesRead = messagesRead,
            staged = staged,
            accountsCreated = accountsCreated,
        )
    }

    private companion object {
        const val TAG = "SmsInboxScanner"
        const val SCAN_YEARS = 1L
    }
}
