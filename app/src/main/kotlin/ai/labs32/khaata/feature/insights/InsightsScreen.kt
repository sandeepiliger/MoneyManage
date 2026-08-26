package ai.labs32.khaata.feature.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.InsightStateDao
import ai.labs32.khaata.core.database.entity.InsightStateEntity
import ai.labs32.khaata.core.insights.Insight
import ai.labs32.khaata.core.insights.InsightEngine
import ai.labs32.khaata.core.insights.InsightSeverity
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.ErrorState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.SubscriptionRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightsUiState(
    val isLoading: Boolean = true,
    val insights: List<Insight> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val insightStateDao: InsightStateDao,
    private val insightEngine: InsightEngine,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }

                val today = clock.today()
                // Two months of history: enough for a month-over-month comparison, bounded so
                // this stays fast on a large ledger.
                val window = DateRange(today.minusMonths(2).withDayOfMonth(1), today)

                val insights = insightEngine.generate(
                    transactions = transactionRepository.getInRange(window),
                    categories = categoryRepository.getAll(),
                    budgets = budgetRepository.getAll(),
                    subscriptions = subscriptionRepository.getAll(),
                    asOf = today,
                )

                // Dismissals are scoped to the period, so next month's version of an insight
                // reappears rather than being permanently silenced.
                val dismissed = insightStateDao.dismissedIdsForPeriod(periodKey()).toSet()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        insights = insights.filterNot { insight -> insight.id in dismissed },
                    )
                }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Insight generation failed", error)
                _uiState.update {
                    it.copy(isLoading = false, error = "We could not work out your insights.")
                }
            }
        }
    }

    fun dismiss(insightId: String) {
        viewModelScope.launch {
            insightStateDao.upsert(
                InsightStateEntity(
                    insightId = insightId,
                    dismissedAt = clock.now(),
                    periodKey = periodKey(),
                ),
            )
            _uiState.update { state ->
                state.copy(insights = state.insights.filterNot { it.id == insightId })
            }
        }
    }

    private fun periodKey(): String = clock.today().let { "${it.year}-${it.monthValue}" }

    private companion object {
        const val TAG = "InsightsViewModel"
    }
}

/**
 * The insights screen.
 *
 * Every insight can be expanded to show the exact figures behind it. That is the whole design:
 * an app that tells someone their food spending is up 40% and cannot show its working is asking
 * for trust it has not earned.
 */
@Composable
fun InsightsScreen(
    onOpenAssistant: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenBudget: (String) -> Unit,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // No FAB of its own: this screen used to layer an ExtendedFloatingActionButton on top of the
    // global add/voice button at the same bottom-right anchor, which pushed the assistant pill off
    // the edge of the screen. The assistant entry point moves into the header instead, next to the
    // title, where it has room and does not compete with the button every screen already has.
    // contentWindowInsets zeroed for the same reason as TransactionsScreen: no topBar here to
    // consume the status-bar inset, so this Scaffold's own default reserves it a second time on
    // top of what the outer chrome Scaffold already applied.
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            InsightsHeader(onOpenAssistant = onOpenAssistant)

            when {
                state.isLoading -> LoadingState()

                state.error != null -> ErrorState(
                    message = state.error!!,
                    onRetry = viewModel::refresh,
                )

                state.insights.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Lightbulb,
                    title = stringResource(R.string.insights_empty_title),
                    description = stringResource(R.string.insights_empty_body),
                    actionLabel = stringResource(R.string.reports_title),
                    onAction = onOpenReports,
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = KhaataTheme.spacing.screenHorizontal,
                        end = KhaataTheme.spacing.screenHorizontal,
                        bottom = KhaataTheme.spacing.bottomBarClearance,
                    ),
                    verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
                ) {
                    items(state.insights, key = { it.id }) { insight ->
                        InsightCard(
                            insight = insight,
                            onDismiss = { viewModel.dismiss(insight.id) },
                            onOpenBudget = onOpenBudget,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightsHeader(onOpenAssistant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = KhaataTheme.spacing.screenHorizontal,
                vertical = KhaataTheme.spacing.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.insights_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(KhaataTheme.spacing.small))
        Row(
            modifier = Modifier
                .clip(KhaataShapeTokens.chip)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onOpenAssistant)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.ai_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun InsightCard(
    insight: Insight,
    onDismiss: () -> Unit,
    onOpenBudget: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val severityColor = severityColor(insight.severity)

    KhaataCard(
        onClick = insight.budgetId?.let { { onOpenBudget(it) } },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = severityIcon(insight.severity),
                contentDescription = null,
                tint = severityColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // Severity is stated in words as well as by colour and icon.
                Text(
                    text = severityLabel(insight.severity),
                    style = MaterialTheme.typography.labelSmall,
                    color = severityColor,
                    modifier = Modifier
                        .clip(KhaataShapeTokens.chip)
                        .background(severityColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = insight.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = insight.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.insights_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (insight.evidence.isNotEmpty()) {
            Spacer(Modifier.height(KhaataTheme.spacing.small))
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.insights_why))
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 4.dp)) {
                    insight.evidence.forEach { evidence ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = evidence.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = MoneyFormatter.plain(evidence.amount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun severityColor(severity: InsightSeverity): Color = when (severity) {
    InsightSeverity.ACTION_NEEDED -> MaterialTheme.colorScheme.error
    InsightSeverity.NOTABLE -> KhaataTheme.money.warning
    InsightSeverity.INFORMATIONAL -> MaterialTheme.colorScheme.primary
}

private fun severityIcon(severity: InsightSeverity): ImageVector = when (severity) {
    InsightSeverity.ACTION_NEEDED -> Icons.Default.ErrorOutline
    InsightSeverity.NOTABLE -> Icons.Default.Warning
    InsightSeverity.INFORMATIONAL -> Icons.Outlined.Info
}

@Composable
private fun severityLabel(severity: InsightSeverity): String = stringResource(
    when (severity) {
        InsightSeverity.ACTION_NEEDED -> R.string.insights_severity_action
        InsightSeverity.NOTABLE -> R.string.insights_severity_notable
        InsightSeverity.INFORMATIONAL -> R.string.insights_severity_info
    },
)
