package ai.labs32.khaata.feature.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.InvestmentPerformance
import ai.labs32.khaata.core.calc.PortfolioSummary
import ai.labs32.khaata.core.model.InvestmentKind
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.SignStyle
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.ChartLegend
import ai.labs32.khaata.core.ui.components.ChartSlice
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.InvestmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

data class InvestmentsUiState(
    val isLoading: Boolean = true,
    val portfolio: PortfolioSummary? = null,
)

@HiltViewModel
class InvestmentsViewModel @Inject constructor(
    investmentRepository: InvestmentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InvestmentsUiState())
    val uiState: StateFlow<InvestmentsUiState> = _uiState.asStateFlow()

    init {
        investmentRepository.observePortfolio()
            .onEach { _uiState.value = InvestmentsUiState(isLoading = false, portfolio = it) }
            .launchIn(viewModelScope)
    }
}

/**
 * The investment tracker.
 *
 * States plainly that values are whatever the user last entered, and flags holdings whose
 * valuation has gone stale. Showing a two-month-old figure as though it were current would be
 * the most misleading thing this screen could do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentsScreen(
    onBack: () -> Unit,
    viewModel: InvestmentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.investments_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val portfolio = state.portfolio
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            portfolio == null || portfolio.holdingsCount == 0 -> EmptyState(
                icon = Icons.Outlined.TrendingUp,
                title = stringResource(R.string.investments_empty_title),
                description = stringResource(R.string.investments_empty_body),
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
            ) {
                item { PortfolioCard(portfolio) }
                item { AllocationCard(portfolio) }

                items(portfolio.performances, key = { it.investment.id }) { performance ->
                    HoldingCard(performance)
                }

                item {
                    Text(
                        text = stringResource(R.string.investments_manual_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = KhaataTheme.spacing.default),
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioCard(portfolio: PortfolioSummary) {
    val money = KhaataTheme.money
    val isProfit = portfolio.gain.isPositive

    KhaataCard {
        CardHeader(title = stringResource(R.string.investments_portfolio_total))
        Spacer(Modifier.height(KhaataTheme.spacing.small))

        MoneyText(money = portfolio.currentValue, style = KhaataTextStyles.amountHero)

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.investments_invested),
            leadingValue = {
                MoneyText(money = portfolio.invested, style = KhaataTextStyles.amountMedium)
            },
            trailingLabel = stringResource(
                if (isProfit) R.string.investments_gain else R.string.investments_loss,
            ),
            trailingValue = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MoneyText(
                        money = portfolio.gain,
                        style = KhaataTextStyles.amountMedium,
                        signStyle = SignStyle.ALWAYS,
                        color = if (isProfit) money.income else money.expense,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = MoneyFormatter.percentage(portfolio.returnPercent),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isProfit) money.income else money.expense,
                    )
                }
            },
        )
    }
}

@Composable
private fun AllocationCard(portfolio: PortfolioSummary) {
    if (portfolio.allocationByKind.size < 2) return
    val money = KhaataTheme.money

    val slices = portfolio.allocationByKind.entries
        .sortedByDescending { it.value.currentValue.amount }
        .mapIndexed { index, (kind, allocation) ->
            ChartSlice(
                label = investmentKindLabel(kind),
                value = allocation.currentValue.amount.toFloat(),
                color = money.swatch(index),
            )
        }

    KhaataCard {
        CardHeader(title = stringResource(R.string.reports_by_category))
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        ChartLegend(
            slices = slices,
            valueFormatter = { value ->
                MoneyFormatter.compact(
                    ai.labs32.khaata.core.money.Money.of(
                        java.math.BigDecimal(value.toDouble()),
                        portfolio.currentValue.currency,
                    ),
                )
            },
            limit = slices.size,
        )
    }
}

@Composable
private fun HoldingCard(performance: InvestmentPerformance) {
    val money = KhaataTheme.money
    val isProfit = performance.isProfit

    KhaataCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = performance.investment.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = investmentKindLabel(performance.investment.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(
                    money = performance.investment.currentValue,
                    style = KhaataTextStyles.amountMedium,
                )
                Text(
                    text = MoneyFormatter.percentage(performance.absoluteReturnPercent),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isProfit) money.income else money.expense,
                )
            }
        }

        // Staleness is surfaced rather than hidden: a figure the user entered two months ago is
        // not the same claim as a current one.
        if (performance.isValuationStale) {
            Spacer(Modifier.height(KhaataTheme.spacing.small))
            Text(
                text = stringResource(
                    R.string.investments_stale_value,
                    performance.valuationAgeDays.toInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = money.warning,
            )
        }
    }
}

@Composable
private fun investmentKindLabel(kind: InvestmentKind): String = stringResource(
    when (kind) {
        InvestmentKind.MUTUAL_FUND -> R.string.investment_kind_mutual_fund
        InvestmentKind.SIP -> R.string.investment_kind_sip
        InvestmentKind.STOCK -> R.string.investment_kind_stock
        InvestmentKind.FIXED_DEPOSIT -> R.string.investment_kind_fixed_deposit
        InvestmentKind.RECURRING_DEPOSIT -> R.string.investment_kind_recurring_deposit
        InvestmentKind.GOLD -> R.string.investment_kind_gold
        InvestmentKind.PPF -> R.string.investment_kind_ppf
        InvestmentKind.NPS -> R.string.investment_kind_nps
        InvestmentKind.EPF -> R.string.investment_kind_epf
        InvestmentKind.OTHER -> R.string.investment_kind_other
    },
)
