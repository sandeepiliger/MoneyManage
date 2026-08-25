package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountBalance
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyMath
import ai.labs32.khaata.core.money.sumOfMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Derives account balances and net worth from the ledger.
 *
 * Balances are never stored. Every figure the app shows is the opening balance plus the
 * postings that explain it, which means a balance and its transaction list can never disagree —
 * the single most damaging kind of bug in a money app, because it destroys trust in every other
 * number on the screen.
 *
 * The production app runs the same arithmetic as an indexed SQL aggregate for speed; this class
 * is the reference implementation those queries are tested against.
 */
object BalanceCalculator {

    /**
     * Current balance of [account] given [transactions].
     *
     * Pending and soft-deleted rows are excluded — [Transaction.signedAmountFor] already returns
     * zero for them, so a caller need not pre-filter.
     */
    fun balanceOf(account: Account, transactions: Iterable<Transaction>): Money =
        account.openingBalance + transactions.sumOfMoney(account.currency) {
            it.signedAmountFor(account.id)
        }

    /** Balance of [account] as it stood at the end of [asOf]. Used for trend charts. */
    fun balanceAsOf(
        account: Account,
        transactions: Iterable<Transaction>,
        asOf: LocalDate,
    ): Money = account.openingBalance + transactions
        .filter { !it.occurredOn.isAfter(asOf) }
        .sumOfMoney(account.currency) { it.signedAmountFor(account.id) }

    /** Balances for every account, in the accounts' own sort order. */
    fun balances(
        accounts: List<Account>,
        transactions: List<Transaction>,
    ): List<AccountBalance> {
        // One pass over the ledger rather than one per account: with thousands of rows and a
        // dozen accounts the naive version is the difference between instant and janky.
        val totals = HashMap<String, Money>(accounts.size)
        val counts = HashMap<String, Int>(accounts.size)
        val lastActivity = HashMap<String, java.time.Instant>(accounts.size)
        val currencyOf = accounts.associate { it.id to it.currency }

        for (transaction in transactions) {
            if (!transaction.isEffective) continue
            for (accountId in transaction.touchedAccountIds()) {
                val currency = currencyOf[accountId] ?: continue
                val delta = transaction.signedAmountFor(accountId)
                if (delta.currency != currency) continue // guarded; cross-currency needs FX
                totals[accountId] = (totals[accountId] ?: Money.zero(currency)) + delta
                counts[accountId] = (counts[accountId] ?: 0) + 1
                val previous = lastActivity[accountId]
                if (previous == null || transaction.updatedAt.isAfter(previous)) {
                    lastActivity[accountId] = transaction.updatedAt
                }
            }
        }

        return accounts.map { account ->
            AccountBalance(
                account = account,
                currentBalance = account.openingBalance +
                    (totals[account.id] ?: Money.zero(account.currency)),
                transactionCount = counts[account.id] ?: 0,
                lastActivityAt = lastActivity[account.id],
            )
        }
    }

    /**
     * Net worth: assets minus liabilities, across accounts flagged to be included.
     *
     * Liability accounts carry a negative balance (spending on a card takes the balance down), so
     * they subtract naturally without a special case.
     */
    fun netWorth(
        balances: List<AccountBalance>,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): NetWorthSummary {
        val included = balances.filter { it.account.includeInNetWorth && !it.account.isArchived }
        val assets = included
            .filter { !it.account.isLiability }
            .sumOfMoney(currency) { it.currentBalance }
        val liabilities = included
            .filter { it.account.isLiability }
            .sumOfMoney(currency) { it.currentBalance }
        return NetWorthSummary(
            assets = assets,
            // Reported as a positive magnitude; the subtraction happens in `netWorth`.
            liabilities = liabilities.abs(),
            netWorth = assets + liabilities,
        )
    }

    /**
     * Money the user can actually spend right now.
     *
     * Distinct from net worth on purpose. Net worth includes a PPF balance and subtracts a home
     * loan; neither tells you whether you can pay for dinner. "Available" counts only accounts
     * the user marked as spendable, which is the honest answer to "how much can I safely spend?".
     */
    fun availableToSpend(
        balances: List<AccountBalance>,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): Money = balances
        .filter { it.account.includeInAvailableBalance && !it.account.isArchived }
        .sumOfMoney(currency) { it.currentBalance }

    /** Net worth at each of the given dates, oldest first, for the trend chart. */
    fun netWorthTrend(
        accounts: List<Account>,
        transactions: List<Transaction>,
        dates: List<LocalDate>,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): List<NetWorthPoint> {
        val included = accounts.filter { it.includeInNetWorth && !it.isArchived }
        if (included.isEmpty()) return dates.map { NetWorthPoint(it, Money.zero(currency)) }

        // Sorting once and sweeping forward keeps this O(n log n + n·|dates|) rather than
        // re-scanning the whole ledger for every point on the chart.
        val effective = transactions.filter { it.isEffective }.sortedBy { it.occurredOn }
        val includedIds = included.map { it.id }.toSet()
        var running = included.sumOfMoney(currency) { it.openingBalance }
        var cursor = 0

        return dates.sorted().map { date ->
            while (cursor < effective.size && !effective[cursor].occurredOn.isAfter(date)) {
                val transaction = effective[cursor]
                for (accountId in transaction.touchedAccountIds()) {
                    if (accountId in includedIds) {
                        running += transaction.signedAmountFor(accountId)
                    }
                }
                cursor++
            }
            NetWorthPoint(date, running)
        }
    }

    /**
     * Percentage change from [previous] to [current], or null when [previous] is zero.
     *
     * A change *from* zero has no defined percentage — reporting it as some huge (or infinite)
     * number would look precise while meaning nothing, so the UI is expected to show no figure
     * at all rather than a misleading one.
     *
     * The denominator is [previous]'s absolute value, not its signed value. Net worth and card
     * balances are routinely negative, and a debtor whose balance improves from -1000 to -500 has
     * cut what they owe in half — that is a +50% change, not -50%. Dividing by the signed amount
     * would flip the sign of every improvement made against a negative starting point.
     */
    fun percentChange(previous: Money, current: Money): BigDecimal? {
        if (previous.amount.signum() == 0) return null
        return current.amount.subtract(previous.amount)
            .divide(previous.amount.abs(), MoneyMath.PRECISION)
            .multiply(BigDecimal("100"))
            .setScale(1, RoundingMode.HALF_UP)
    }
}

/** Accounts this transaction posts against — one, or two for a transfer. */
fun Transaction.touchedAccountIds(): List<String> =
    if (transferAccountId != null) listOf(accountId, transferAccountId) else listOf(accountId)

data class NetWorthSummary(
    val assets: Money,
    /** Positive magnitude of what is owed. */
    val liabilities: Money,
    val netWorth: Money,
)

data class NetWorthPoint(val date: LocalDate, val netWorth: Money)
