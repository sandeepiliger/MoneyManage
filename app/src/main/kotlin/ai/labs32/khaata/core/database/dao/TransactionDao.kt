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
        """,
    )
    suspend fun signedTotalForAccount(accountId: String, asOf: LocalDate? = null): Long

    @Query(
        """
        SELECT
          CASE WHEN transferAccountId = accounts.id THEN accounts.id ELSE transactions.accountId END AS accountId,
          COALESCE(SUM(
            CASE
              WHEN transferAccountId = accounts.id THEN amount_minor_units
              WHEN transactions.accountId = accounts.id AND type = 'INCOME' THEN amount_minor_units
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
     * True when a transaction with this bank reference already exists.
     *
     * The duplicate guard for SMS import: the same message can be delivered twice, and a user
     * seeing their rent recorded twice loses trust in every other number.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM transactions
            WHERE referenceNumber = :reference AND referenceNumber IS NOT NULL AND deletedAt IS NULL
        )
        """,
    )
    suspend fun existsWithReference(reference: String): Boolean

    /**
     * Fallback duplicate check for messages with no reference number: same amount, same account
     * and same day.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM transactions
            WHERE amount_minor_units = :minorUnits
              AND accountId = :accountId
              AND occurredOn = :occurredOn
              AND deletedAt IS NULL
        )
        """,
    )
    suspend fun existsSimilar(minorUnits: Long, accountId: String, occurredOn: LocalDate): Boolean

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
