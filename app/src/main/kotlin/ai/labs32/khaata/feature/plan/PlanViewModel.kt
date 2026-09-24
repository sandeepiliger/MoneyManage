package ai.labs32.khaata.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.calc.BudgetProgress
import ai.labs32.khaata.core.calc.GoalProgress
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.ScheduledOccurrence
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.DueOccurrence
import ai.labs32.khaata.data.repository.GoalRepository
import ai.labs32.khaata.data.repository.LoanRepository
import ai.labs32.khaata.data.repository.RecurringRepository
import ai.labs32.khaata.data.repository.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** Everything the Plan tab shows. */
data class PlanUiState(
    val isLoading: Boolean = true,
    val month: YearMonth = YearMonth.now(),
    val isCurrentMonth: Boolean = true,
    /** Ordered by how much attention each needs, so an overspent budget is never buried. */
    val budgets: List<BudgetProgress> = emptyList(),
    /**
     * The budget with no category filter, when there is one: the only figure that can honestly be
     * called "the month's budget". Budgets over separate categories are never summed into one,
     * because two budgets can cover the same spending and the total would count it twice.
     */
    val overall: BudgetProgress? = null,
    /** What can be spent per day and still land inside every budget. Current month only. */
    val dailySafeSpend: Money? = null,
    /** Manual bills that have come due and are waiting for "did this go out?". */
    val awaiting: List<DueOccurrence> = emptyList(),
    /** Bills, subscriptions and EMIs due in the next month. */
    val upcoming: List<ScheduledOccurrence> = emptyList(),
    val goals: List<GoalProgress> = emptyList(),
) {
    val onTrackCount: Int get() = budgets.count { !it.status.needsAttention }
}

/**
 * Budgets, bills and goals together: everything about the rest of the month.
 *
 * Budgets follow the month being viewed, so the user can step back to see how last month closed.
 * Bills and goals are always about now -- a bill from last month is either paid or overdue, and
 * both of those are today's business.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val goalRepository: GoalRepository,
    private val recurringRepository: RecurringRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val loanRepository: LoanRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val currentMonth: YearMonth = YearMonth.from(clock.today())
    private val month = MutableStateFlow(currentMonth)

    private val _uiState = MutableStateFlow(PlanUiState(month = currentMonth))
    val uiState: StateFlow<PlanUiState> = _uiState.asStateFlow()

    init {
        month
            .flatMapLatest { viewed ->
                val isCurrent = viewed == currentMonth
                // A past month is judged as it closed: on its last day, with nothing left of it.
                val asOf = if (isCurrent) clock.today() else viewed.atEndOfMonth()
                budgetRepository.observeProgress(asOf).map { progress -> Triple(viewed, isCurrent, progress) }
            }
            .onEach { (viewed, isCurrent, progress) ->
                val sorted = progress.sortedWith(
                    compareByDescending<BudgetProgress> { it.status.ordinal }
                        .thenByDescending { it.percentUsed },
                )
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        month = viewed,
                        isCurrentMonth = isCurrent,
                        budgets = sorted,
                        overall = sorted.firstOrNull { it.budget.isOverallLimit },
                        dailySafeSpend = if (isCurrent) {
                            sorted.mapNotNull { it.safeDailySpend }
                                .takeIf { it.isNotEmpty() }
                                ?.reduce { total, next -> total + next }
                        } else {
                            null
                        },
                    )
                }
            }
            .catch { error ->
                KhaataLog.e(TAG, "Plan budgets failed", error)
                _uiState.update { it.copy(isLoading = false) }
            }
            .launchIn(viewModelScope)

        combine(
            recurringRepository.observeAwaitingConfirmation(),
            recurringRepository.observeUpcoming(UPCOMING_DAYS),
            subscriptionRepository.observeUpcoming(UPCOMING_DAYS),
            loanRepository.observeUpcomingEmis(UPCOMING_DAYS),
            goalRepository.observeProgress(),
        ) { awaiting, recurring, subscriptions, emis, goals ->
            val awaitingKeys = awaiting.map { it.rule.id to it.dueOn }.toSet()
            PlanExtras(
                awaiting = awaiting,
                // A bill already asking "did this go out?" is not also listed as coming up.
                upcoming = (recurring + subscriptions + emis)
                    .filterNot { (it.ruleId to it.dueOn) in awaitingKeys }
                    .sortedBy { it.dueOn }
                    .take(UPCOMING_LIMIT),
                goals = goals.filter { !it.goal.isArchived },
            )
        }
            .onEach { extras ->
                _uiState.update {
                    it.copy(awaiting = extras.awaiting, upcoming = extras.upcoming, goals = extras.goals)
                }
            }
            .catch { error -> KhaataLog.e(TAG, "Plan bills and goals failed", error) }
            .launchIn(viewModelScope)
    }

    fun previousMonth() = showMonth(month.value.minusMonths(1))

    /** Never past the current month: a budget has nothing to say about a month not yet started. */
    fun nextMonth() {
        if (month.value < currentMonth) showMonth(month.value.plusMonths(1))
    }

    /**
     * The heading changes on the tap, not when that month's figures arrive: the budgets are read
     * off the main thread, and a heading that lagged behind the arrow looked like a missed tap.
     */
    private fun showMonth(target: YearMonth) {
        month.value = target
        _uiState.update { it.copy(month = target, isCurrentMonth = target == currentMonth) }
    }

    /** Records the bill as paid, the same as "Record it" on the Recurring screen. */
    fun markPaid(occurrence: DueOccurrence) {
        viewModelScope.launch {
            recurringRepository.postOccurrence(occurrence.rule.id, occurrence.dueOn)
        }
    }

    /** Closes the reminder without inventing a payment, for a bill that did not go out. */
    fun skip(occurrence: DueOccurrence) {
        viewModelScope.launch {
            recurringRepository.skipOccurrence(occurrence.rule.id, occurrence.dueOn)
        }
    }

    private data class PlanExtras(
        val awaiting: List<DueOccurrence>,
        val upcoming: List<ScheduledOccurrence>,
        val goals: List<GoalProgress>,
    )

    private companion object {
        const val TAG = "PlanViewModel"
        const val UPCOMING_DAYS = 30
        const val UPCOMING_LIMIT = 6
    }
}
