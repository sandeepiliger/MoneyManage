package ai.labs32.khaata.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import ai.labs32.khaata.core.categorize.MerchantCategorizer
import ai.labs32.khaata.core.categorize.MerchantNormaliser
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.MerchantRuleDao
import ai.labs32.khaata.core.database.dao.TransactionDao
import ai.labs32.khaata.core.database.toDomain
import ai.labs32.khaata.core.database.Converters
import ai.labs32.khaata.core.database.toDomainOrNull
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the ledger.
 *
 * The repository owns three things the UI must never have to remember:
 *
 *  1. **Timestamps.** `createdAt` and `updatedAt` are set here from the injected clock, so audit
 *     trails are consistent and testable.
 *  2. **Learning.** Saving a categorised transaction teaches the merchant rule set, so the next
 *     one is pre-filled. Doing this at the repository means every entry path — quick add, natural
 *     language, SMS import, CSV — benefits without repeating itself.
 *  3. **Soft delete.** Deleting hides a row and keeps it recoverable; only explicit purges remove
 *     data permanently.
 */
@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val merchantRuleDao: MerchantRuleDao,
    private val categorizer: MerchantCategorizer,
    private val clock: KhaataClock,
) {

    // ---- Observation -------------------------------------------------------------------------

    fun observeAll(): Flow<List<Transaction>> =
        transactionDao.observeAll().map { it.toDomain() }

    fun observeRecent(limit: Int = 10): Flow<List<Transaction>> =
        transactionDao.observeRecent(limit).map { it.toDomain() }

    fun observeInRange(range: DateRange): Flow<List<Transaction>> =
        transactionDao.observeBetween(range.start, range.endInclusive).map { it.toDomain() }

    fun observeById(id: String): Flow<Transaction?> =
        transactionDao.observeById(id).map { it?.toDomainOrNull() }

    fun observePending(): Flow<List<Transaction>> =
        transactionDao.observePending().map { it.toDomain() }

    fun observePendingCount(): Flow<Int> = transactionDao.observePendingCount()

    fun observeDeleted(limit: Int = 50): Flow<List<Transaction>> =
        transactionDao.observeDeleted(limit).map { it.toDomain() }

    suspend fun findById(id: String): Transaction? = transactionDao.findById(id)?.toDomainOrNull()

    suspend fun getInRange(range: DateRange): List<Transaction> =
        transactionDao.getBetween(range.start, range.endInclusive).toDomain()

    /**
     * The paged, filtered transaction list.
     *
     * Paged rather than a plain list because this is the one screen that can hold years of
     * history; loading it whole would make opening the tab visibly slow on a mid-range phone.
     */
    fun pagedTransactions(filter: TransactionFilter): Flow<PagingData<Transaction>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            // Enough to fill a tall screen plus a scroll, without loading the whole table.
            initialLoadSize = PAGE_SIZE * 2,
            prefetchDistance = PAGE_SIZE / 2,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = {
            transactionDao.pagedFiltered(
                fromDate = filter.dateRange?.start,
                toDate = filter.dateRange?.endInclusive,
                type = filter.type,
                accountIds = filter.accountIds.toList(),
                accountCount = filter.accountIds.size,
                categoryIds = filter.categoryIds.toList(),
                categoryCount = filter.categoryIds.size,
                minMinor = filter.minAmount?.minorUnits,
                maxMinor = filter.maxAmount?.minorUnits,
                query = filter.query?.takeIf { it.isNotBlank() },
                tagPattern = filter.tagPattern(),
                sortByAmount = filter.sort == TransactionSort.AMOUNT_DESC,
            )
        },
    ).flow.map { paging -> paging.map { it.toDomainOrNull() ?: PLACEHOLDER } }

    /** Non-paged filtered list, for export and for totalling a filtered view. */
    suspend fun listFiltered(filter: TransactionFilter): List<Transaction> =
        transactionDao.listFiltered(
            fromDate = filter.dateRange?.start,
            toDate = filter.dateRange?.endInclusive,
            type = filter.type,
            accountIds = filter.accountIds.toList(),
            accountCount = filter.accountIds.size,
            categoryIds = filter.categoryIds.toList(),
            categoryCount = filter.categoryIds.size,
            minMinor = filter.minAmount?.minorUnits,
            maxMinor = filter.maxAmount?.minorUnits,
            query = filter.query?.takeIf { it.isNotBlank() },
            tagPattern = filter.tagPattern(),
        ).toDomain()

    // ---- Writes ------------------------------------------------------------------------------

    /**
     * Saves a new transaction and returns its id.
     *
     * @param learnCategory teaches the merchant rule set from this entry. False for imports the
     *   user has not reviewed, so an SMS the parser guessed wrong does not poison future
     *   suggestions.
     */
    suspend fun create(
        type: TransactionType,
        amount: Money,
        accountId: String,
        categoryId: String?,
        transferAccountId: String? = null,
        merchant: String? = null,
        note: String? = null,
        occurredOn: LocalDate = clock.today(),
        tags: Set<String> = emptySet(),
        source: TransactionSource = TransactionSource.MANUAL,
        referenceNumber: String? = null,
        recurringRuleId: String? = null,
        isPending: Boolean = false,
        learnCategory: Boolean = true,
    ): String {
        val now = clock.now()
        val transaction = Transaction(
            id = UUID.randomUUID().toString(),
            type = type,
            amount = amount,
            accountId = accountId,
            transferAccountId = transferAccountId,
            categoryId = categoryId,
            merchant = merchant?.trim()?.takeIf { it.isNotBlank() },
            note = note?.trim()?.takeIf { it.isNotBlank() },
            occurredOn = occurredOn,
            tags = tags,
            source = source,
            referenceNumber = referenceNumber,
            recurringRuleId = recurringRuleId,
            isPending = isPending,
            createdAt = now,
            updatedAt = now,
        )
        transactionDao.insert(transaction.toEntity())

        if (learnCategory && categoryId != null && merchant != null) {
            learnMerchantRule(merchant, categoryId, accountId, isExplicitUserChoice = false)
        }
        return transaction.id
    }

    /** Updates an existing transaction, refreshing its `updatedAt` stamp. */
    suspend fun update(transaction: Transaction, learnCategory: Boolean = true) {
        transactionDao.update(transaction.copy(updatedAt = clock.now()).toEntity())

        // An edit is a stronger signal than an initial save: the user looked at what we guessed
        // and changed it, so this teaches the rule set as an explicit choice.
        val merchant = transaction.merchant
        val categoryId = transaction.categoryId
        if (learnCategory && categoryId != null && merchant != null) {
            learnMerchantRule(
                merchant,
                categoryId,
                transaction.accountId,
                isExplicitUserChoice = true,
            )
        }
    }

    /** Hides a transaction, keeping it recoverable. */
    suspend fun delete(id: String) = transactionDao.softDelete(id, clock.now())

    suspend fun restore(id: String) = transactionDao.restore(id, clock.now())

    /** Copies a transaction onto today's date, for a repeat purchase. */
    suspend fun duplicate(id: String): String? {
        val original = transactionDao.findById(id)?.toDomainOrNull() ?: return null
        return create(
            type = original.type,
            amount = original.amount,
            accountId = original.accountId,
            categoryId = original.categoryId,
            transferAccountId = original.transferAccountId,
            merchant = original.merchant,
            note = original.note,
            occurredOn = clock.today(),
            tags = original.tags,
            source = TransactionSource.MANUAL,
            learnCategory = false,
        )
    }

    /** Confirms an imported row, moving it out of pending so it affects balances. */
    suspend fun confirmPending(id: String) = transactionDao.confirmPending(id, clock.now())

    suspend fun createAll(transactions: List<Transaction>) {
        val now = clock.now()
        transactionDao.upsertAll(
            transactions.map { it.copy(createdAt = now, updatedAt = now).toEntity() },
        )
    }

    // ---- Duplicate detection -----------------------------------------------------------------

    /**
     * Whether an imported transaction is already recorded.
     *
     * A bank reference is the reliable signal; without one, matching on amount, account and date
     * catches the common case of the same SMS being delivered twice.
     */
    suspend fun isLikelyDuplicate(
        referenceNumber: String?,
        amount: Money,
        accountId: String,
        occurredOn: LocalDate,
    ): Boolean {
        if (!referenceNumber.isNullOrBlank() && transactionDao.existsWithReference(referenceNumber)) {
            return true
        }
        return transactionDao.existsSimilar(amount.minorUnits, accountId, occurredOn)
    }

    // ---- Aggregates --------------------------------------------------------------------------

    fun observeTotalSpend(range: DateRange, currency: ai.labs32.khaata.core.money.CurrencyCode): Flow<Money> =
        transactionDao.observeTotalSpend(range.start, range.endInclusive)
            .map { Money.ofMinor(it, currency) }

    fun observeTotalIncome(range: DateRange, currency: ai.labs32.khaata.core.money.CurrencyCode): Flow<Money> =
        transactionDao.observeTotalIncome(range.start, range.endInclusive)
            .map { Money.ofMinor(it, currency) }

    suspend fun merchantSuggestions(prefix: String, limit: Int = 8): List<String> =
        if (prefix.isBlank()) emptyList() else transactionDao.merchantSuggestions(prefix.trim(), limit)

    suspend fun count(): Int = transactionDao.count()

    suspend fun countForAccount(accountId: String): Int = transactionDao.countForAccount(accountId)

    suspend fun countForCategory(categoryId: String): Int = transactionDao.countForCategory(categoryId)

    // ---- Housekeeping ------------------------------------------------------------------------

    /** Permanently removes rows deleted more than [retentionDays] ago. */
    suspend fun purgeOldDeleted(retentionDays: Long = TRASH_RETENTION_DAYS): Int =
        transactionDao.purgeDeletedBefore(clock.now().minusSeconds(retentionDays * 86_400))

    suspend fun deleteAll() = transactionDao.deleteAll()

    suspend fun deleteDemoData() = transactionDao.deleteDemoData()

    suspend fun getAllForExport(): List<Transaction> = transactionDao.getAllForExport().toDomain()

    // ---- Internal ----------------------------------------------------------------------------

    private suspend fun learnMerchantRule(
        merchant: String,
        categoryId: String,
        accountId: String,
        isExplicitUserChoice: Boolean,
    ) {
        val key = MerchantNormaliser.normalise(merchant) ?: return
        val existing = merchantRuleDao.getAll().map { it.toDomain() }
        val updated = categorizer.learn(
            merchantText = merchant,
            categoryId = categoryId,
            accountId = accountId,
            rules = existing,
            isExplicitUserChoice = isExplicitUserChoice,
            newRuleId = { UUID.randomUUID().toString() },
        )
        // Only the rule for this merchant can have changed, so write that one row rather than
        // rewriting the whole table on every save.
        updated.firstOrNull { it.merchantKey == key }?.let {
            merchantRuleDao.upsert(it.toEntity())
        }
    }

    private companion object {
        const val PAGE_SIZE = 40
        const val TRASH_RETENTION_DAYS = 30L

        /**
         * Stand-in for a row that failed domain validation.
         *
         * Paging needs a non-null value for every position, so an unusable row becomes a clearly
         * marked zero-amount placeholder the UI filters out rather than crashing the list.
         */
        val PLACEHOLDER = Transaction(
            id = "invalid",
            type = TransactionType.EXPENSE,
            amount = Money.of(1),
            accountId = "",
            categoryId = null,
            occurredOn = LocalDate.EPOCH,
            isPending = true,
        )
    }
}

