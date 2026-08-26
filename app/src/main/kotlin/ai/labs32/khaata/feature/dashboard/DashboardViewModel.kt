package ai.labs32.khaata.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.calc.BudgetProgress
import ai.labs32.khaata.core.calc.CashflowAnalyzer
import ai.labs32.khaata.core.calc.CashflowSummary
import ai.labs32.khaata.core.calc.CategorySpend
import ai.labs32.khaata.core.calc.GoalProgress
import ai.labs32.khaata.core.calc.NetWorthSummary
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.InsightStateDao
import ai.labs32.khaata.core.database.entity.InsightStateEntity
import ai.labs32.khaata.core.insights.Insight
import ai.labs32.khaata.core.insights.InsightEngine
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountBalance
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.DashboardCard
import ai.labs32.khaata.core.model.ScheduledOccurrence
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.GoalRepository
import ai.labs32.khaata.data.repository.LoanRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.RecurringRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.core.calc.SubscriptionTotals
import ai.labs32.khaata.data.repository.SubscriptionRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Everything the dashboard shows.
 *
 * Assembled from several flows rather than one giant query, so a change to any one part — a new
 * transaction, an edited budget — refreshes only what it affects.
 */
data class DashboardUiState(
    val isLoading: Boolean = true,
    val greetingKey: GreetingKey = GreetingKey.MORNING,
    val displayName: String? = null,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val amountsHidden: Boolean = false,
    val isDemoMode: Boolean = false,
    val pendingImportCount: Int = 0,

    val availableToSpend: Money = Money.zero(),
    val netWorth: NetWorthSummary? = null,
    val monthSummary: CashflowSummary? = null,
    val budgetRemaining: Money? = null,

    val categoryBreakdown: List<CategorySpend> = emptyList(),
    val budgetProgress: List<BudgetProgress> = emptyList(),
    /**
     * Sum of [BudgetProgress.safeDailySpend] across every budget on screen -- what can be spent
     * per day, in total, and still land inside every one of them. Null when there is nothing to
     * sum, either because there are no budgets yet or none has any period left to spend safely in.
     */
    val dailySafeSpend: Money? = null,
    val upcoming: List<ScheduledOccurrence> = emptyList(),
    val recentTransactions: List<Transaction> = emptyList(),
    val goals: List<GoalProgress> = emptyList(),
    val accounts: List<AccountBalance> = emptyList(),
    val subscriptionCost: SubscriptionTotals? = null,
    val netWorthTrend: List<Pair<String, Float>> = emptyList(),
    val netWorthChangePercent: BigDecimal? = null,
    val topInsight: Insight? = null,

    val categories: List<Category> = emptyList(),
    val cardOrder: List<DashboardCard> = DashboardCard.DEFAULT_ORDER,
    val hiddenCards: Set<DashboardCard> = emptySet(),
    val error: String? = null,
) {
    /** True when the user has recorded nothing at all, which gets the first-run empty state. */
    val isEmpty: Boolean
        get() = !isLoading && recentTransactions.isEmpty() && accounts.all { it.transactionCount == 0 }

    /** Cards to render, in the user's order, minus the ones they hid. */
    val visibleCards: List<DashboardCard>
        get() = cardOrder.filterNot { it in hiddenCards }

    val savingsRatePercent: BigDecimal? get() = monthSummary?.savingsRatePercent
}

