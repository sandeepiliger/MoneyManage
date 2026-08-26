package ai.labs32.khaata.core.sms

import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.sms.BankSmsParser
import ai.labs32.khaata.core.sms.ParsedSms
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

/** What happened to one message. Every path is named, so nothing is silently dropped. */
sealed interface SmsImportOutcome {
    /**
     * A pending transaction was created and is waiting for the user to confirm it.
     *
     * [categoryName] and [accountName] are resolved here rather than left for the notification to
     * look up: the receiver has no repositories, and the notifier deliberately has no database
     * access beyond its own log. Null category means nothing was confidently suggested.
     */
    data class Staged(
        val transactionId: String,
        val parsed: ParsedSms,
        val categoryName: String?,
        val accountName: String,
    ) : SmsImportOutcome

    /** The message was not a transaction — an OTP, a promotion, a balance alert. */
    data object NotATransaction : SmsImportOutcome

    /** The user has not enabled SMS import, so the message was never looked at. */
    data object NotEnabled : SmsImportOutcome

    /** Already in the ledger, matched on reference number or on amount, account and date. */
    data object Duplicate : SmsImportOutcome

    /** Parsed, but no account matched the digits in the message and there is none to guess. */
    data object NoMatchingAccount : SmsImportOutcome
}

/**
 * Turns a bank SMS into a pending transaction.
 *
 * Three rules govern everything here.
 *
 * The message is parsed on the device and never leaves it. [BankSmsParser] is pure Kotlin with no
 * network access, and nothing in this class writes a message body to a log, to analytics, or to
 * any field that is later exported.
 *
 * Nothing is ever posted straight to the ledger. Every import lands as `isPending`, which the user
 * confirms or discards. A bank SMS is a claim about what happened, not a fact — the message can be
 * a duplicate, a pre-authorisation that never settles, or simply wrong — and a balance built on
 * unconfirmed claims is one the user cannot reconcile.
 *
 * A message with no matching account is refused rather than filed against a guess. Putting a
 * transaction on the wrong account silently corrupts two balances and the user has no way to see
 * that it happened.
 */
@Singleton
class SmsTransactionImporter @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val clock: KhaataClock,
) {

    suspend fun import(body: String, sender: String?): SmsImportOutcome {
        if (!settingsRepository.current().smsImportEnabled) return SmsImportOutcome.NotEnabled

        val currency = profileRepository.currency()
        val parsed = BankSmsParser.parse(
            body = body,
            receivedOn = clock.today(),
            sender = sender,
            currency = currency,
        )
        if (parsed == null) {
            // Never the body — only which gate rejected it, so "not detected" reports are
            // diagnosable from logcat instead of another round of guessing at real-world wording.
            KhaataLog.d(TAG, "Not a transaction: ${BankSmsParser.diagnoseRejection(body)}")
            return SmsImportOutcome.NotATransaction
        }

        val accounts = accountRepository.getAll().filterNot { it.isArchived }
        val account = matchAccount(parsed, accounts) ?: return SmsImportOutcome.NoMatchingAccount

        if (
            transactionRepository.isLikelyDuplicate(
                referenceNumber = parsed.referenceNumber,
                amount = parsed.amount,
                accountId = account.id,
                occurredOn = parsed.occurredOn,
            )
        ) {
            return SmsImportOutcome.Duplicate
        }

        val suggestion = categoryRepository.suggestFor(parsed.merchantDisplayName ?: parsed.merchantRaw)

        val id = transactionRepository.create(
            type = parsed.type,
            amount = parsed.amount,
            accountId = account.id,
            categoryId = suggestion?.categoryId,
            merchant = parsed.merchantDisplayName ?: parsed.merchantRaw,
            // The note is deliberately not the message body. Storing the SMS would put bank text
            // into exports, backups and anything a future feature reads from a transaction.
            note = null,
            occurredOn = parsed.occurredOn,
            source = TransactionSource.SMS_IMPORT,
            referenceNumber = parsed.referenceNumber,
            isPending = true,
            // Nothing is learned from an unconfirmed row: a wrong parse would teach the
            // categoriser the wrong merchant before anyone had a chance to correct it.
            learnCategory = false,
        )

        // Logged by outcome and confidence only — never the body, the merchant or the amount.
        KhaataLog.d(TAG, "Staged an SMS import, confidence=${parsed.confidence}")

        return SmsImportOutcome.Staged(
            transactionId = id,
            parsed = parsed,
            categoryName = suggestion?.categoryId?.let { categoryRepository.findById(it)?.name },
            accountName = account.name,
        )
    }

    /**
     * Finds the account a message refers to.
     *
     * Matched on the masked digits the bank quotes ("a/c XX4821") when that is possible, and
     * otherwise on there being exactly one account it could be.
     *
     * The third case below is the one that matters in practice. Almost every Indian bank SMS
     * quotes some digits, and onboarding never asks for an account's last four -- so requiring a
     * digit match meant the common setup (one account, no masked digits recorded) matched nothing
     * and every message was refused. The feature looked dead for the default configuration.
     *
     * The safety property is kept where it actually applies: if any account *does* declare masked
     * digits, the user has told us how to tell them apart, so a message quoting digits that match
     * none of them is a real mismatch and is still refused rather than guessed at. Only when there
     * is nothing to discriminate on do we fall back to "there is only one account this can be".
     */
    private fun matchAccount(parsed: ParsedSms, accounts: List<Account>): Account? {
        val suffix = parsed.accountSuffix
        if (suffix.isNullOrBlank()) return accounts.singleOrNull()

        val matches = accounts.filter { account ->
            account.maskedIdentifier?.takeLast(suffix.length)?.equals(suffix, ignoreCase = true) == true
        }
        if (matches.isNotEmpty()) return matches.singleOrNull()

        val noneDeclareDigits = accounts.none { !it.maskedIdentifier.isNullOrBlank() }
        return if (noneDeclareDigits) accounts.singleOrNull() else null
    }

    private companion object {
        const val TAG = "SmsImporter"
    }
}
