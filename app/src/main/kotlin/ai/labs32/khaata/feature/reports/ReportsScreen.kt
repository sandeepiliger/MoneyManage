package ai.labs32.khaata.feature.reports

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ads.AdPlacement
import ai.labs32.khaata.core.calc.AccountSpend
import ai.labs32.khaata.core.calc.CashflowAnalyzer
import ai.labs32.khaata.core.calc.CashflowSummary
import ai.labs32.khaata.core.calc.CategorySpend
import ai.labs32.khaata.core.calc.MerchantSpend
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.ReportPeriod
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.SignStyle
import ai.labs32.khaata.core.ui.components.BarGroup
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.ChartLegend
import ai.labs32.khaata.core.ui.components.ChartPoint
import ai.labs32.khaata.core.ui.components.ChartSlice
import ai.labs32.khaata.core.ui.components.DonutChart
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.GroupedBarChart
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.RankedBarList
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.components.TrendLineChart
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.backup.ExportedFile
import ai.labs32.khaata.data.export.StatementData
import ai.labs32.khaata.data.export.StatementExporter
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import ai.labs32.khaata.feature.ads.AdSlot
import ai.labs32.khaata.feature.shared.chartMoneyFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ReportsUiState(
    val isLoading: Boolean = true,
    val period: ReportPeriod = ReportPeriod.THIS_MONTH,
    val range: DateRange? = null,
    val summary: CashflowSummary? = null,
    val previousSummary: CashflowSummary? = null,
    val categories: List<CategorySpend> = emptyList(),
    val accounts: List<AccountSpend> = emptyList(),
    val merchants: List<MerchantSpend> = emptyList(),
    val monthlySeries: List<CashflowSummary> = emptyList(),
    val accountNames: Map<String, String> = emptyMap(),
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val isExporting: Boolean = false,
    val exportedFile: ExportedFile? = null,
    val exportError: Boolean = false,
) {
    val hasData: Boolean get() = summary?.hasActivity == true
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val clock: KhaataClock,
    private val statementExporter: StatementExporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private val period = MutableStateFlow(ReportPeriod.THIS_MONTH)

    init {
        period
            .flatMapLatest { selected ->
                val today = clock.today()
                val range = selected.range(today)
                val trailing = DateRange.trailingMonths(today, selected.trendMonths)
                // One query covering the period, its comparison period and the trend window, so
                // switching filters does not fan out into three round trips.
                val queryRange = DateRange(
                    start = minOf(range.start, range.previousPeriod().start, trailing.first().start),
                    endInclusive = maxOf(range.endInclusive, today),
                )

                combine(
                    transactionRepository.observeInRange(queryRange),
                    categoryRepository.observeAll(),
                    accountRepository.observeAll(),
                ) { transactions, categories, accounts ->
                    val currency = accounts.firstOrNull()?.currency ?: CurrencyCode.DEFAULT
                    ReportsUiState(
                        isLoading = false,
                        period = selected,
                        range = range,
                        summary = CashflowAnalyzer.summarise(transactions, range, currency),
                        previousSummary = CashflowAnalyzer.summarise(
                            transactions,
                            range.previousPeriod(),
                            currency,
                        ),
                        categories = CashflowAnalyzer.categoryBreakdown(
                            transactions,
                            categories,
                            range,
                            currency,
                        ),
                        accounts = CashflowAnalyzer.accountBreakdown(transactions, range, currency),
                        merchants = CashflowAnalyzer.merchantBreakdown(
                            transactions,
                            range,
                            currency,
                            limit = MERCHANT_LIMIT,
                        ),
                        monthlySeries = CashflowAnalyzer.series(transactions, trailing, currency),
                        accountNames = accounts.associate { it.id to it.name },
                        currency = currency,
                    )
                }
            }
            // Everything above runs off the main thread. The six CashflowAnalyzer passes in the
            // combine block above each walk the whole transaction list, and for THIS_YEAR or
            // FINANCIAL_YEAR that list is a year or more of ledger -- without this they execute
            // on the collector's dispatcher, which viewModelScope makes Main. Room's Flow
            // invalidation is also table-level, so this re-runs on ANY write to transactions
            // anywhere in the app, not just ones inside the selected range.
            .flowOn(Dispatchers.Default)
            .onEach { fresh ->
                // The export fields belong to a separate, user-triggered action rather than to
                // the ledger snapshot this flow re-emits on every change, so a database write
                // that happens to land mid-export must not wipe out its in-flight state.
                _uiState.update { current ->
                    fresh.copy(
                        isExporting = current.isExporting,
                        exportedFile = current.exportedFile,
                        exportError = current.exportError,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun selectPeriod(selected: ReportPeriod) {
        _uiState.update { it.copy(isLoading = true, period = selected) }
        period.value = selected
    }

    /**
     * Builds and writes the statement PDF for whatever is currently on screen.
     *
     * [periodLabel] is resolved by the caller because it is a `stringResource` lookup and this
     * ViewModel has no Compose context to make one from.
     */
    fun exportStatement(periodLabel: String) {
        val range = _uiState.value.range ?: return
        val summary = _uiState.value.summary ?: return
        if (_uiState.value.isExporting) return

        _uiState.update { it.copy(isExporting = true, exportError = false) }
        viewModelScope.launch {
            // Transfers are excluded here the same way CashflowAnalyzer excludes them from every
            // total above: a statement that lists a transfer next to "amounts exclude transfers"
            // would contradict its own footnote.
            val transactions = transactionRepository.getInRange(range)
                .filter { it.isEffective && !it.type.isTransfer }
                .sortedBy { it.occurredOn }

            val statement = StatementData(
                periodLabel = periodLabel,
                periodStart = range.start,
                periodEnd = range.endInclusive,
                generatedOn = clock.today(),
                currency = _uiState.value.currency,
                summary = summary,
                categories = _uiState.value.categories,
                transactions = transactions,
            )

            val result = statementExporter.export(statement)
            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportedFile = result.getOrNull(),
                    exportError = result.isFailure,
                )
            }
        }
    }

    /** Clears a completed export once the UI has acted on it (shared, or the failure shown). */
    fun consumeExport() {
        _uiState.update { it.copy(exportedFile = null, exportError = false) }
    }

    private companion object {
        const val MERCHANT_LIMIT = 8
    }
}

/**
 * Reports.
 *
 * Every figure here excludes transfers between the user's own accounts. That is stated at the
 * bottom of the screen rather than left implicit, because a user who moved ₹50,000 into a fixed
 * deposit and sees it in "spending" concludes the app is broken — and they would be right.
 *
 * Each chart is paired with the same information as text. A donut a screen reader cannot describe
 * is not a report, and colour alone never carries a meaning here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val currentPeriodLabel = periodLabel(state.period)
    val shareTitle = stringResource(R.string.statement_share_title)
    val exportFailedMessage = stringResource(R.string.reports_export_failed)

    // The share sheet fires exactly once per completed export, then the state is cleared so
    // rotating the screen or recomposing for an unrelated reason does not reopen it.
    LaunchedEffect(state.exportedFile) {
        state.exportedFile?.let { file ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = file.mimeType
                putExtra(Intent.EXTRA_STREAM, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, shareTitle))
            viewModel.consumeExport()
        }
    }

    LaunchedEffect(state.exportError) {
        if (state.exportError) {
            snackbarHostState.showSnackbar(exportFailedMessage)
            viewModel.consumeExport()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.reports_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.exportStatement(currentPeriodLabel) },
                        enabled = state.hasData && !state.isExporting,
                    ) {
                        if (state.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.reports_export_pdf),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PeriodFilter(selected = state.period, onSelect = viewModel::selectPeriod)

            when {
                state.isLoading -> LoadingState()

                !state.hasData -> EmptyState(
                    icon = Icons.Outlined.Assessment,
                    title = stringResource(R.string.reports_empty_title),
                    description = stringResource(R.string.reports_empty_body),
                )

                else -> ReportsContent(state)
            }
        }
    }
}

@Composable
private fun PeriodFilter(selected: ReportPeriod, onSelect: (ReportPeriod) -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Box {
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = KhaataTheme.spacing.screenHorizontal,
                vertical = KhaataTheme.spacing.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small),
        ) {
            items(ReportPeriod.entries, key = { it.name }) { period ->
                FilterChip(
                    selected = selected == period,
                    onClick = { onSelect(period) },
                    label = { Text(periodLabel(period)) },
                    // Selected now reads the same way everywhere in the app: primaryContainer,
                    // not secondaryContainer -- brass is reserved for a warning state, and a
                    // selected period chip is not one.
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
        // A chip cut off mid-word at the edge reads as a broken layout rather than a scrollable
        // row. The fade says "there is more here" without needing to fully reveal it.
        //
        // matchParentSize() rather than an explicit width: this box's own height would otherwise
        // have nothing to resolve against, since the Box it sits in is sized by the LazyRow, not
        // by a fixed height passed down from its parent. The gradient's colour stops do the actual
        // narrowing instead -- transparent for the first 88% of the width, fading only over the
        // last 12%, so the effect is confined to the trailing edge without constraining the box.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to background.copy(alpha = 0f),
                        0.88f to background.copy(alpha = 0f),
                        1f to background,
                    ),
                ),
        )
    }
}

@Composable
private fun ReportsContent(state: ReportsUiState) {
    val summary = state.summary ?: return

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = KhaataTheme.spacing.screenHorizontal,
            end = KhaataTheme.spacing.screenHorizontal,
            bottom = KhaataTheme.spacing.large,
        ),
        verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
    ) {
        item(key = "summary") { SummaryCard(summary, state.previousSummary) }

        if (state.categories.isNotEmpty()) {
            item(key = "categories") { CategoryCard(state.categories) }
        }

        if (state.monthlySeries.size >= 2) {
            item(key = "income-expense") { IncomeExpenseCard(state.monthlySeries) }
            item(key = "savings-trend") { SavingsTrendCard(state.monthlySeries) }
        }

        if (state.merchants.isNotEmpty()) {
            item(key = "merchants") { MerchantCard(state.merchants) }
        }

        if (state.accounts.size >= 2) {
            item(key = "accounts") { AccountCard(state.accounts, state.accountNames) }
        }

        item(key = "transfers-note") {
            Text(
                text = stringResource(R.string.reports_transfers_excluded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = KhaataTheme.spacing.small),
            )
        }

        // The only ad on this screen, below the content rather than between the charts, and
        // nowhere near an amount someone is reading.
        item(key = "ad") { AdSlot(placement = AdPlacement.REPORTS_FOOTER) }
    }
}

@Composable
private fun SummaryCard(summary: CashflowSummary, previous: CashflowSummary?) {
    val money = KhaataTheme.money

    KhaataCard {
        CardHeader(title = stringResource(R.string.reports_monthly))
        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.dashboard_income),
            leadingValue = {
                MoneyText(
                    money = summary.income,
                    style = KhaataTextStyles.amountMedium,
                    color = money.income,
                )
            },
            trailingLabel = stringResource(R.string.dashboard_expenses),
            trailingValue = {
                MoneyText(
                    money = summary.expense,
                    style = KhaataTextStyles.amountMedium,
                    color = money.expense,
                )
            },
        )

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.dashboard_saved),
            leadingValue = {
                // amountHero is sized for a screen-width figure, not half a card -- at that width
                // a real month's saving ("+₹20,900") truncates. amountLarge is what the dashboard's
                // own half-width income/expense tiles use for the same reason.
                MoneyText(
                    money = summary.net,
                    style = KhaataTextStyles.amountLarge,
                    signStyle = SignStyle.ALWAYS,
                    color = if (summary.isSurplus) money.income else money.expense,
                )
            },
            trailingLabel = stringResource(R.string.dashboard_savings_rate),
            trailingValue = {
                Text(
                    // Null means there was no income to divide by, which is a different
                    // statement from "you saved 0%".
                    text = summary.savingsRatePercent
                        ?.let { MoneyFormatter.percentage(it, decimals = 0) }
                        ?: stringResource(R.string.reports_no_income),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
        )

        if (previous != null && previous.hasActivity) {
            Spacer(Modifier.height(KhaataTheme.spacing.default))
            ComparisonRow(current = summary.expense, previous = previous.expense)
        }

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.reports_average_daily),
            leadingValue = {
                MoneyText(
                    money = summary.averageDailySpend,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            trailingLabel = stringResource(R.string.reports_transaction_count),
            trailingValue = {
                Text(
                    text = pluralStringResource(
                        R.plurals.reports_transactions,
                        summary.expenseCount + summary.incomeCount,
                        summary.expenseCount + summary.incomeCount,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
        )
    }
}

/**
 * Change against the comparable previous period.
 *
 * Worded as a plain fact ("₹2,400 more than last month") rather than as praise or a telling-off.
 * A month with a wedding in it is not a failure, and an app that reads it as one gets ignored.
 */
@Composable
private fun ComparisonRow(current: Money, previous: Money) {
    val difference = current - previous
    if (difference.isZero) return

    val text = if (difference.isPositive) {
        stringResource(
            R.string.reports_more_than_previous,
            MoneyFormatter.plain(difference),
        )
    } else {
        stringResource(
            R.string.reports_less_than_previous,
            MoneyFormatter.plain(Money.zero(difference.currency) - difference),
        )
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CategoryCard(categories: List<CategorySpend>) {
    val money = KhaataTheme.money
    val uncategorised = stringResource(R.string.categories_uncategorised)
    val total = categories.fold(Money.zero(categories.first().amount.currency)) { acc, it ->
        acc + it.amount
    }

    val slices = categories.mapIndexed { index, spend ->
        ChartSlice(
            label = spend.category?.name ?: uncategorised,
            value = spend.amount.amount.toFloat(),
            color = money.swatch(spend.category?.colorSeed ?: index),
        )
    }

    KhaataCard {
        CardHeader(title = stringResource(R.string.reports_by_category))
        Spacer(Modifier.height(KhaataTheme.spacing.default))

        DonutChart(
            slices = slices,
            centerLabel = stringResource(R.string.dashboard_expenses),
            centerValue = MoneyFormatter.compact(total),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        // The legend is the text alternative to the chart, not decoration: it carries the same
        // figures in the same order, which is what a screen reader and a colour-blind user get.
        ChartLegend(
            slices = slices,
            valueFormatter = chartMoneyFormatter(total.currency),
            limit = CATEGORY_LEGEND_LIMIT,
        )
    }
}

@Composable
private fun IncomeExpenseCard(series: List<CashflowSummary>) {
    val formatter = DateTimeFormatter.ofPattern("MMM")
    val groups = series.map { summary ->
        BarGroup(
            label = summary.period.start.format(formatter),
            primary = summary.income.amount.toFloat(),
            secondary = summary.expense.amount.toFloat(),
        )
    }
    val currency = series.first().income.currency

    KhaataCard {
        CardHeader(title = stringResource(R.string.reports_income_vs_expense))
        Spacer(Modifier.height(KhaataTheme.spacing.default))
        GroupedBarChart(
            groups = groups,
            valueFormatter = chartMoneyFormatter(currency),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        LegendKey()
    }
}

/** Names the two bar colours in words, so the chart does not rely on colour alone. */
@Composable
private fun LegendKey() {
    val money = KhaataTheme.money
    Row(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.default)) {
        LegendEntry(
            color = money.income,
            label = stringResource(R.string.dashboard_income),
        )
        LegendEntry(
            color = money.expense,
            label = stringResource(R.string.dashboard_expenses),
        )
    }
}

@Composable
private fun LegendEntry(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
            drawCircle(color)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavingsTrendCard(series: List<CashflowSummary>) {
    val formatter = DateTimeFormatter.ofPattern("MMM")
    val currency = series.first().net.currency
    val points = series.map {
        ChartPoint(label = it.period.start.format(formatter), value = it.net.amount.toFloat())
    }

    KhaataCard {
        CardHeader(title = stringResource(R.string.reports_savings_trend))
        Spacer(Modifier.height(KhaataTheme.spacing.default))
        TrendLineChart(
            points = points,
            valueFormatter = chartMoneyFormatter(currency),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        // A negative month is a fact worth stating in words; the line dipping below the axis is
        // easy to miss on a small screen.
        val deficitMonths = series.count { it.hasActivity && !it.isSurplus }
        if (deficitMonths > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.reports_deficit_months,
                    deficitMonths,
                    deficitMonths,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MerchantCard(merchants: List<MerchantSpend>) {
    val money = KhaataTheme.money
    val currency = merchants.first().amount.currency
    val slices = merchants.mapIndexed { index, spend ->
        ChartSlice(
            label = spend.merchant,
            value = spend.amount.amount.toFloat(),
            color = money.swatch(index),
        )
    }

    KhaataCard {
        CardHeader(title = stringResource(R.string.reports_by_merchant))
        Spacer(Modifier.height(KhaataTheme.spacing.default))
        RankedBarList(
            slices = slices,
            valueFormatter = chartMoneyFormatter(currency, compact = false),
            limit = slices.size,
        )
    }
}

@Composable
private fun AccountCard(accounts: List<AccountSpend>, names: Map<String, String>) {
    // No colour here: the account name already identifies the row, so a swatch would add tint
    // without adding information.
    val unknown = stringResource(R.string.reports_unknown_account)

    KhaataCard {
        CardHeader(title = stringResource(R.string.reports_by_account))
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        accounts.forEach { spend ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = names[spend.accountId] ?: unknown,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = MoneyFormatter.percentage(spend.shareOfTotalPercent, decimals = 0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(KhaataTheme.spacing.small))
                MoneyText(money = spend.amount, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun periodLabel(period: ReportPeriod): String = stringResource(
    when (period) {
        ReportPeriod.THIS_MONTH -> R.string.reports_period_this_month
        ReportPeriod.LAST_MONTH -> R.string.reports_period_last_month
        ReportPeriod.LAST_30_DAYS -> R.string.reports_period_30_days
        ReportPeriod.LAST_3_MONTHS -> R.string.reports_period_3_months
        ReportPeriod.LAST_6_MONTHS -> R.string.reports_period_6_months
        ReportPeriod.THIS_YEAR -> R.string.reports_period_year
        ReportPeriod.FINANCIAL_YEAR -> R.string.reports_period_financial_year
    },
)

/** Long enough to cover a household's real spread without turning the legend into a wall. */
private const val CATEGORY_LEGEND_LIMIT = 10