/** Which greeting to show. Resolved to a string in the UI so it stays localisable. */
enum class GreetingKey { MORNING, AFTERNOON, EVENING }

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val goalRepository: GoalRepository,
    private val recurringRepository: RecurringRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val loanRepository: LoanRepository,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val insightEngine: InsightEngine,
    private val insightStateDao: InsightStateDao,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /**
     * Whether amounts are masked.
     *
     * A per-session toggle rather than a stored preference: it exists for the moment someone is
     * on a train and wants to check a date without their balance visible to the next seat, and it
     * should not persist and confuse them tomorrow.
     */
    private val amountsHidden = MutableStateFlow(false)

    init {
        observeCore()
        observeSecondary()
    }

    /**
     * The figures at the top of the screen.
     *
     * Split from the rest so the balance and this month's totals paint as soon as they are ready,
     * rather than waiting on goals and subscription costs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCore() {
        val thisMonth = DateRange.ofMonth(clock.today())

        combine(
            accountRepository.observeBalances(),
            transactionRepository.observeInRange(thisMonth),
            profileRepository.observe(),
            settingsRepository.settings,
            amountsHidden,
        ) { balances, transactions, profile, settings, hidden ->
            val currency = profile?.currency ?: CurrencyCode.DEFAULT
            val summary = CashflowAnalyzer.summarise(transactions, thisMonth, currency)

            _uiState.value.copy(
                isLoading = false,
                greetingKey = greetingFor(clock.nowLocal().hour),
                displayName = profile?.displayName,
                currency = currency,
                amountsHidden = hidden,
                isDemoMode = profile?.isDemoMode == true,
                availableToSpend = ai.labs32.khaata.core.calc.BalanceCalculator
                    .availableToSpend(balances, currency),
                netWorth = ai.labs32.khaata.core.calc.BalanceCalculator.netWorth(balances, currency),
                monthSummary = summary,
                accounts = balances.filter { !it.account.isArchived },
                cardOrder = settings.dashboardCardOrder,
                hiddenCards = settings.hiddenDashboardCards,
                error = null,
            )
        }
            .catch { error ->
                KhaataLog.e(TAG, "Dashboard core stream failed", error)
                emit(_uiState.value.copy(isLoading = false, error = DATA_ERROR))
            }
            // BalanceCalculator and CashflowAnalyzer both fold the whole month's ledger; without
            // this they do it on the collector's dispatcher, which viewModelScope makes Main.
            .flowOn(Dispatchers.Default)
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    /** Cards below the fold, which can arrive a beat later without the screen looking broken. */
    private fun observeSecondary() {
        val today = clock.today()
        val thisMonth = DateRange.ofMonth(today)

        combine(
            transactionRepository.observeInRange(thisMonth),
            categoryRepository.observeActive(),
            budgetRepository.observeProgress(),
            goalRepository.observeProgress(),
            transactionRepository.observeRecent(RECENT_LIMIT),
        ) { monthTransactions, categories, budgets, goals, recent ->
            val currency = _uiState.value.currency
            SecondaryData(
                categoryBreakdown = CashflowAnalyzer
                    .categoryBreakdown(monthTransactions, categories, thisMonth, currency)
                    .take(BREAKDOWN_LIMIT),
                budgetProgress = budgets,
                dailySafeSpend = budgets.mapNotNull { it.safeDailySpend }
                    .takeIf { it.isNotEmpty() }
                    ?.reduce { total, next -> total + next },
                goals = goals.filter { !it.goal.isArchived }.take(GOAL_LIMIT),
                recentTransactions = recent,
                categories = categories,
                budgetRemaining = budgets
                    .filter { it.budget.isOverallLimit }
                    .fold(null as Money?) { acc, progress ->
                        (acc ?: Money.zero(currency)) + progress.remaining.floorAtZero()
                    },
            )
        }
            .catch { error ->
                KhaataLog.e(TAG, "Dashboard secondary stream failed", error)
            }
            .flowOn(Dispatchers.Default)
            .onEach { data ->
                _uiState.update {
                    it.copy(
                        categoryBreakdown = data.categoryBreakdown,
                        budgetProgress = data.budgetProgress,
                        dailySafeSpend = data.dailySafeSpend,
                        goals = data.goals,
                        recentTransactions = data.recentTransactions,
                        categories = data.categories,
                        budgetRemaining = data.budgetRemaining,
                    )
                }
            }
            .launchIn(viewModelScope)

        // Upcoming payments pull from four sources; merged and sorted so the user sees one list
        // rather than having to check bills, subscriptions, EMIs and cards separately.
        combine(
            recurringRepository.observeUpcoming(UPCOMING_DAYS),
            subscriptionRepository.observeUpcoming(UPCOMING_DAYS),
            loanRepository.observeUpcomingEmis(UPCOMING_DAYS),
            subscriptionRepository.observeCostSummary(),
            transactionRepository.observePendingCount(),
        ) { recurring, subscriptions, emis, subscriptionCost, pendingCount ->
            UpcomingData(
                occurrences = (recurring + subscriptions + emis)
                    .sortedBy { it.dueOn }
                    .take(UPCOMING_LIMIT),
                subscriptionCost = subscriptionCost,
                pendingImportCount = pendingCount,
            )
        }
            .catch { error -> KhaataLog.e(TAG, "Upcoming stream failed", error) }
            .onEach { data ->
                _uiState.update {
                    it.copy(
                        upcoming = data.occurrences,
                        subscriptionCost = data.subscriptionCost,
                        pendingImportCount = data.pendingImportCount,
                    )
                }
            }
            .launchIn(viewModelScope)

        refreshInsight()
        refreshNetWorthTrend()
    }

    /**
     * Computes the single insight shown on the dashboard.
     *
     * One only. A dashboard that lists eight observations is one nobody reads; the most urgent
     * one, with the full list a tap away, is what actually gets acted on.
     *
     * Fetches a small candidate pool rather than just the top one, because the top-ranked insight
     * can be the one just snoozed -- without also reading the dismissed set, snoozing would look
     * like it did nothing, since regenerating would hand back the exact same insight.
     */
    private fun refreshInsight() {
        // InsightEngine.generate walks three months of transactions against every budget,
        // category and subscription. viewModelScope is Main, so it needs moving off it.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val today = clock.today()
                val window = DateRange(today.minusMonths(2).withDayOfMonth(1), today)
                val transactions = transactionRepository.getInRange(window)
                val categories = categoryRepository.getAll()
                val budgets = budgetRepository.getAll()
                val subscriptions = subscriptionRepository.getAll()

                val insights = insightEngine.generate(
                    transactions = transactions,
                    categories = categories,
                    budgets = budgets,
                    subscriptions = subscriptions,
                    asOf = today,
                    limit = INSIGHT_CANDIDATE_LIMIT,
                )
                val dismissed = insightStateDao.dismissedIdsForPeriod(periodKey()).toSet()
                val topInsight = insights.firstOrNull { it.id !in dismissed }
                _uiState.update { it.copy(topInsight = topInsight) }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Insight generation failed", error)
            }
        }
    }

    /**
     * Snoozes the current top insight for the rest of this period, the same dismissal
     * [InsightsViewModel][ai.labs32.khaata.feature.insights.InsightsViewModel] already offers on
     * the full list -- scoped to the period rather than forever, so next month's version of the
     * same observation is not silenced by a tap made weeks earlier.
     */
    fun snoozeInsight(insightId: String) {
        viewModelScope.launch {
            insightStateDao.upsert(
                InsightStateEntity(
                    insightId = insightId,
                    dismissedAt = clock.now(),
                    periodKey = periodKey(),
                ),
            )
            refreshInsight()
        }
    }

    private fun periodKey(): String = clock.today().let { "${it.year}-${it.monthValue}" }

    private fun refreshNetWorthTrend() {
        // Six months of balances recomputed from the full transaction history -- same reasoning
        // as refreshInsight above.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val today = clock.today()
                val months = DateRange.trailingMonths(today, TREND_MONTHS)
                val accounts = accountRepository.getAll()
                val transactions = transactionRepository.getInRange(
                    DateRange(months.first().start, today),
                )
                val points = ai.labs32.khaata.core.calc.BalanceCalculator.netWorthTrend(
                    accounts = accounts,
                    transactions = transactions,
                    dates = months.map { it.endInclusive.coerceAtMost(today) },
                    currency = _uiState.value.currency,
                )
                val change = if (points.size >= 2) {
                    ai.labs32.khaata.core.calc.BalanceCalculator.percentChange(
                        previous = points[points.size - 2].netWorth,
                        current = points.last().netWorth,
                    )
                } else {
                    null
                }
                _uiState.update { state ->
                    state.copy(
                        netWorthTrend = points.map { point ->
                            point.date.month.name.take(3) to point.netWorth.amount.toFloat()
                        },
                        netWorthChangePercent = change,
                    )
                }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Net worth trend failed", error)
            }
        }
    }

    fun toggleAmountVisibility() {
        amountsHidden.value = !amountsHidden.value
    }

    fun retry() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        observeCore()
        observeSecondary()
    }

    private fun greetingFor(hour: Int): GreetingKey = when (hour) {
        in 0..11 -> GreetingKey.MORNING
        in 12..16 -> GreetingKey.AFTERNOON
        else -> GreetingKey.EVENING
    }

    private data class SecondaryData(
        val categoryBreakdown: List<CategorySpend>,
        val budgetProgress: List<BudgetProgress>,
        val dailySafeSpend: Money?,
        val goals: List<GoalProgress>,
        val recentTransactions: List<Transaction>,
        val categories: List<Category>,
        val budgetRemaining: Money?,
    )

    private data class UpcomingData(
        val occurrences: List<ScheduledOccurrence>,
        val subscriptionCost: SubscriptionTotals,
        val pendingImportCount: Int,
    )

    private companion object {
        const val TAG = "DashboardViewModel"
        const val DATA_ERROR = "We could not load your dashboard."
        const val RECENT_LIMIT = 6
        const val BREAKDOWN_LIMIT = 6
        const val GOAL_LIMIT = 3
        const val UPCOMING_DAYS = 30
        const val UPCOMING_LIMIT = 5
        const val TREND_MONTHS = 6
        const val INSIGHT_CANDIDATE_LIMIT = 5
    }
}

private fun java.time.LocalDate.coerceAtMost(other: java.time.LocalDate): java.time.LocalDate =
    if (isAfter(other)) other else this
