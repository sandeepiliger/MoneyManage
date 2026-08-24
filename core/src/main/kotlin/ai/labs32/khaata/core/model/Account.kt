package ai.labs32.khaata.core.model

import ai.labs32.khaata.core.common.InstantSerializer
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import kotlinx.serialization.Serializable
import java.time.Instant

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
        get() = if (account.isLiability) currentBalance.abs() else currentBalance

    /** Signed contribution to net worth: assets add, liabilities subtract. */
    val netWorthContribution: Money
        get() = when {
            !account.includeInNetWorth -> Money.zero(account.currency)
            else -> currentBalance
        }
}
