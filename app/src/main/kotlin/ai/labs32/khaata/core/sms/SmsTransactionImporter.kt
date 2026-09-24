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
import ai.labs32.khaata.core.sms.TransferPairing
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What happened to one message. Every path is named, so nothing is silently dropped. */
sealed interface SmsImportOutcome {
    /**
     * A transaction was created from the message: added to the ledger straight away when
     * [autoAdded], otherwise pending and waiting for the user to confirm it.
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
        /** Added to the ledger rather than staged for review; see [SmsTransactionImporter.import]. */
        val autoAdded: Boolean = false,
    ) : SmsImportOutcome

    /** The message was not a transaction — an OTP, a promotion, a balance alert. */
    data object NotATransaction : SmsImportOutcome

    /** The user has not enabled SMS import, so the message was never looked at. */
    data object NotEnabled : SmsImportOutcome

    /** Already in the ledger, matched on reference number or on amount, account and date. */
    data object Duplicate : SmsImportOutcome

    /** Parsed, but no account matched the digits in the message and there is none to guess. */
    data object NoMatchingAccount : SmsImportOutcome

    /**
     * A message from before the account's balance was stated, on that balance's own day, so the
     * balance already includes it. Staging it would let one tap count it twice. Only an inbox
     * scan can see such a message; see [SmsTransactionImporter.import]'s `receivedAt`.
     */
    data object AlreadyInBalance : SmsImportOutcome

    /**
     * The other leg of a transfer already recorded from an earlier message (waiting for review, or
     * added and not yet touched), so that row became the transfer instead of a second row being
     * created. The first leg was already announced.
     */
    data class PairedAsTransfer(val transactionId: String) : SmsImportOutcome
}

