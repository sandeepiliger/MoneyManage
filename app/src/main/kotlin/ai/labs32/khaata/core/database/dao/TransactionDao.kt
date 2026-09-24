package ai.labs32.khaata.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import ai.labs32.khaata.core.database.entity.TransactionEntity
import ai.labs32.khaata.core.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * Ledger queries.
 *
 * Aggregation happens in SQL rather than in Kotlin. With several thousand transactions, loading
 * the whole table to sum it would stall the main thread and blow up memory on the mid-range
 * devices this app targets; `SUM()` over an index does the same work in microseconds. The
 * arithmetic is identical because amounts are stored as integer minor units, so summing them is
 * exact — the same result `BalanceCalculator` produces, which is asserted in the DAO tests.
 */
@Dao
interface TransactionDao {

    // ---- Writes ------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(transactions: List<TransactionEntity>)

    @Update
    suspend fun update(transaction: TransactionEntity)

    /**
     * Soft delete. The row stays so an undo is possible and history remains auditable.
     */
    @Query("UPDATE transactions SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Instant)

    @Query("UPDATE transactions SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restore(id: String, updatedAt: Instant)

    /** Permanent removal, used only by "delete all data" and by trash cleanup. */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM transactions WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Instant): Int

    @Query("UPDATE transactions SET isPending = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun confirmPending(id: String, updatedAt: Instant)

    // ---- Reads -------------------------------------------------------------------------------

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: String): Flow<TransactionEntity?>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0
        ORDER BY occurredOn DESC, createdAt DESC
        """,
    )
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0
        ORDER BY occurredOn DESC, createdAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0
          AND occurredOn BETWEEN :from AND :to
        ORDER BY occurredOn DESC, createdAt DESC
        """,
    )
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0 AND occurredOn BETWEEN :from AND :to
        ORDER BY occurredOn DESC, createdAt DESC
        """,
    )
    suspend fun getBetween(from: LocalDate, to: LocalDate): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY occurredOn DESC")
    suspend fun getAllIncludingPending(): List<TransactionEntity>

    /** Everything, deleted rows included — used only to build a full backup. */
    @Query("SELECT * FROM transactions ORDER BY occurredOn DESC")
    suspend fun getAllForExport(): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE isPending = 1 AND deletedAt IS NULL
        ORDER BY occurredOn DESC, createdAt DESC
        """,
    )
    fun observePending(): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE isPending = 1 AND deletedAt IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NOT NULL
        ORDER BY deletedAt DESC
        LIMIT :limit
        """,
    )
    fun observeDeleted(limit: Int): Flow<List<TransactionEntity>>

    /**
     * The filtered, paged transaction list.
     *
     * One query with nullable parameters rather than a dynamic string: it keeps the statement
     * prepared and cached, and it cannot be built wrong at a call site. Each `:param IS NULL OR`
     * clause is elided by SQLite's optimiser when the parameter is null.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL
          AND isPending = 0
          AND (:fromDate IS NULL OR occurredOn >= :fromDate)
          AND (:toDate IS NULL OR occurredOn <= :toDate)
          AND (:type IS NULL OR type = :type)
          AND (:accountCount = 0 OR accountId IN (:accountIds) OR transferAccountId IN (:accountIds))
          AND (:categoryCount = 0 OR categoryId IN (:categoryIds))
          AND (:minMinor IS NULL OR amount_minor_units >= :minMinor)
          AND (:maxMinor IS NULL OR amount_minor_units <= :maxMinor)
          AND (
                :query IS NULL
                OR merchant LIKE '%' || :query || '%'
                OR note LIKE '%' || :query || '%'
                OR referenceNumber LIKE '%' || :query || '%'
              )
          AND (:tagPattern IS NULL OR tags LIKE '%' || :tagPattern || '%')
        ORDER BY
          CASE WHEN :sortByAmount = 1 THEN amount_minor_units END DESC,
          occurredOn DESC,
          createdAt DESC
        """,
    )
    fun pagedFiltered(
        fromDate: LocalDate?,
        toDate: LocalDate?,
        type: TransactionType?,
        accountIds: List<String>,
        accountCount: Int,
        categoryIds: List<String>,
        categoryCount: Int,
        minMinor: Long?,
        maxMinor: Long?,
        query: String?,
        tagPattern: String?,
        sortByAmount: Boolean,
    ): PagingSource<Int, TransactionEntity>

    /** Non-paged counterpart of [pagedFiltered], for export and for computing filtered totals. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL
          AND isPending = 0
          AND (:fromDate IS NULL OR occurredOn >= :fromDate)
          AND (:toDate IS NULL OR occurredOn <= :toDate)
          AND (:type IS NULL OR type = :type)
          AND (:accountCount = 0 OR accountId IN (:accountIds) OR transferAccountId IN (:accountIds))
          AND (:categoryCount = 0 OR categoryId IN (:categoryIds))
          AND (:minMinor IS NULL OR amount_minor_units >= :minMinor)
          AND (:maxMinor IS NULL OR amount_minor_units <= :maxMinor)
          AND (
                :query IS NULL
                OR merchant LIKE '%' || :query || '%'
                OR note LIKE '%' || :query || '%'
                OR referenceNumber LIKE '%' || :query || '%'
              )
          AND (:tagPattern IS NULL OR tags LIKE '%' || :tagPattern || '%')
        ORDER BY occurredOn DESC, createdAt DESC
        """,
    )
    suspend fun listFiltered(
        fromDate: LocalDate?,
        toDate: LocalDate?,
        type: TransactionType?,
        accountIds: List<String>,
        accountCount: Int,
        categoryIds: List<String>,
        categoryCount: Int,
        minMinor: Long?,
        maxMinor: Long?,
        query: String?,
        tagPattern: String?,
    ): List<TransactionEntity>

    /**
     * Aggregate counterpart of [listFiltered]: the same WHERE clause, but summed and counted by
     * SQLite instead of loaded row-by-row into Kotlin.
     *
     * The spend sum mirrors `Transaction.countsAsSpending` (`isEffective && type == EXPENSE`):
     * `isEffective` is already guaranteed by the `deletedAt IS NULL AND isPending = 0` clause
     * below, so only the type comparison needs restating here. [count] mirrors the unfiltered
     * row count `listFiltered(...).size` — every matching row, not just expenses.
     */
    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount_minor_units ELSE 0 END), 0) AS totalMinor,
          COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount_minor_units ELSE 0 END), 0) AS incomeMinor,
          COUNT(*) AS count
        FROM transactions
        WHERE deletedAt IS NULL
          AND isPending = 0
          AND (:fromDate IS NULL OR occurredOn >= :fromDate)
          AND (:toDate IS NULL OR occurredOn <= :toDate)
          AND (:type IS NULL OR type = :type)
          AND (:accountCount = 0 OR accountId IN (:accountIds) OR transferAccountId IN (:accountIds))
          AND (:categoryCount = 0 OR categoryId IN (:categoryIds))
          AND (:minMinor IS NULL OR amount_minor_units >= :minMinor)
          AND (:maxMinor IS NULL OR amount_minor_units <= :maxMinor)
          AND (
                :query IS NULL
                OR merchant LIKE '%' || :query || '%'
                OR note LIKE '%' || :query || '%'
                OR referenceNumber LIKE '%' || :query || '%'
              )
          AND (:tagPattern IS NULL OR tags LIKE '%' || :tagPattern || '%')
        """,
    )
    suspend fun filteredSpendTotal(
        fromDate: LocalDate?,
        toDate: LocalDate?,
        type: TransactionType?,
        accountIds: List<String>,
        accountCount: Int,
        categoryIds: List<String>,
        categoryCount: Int,
        minMinor: Long?,
        maxMinor: Long?,
        query: String?,
        tagPattern: String?,
    ): FilteredTotalRow

    /**
     * [filteredSpendTotal] as a stream, for Activity's In / Out / Net strip, so the figures move
     * the moment a transaction is added, edited or deleted rather than only when the filter does.
     * Same WHERE clause, word for word.
     */
    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount_minor_units ELSE 0 END), 0) AS totalMinor,
          COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount_minor_units ELSE 0 END), 0) AS incomeMinor,
          COUNT(*) AS count
        FROM transactions
        WHERE deletedAt IS NULL
          AND isPending = 0
          AND (:fromDate IS NULL OR occurredOn >= :fromDate)
          AND (:toDate IS NULL OR occurredOn <= :toDate)
          AND (:type IS NULL OR type = :type)
          AND (:accountCount = 0 OR accountId IN (:accountIds) OR transferAccountId IN (:accountIds))
          AND (:categoryCount = 0 OR categoryId IN (:categoryIds))
          AND (:minMinor IS NULL OR amount_minor_units >= :minMinor)
          AND (:maxMinor IS NULL OR amount_minor_units <= :maxMinor)
          AND (
                :query IS NULL
                OR merchant LIKE '%' || :query || '%'
                OR note LIKE '%' || :query || '%'
                OR referenceNumber LIKE '%' || :query || '%'
              )
          AND (:tagPattern IS NULL OR tags LIKE '%' || :tagPattern || '%')
        """,
    )
    fun observeFilteredSpendTotal(
        fromDate: LocalDate?,
        toDate: LocalDate?,
        type: TransactionType?,
        accountIds: List<String>,
        accountCount: Int,
        categoryIds: List<String>,
        categoryCount: Int,
        minMinor: Long?,
        maxMinor: Long?,
        query: String?,
        tagPattern: String?,
    ): Flow<FilteredTotalRow>

    /**
     * Money out and money in per day for the same filter, for the total on each date header.
     *
     * Spending and income follow `Transaction.countsAsSpending` and `countsAsIncome`, as in
     * [filteredSpendTotal]: effective rows only, transfers in neither, since moving money between
     * two of your own accounts is not money spent that day.
     */
    @Query(
        """
        SELECT
          occurredOn AS day,
          COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount_minor_units ELSE 0 END), 0) AS spentMinor,
          COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount_minor_units ELSE 0 END), 0) AS incomeMinor
        FROM transactions
        WHERE deletedAt IS NULL
          AND isPending = 0
          AND (:fromDate IS NULL OR occurredOn >= :fromDate)
          AND (:toDate IS NULL OR occurredOn <= :toDate)
          AND (:type IS NULL OR type = :type)
          AND (:accountCount = 0 OR accountId IN (:accountIds) OR transferAccountId IN (:accountIds))
          AND (:categoryCount = 0 OR categoryId IN (:categoryIds))
          AND (:minMinor IS NULL OR amount_minor_units >= :minMinor)
          AND (:maxMinor IS NULL OR amount_minor_units <= :maxMinor)
          AND (
                :query IS NULL
                OR merchant LIKE '%' || :query || '%'
                OR note LIKE '%' || :query || '%'
                OR referenceNumber LIKE '%' || :query || '%'
              )
          AND (:tagPattern IS NULL OR tags LIKE '%' || :tagPattern || '%')
        GROUP BY occurredOn
        """,
    )
    fun observeDailyTotals(
        fromDate: LocalDate?,
        toDate: LocalDate?,
        type: TransactionType?,
        accountIds: List<String>,
        accountCount: Int,
        categoryIds: List<String>,
        categoryCount: Int,
        minMinor: Long?,
        maxMinor: Long?,
        query: String?,
        tagPattern: String?,
    ): Flow<List<DayInOutRow>>

    // ---- Aggregates --------------------------------------------------------------------------

    /**
     * The signed sum of postings against [accountId], in minor units.
     *
     * Income and the incoming leg of a transfer add; expenses and the outgoing leg subtract. This
     * mirrors `Transaction.signedAmountFor` exactly, and a test asserts the two agree.
     */
    @Query(
        """
        SELECT COALESCE(SUM(
            CASE
              WHEN transferAccountId = :accountId THEN amount_minor_units
              WHEN accountId = :accountId AND type = 'INCOME' THEN amount_minor_units
              WHEN accountId = :accountId THEN -amount_minor_units
              ELSE 0
            END
        ), 0)
        FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0
          AND (accountId = :accountId OR transferAccountId = :accountId)
          AND (:asOf IS NULL OR occurredOn <= :asOf)
          AND (:since IS NULL OR occurredOn >= :since)
        """,
    )
    suspend fun signedTotalForAccount(
        accountId: String,
        asOf: LocalDate? = null,
        // The account's opening-balance date. Anything earlier is already inside that balance;
        // see Account.movesBalanceOn, which this must agree with exactly.
        since: LocalDate? = null,
    ): Long

    @Query(
        """
        SELECT
          CASE WHEN transferAccountId = accounts.id THEN accounts.id ELSE transactions.accountId END AS accountId,
          COALESCE(SUM(
            CASE
              -- Before a stated opening balance, a transaction is already inside that balance.
              -- It is excluded from the sum only, not from the count or last activity: it is
              -- still history on the account. Mirrors Account.movesBalanceOn.
              WHEN accounts.openingBalanceDate IS NOT NULL
                AND transactions.occurredOn < accounts.openingBalanceDate THEN 0
              WHEN transferAccountId = accounts.id THEN amount_minor_units
              WHEN transactions.accountId = accounts.id AND transactions.type = 'INCOME' THEN amount_minor_units
              WHEN transactions.accountId = accounts.id THEN -amount_minor_units
              ELSE 0
            END
          ), 0) AS totalMinor,
          COUNT(*) AS transactionCount,
          MAX(transactions.updatedAt) AS lastActivityAt
        FROM transactions
        JOIN accounts ON transactions.accountId = accounts.id OR transactions.transferAccountId = accounts.id
        WHERE transactions.deletedAt IS NULL AND transactions.isPending = 0
        GROUP BY accounts.id
        """,
    )
    fun observeAccountTotals(): Flow<List<AccountTotalRow>>

    @Query(
        """
        SELECT COALESCE(SUM(amount_minor_units), 0) FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0 AND type = 'EXPENSE'
          AND occurredOn BETWEEN :from AND :to
        """,
    )
    fun observeTotalSpend(from: LocalDate, to: LocalDate): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(amount_minor_units), 0) FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0 AND type = 'INCOME'
          AND occurredOn BETWEEN :from AND :to
        """,
    )
    fun observeTotalIncome(from: LocalDate, to: LocalDate): Flow<Long>

    @Query(
        """
        SELECT categoryId, COALESCE(SUM(amount_minor_units), 0) AS totalMinor, COUNT(*) AS transactionCount
        FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0 AND type = 'EXPENSE'
          AND occurredOn BETWEEN :from AND :to
        GROUP BY categoryId
        ORDER BY totalMinor DESC
        """,
    )
    fun observeCategoryTotals(from: LocalDate, to: LocalDate): Flow<List<CategoryTotalRow>>

    @Query(
        """
        SELECT merchant AS merchant, COALESCE(SUM(amount_minor_units), 0) AS totalMinor,
               COUNT(*) AS transactionCount
        FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0 AND type = 'EXPENSE'
          AND merchant IS NOT NULL AND merchant != ''
          AND occurredOn BETWEEN :from AND :to
        GROUP BY merchantKey
        ORDER BY totalMinor DESC
        LIMIT :limit
        """,
    )
    suspend fun merchantTotals(from: LocalDate, to: LocalDate, limit: Int): List<MerchantTotalRow>

    @Query(
        """
        SELECT occurredOn AS date, COALESCE(SUM(amount_minor_units), 0) AS totalMinor,
               COUNT(*) AS transactionCount
        FROM transactions
        WHERE deletedAt IS NULL AND isPending = 0 AND type = 'EXPENSE'
          AND occurredOn BETWEEN :from AND :to
        GROUP BY occurredOn
        ORDER BY occurredOn
        """,
    )
    suspend fun dailyTotals(from: LocalDate, to: LocalDate): List<DailyTotalRow>

    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId AND deletedAt IS NULL")
    suspend fun countForAccount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId AND deletedAt IS NULL")
    suspend fun countForCategory(categoryId: String): Int

    /** Distinct merchant names for autocomplete, most-used first. */
    @Query(
        """
        SELECT merchant FROM transactions
        WHERE deletedAt IS NULL AND merchant IS NOT NULL AND merchant != ''
          AND merchant LIKE :prefix || '%'
        GROUP BY merchantKey
        ORDER BY COUNT(*) DESC
        LIMIT :limit
        """,
    )
    suspend fun merchantSuggestions(prefix: String, limit: Int): List<String>

    /**
     * Whether a recurring rule's occurrence on [date] is already in the ledger.
     *
     * Either this rule already posted that date (a retry after the process died between writing
     * the row and advancing the rule), or the same payment arrived another way -- the bank's
     * NACH SMS for the EMI, the rent typed in by hand -- within [from]..[to]. Auto-posting on top
     * of those counted the payment twice. Other postings of the same rule on other dates are not
     * matches, or a daily rule would block itself.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM transactions
            WHERE deletedAt IS NULL
              AND amount_minor_units = :minorUnits
              AND (
                (accountId = :accountId AND (CASE WHEN type = 'INCOME' THEN 0 ELSE 1 END) = :outflow)
                OR (transferAccountId = :accountId AND :outflow = 0)
              )
              AND (
                (recurringRuleId = :ruleId AND occurredOn = :date)
                OR ((recurringRuleId IS NULL OR recurringRuleId != :ruleId) AND occurredOn BETWEEN :from AND :to)
              )
        )
        """,
    )
    suspend fun occurrenceAlreadyRecorded(
        ruleId: String,
        minorUnits: Long,
        accountId: String,
        outflow: Boolean,
        date: LocalDate,
        from: LocalDate,
        to: LocalDate,
    ): Boolean

    /** Live rows on [accountId] on either side of a transfer; what blocks deleting the account. */
    @Query(
        "SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL " +
            "AND (accountId = :accountId OR transferAccountId = :accountId)",
    )
    suspend fun countLiveTouchingAccount(accountId: String): Int

    /**
     * Permanently removes rows on [accountId] that were already deleted, so they no longer hold
     * the account in place through its foreign key when the account itself is deleted.
     */
    @Query(
        "DELETE FROM transactions WHERE deletedAt IS NOT NULL " +
            "AND (accountId = :accountId OR transferAccountId = :accountId)",
    )
    suspend fun purgeDeletedTouchingAccount(accountId: String)

    /** Every distinct stored tag set, for tag suggestions and the tag filter. Decoded by the caller. */
    @Query("SELECT DISTINCT tags FROM transactions WHERE deletedAt IS NULL AND tags != ''")
    fun observeTagColumns(): Flow<List<String>>

    /**
     * True when this account already has a row for this bank reference moving money the same way.
     *
     * The duplicate guard for SMS import: the same message can be delivered twice, and a user
     * seeing their rent recorded twice loses trust in every other number.
     *
     * Scoped to the account and the direction rather than the reference alone. A UPI transfer
     * between two of the user's own accounts quotes one reference in both banks' messages, and a
     * refund quotes the purchase's; a global match dropped the second leg as a "duplicate" and
     * left that account's balance short by the whole amount. A transfer row counts as an outflow
     * for its source account and an inflow for its destination.
     *
     * Soft-deleted rows count only when they were themselves imported: a message the user
     * discarded stays discarded when the inbox is read again, while a hand-entered row they
     * deleted never blocks a genuine new message.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM transactions
            WHERE referenceNumber = :reference
              AND (deletedAt IS NULL OR source = 'SMS_IMPORT')
              AND (
                (accountId = :accountId AND (CASE WHEN type = 'INCOME' THEN 0 ELSE 1 END) = :outflow)
                OR (transferAccountId = :accountId AND :outflow = 0)
              )
        )
        """,
    )
    suspend fun existsWithReference(reference: String, accountId: String, outflow: Boolean): Boolean

    /**
     * Fallback duplicate check: same amount, same account, same direction, same day.
     *
     * This is what recognises a hand-entered expense as the one the bank then messages about.
     * Two rows that each carry a different bank reference are two different payments however
     * alike they look -- two ₹20 teas on one day -- so a reference on both sides that disagrees
     * rules a match out.
     *
     * A transfer's destination leg matches any inflow of the amount within [transferFrom]..
     * [transferTo], whatever its reference: the credit SMS for a transfer the user logged, or one
     * already paired from the debit SMS, often quotes its own bank's reference and posts a day
     * later, and must be recognised rather than added again as income.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM transactions
            WHERE amount_minor_units = :minorUnits
              AND (deletedAt IS NULL OR source = 'SMS_IMPORT')
              AND (
                (
                  accountId = :accountId
                  AND occurredOn = :occurredOn
                  AND (CASE WHEN type = 'INCOME' THEN 0 ELSE 1 END) = :outflow
                  AND (referenceNumber IS NULL OR :reference IS NULL OR referenceNumber = :reference)
                )
                OR (
                  transferAccountId = :accountId
                  AND :outflow = 0
                  AND occurredOn BETWEEN :transferFrom AND :transferTo
                )
                OR (
                  -- An EMI or rent a recurring rule already posted; the bank's own message
                  -- usually lands a day or two off the due date.
                  source = 'RECURRING'
                  AND accountId = :accountId
                  AND (CASE WHEN type = 'INCOME' THEN 0 ELSE 1 END) = :outflow
                  AND occurredOn BETWEEN :transferFrom AND :transferTo
                )
              )
        )
        """,
    )
    suspend fun existsSimilar(
        minorUnits: Long,
        accountId: String,
        occurredOn: LocalDate,
        outflow: Boolean,
        reference: String?,
        transferFrom: LocalDate,
        transferTo: LocalDate,
    ): Boolean

    /**
     * Imports still awaiting review that could be the other leg of a transfer: the same amount,
     * within [from]..[to], not already paired. [TransferPairing][ai.labs32.khaata.core.sms.TransferPairing]
     * makes the actual decision.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE isPending = 1 AND deletedAt IS NULL AND source = 'SMS_IMPORT'
          AND transferAccountId IS NULL
          AND amount_minor_units = :minorUnits
          AND occurredOn BETWEEN :from AND :to
        """,
    )
    suspend fun pendingImportsForAmount(minorUnits: Long, from: LocalDate, to: LocalDate): List<TransactionEntity>

    @Transaction
    suspend fun replaceAll(transactions: List<TransactionEntity>) {
        deleteAll()
        upsertAll(transactions)
    }

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    /** Removes only demo rows, so leaving demo mode does not touch anything the user entered. */
    @Query("DELETE FROM transactions WHERE source = 'DEMO'")
    suspend fun deleteDemoData()
}

// ---- Aggregate projections -------------------------------------------------------------------

data class AccountTotalRow(
    val accountId: String,
    val totalMinor: Long,
    val transactionCount: Int,
    val lastActivityAt: Instant?,
)

data class CategoryTotalRow(
    val categoryId: String?,
    val totalMinor: Long,
    val transactionCount: Int,
)

data class MerchantTotalRow(
    val merchant: String,
    val totalMinor: Long,
    val transactionCount: Int,
)

data class DailyTotalRow(
    val date: LocalDate,
    val totalMinor: Long,
    val transactionCount: Int,
)

data class FilteredTotalRow(
    /** Spending only. */
    val totalMinor: Long,
    /** Money received. Kept apart so a filtered view of income does not read as ₹0 spent. */
    val incomeMinor: Long,
    val count: Int,
)

/** One day's money out and in, for the Activity list's date headers; see [TransactionDao.observeDailyTotals]. */
data class DayInOutRow(
    val day: LocalDate,
    val spentMinor: Long,
    val incomeMinor: Long,
)
