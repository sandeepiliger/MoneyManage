package ai.labs32.khaata.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.labs32.khaata.core.calc.BalanceCalculator
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.database.KhaataDatabase
import ai.labs32.khaata.core.database.dao.AccountDao
import ai.labs32.khaata.core.database.dao.TransactionDao
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/**
 * Asserts the SQL aggregates agree with [BalanceCalculator].
 *
 * There are two implementations of "what is this account's balance": the Kotlin one, which is
 * unit-tested in `:core`, and the SQL one, which exists because summing forty thousand rows in
 * Kotlin on a mid-range phone is visibly slow. Two implementations of the same rule always drift
 * eventually, and the failure mode is the worst one this app has — a balance that is quietly
 * wrong, in a way the user cannot check without adding it all up themselves.
 *
 * So every case that distinguishes them is exercised against both: transfers in each direction,
 * soft-deleted rows, pending rows, and a same-day mixture of all of them.
 *
 * Runs on a device or emulator against a real SQLite instance rather than under Robolectric,
 * because the point is the behaviour of SQLite's own `SUM` and `CASE`, not a stand-in for it.
 */
@RunWith(AndroidJUnit4::class)
class TransactionAggregateParityTest {

    private lateinit var database: KhaataDatabase
    private lateinit var transactionDao: TransactionDao
    private lateinit var accountDao: AccountDao

