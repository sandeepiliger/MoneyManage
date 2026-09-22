package ai.labs32.khaata.core.sms

import android.content.Context
import android.provider.Telephony
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * [SmsTransactionReceiver] only ever sees messages that arrive *after* the user grants
 * permission, so this is how the recent past gets in. It runs only when the user asks for it --
 * from the opt-in on the onboarding SMS step, or "Import recent bank messages" in the privacy
 * dashboard -- and never on its own at launch. An earlier build scanned a year of messages
 * silently on the first launch after SMS was switched on, and a user who had just typed in
 * today's balance saw a year of transactions pile on top of it: a ledger that changes
 * underneath you is worse than an empty one.
 *
 * Deliberate limits:
 *
 *  - **90 days.** Enough to fill this month and the last two for the reports and budgets, without
 *    walking years of messages on a low-end phone or flooding the review queue.
 *  - **Newest first.** An account the scan has to create takes its balance from the newest
 *    message that quotes one, which is the only figure still true today. See
 *    [SmsTransactionImporter.import]'s `fromInboxScan`.
 *  - **Review, never balance.** Everything is staged as pending, and every account the user
 *    stated a balance for carries that balance's date, so confirming history from before it
 *    lists the spending without moving the balance a second time.
 *  - **Silent.** It stages transactions and posts no notifications, because the receiver -- not
 *    the importer -- is what notifies. The caller reports the counts in [SmsScanResult].
 *  - **Never retains a message.** Bodies are read into memory, parsed, and dropped. Nothing here
 *    logs, stores or transmits message text, exactly as the live path already guarantees.
 */
@Singleton
class SmsInboxScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: SmsTransactionImporter,
    private val settingsRepository: SettingsRepository,
) {

    /** Two scans at once would race each other's duplicate checks and stage rows twice. */
    private val mutex = Mutex()

    /**
     * Scans the last [SCAN_DAYS] days of the inbox. Called only on the user's request.
     *
     * Safe to repeat: rows already imported are recognised as duplicates and skipped. Returns
     * null when SMS reading is off or the permission is missing, so a caller can say why nothing
     * happened instead of reporting "found nothing".
     */
    suspend fun scanNow(): SmsScanResult? {
        if (!settingsRepository.current().smsImportEnabled) return null
        if (!SmsPermission.isGranted(context)) return null
        return mutex.withLock { scan() }
    }

    private suspend fun scan(): SmsScanResult = withContext(Dispatchers.IO) {
        val since = LocalDate.now().minusDays(SCAN_DAYS)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        var messagesRead = 0
        var staged = 0
        var accountsCreated = 0
        var completed = false

        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC",
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

                    // Each message keeps its own date, so the imported history reads as the
                    // history it actually was rather than everything landing today.
                    val receivedAt = Instant.ofEpochMilli(cursor.getLong(dateColumn))
                    val receivedOn = receivedAt.atZone(ZoneId.systemDefault()).toLocalDate()

                    val outcome = runCatching {
                        importer.import(
                            body = body,
                            sender = cursor.getString(addressColumn),
                            receivedOn = receivedOn,
                            fromInboxScan = true,
                            receivedAt = receivedAt,
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
            completed = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A SecurityException here means the permission was revoked mid-scan; anything else is
            // a content-provider failure. Neither is worth losing what was already imported.
            KhaataLog.e(TAG, "Inbox scan stopped early", error)
        }

        // Only a scan that reached the end of the window counts as done; one cut short by a
        // revoked permission or a provider failure can simply be asked for again.
        if (completed) settingsRepository.setSmsInboxScanned(true)
        KhaataLog.d(TAG, "Inbox scan: read=$messagesRead staged=$staged accounts=$accountsCreated")

        SmsScanResult(
            messagesRead = messagesRead,
            staged = staged,
            accountsCreated = accountsCreated,
        )
    }

    companion object {
        private const val TAG = "SmsInboxScanner"
        /** How far back a scan reads. The user-facing copy says 90 days; keep them in step. */
        const val SCAN_DAYS = 90L
    }
}
