package ai.labs32.khaata.data.repository

import ai.labs32.khaata.core.calc.BalanceCalculator
import ai.labs32.khaata.core.calc.NetWorthSummary
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.AccountDao
import ai.labs32.khaata.core.database.dao.TransactionDao
import ai.labs32.khaata.core.database.toDomain
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountBalance
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accounts and their derived balances.
 *
 * Balances are computed by combining the account list with SQL-aggregated posting totals, so the
 * work stays proportional to the number of accounts rather than the number of transactions. The
 * result is identical to running [BalanceCalculator] over the whole ledger, which is what the
 * DAO tests assert.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val clock: KhaataClock,
) {

    fun observeActive(): Flow<List<Account>> =
        accountDao.observeActive().map { list -> list.map { it.toDomain() } }

    fun observeAll(): Flow<List<Account>> =
        accountDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeById(id: String): Flow<Account?> =
        accountDao.observeById(id).map { it?.toDomain() }

    suspend fun findById(id: String): Account? = accountDao.findById(id)?.toDomain()

    suspend fun getAll(): List<Account> = accountDao.getAll().map { it.toDomain() }

    fun observeActiveCount(): Flow<Int> = accountDao.observeActiveCount()

    suspend fun activeCount(): Int = accountDao.activeCount()

    /**
     * Accounts with their current balances.
     *
     * Combines two flows rather than reading transactions: the totals query is a single indexed
     * aggregate, so this stays fast with a large ledger.
     */
    fun observeBalances(): Flow<List<AccountBalance>> =
        combine(
            accountDao.observeAll(),
            transactionDao.observeAccountTotals(),
        ) { accounts, totals ->
            val byAccount = totals.associateBy { it.accountId }
            accounts.map { entity ->
                val account = entity.toDomain()
                val row = byAccount[account.id]
                AccountBalance(
                    account = account,
                    currentBalance = account.openingBalance +
                        Money.ofMinor(row?.totalMinor ?: 0L, account.currency),
                    transactionCount = row?.transactionCount ?: 0,
                    lastActivityAt = row?.lastActivityAt,
                )
            }
        }

    fun observeActiveBalances(): Flow<List<AccountBalance>> =
        observeBalances().map { list -> list.filter { !it.account.isArchived } }

    fun observeNetWorth(currency: CurrencyCode = CurrencyCode.DEFAULT): Flow<NetWorthSummary> =
        observeBalances().map { BalanceCalculator.netWorth(it, currency) }

    fun observeAvailableToSpend(currency: CurrencyCode = CurrencyCode.DEFAULT): Flow<Money> =
        observeBalances().map { BalanceCalculator.availableToSpend(it, currency) }

    suspend fun balanceOf(accountId: String): Money? {
        val account = accountDao.findById(accountId)?.toDomain() ?: return null
        val total = transactionDao.signedTotalForAccount(accountId)
        return account.openingBalance + Money.ofMinor(total, account.currency)
    }

    // ---- Writes ------------------------------------------------------------------------------

    suspend fun create(
        name: String,
        type: AccountType,
        openingBalance: Money,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
        institution: String? = null,
        maskedIdentifier: String? = null,
        includeInNetWorth: Boolean = true,
        includeInAvailableBalance: Boolean = type.countsAsSpendableByDefault,
        colorSeed: Int = 0,
        notes: String? = null,
    ): String {
        val now = clock.now()
        val account = Account(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            type = type,
            currency = currency,
            openingBalance = openingBalance,
            institution = institution?.trim()?.takeIf { it.isNotBlank() },
            // Guarded rather than trusted: only the last four digits are ever stored, so a full
            // number pasted into the field cannot be persisted by mistake.
            maskedIdentifier = maskedIdentifier?.filter { it.isDigit() }?.takeLast(4)
                ?.takeIf { it.length == 4 },
            includeInNetWorth = includeInNetWorth,
            includeInAvailableBalance = includeInAvailableBalance,
            colorSeed = colorSeed,
            iconKey = type.defaultIconKey,
            notes = notes?.trim()?.takeIf { it.isNotBlank() },
            sortOrder = accountDao.nextSortOrder(),
            createdAt = now,
            updatedAt = now,
        )
        accountDao.upsert(account.toEntity())
        return account.id
    }

    suspend fun update(account: Account) {
        accountDao.update(account.copy(updatedAt = clock.now()).toEntity())
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        accountDao.setArchived(id, archived, clock.now())
    }

    /**
     * Deletes an account, or reports why it cannot be deleted.
     *
     * An account with transactions is never removed: doing so would orphan or destroy history and
     * silently change every past total. Archiving keeps the history and hides the account.
     */
    suspend fun delete(id: String): AccountDeletionResult {
        val account = accountDao.findById(id) ?: return AccountDeletionResult.NotFound
        val transactionCount = transactionDao.countForAccount(id)
        if (transactionCount > 0) {
            return AccountDeletionResult.HasTransactions(transactionCount)
        }
        accountDao.delete(account)
        return AccountDeletionResult.Deleted
    }

    suspend fun reorder(orderedIds: List<String>) {
        val now = clock.now()
        val accounts = accountDao.getAll().associateBy { it.id }
        val updates = orderedIds.mapIndexedNotNull { index, id ->
            accounts[id]?.copy(sortOrder = index, updatedAt = now)
        }
        accountDao.upsertAll(updates)
    }

    /** Matches a bank SMS's masked suffix to an account, so imports land in the right place. */
    suspend fun findByMaskedIdentifier(suffix: String): Account? =
        accountDao.findByMaskedIdentifier(suffix)?.toDomain()

    suspend fun upsertAll(accounts: List<Account>) =
        accountDao.upsertAll(accounts.map { it.toEntity() })

    suspend fun deleteAll() = accountDao.deleteAll()

    suspend fun deleteDemoData() = accountDao.deleteDemoData()
}

/** Why an account delete did or did not happen. */
sealed interface AccountDeletionResult {
    data object Deleted : AccountDeletionResult
    data object NotFound : AccountDeletionResult

    /** The account still holds [transactionCount] transactions; offer archiving instead. */
    data class HasTransactions(val transactionCount: Int) : AccountDeletionResult
}
