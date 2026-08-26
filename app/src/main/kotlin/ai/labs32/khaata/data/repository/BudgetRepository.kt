package ai.labs32.khaata.data.repository

import ai.labs32.khaata.core.calc.BudgetCalculator
import ai.labs32.khaata.core.calc.BudgetProgress
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.BudgetDao
import ai.labs32.khaata.core.database.toDomainOrNull
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.BudgetPeriod
import ai.labs32.khaata.core.money.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Budgets and their evaluated progress.
 *
 * Progress is derived on read rather than stored. A stored "spent so far" would have to be
 * updated by every transaction write, every edit, every delete and every category change, and
 * would drift out of step with the ledger the first time one of those paths was missed.
 */
@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: KhaataClock,
) {

    fun observeActive(): Flow<List<Budget>> =
        budgetDao.observeActive().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    fun observeAll(): Flow<List<Budget>> =
        budgetDao.observeAll().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    fun observeById(id: String): Flow<Budget?> =
        budgetDao.observeById(id).map { it?.toDomainOrNull() }

    suspend fun findById(id: String): Budget? = budgetDao.findById(id)?.toDomainOrNull()

    suspend fun getAll(): List<Budget> = budgetDao.getAll().mapNotNull { it.toDomainOrNull() }

    /**
     * Every active budget with its current position.
     *
     * The widest period any budget covers is loaded once and shared, rather than querying per
     * budget, so a user with a dozen budgets still gets one database read.
     */
    fun observeProgress(): Flow<List<BudgetProgress>> {
        val today = clock.today()
        return combine(
            observeActive(),
            transactionRepository.observeInRange(evaluationWindow(today)),
            categoryRepository.observeAll(),
        ) { budgets, transactions, categories ->
            val rollup = BudgetCalculator.buildCategoryRollup(categories)
            budgets.map { budget ->
                val carried = if (budget.rollsOver) {
                    BudgetCalculator.carryOverInto(budget, transactions, today, rollup)
                } else {
                    Money.zero(budget.limit.currency)
                }
                BudgetCalculator.evaluate(budget, transactions, today, rollup, carried)
            }
        }
            // Each budget is evaluated by folding the whole evaluation window, and rollover
            // budgets fold the preceding period a second time. Switched here rather than at each
            // ViewModel so every consumer of this flow gets it off the main thread, including the
            // ones that collect it directly.
            .flowOn(Dispatchers.Default)
    }

    /** Only the budgets that need the user's attention, for the dashboard card. */
    fun observeNeedingAttention(): Flow<List<BudgetProgress>> =
        observeProgress().map { list -> list.filter { it.status.needsAttention } }

    suspend fun progressFor(budgetId: String): BudgetProgress? {
        val budget = findById(budgetId) ?: return null
        val today = clock.today()
        val transactions = transactionRepository.getInRange(evaluationWindow(today))
        val rollup = categoryRepository.categoryRollup()
        val carried = if (budget.rollsOver) {
            BudgetCalculator.carryOverInto(budget, transactions, today, rollup)
        } else {
            Money.zero(budget.limit.currency)
        }
        return BudgetCalculator.evaluate(budget, transactions, today, rollup, carried)
    }

    // ---- Writes ------------------------------------------------------------------------------

    suspend fun create(
        name: String,
        limit: Money,
        period: BudgetPeriod = BudgetPeriod.MONTHLY,
        categoryIds: Set<String> = emptySet(),
        accountIds: Set<String> = emptySet(),
        anchorDate: LocalDate = clock.today().withDayOfMonth(1),
        alertThresholdPercent: Int = Budget.DEFAULT_ALERT_THRESHOLD,
        rollsOver: Boolean = false,
    ): String {
        val budget = Budget(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            limit = limit,
            period = period,
            categoryIds = categoryIds,
            accountIds = accountIds,
            anchorDate = anchorDate,
            alertThresholdPercent = alertThresholdPercent,
            rollsOver = rollsOver,
            sortOrder = budgetDao.nextSortOrder(),
        )
        budgetDao.upsert(budget.toEntity())
        return budget.id
    }

    suspend fun update(budget: Budget) = budgetDao.upsert(budget.toEntity())

    suspend fun setActive(id: String, active: Boolean) {
        findById(id)?.let { budgetDao.upsert(it.copy(isActive = active).toEntity()) }
    }

    suspend fun delete(id: String) {
        budgetDao.findById(id)?.let { budgetDao.delete(it) }
    }

    suspend fun upsertAll(budgets: List<Budget>) =
        budgetDao.upsertAll(budgets.map { it.toEntity() })

    suspend fun deleteAll() = budgetDao.deleteAll()

    suspend fun deleteDemoData() = budgetDao.deleteDemoData()

    /**
     * The date window budget evaluation needs.
     *
     * Two months back covers the current period for any anchor day, plus the previous period a
     * rollover budget carries from. Narrow enough to stay cheap, wide enough to be correct.
     */
    private fun evaluationWindow(today: LocalDate): DateRange =
        DateRange(today.minusMonths(2).withDayOfMonth(1), today.plusMonths(1).withDayOfMonth(1))
}