/**
 * Turns a bank SMS into a transaction.
 *
 * Four rules govern everything here.
 *
 * The message is parsed on the device and never leaves it. [BankSmsParser] is pure Kotlin with no
 * network access, and nothing in this class writes a message body to a log, to analytics, or to
 * any field that is later exported.
 *
 * A message arriving now is added to the ledger straight away, unless the user has turned
 * [AppSettings.smsAutoAdd][ai.labs32.khaata.core.model.AppSettings.smsAutoAdd] off: most bank
 * messages are right, and asking about each one made the app a queue to clear rather than a record
 * that keeps itself. What makes that safe is everything before the write -- duplicates (including
 * ones the user already typed or deleted) are skipped, a message from before a stated balance is
 * skipped, and the two messages of a transfer become one row. One that is still wrong is a swipe
 * to remove. The one-time read of past messages always stages for review instead: history can
 * overlap a balance the user typed in themselves, and nobody should find a year of it added
 * unasked.
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
     * @param fromInboxScan true when the message is history read back from the inbox rather than
     *   one arriving now. It changes only how an account created from the message is seeded; see
     *   [createAccountFromSms].
     * @param receivedAt the moment the message arrived, when known (the inbox scan knows it). An
     *   account's opening balance is dated by day, so a message from that same day cannot be
     *   placed before or after the balance by its date alone; the moment can. One that arrived
     *   before the account existed is already inside the balance the account was given.
     */
    suspend fun import(
        body: String,
        sender: String?,
        receivedOn: LocalDate = clock.today(),
        fromInboxScan: Boolean = false,
        receivedAt: Instant? = null,
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

        // Straight into the ledger for a message arriving now, when the user has left that on;
        // history from the inbox read always waits for review. See the class comment.
        val autoAdd = !fromInboxScan && settingsRepository.current().smsAutoAdd

        val accounts = accountRepository.getAll().filterNot { it.isArchived }
        val (account, isNewAccount) = when (val match = matchAccount(parsed, accounts)) {
            is AccountMatch.Found -> match.account to false
            is AccountMatch.SafeToCreate -> createAccountFromSms(parsed, sender, currency, match.suffix, fromInboxScan) to true
            AccountMatch.Refuse -> return SmsImportOutcome.NoMatchingAccount
        }

        val snapshotDay = account.openingBalanceDate
        if (
            !isNewAccount &&
            receivedAt != null &&
            snapshotDay != null &&
            !parsed.occurredOn.isBefore(snapshotDay) &&
            receivedAt.isBefore(account.createdAt)
        ) {
            // Dated on or after the snapshot day, so it would move the balance -- but it arrived
            // before the account was set up with that balance, so it is already counted in it.
            return SmsImportOutcome.AlreadyInBalance
        }

        if (
            transactionRepository.isLikelyDuplicate(
                referenceNumber = parsed.referenceNumber,
                amount = parsed.amount,
                accountId = account.id,
                occurredOn = parsed.occurredOn,
                type = parsed.type,
            )
        ) {
            return SmsImportOutcome.Duplicate
        }

        // The other leg of a transfer between the user's own accounts, already recorded from the
        // first message: one transfer, not an expense plus an income. See [TransferPairing].
        val counterpart = TransferPairing.counterpart(
            type = parsed.type,
            amount = parsed.amount,
            accountId = account.id,
            occurredOn = parsed.occurredOn,
            candidates = transactionRepository.pairingCandidatesNear(
                parsed.amount,
                parsed.occurredOn,
                TransferPairing.WINDOW_DAYS,
            ),
            reference = parsed.referenceNumber,
        )
        if (counterpart != null) {
            val merged = TransferPairing.merge(
                pending = counterpart,
                incomingAccountId = account.id,
                incomingOccurredOn = parsed.occurredOn,
                incomingReference = parsed.referenceNumber,
            )
            transactionRepository.update(merged, learnCategory = false)
            KhaataLog.d(TAG, "Paired an SMS with an earlier import as one transfer")
            return SmsImportOutcome.PairedAsTransfer(merged.id)
        }

        // A card bill paid from a bank account moves money to the card; the card's purchases are
        // the spending. Filed as a transfer when there is exactly one card it can be -- with
        // several, a guess could post the payment against the wrong card's outstanding.
        val billCard = if (parsed.isCardBillPayment && account.type != AccountType.CREDIT_CARD) {
            accounts.filter { it.type == AccountType.CREDIT_CARD && it.id != account.id }.singleOrNull()
        } else {
            null
        }

        val suggestion = if (billCard == null) {
            categoryRepository.suggestFor(parsed.merchantDisplayName ?: parsed.merchantRaw)
        } else {
            null
        }

        val id = transactionRepository.create(
            type = if (billCard != null) TransactionType.TRANSFER else parsed.type,
            amount = parsed.amount,
            accountId = account.id,
            categoryId = suggestion?.categoryId,
            transferAccountId = billCard?.id,
            merchant = parsed.merchantDisplayName ?: parsed.merchantRaw,
            // The note is deliberately not the message body. Storing the SMS would put bank text
            // into exports, backups and anything a future feature reads from a transaction.
            note = null,
            occurredOn = parsed.occurredOn,
            source = TransactionSource.SMS_IMPORT,
            referenceNumber = parsed.referenceNumber,
            isPending = !autoAdd,
            // Nothing is learned from a row the user has not looked at, added or not: a wrong
            // parse would teach the categoriser the wrong merchant before anyone could correct it.
            learnCategory = false,
        )

        // Logged by outcome and confidence only — never the body, the merchant or the amount.
        KhaataLog.d(TAG, "Imported an SMS, autoAdded=$autoAdd, confidence=${parsed.confidence}, newAccount=$isNewAccount")

        return SmsImportOutcome.Staged(
            transactionId = id,
            // The notification words itself from the type, so a bill payment says "transferred".
            parsed = if (billCard != null) parsed.copy(type = TransactionType.TRANSFER) else parsed,
            categoryName = suggestion?.categoryId?.let { categoryRepository.findById(it)?.name },
            accountName = account.name,
            isNewAccount = isNewAccount,
            autoAdded = autoAdd,
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
    private fun matchAccount(parsed: ParsedSms, allAccounts: List<Account>): AccountMatch {
        // Only accounts of the right kind are candidates. A credit card spend is card debt, not
        // money leaving the bank; a bank, debit card or UPI message is money in or out of a bank
        // account or wallet, never a card or loan. Without this split, the one-account fallbacks
        // below filed a credit card purchase against someone's only (bank) account, or a bank
        // debit against their only (card) account -- the balance moved in the wrong place.
        if (parsed.isCreditCard) return matchCreditCard(parsed, allAccounts)
        val accounts = allAccounts.filterNot { it.type.isLiability }
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
     * A credit card message: the card with those digits, or the only card on file when none
     * declares digits. Never created blind -- a card has a limit and a statement cycle the user
     * sets up on the Cards screen -- and never a bank account.
     */
    private fun matchCreditCard(parsed: ParsedSms, accounts: List<Account>): AccountMatch {
        val cards = accounts.filter { it.type == AccountType.CREDIT_CARD }
        val suffix = parsed.accountSuffix
        if (!suffix.isNullOrBlank()) {
            val byDigits = cards.filter {
                it.maskedIdentifier?.takeLast(suffix.length)?.equals(suffix, ignoreCase = true) == true
            }
            if (byDigits.isNotEmpty()) {
                return byDigits.singleOrNull()?.let { AccountMatch.Found(it) } ?: AccountMatch.Refuse
            }
        }
        val single = cards.singleOrNull()
        return if (single != null && single.maskedIdentifier.isNullOrBlank()) {
            AccountMatch.Found(single)
        } else {
            AccountMatch.Refuse
        }
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
     * The balance is seeded from the bank's own quoted "Avl Bal" when the message carries one,
     * rather than a blank zero the user has to notice and correct, and it is dated so history
     * the user later confirms does not move it a second time. For a live message the snapshot
     * starts that day with this transaction reversed out; for inbox history -- read newest first
     * -- it is the newest quoted balance, taken as of the end of its day. Absent an `Avl Bal` it
     * is honestly zero and undated, the same starting point manual account creation defaults to.
     */
    private suspend fun createAccountFromSms(
        parsed: ParsedSms,
        sender: String?,
        currency: CurrencyCode,
        suffix: String?,
        fromInboxScan: Boolean,
    ): Account {
        val bankName = BankSenderRegistry.nameFor(sender)
        val displayName = when {
            bankName != null && suffix != null -> "$bankName ••$suffix"
            bankName != null -> bankName
            suffix != null -> "Account ••$suffix"
            else -> "Account"
        }

        // The balance is a dated snapshot: transactions dated before openingBalanceDate are
        // already inside it and never move it again, whether or not they are confirmed later.
        val today = clock.today()
        val avlBal = parsed.availableBalance
        val (openingBalance, openingBalanceDate) = when {
            // Nothing to seed from. Zero, undated -- every confirmed import counts, which is the
            // same starting point manual account creation has always had.
            avlBal == null -> Money.zero(currency) to null

            // History from the inbox scan, which reads newest first -- so this is the newest
            // balance the bank has quoted for the account, and the only one still true. Taken as
            // the balance at the end of that day: everything older the scan goes on to find, and
            // anything else from that same day, is already inside it.
            fromInboxScan && parsed.occurredOn.isBefore(today) ->
                avlBal to parsed.occurredOn.plusDays(1)

            // A message arriving now (or a scanned one from today, which later messages today
            // must still move). Avl Bal is the balance *after* this transaction, so its own
            // effect is reversed out and the snapshot starts today: once confirmed, the two
            // cancel back to exactly what the bank reported.
            else -> when (parsed.type) {
                TransactionType.EXPENSE -> avlBal + parsed.amount
                TransactionType.INCOME -> avlBal - parsed.amount
                TransactionType.TRANSFER -> avlBal
            } to parsed.occurredOn
        }

        val id = accountRepository.create(
            name = displayName,
            type = AccountType.BANK,
            openingBalance = openingBalance,
            openingBalanceDate = openingBalanceDate,
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
