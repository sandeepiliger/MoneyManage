package ai.labs32.khaata.core.model

import ai.labs32.khaata.core.common.InstantSerializer
import ai.labs32.khaata.core.common.LocalDateSerializer
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * Where money sits.
 *
 * The reference apps mostly treat a "wallet" or "account" as a bucket with a mutable balance.
 * We derive the balance from the opening balance plus the postings instead, so a balance can
 * never drift away from the transactions that explain it — the "where did my money go?" question
 * always reconciles.
 */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val openingBalance: Money = Money.zero(currency),
    /**
     * The day [openingBalance] was true, when the user actually stated a balance.
     *
     * An opening balance entered as "what is in this account now" is a snapshot: every
     * transaction before that day is already inside the number. Counting those transactions on
     * top of it again is a double count, and it is exactly what happened when an SMS backfill or a
     * backdated entry landed in an account whose balance the user had typed in that morning — the
     * balance went wrong by the net of everything before it.
     *
     * Null means no balance was stated -- an account created at zero because nobody knew the
     * figure, or one that predates this field. Then the opening balance comes before all of the
     * account's history, and every transaction counts, as it always has. That distinction matters
     * most for credit cards: a card added with no outstanding stated must still have its imported
     * spend counted, because its outstanding is derived from exactly that balance.
     */
    @Serializable(with = LocalDateSerializer::class)
    val openingBalanceDate: LocalDate? = null,
    /** Bank or issuer name, e.g. "HDFC Bank". Free text — we never ask for credentials. */
    val institution: String? = null,
    /** Last four digits only, for recognition. Never a full account or card number. */
    val maskedIdentifier: String? = null,
    val includeInNetWorth: Boolean = true,
    val includeInAvailableBalance: Boolean = type.countsAsSpendableByDefault,
    val colorSeed: Int = 0,
    val iconKey: String = type.defaultIconKey,
    val notes: String? = null,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant = Instant.EPOCH,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant = Instant.EPOCH,
) {
    val isLiability: Boolean get() = type.isLiability

    /**
     * Whether something that happened on [date] moves this account's balance.
     *
     * The snapshot day itself counts: the common order is to enter a balance and then log the
     * day's spending, so treating the day as after the snapshot is right far more often than not.
     */
    fun movesBalanceOn(date: LocalDate): Boolean =
        openingBalanceDate == null || !date.isBefore(openingBalanceDate)
}

/**
 * The kind of account, which decides how its balance is interpreted.
 *
 * [isLiability] is the important one: a credit card with a "balance" of ₹18,000 means you owe
 * ₹18,000, so it must subtract from net worth rather than add to it. Conflating the two is a
 * common source of wrong net-worth figures.
 */
@Serializable
enum class AccountType(
    val isLiability: Boolean,
    val countsAsSpendableByDefault: Boolean,
    val defaultIconKey: String,
) {
    CASH(isLiability = false, countsAsSpendableByDefault = true, defaultIconKey = "cash"),
    BANK(isLiability = false, countsAsSpendableByDefault = true, defaultIconKey = "bank"),
    SAVINGS(isLiability = false, countsAsSpendableByDefault = true, defaultIconKey = "savings"),
    CURRENT(isLiability = false, countsAsSpendableByDefault = true, defaultIconKey = "current"),
    WALLET(isLiability = false, countsAsSpendableByDefault = true, defaultIconKey = "wallet"),
    CREDIT_CARD(isLiability = true, countsAsSpendableByDefault = false, defaultIconKey = "credit_card"),
    INVESTMENT(isLiability = false, countsAsSpendableByDefault = false, defaultIconKey = "investment"),
    LOAN(isLiability = true, countsAsSpendableByDefault = false, defaultIconKey = "loan"),
    OTHER(isLiability = false, countsAsSpendableByDefault = false, defaultIconKey = "other"),
    ;

    companion object {
        /** Types offered during onboarding, in the order most Indian users need them. */
        val ONBOARDING_ORDER: List<AccountType> =
            listOf(BANK, CASH, WALLET, CREDIT_CARD, SAVINGS, INVESTMENT, LOAN, CURRENT, OTHER)
    }
}

/** An account together with its derived balance, which is what every screen actually shows. */
data class AccountBalance(
    val account: Account,
    /** Opening balance plus every posting. Negative on a credit card means money is owed. */
    val currentBalance: Money,
    val transactionCount: Int,
    @Serializable(with = InstantSerializer::class) val lastActivityAt: Instant? = null,
) {
    /**
     * The balance as a user reads it on a statement.
     *
     * For a liability we show the magnitude owed rather than a negative number, because
     * "-₹18,000 outstanding" reads as a double negative.
     */
    val displayBalance: Money
        get() = if (isOwed) currentBalance.abs() else currentBalance

    /**
     * True when this is a card or loan with money actually owed on it.
     *
     * A liability can be in credit -- a card paid more than its bill -- and then it is money the
     * user has, not money they owe. Showing its magnitude as "outstanding" turned an overpayment
     * into a debt on screen.
     */
    val isOwed: Boolean
        get() = account.isLiability && currentBalance.isNegative

    /** Signed contribution to net worth: assets add, liabilities subtract. */
    val netWorthContribution: Money
        get() = when {
            !account.includeInNetWorth -> Money.zero(account.currency)
            else -> currentBalance
        }
}