    private val hdfc = account("acc-hdfc", "HDFC", "50000")
    private val cash = account("acc-cash", "Cash", "2000", AccountType.CASH)
    private val card = account("acc-card", "Card", "0", AccountType.CREDIT_CARD)
    private val accounts = listOf(hdfc, cash, card)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KhaataDatabase::class.java,
        ).build()
        transactionDao = database.transactionDao()
        accountDao = database.accountDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun expensesAndIncomeAgree() = runTest {
        val transactions = listOf(
            expense("t1", "850", hdfc.id),
            expense("t2", "1200", hdfc.id),
            income("t3", "35000", hdfc.id),
            expense("t4", "300", cash.id),
        )
        assertParity(transactions)
    }

    /**
     * The case the two implementations are most likely to disagree on: a transfer touches two
     * accounts from one row, and the sign depends on which account is being asked about.
     */
    @Test
    fun transfersAgreeOnBothLegs() = runTest {
        val transactions = listOf(
            transfer("t1", "10000", from = hdfc.id, to = cash.id),
            transfer("t2", "4000", from = cash.id, to = hdfc.id),
        )
        assertParity(transactions)
    }

    @Test
    fun softDeletedRowsAreExcludedByBoth() = runTest {
        val transactions = listOf(
            expense("t1", "850", hdfc.id),
            expense("t2", "9999", hdfc.id).copy(deletedAt = Instant.parse("2026-03-02T10:00:00Z")),
        )
        assertParity(transactions)
    }

    /** A pending import is a claim, not a posting, so neither implementation may count it. */
    @Test
    fun pendingRowsAreExcludedByBoth() = runTest {
        val transactions = listOf(
            expense("t1", "850", hdfc.id),
            expense("t2", "5000", hdfc.id).copy(isPending = true),
        )
        assertParity(transactions)
    }

    @Test
    fun aMixtureOfEveryCaseAgrees() = runTest {
        val transactions = listOf(
            income("t1", "82000", hdfc.id),
            expense("t2", "25000", hdfc.id),
            expense("t3", "649", card.id),
            transfer("t4", "15000", from = hdfc.id, to = cash.id),
            transfer("t5", "5000", from = cash.id, to = card.id),
            expense("t6", "1200", cash.id).copy(isPending = true),
            expense("t7", "7500", hdfc.id).copy(deletedAt = Instant.parse("2026-03-05T09:00:00Z")),
            income("t8", "500", cash.id),
        )
        assertParity(transactions)
    }

    @Test
    fun anAccountWithNoActivityIsItsOpeningBalance() = runTest {
        assertParity(listOf(expense("t1", "100", cash.id)))
    }

    @Test
    fun periodTotalsAgreeWithTheCalculator() = runTest {
        val transactions = listOf(
            expense("t1", "850", hdfc.id, LocalDate.of(2026, 3, 4)),
            expense("t2", "1200", hdfc.id, LocalDate.of(2026, 3, 20)),
            // Outside the period on both sides.
            expense("t3", "9999", hdfc.id, LocalDate.of(2026, 2, 27)),
            expense("t4", "8888", hdfc.id, LocalDate.of(2026, 4, 2)),
            income("t5", "35000", hdfc.id, LocalDate.of(2026, 3, 1)),
            transfer("t6", "10000", from = hdfc.id, to = cash.id, on = LocalDate.of(2026, 3, 10)),
        )
        insert(transactions)

        val march = DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))

        val sqlSpend = transactionDao
            .observeTotalSpend(march.start, march.endInclusive)
            .first()
        val sqlIncome = transactionDao
            .observeTotalIncome(march.start, march.endInclusive)
            .first()

        val calculatedSpend = transactions
            .filter { it.countsAsSpending && it.occurredOn in march }
            .fold(Money.zero()) { acc, t -> acc + t.amount }
        val calculatedIncome = transactions
            .filter { it.countsAsIncome && it.occurredOn in march }
            .fold(Money.zero()) { acc, t -> acc + t.amount }

        assertThat(Money.ofMinor(sqlSpend, CurrencyCode.INR)).isEqualTo(calculatedSpend)
        assertThat(Money.ofMinor(sqlIncome, CurrencyCode.INR)).isEqualTo(calculatedIncome)

        // The transfer must be in neither total. Stated separately so a regression that quietly
        // starts counting transfers as spending fails here with an obvious message.
        assertThat(calculatedSpend).isEqualTo(Money.of("2050"))
    }

    // ---- Helpers -----------------------------------------------------------------------------

    /** Inserts [transactions] and asserts every account's SQL balance equals the calculated one. */
    private suspend fun assertParity(transactions: List<Transaction>) {
        insert(transactions)

        val calculated = BalanceCalculator.balances(accounts, transactions)
            .associate { it.account.id to it.currentBalance }

        for (account in accounts) {
            val fromSql = Money.ofMinor(
                transactionDao.signedTotalForAccount(account.id),
                CurrencyCode.INR,
            ) + account.openingBalance

            assertThat(fromSql).isEqualTo(calculated.getValue(account.id))
        }

        // The batched query used by the account list must agree with the per-account one, or the
        // list and the detail screen show different numbers for the same account.
        val totals = transactionDao.observeAccountTotals().first().associateBy { it.accountId }
        for (account in accounts) {
            val batched = Money.ofMinor(totals[account.id]?.totalMinor ?: 0L, CurrencyCode.INR) +
                account.openingBalance
            assertThat(batched).isEqualTo(calculated.getValue(account.id))
        }
    }

    private suspend fun insert(transactions: List<Transaction>) {
        accountDao.upsertAll(accounts.map { it.toEntity() })
        transactionDao.upsertAll(transactions.map { it.toEntity() })
    }

    private fun account(
        id: String,
        name: String,
        opening: String,
        type: AccountType = AccountType.BANK,
    ) = Account(
        id = id,
        name = name,
        type = type,
        openingBalance = Money.of(opening),
    )

    private fun expense(
        id: String,
        amount: String,
        accountId: String,
        on: LocalDate = LocalDate.of(2026, 3, 10),
    ) = base(id, TransactionType.EXPENSE, amount, accountId, on)

    private fun income(
        id: String,
        amount: String,
        accountId: String,
        on: LocalDate = LocalDate.of(2026, 3, 10),
    ) = base(id, TransactionType.INCOME, amount, accountId, on)

    private fun transfer(
        id: String,
        amount: String,
        from: String,
        to: String,
        on: LocalDate = LocalDate.of(2026, 3, 10),
    ) = base(id, TransactionType.TRANSFER, amount, from, on).copy(transferAccountId = to)

    private fun base(
        id: String,
        type: TransactionType,
        amount: String,
        accountId: String,
        on: LocalDate,
    ) = Transaction(
        id = id,
        type = type,
        amount = Money.of(amount),
        accountId = accountId,
        occurredOn = on,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
    )
}
