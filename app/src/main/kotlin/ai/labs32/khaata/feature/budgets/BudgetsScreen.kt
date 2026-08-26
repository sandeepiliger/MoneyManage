package ai.labs32.khaata.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.BudgetProgress
import ai.labs32.khaata.core.model.BudgetStatus
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LabelledProgress
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.feature.shared.AddRow
import ai.labs32.khaata.feature.shared.budgetStatusColor
import ai.labs32.khaata.feature.shared.budgetStatusLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

data class BudgetsUiState(
    val isLoading: Boolean = true,
    val progress: List<BudgetProgress> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BudgetsUiState())
    val uiState: StateFlow<BudgetsUiState> = _uiState.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        budgetRepository.observeProgress()
            .catch { _uiState.value = BudgetsUiState(isLoading = false, error = LOAD_ERROR) }
            .onEach { progress ->
                // Ordered by how much attention each needs, so an overspent budget is never
                // buried below three healthy ones.
                _uiState.value = BudgetsUiState(
                    isLoading = false,
                    progress = progress.sortedWith(
                        compareByDescending<BudgetProgress> { it.status.ordinal }
                            .thenByDescending { it.percentUsed },
                    ),
                )
            }
            .launchIn(viewModelScope)
    }

    fun retry() {
        _uiState.value = BudgetsUiState(isLoading = true)
        observe()
    }

    private companion object {
        const val LOAD_ERROR = "We could not load your budgets."
    }
}

/**
 * The budget list.
 *
 * Each row leads with the status in words, not just a coloured bar, and includes the pacing
 * figure — "about ₹600 a day keeps you on track" is something a user can act on, where "68%
 * used" is not.
 */
@Composable
fun BudgetsScreen(
    onOpenBudget: (String) -> Unit,
    onAddBudget: () -> Unit,
    viewModel: BudgetsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isLoading -> LoadingState()

        state.error != null -> ai.labs32.khaata.core.ui.components.ErrorState(
            message = state.error!!,
            onRetry = viewModel::retry,
        )

        state.progress.isEmpty() -> EmptyState(
            icon = Icons.Outlined.PieChart,
            title = stringResource(R.string.budgets_empty_title),
            description = stringResource(R.string.budgets_empty_body),
            actionLabel = stringResource(R.string.budgets_add),
            onAction = onAddBudget,
        )

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = KhaataTheme.spacing.screenHorizontal,
                end = KhaataTheme.spacing.screenHorizontal,
                top = KhaataTheme.spacing.default,
                bottom = KhaataTheme.spacing.bottomBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
        ) {
            items(state.progress, key = { it.budget.id }) { progress ->
                BudgetCard(progress = progress, onClick = { onOpenBudget(progress.budget.id) })
            }
            item(key = "add-budget") {
                AddRow(label = stringResource(R.string.budgets_add), onClick = onAddBudget)
            }
        }
    }
}

@Composable
private fun BudgetCard(progress: BudgetProgress, onClick: () -> Unit) {
    val statusColor = budgetStatusColor(progress.status)
    val statusLabel = budgetStatusLabel(progress.status)

    KhaataCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = progress.budget.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The status has an icon as well as a colour, so it never depends on hue.
                    Icon(
                        imageVector = statusIcon(progress.status),
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(
                    money = progress.remaining.floorAtZero(),
                    style = KhaataTextStyles.amountLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (progress.isOverspent) {
                        stringResource(
                            R.string.budgets_overspent_by,
                            MoneyFormatter.plain(progress.overspentBy),
                        )
                    } else {
                        stringResource(R.string.dashboard_budget_remaining)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (progress.isOverspent) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        LabelledProgress(
            progressPercent = progress.percentUsedClamped,
            statusLabel = statusLabel,
            progressColor = statusColor,
            height = 10.dp,
        )

        Spacer(Modifier.height(KhaataTheme.spacing.small))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.budgets_spent_of,
                    MoneyFormatter.plain(progress.spent),
                    MoneyFormatter.plain(progress.limit),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(
                    R.plurals.budgets_days_left,
                    progress.daysRemaining,
                    progress.daysRemaining,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The pacing line is the actionable part, so it gets its own row rather than being
        // squeezed in beside the totals. Shown only when the budget is actually off track
        // (PROJECTED_OVER or NEARING_LIMIT) -- an on-track budget doesn't need to be told what
        // pace to keep since it's already keeping it, and the old `!isOverspent` condition also
        // showed this line for EXHAUSTED budgets, where safeDailySpend is a real but useless ₹0.
        progress.safeDailySpend
            ?.takeIf {
                progress.status == BudgetStatus.PROJECTED_OVER ||
                    progress.status == BudgetStatus.NEARING_LIMIT
            }
            ?.let { daily ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.budgets_safe_daily, MoneyFormatter.plain(daily)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

        if (progress.carriedOver.isPositive) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.budgets_carried_over,
                    MoneyFormatter.plain(progress.carriedOver),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusIcon(status: BudgetStatus): ImageVector = when (status) {
    BudgetStatus.ON_TRACK -> Icons.Default.CheckCircle
    BudgetStatus.PROJECTED_OVER -> Icons.Default.TrendingUp
    BudgetStatus.NEARING_LIMIT -> Icons.Default.Warning
    BudgetStatus.EXHAUSTED -> Icons.Default.Warning
    BudgetStatus.OVERSPENT -> Icons.Default.ErrorOutline
}
