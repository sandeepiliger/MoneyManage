package ai.labs32.khaata.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
 * Runs under Robolectric, whose SQLite is the real native library, so it exercises SQLite's own
 * `SUM` and `CASE` -- and, unlike an instrumentation test, it runs in CI on every push. It sat in
 * `androidTest` for the whole life of the project and never executed once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    /**
     * An opening balance with a date is a snapshot: activity before it is already inside it. The
     * SQL has to skip exactly the rows the calculator skips, on both legs of a transfer.
     */
    @Test
    fun activityBeforeAnOpeningBalanceDateIsExcludedByBoth() = runTest {
        val snapshot = LocalDate.of(2026, 3, 10)
        val dated = listOf(
            hdfc.copy(openingBalanceDate = snapshot),
            cash,
            card.copy(openingBalanceDate = snapshot.plusDays(5)),
        )
        val transactions = listOf(
            expense("t1", "850", hdfc.id, on = snapshot.minusDays(20)),
            expense("t2", "1200", hdfc.id, on = snapshot),
            income("t3", "35000", hdfc.id, on = snapshot.plusDays(1)),
            transfer("t4", "4000", from = hdfc.id, to = cash.id, on = snapshot.minusDays(1)),
            transfer("t5", "900", from = cash.id, to = card.id, on = snapshot.plusDays(2)),
            expense("t6", "649", card.id, on = snapshot.plusDays(6)),
        )
        assertParity(transactions, dated)
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

    /**
     * A large, seeded, random ledger: every kind of row the app stores, on every account, around
     * each account's opening-balance date. Hand-picked cases only cover the cases someone thought
     * of; this checks every SQL figure against the Kotlin rules to the paisa across thousands.
     */
    @Test
    fun aLargeRandomLedgerAgreesEverywhere() = runTest {
        val random = java.util.Random(20_260_923L)
        val dated = listOf(
            hdfc.copy(openingBalanceDate = LocalDate.of(2026, 3, 5)),
            cash,
            card.copy(openingBalanceDate = LocalDate.of(2026, 3, 20)),
        )
        val categories = listOf(null, "cat-food", "cat-fuel", "cat-rent", "cat-salary")
        // Transactions reference categories by foreign key, so the ones used must exist.
        database.categoryDao().upsertAll(
            categories.filterNotNull().map { id ->
                ai.labs32.khaata.core.model.Category(
                    id = id,
                    name = id,
                    group = ai.labs32.khaata.core.model.CategoryGroup.entries.first(),
                ).toEntity()
            },
        )
        val start = LocalDate.of(2026, 2, 1)
        val transactions = (1..3_000).map { n ->
            val on = start.plusDays(random.nextInt(89).toLong())
            val from = dated[random.nextInt(dated.size)].id
            val amount = Money.ofMinor(1L + random.nextInt(5_000_000), CurrencyCode.INR).toPlainString()
            val base = when (random.nextInt(10)) {
                in 0..5 -> expense("r$n", amount, from, on)
                in 6..7 -> income("r$n", amount, from, on)
                else -> {
                    val to = dated.map { it.id }.filter { it != from }[random.nextInt(dated.size - 1)]
                    transfer("r$n", amount, from = from, to = to, on = on)
                }
            }
            base.copy(
                categoryId = if (base.type == TransactionType.TRANSFER) null else categories[random.nextInt(categories.size)],
                isPending = random.nextInt(10) == 0,
                deletedAt = if (random.nextInt(20) == 0) Instant.parse("2026-04-01T00:00:00Z") else null,
            )
        }

        assertParity(transactions, dated)

        val march = DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        val effectiveInMarch = transactions.filter { it.isEffective && it.occurredOn in march }
        fun sumOf(rows: List<Transaction>) = rows.fold(Money.zero()) { acc, t -> acc + t.amount }

        // Month totals.
        assertThat(Money.ofMinor(transactionDao.observeTotalSpend(march.start, march.endInclusive).first(), CurrencyCode.INR))
            .isEqualTo(sumOf(effectiveInMarch.filter { it.countsAsSpending }))
        assertThat(Money.ofMinor(transactionDao.observeTotalIncome(march.start, march.endInclusive).first(), CurrencyCode.INR))
            .isEqualTo(sumOf(effectiveInMarch.filter { it.countsAsIncome }))

        // Spending by category, the uncategorised bucket included.
        val sqlByCategory = transactionDao.observeCategoryTotals(march.start, march.endInclusive).first()
            .associate { it.categoryId to it.totalMinor }
        val calculatedByCategory = effectiveInMarch.filter { it.countsAsSpending }
            .groupBy { it.categoryId }
            .mapValues { (_, rows) -> sumOf(rows).minorUnits }
        assertThat(sqlByCategory).isEqualTo(calculatedByCategory)

        // Daily spending, which the reports chart draws.
        val sqlDaily = transactionDao.dailyTotals(march.start, march.endInclusive).associate { it.date to it.totalMinor }
        val calculatedDaily = effectiveInMarch.filter { it.countsAsSpending }
            .groupBy { it.occurredOn }
            .mapValues { (_, rows) -> sumOf(rows).minorUnits }
        assertThat(sqlDaily).isEqualTo(calculatedDaily)

        // The Transactions screen's filtered total: spending and income apart, transfers in
        // neither, an account filter matching either leg of a transfer.
        val filtered = transactionDao.filteredSpendTotal(
            fromDate = march.start,
            toDate = march.endInclusive,
            type = null,
            accountIds = listOf(hdfc.id),
            accountCount = 1,
            categoryIds = emptyList(),
            categoryCount = 0,
            minMinor = null,
            maxMinor = null,
            query = null,
            tagPattern = null,
        )
        val onHdfc = effectiveInMarch.filter { it.accountId == hdfc.id || it.transferAccountId == hdfc.id }
        assertThat(filtered.count).isEqualTo(onHdfc.size)
        assertThat(filtered.totalMinor).isEqualTo(sumOf(onHdfc.filter { it.type == TransactionType.EXPENSE }).minorUnits)
        assertThat(filtered.incomeMinor).isEqualTo(sumOf(onHdfc.filter { it.type == TransactionType.INCOME }).minorUnits)

        // Net worth and available-to-spend from the SQL balances equal the calculator's.
        val sqlTotals = transactionDao.observeAccountTotals().first().associateBy { it.accountId }
        val sqlBalances = dated.map { account ->
            ai.labs32.khaata.core.model.AccountBalance(
                account = account,
                currentBalance = account.openingBalance +
                    Money.ofMinor(sqlTotals[account.id]?.totalMinor ?: 0L, CurrencyCode.INR),
                transactionCount = sqlTotals[account.id]?.transactionCount ?: 0,
                lastActivityAt = sqlTotals[account.id]?.lastActivityAt,
            )
        }
        val calculatedBalances = BalanceCalculator.balances(dated, transactions)
        assertThat(BalanceCalculator.netWorth(sqlBalances)).isEqualTo(BalanceCalculator.netWorth(calculatedBalances))
        assertThat(BalanceCalculator.availableToSpend(sqlBalances))
            .isEqualTo(BalanceCalculator.availableToSpend(calculatedBalances))
    }

    // ---- Helpers -----------------------------------------------------------------------------

    /** Inserts [transactions] and asserts every account's SQL balance equals the calculated one. */
    private suspend fun assertParity(
        transactions: List<Transaction>,
        accounts: List<Account> = this.accounts,
    ) {
        insert(transactions, accounts)

        val calculated = BalanceCalculator.balances(accounts, transactions)
            .associate { it.account.id to it.currentBalance }

        for (account in accounts) {
            val fromSql = Money.ofMinor(
                transactionDao.signedTotalForAccount(account.id, since = account.openingBalanceDate),
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

    private suspend fun insert(
        transactions: List<Transaction>,
        accounts: List<Account> = this.accounts,
    ) {
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
    ) = base(id, TransactionType.TRANSFER, amount, from, on, transferAccountId = to)

    private fun base(
        id: String,
        type: TransactionType,
        amount: String,
        accountId: String,
        on: LocalDate,
        // Set at construction: Transaction refuses a transfer without a destination, so building
        // one and copying the destination in afterwards throws before the copy is reached.
        transferAccountId: String? = null,
    ) = Transaction(
        id = id,
        type = type,
        amount = Money.of(amount),
        accountId = accountId,
        transferAccountId = transferAccountId,
        occurredOn = on,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
    )
}
