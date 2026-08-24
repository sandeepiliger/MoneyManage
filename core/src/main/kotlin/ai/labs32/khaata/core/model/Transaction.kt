package ai.labs32.khaata.core.model

import ai.labs32.khaata.core.common.InstantSerializer
import ai.labs32.khaata.core.common.LocalDateSerializer
import ai.labs32.khaata.core.money.Money
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * A single movement of money.
 *
 * [amount] is always stored as a positive magnitude; direction lives in [type]. Storing a signed
 * amount instead means every query has to remember the convention, and transfers — which are
 * negative on one account and positive on another — have no single correct sign at all. Callers
 * ask [signedAmountFor] for the effect on a particular account.
 */
@Serializable
data class Transaction(
    val id: String,
    val type: TransactionType,
    /** Always positive. Validated on construction — a zero or negative amount is not a thing. */
    val amount: Money,
    /** The account the money leaves (expense/transfer) or arrives in (income). */
    val accountId: String,
    /** The receiving account. Required for [TransactionType.TRANSFER], null otherwise. */
    val transferAccountId: String? = null,
    val categoryId: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    @Serializable(with = LocalDateSerializer::class) val occurredOn: LocalDate,
    val tags: Set<String> = emptySet(),
    val receiptId: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    /** UPI reference / bank RRN when one was captured. Never a card or account number. */
    val referenceNumber: String? = null,
    /** Set when this row was generated from a [RecurringRule], for traceability. */
    val recurringRuleId: String? = null,
    /** Pending rows are excluded from balances until confirmed (used by SMS import). */
    val isPending: Boolean = false,
    /** Soft delete. Rows keep their history so an accidental delete is recoverable. */
    @Serializable(with = InstantSerializer::class) val deletedAt: Instant? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant = Instant.EPOCH,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant = Instant.EPOCH,
) {
    init {
        require(amount.isPositive) {
            "Transaction amount must be positive; direction is carried by `type`. Got $amount"
        }
        when (type) {
            TransactionType.TRANSFER -> {
                require(transferAccountId != null) { "A transfer needs a destination account" }
                require(transferAccountId != accountId) {
                    "A transfer must move money between two different accounts"
                }
            }
            else -> require(transferAccountId == null) {
                "Only transfers may set transferAccountId (type=$type)"
            }
        }
    }

    val isDeleted: Boolean get() = deletedAt != null

    /** True when this row should be counted in balances, budgets and reports. */
    val isEffective: Boolean get() = !isDeleted && !isPending

    /**
     * The signed effect of this transaction on [accountId].
     *
     * Returns zero for an account this transaction does not touch, so callers can fold over a
     * mixed list without filtering first.
     */
    fun signedAmountFor(accountId: String): Money = when {
        !isEffective -> Money.zero(amount.currency)
        type == TransactionType.TRANSFER && accountId == this.transferAccountId -> amount
        accountId != this.accountId -> Money.zero(amount.currency)
        type == TransactionType.INCOME -> amount
        else -> -amount // EXPENSE, and the outgoing leg of a TRANSFER
    }

    /** True when this row moves money out of the user's own money, i.e. counts as spending. */
    val countsAsSpending: Boolean get() = isEffective && type == TransactionType.EXPENSE

    /** True when this row adds to the user's money, i.e. counts as income. */
    val countsAsIncome: Boolean get() = isEffective && type == TransactionType.INCOME

    /** A short label for lists: the merchant if we have one, otherwise the note. */
    fun displayTitle(fallback: String): String =
        merchant?.takeIf { it.isNotBlank() }
            ?: note?.takeIf { it.isNotBlank() }
            ?: fallback
}

@Serializable
enum class TransactionType {
    EXPENSE,
    INCOME,

    /**
     * Movement between two of the user's own accounts.
     *
     * Transfers are deliberately excluded from income, expense and budget totals. Counting a
     * ₹20,000 move from savings to current as ₹20,000 of spending is a mistake that makes the
     * whole month's numbers useless, and it is one users notice immediately.
     */
    TRANSFER,
    ;

    val isTransfer: Boolean get() = this == TRANSFER
}

/** Where a transaction came from. Surfaced in the UI so imported rows are never mistaken for manual ones. */
@Serializable
enum class TransactionSource {
    MANUAL,
    QUICK_ADD,
    NATURAL_LANGUAGE,
    SMS_IMPORT,
    NOTIFICATION_IMPORT,
    CSV_IMPORT,
    RECURRING,
    DEMO,
    ;

    /** Imported rows land as pending so the user confirms before they move a balance. */
    val requiresReview: Boolean
        get() = this == SMS_IMPORT || this == NOTIFICATION_IMPORT
}
