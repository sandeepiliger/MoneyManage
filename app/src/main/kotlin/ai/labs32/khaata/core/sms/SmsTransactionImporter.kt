package ai.labs32.khaata.core.sms

import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.sms.AccountSuffixKind
import ai.labs32.khaata.core.sms.BankSenderRegistry
import ai.labs32.khaata.core.sms.BankSmsParser
import ai.labs32.khaata.core.sms.ParsedSms
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import java.time.LocalDate
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
        /** True when [accountName] did not exist before this message — see [SmsTransactionImporter.createAccountFromSms]. */
        val isNewAccount: Boolean,
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
 * Four rules govern everything here.
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
 *
 * A new bank account is created automatically the first time its masked digits show up, so the
 * only thing standing between "install and grant SMS access" and a working ledger is that grant.
 * This is deliberately narrower than "auto-create for anything unmatched" — see
 * [createAccountFromSms] for exactly which messages qualify and why the rest still refuse.
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

    /**
     * @param receivedOn when the message arrived, used as the transaction date for messages whose
     *   text carries none. Defaults to today, which is right for a message arriving live; a scan
     *   of the existing inbox must pass each message's own timestamp instead, or a year of history
     *   would all land on the day the user turned the feature on.
     */
    suspend fun import(
        body: String,
        sender: String?,
        receivedOn: LocalDate = clock.today(),
    ): SmsImportOutcome {
        if (!settingsRepository.current().smsImportEnabled) return SmsImportOutcome.NotEnabled

        val currency = profileRepository.currency()
        val parsed = BankSmsParser.parse(
            body = body,
            receivedOn = receivedOn,
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
        val (account, isNewAccount) = when (val match = matchAccount(parsed, accounts)) {
            is AccountMatch.Found -> match.account to false
            is AccountMatch.SafeToCreate -> createAccountFromSms(parsed, sender, currency, match.suffix) to true
            AccountMatch.Refuse -> return SmsImportOutcome.NoMatchingAccount
        }

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
        KhaataLog.d(TAG, "Staged an SMS import, confidence=${parsed.confidence}, newAccount=$isNewAccount")

        return SmsImportOutcome.Staged(
            transactionId = id,
            parsed = parsed,
            categoryName = suggestion?.categoryId?.let { categoryRepository.findById(it)?.name },
            accountName = account.name,
            isNewAccount = isNewAccount,
        )
    }

    /** What [matchAccount] decided about which account a message refers to. */
    private sealed interface AccountMatch {
        data class Found(val account: Account) : AccountMatch

        /**
         * No existing account matched, but it is safe to create one — a genuine bank-account
         * suffix that names a new account, or (when [suffix] is null) a completely fresh install
         * with nothing on file yet at all.
         */
        data class SafeToCreate(val suffix: String?) : AccountMatch

        /** No existing account matched, and creating one blind would risk a wrong guess. */
        data object Refuse : AccountMatch
    }

    /**
     * Finds the account a message refers to.
     *
     * Matched on the masked digits the bank quotes ("a/c XX4821") when that is possible, and
     * otherwise on there being exactly one account it could be.
     *
     * The single-account fallback is the case that matters in practice. Almost every Indian bank
     * SMS quotes some digits, and onboarding never asks for an account's last four -- so requiring
     * a digit match meant the common setup (one account, no masked digits recorded) matched
     * nothing and every message was refused. The feature looked dead for the default
     * configuration.
     *
     * The safety property is kept where it actually applies: if any account *does* declare masked
     * digits, the user has told us how to tell them apart, so a message quoting digits that match
     * none of them is a real mismatch, not a candidate for the fallback. Only when there is
     * nothing to discriminate on do we fall back to "there is only one account this can be".
     */
    private fun matchAccount(parsed: ParsedSms, accounts: List<Account>): AccountMatch {
        val suffix = parsed.accountSuffix
        if (suffix.isNullOrBlank()) {
            // No digits to match on at all -- a UPI app's own "you sent" confirmation typically
            // reads this way, since it knows the funding source but doesn't say it. With exactly
            // one account this is still an unambiguous match; with none yet, it is the very first
            // message on a fresh install, and there is exactly one sensible account for it to be.
            // Two or more accounts and no digits is the one genuinely ambiguous shape here, so
            // that alone still refuses -- see the matching case below.
            return when {
                accounts.size == 1 -> AccountMatch.Found(accounts.single())
                accounts.isEmpty() -> AccountMatch.SafeToCreate(suffix = null)
                else -> AccountMatch.Refuse
            }
        }

        val matches = accounts.filter { account ->
            account.maskedIdentifier?.takeLast(suffix.length)?.equals(suffix, ignoreCase = true) == true
        }
        if (matches.isNotEmpty()) {
            return matches.singleOrNull()?.let { AccountMatch.Found(it) } ?: AccountMatch.Refuse
        }

        val someAccountDeclaresDigits = accounts.any { !it.maskedIdentifier.isNullOrBlank() }

        // Exactly one account, and nothing on file to tell accounts apart by: the message quotes
        // digits, but there is only one account they could possibly belong to. This is the
        // default setup -- onboarding creates one account and never asks for its last four --
        // and nearly every Indian bank SMS quotes "a/c XX4821", so without this the common case
        // matches nothing and every message is refused. Auto-creating instead would be worse: it
        // would silently duplicate the account the user already has.
        if (accounts.size == 1 && !someAccountDeclaresDigits) {
            return AccountMatch.Found(accounts.single())
        }

        if (accounts.isEmpty() || someAccountDeclaresDigits) {
            // Either a fresh install with nothing to match against yet, or the user has shown
            // they distinguish accounts by digits -- either way, a suffix matching none of them
            // confidently names an account that does not exist yet, not an ambiguous guess.
            //
            // Restricted to a bank-account suffix. A card could be a credit card, a domain this
            // app tracks separately with its own statement cycle, or a debit card belonging to an
            // account already on file under a different masked number -- guessing wrong there
            // files the transaction against the wrong kind of record, which is worse than asking.
            //
            // Restricted to exactly 4 digits too: accountRepository.create only ever persists a
            // 4-digit masked identifier (see its own guard), so a 3-digit suffix would create an
            // account with none recorded -- unmatchable by any later message with the same
            // suffix, which would auto-create a fresh duplicate account every single time.
            return if (parsed.accountSuffixKind == AccountSuffixKind.BANK && suffix.length == 4) {
                AccountMatch.SafeToCreate(suffix)
            } else {
                AccountMatch.Refuse
            }
        }

        // Multiple accounts exist and none of them declare digits: an unmatched suffix could
        // belong to any of them. Guessing risks silently duplicating a real account, so this is
        // the one case that still asks the user to add digits to their existing accounts instead.
        return AccountMatch.Refuse
    }

    /**
     * Creates the account [AccountMatch.SafeToCreate] decided this message is for.
     *
     * The name is inferred from the SMS sender when [BankSenderRegistry] recognises it ("HDFC
     * Bank ••4321"); an unrecognised sender falls back to a plain masked-digits label ("Account
     * ••4321") rather than guess at a bank name and risk telling someone their money sits
     * somewhere it does not. [suffix] is null for a message that named no digits at all -- see
     * [AccountMatch.SafeToCreate] -- in which case the name is the bank alone, or "Account" when
     * even that is unknown.
     *
     * The opening balance is backed out from the bank's own quoted "Avl Bal", when the message
     * carries one, so the account is seeded from the bank's own arithmetic rather than a blank
     * zero the user has to notice and correct. `Avl Bal` describes the balance *after* this
     * transaction, so the opening balance is that figure with this transaction's own effect
     * reversed out -- once the transaction is confirmed, the two cancel back out to what the bank
     * reported. Absent an `Avl Bal`, this is honestly zero, the same starting point manual account
     * creation already defaults to.
     */
    private suspend fun createAccountFromSms(
        parsed: ParsedSms,
        sender: String?,
        currency: CurrencyCode,
        suffix: String?,
    ): Account {
        val bankName = BankSenderRegistry.nameFor(sender)
        val displayName = when {
            bankName != null && suffix != null -> "$bankName ••$suffix"
            bankName != null -> bankName
            suffix != null -> "Account ••$suffix"
            else -> "Account"
        }

        val openingBalance = parsed.availableBalance?.let { avlBal ->
            when (parsed.type) {
                TransactionType.EXPENSE -> avlBal + parsed.amount
                TransactionType.INCOME -> avlBal - parsed.amount
                TransactionType.TRANSFER -> avlBal
            }
        } ?: Money.zero(currency)

        val id = accountRepository.create(
            name = displayName,
            type = AccountType.BANK,
            openingBalance = openingBalance,
            currency = currency,
            institution = bankName,
            maskedIdentifier = suffix,
        )

        KhaataLog.d(TAG, "Auto-created an account from SMS, bank recognised=${bankName != null}")

        return accountRepository.findById(id)
            ?: error("Account $id was created and immediately unreadable")
    }

    private companion object {
        const val TAG = "SmsImporter"
    }
}