/** Filters applied to the transaction list. All null/empty means "everything". */
data class TransactionFilter(
    val query: String? = null,
    val dateRange: DateRange? = null,
    val type: TransactionType? = null,
    val accountIds: Set<String> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
    val minAmount: Money? = null,
    val maxAmount: Money? = null,
    val tags: Set<String> = emptySet(),
    val sort: TransactionSort = TransactionSort.DATE_DESC,
) {
    val isActive: Boolean
        get() = !query.isNullOrBlank() || dateRange != null || type != null ||
            accountIds.isNotEmpty() || categoryIds.isNotEmpty() ||
            minAmount != null || maxAmount != null || tags.isNotEmpty()

    /**
     * The LIKE fragment matching [tags], or null when no tag filter is set.
     *
     * Only one tag is filtered on at a time: multi-tag filtering is an AND across LIKE clauses,
     * which cannot be expressed in a single prepared statement with a variable clause count.
     */
    fun tagPattern(): String? =
        tags.firstOrNull()?.let { Converters.tagMatchPattern(it) }

    /** How many filter facets are set, for the "Filters (3)" chip. */
    val activeCount: Int
        get() = listOf(
            !query.isNullOrBlank(),
            dateRange != null,
            type != null,
            accountIds.isNotEmpty(),
            categoryIds.isNotEmpty(),
            minAmount != null || maxAmount != null,
            tags.isNotEmpty(),
        ).count { it }
}

enum class TransactionSort { DATE_DESC, AMOUNT_DESC }
