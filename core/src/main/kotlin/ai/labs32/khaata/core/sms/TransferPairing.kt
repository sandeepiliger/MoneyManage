package ai.labs32.khaata.core.sms

import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Recognises the two bank messages one transfer between the user's own accounts produces.
 *
 * Moving money from HDFC to ICICI, or paying a credit card bill from a savings account, sends a
 * debit SMS from one bank and a credit SMS from the other. Imported separately they become an
 * expense and an income: every balance is still right, but the month's spending and income are
 * both inflated by the amount, and a card bill -- the spending already counted once on the card
 * -- lands in the budgets a second time. Recorded as one transfer, it is neither.
 *
 * Deliberately narrow, because a wrong pairing hides a real expense and a real income from the
 * reports. Only rows still waiting for review are considered, so nothing the user has already
 * confirmed is rewritten behind their back, and an ambiguous match pairs with nothing.
 */
object TransferPairing {

    /** Banks post the two legs of an IMPS or NEFT transfer up to a couple of days apart. */
    const val WINDOW_DAYS = 2L

    /**
     * The single staged import that is the other leg of an incoming message, or null.
     *
     * A counterpart moves the same amount the opposite way on a different account within
     * [WINDOW_DAYS], is still pending, came from SMS, and is not already a transfer.
     *
     * Bank references settle it where both legs quote one. UPI, IMPS and NEFT carry the same
     * RRN or UTR into both banks' messages, so an equal reference picks that candidate out of
     * several, and two references that differ mean two different payments that merely look
     * alike. A card's "payment received" usually quotes none, which leaves amount and date.
     */
    fun counterpart(
        type: TransactionType,
        amount: Money,
        accountId: String,
        occurredOn: LocalDate,
        candidates: List<Transaction>,
        reference: String? = null,
    ): Transaction? {
        if (type == TransactionType.TRANSFER) return null
        val incomingReference = reference?.takeIf { it.isNotBlank() }
        val eligible = candidates.filter { candidate ->
            candidate.isPending &&
                !candidate.isDeleted &&
                candidate.source == TransactionSource.SMS_IMPORT &&
                candidate.transferAccountId == null &&
                candidate.type != TransactionType.TRANSFER &&
                candidate.type != type &&
                candidate.amount == amount &&
                candidate.accountId != accountId &&
                kotlin.math.abs(ChronoUnit.DAYS.between(candidate.occurredOn, occurredOn)) <= WINDOW_DAYS &&
                !referencesConflict(candidate.referenceNumber, incomingReference)
        }
        if (incomingReference != null) {
            eligible.singleOrNull { it.referenceNumber == incomingReference }?.let { return it }
        }
        return eligible.singleOrNull()
    }

    private fun referencesConflict(existing: String?, incoming: String?): Boolean =
        !existing.isNullOrBlank() && incoming != null && existing != incoming

    /**
     * [pending] rewritten as the one transfer that it and the incoming leg together describe.
     *
     * Money leaves the account that was debited and arrives in the one that was credited, on the
     * debit's date, which is when it actually left. A transfer has no category, and the bank's
     * reference is kept from whichever leg quoted one.
     */
    fun merge(
        pending: Transaction,
        incomingAccountId: String,
        incomingOccurredOn: LocalDate,
        incomingReference: String?,
    ): Transaction {
        val pendingIsDebit = pending.type == TransactionType.EXPENSE
        return pending.copy(
            type = TransactionType.TRANSFER,
            accountId = if (pendingIsDebit) pending.accountId else incomingAccountId,
            transferAccountId = if (pendingIsDebit) incomingAccountId else pending.accountId,
            categoryId = null,
            occurredOn = if (pendingIsDebit) pending.occurredOn else incomingOccurredOn,
            referenceNumber = pending.referenceNumber ?: incomingReference,
        )
    }
}
