package ai.labs32.khaata.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.ErrorState
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.shared.TransactionRow
import ai.labs32.khaata.feature.shared.relativeDateLabel
import java.time.LocalDate

/**
 * The transaction list.
 *
 * Paged, because this is the one screen that can hold years of history. Rows are grouped by date
 * with a running header, which is how people scan a ledger — "what did I spend on Tuesday?"
 * rather than "show me row 240".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onOpenTransaction: (String) -> Unit,
    onAddTransaction: () -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagedTransactions = viewModel.transactions.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }

    val undoLabel = stringResource(R.string.action_undo)
    val deletedMessage = stringResource(R.string.transaction_deleted)

    LaunchedEffect(state.recentlyDeletedId) {
        val id = state.recentlyDeletedId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.clearUndo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchAndFilterBar(
                searchText = state.searchText,
                activeFilterCount = state.filter.activeCount,
                onSearchChange = viewModel::onSearchChange,
                onOpenFilters = { viewModel.setFiltersVisible(true) },
                onClearFilters = viewModel::clearFilters,
            )

            QuickTypeFilters(
                selected = state.filter.type,
                onSelect = viewModel::onTypeFilterChange,
            )

            if (state.filter.isActive && state.filteredTotal != null) {
                FilterSummary(
                    count = state.filteredCount,
                    total = state.filteredTotal!!,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            TransactionList(
                pagedTransactions = pagedTransactions,
                state = state,
                onOpenTransaction = onOpenTransaction,
                onAddTransaction = onAddTransaction,
                onClearFilters = viewModel::clearFilters,
            )
        }
    }

    if (state.showFilters) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.setFiltersVisible(false) },
            sheetState = sheetState,
        ) {
            FilterSheet(
                state = state,
                viewModel = viewModel,
                onDone = { viewModel.setFiltersVisible(false) },
            )
        }
    }
}

@Composable
private fun TransactionList(
    pagedTransactions: androidx.paging.compose.LazyPagingItems<ai.labs32.khaata.core.model.Transaction>,
    state: TransactionsUiState,
    onOpenTransaction: (String) -> Unit,
    onAddTransaction: () -> Unit,
    onClearFilters: () -> Unit,
) {
    val refreshState = pagedTransactions.loadState.refresh

    // Indexed once per change of the underlying lists rather than scanned per row. Looking these
    // up with `firstOrNull` inside the row made every visible row walk the whole category and
    // account list on every frame, which is what a long ledger felt slow scrolling through.
    val categoriesById = remember(state.categories) { state.categories.associateBy { it.id } }
    val accountsById = remember(state.accounts) { state.accounts.associateBy { it.id } }

    when {
        refreshState is LoadState.Loading && pagedTransactions.itemCount == 0 -> LoadingState()

        refreshState is LoadState.Error -> ErrorState(
            message = stringResource(R.string.state_error_database),
            onRetry = pagedTransactions::retry,
        )

        pagedTransactions.itemCount == 0 && state.filter.isActive -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.transaction_filter_empty_title),
            description = stringResource(R.string.transaction_filter_empty_body),
            actionLabel = stringResource(R.string.action_clear),
            onAction = onClearFilters,
        )

        pagedTransactions.itemCount == 0 -> EmptyState(
            icon = Icons.Outlined.ReceiptLong,
            title = stringResource(R.string.transaction_list_empty_title),
            description = stringResource(R.string.transaction_list_empty_body),
            actionLabel = stringResource(R.string.dashboard_empty_action),
            onAction = onAddTransaction,
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = KhaataTheme.spacing.bottomBarClearance),
        ) {
            items(
                count = pagedTransactions.itemCount,
                key = pagedTransactions.itemKey { it.id },
            ) { index ->
                val transaction = pagedTransactions[index] ?: return@items

                // A date header whenever the day changes. Computed from the neighbouring row
                // rather than by pre-grouping, so it works with paging.
                val previous = if (index > 0) pagedTransactions.peek(index - 1) else null
                if (previous == null || previous.occurredOn != transaction.occurredOn) {
                    DateHeader(transaction.occurredOn)
                }

                val category = categoriesById[transaction.categoryId]

                TransactionRow(
                    transaction = transaction,
                    categoryName = category?.name,
                    accountName = accountsById[transaction.accountId]?.name,
                    categoryColorSeed = category?.colorSeed ?: 0,
                    onClick = { onOpenTransaction(transaction.id) },
                    showDate = false,
                )
            }

            if (pagedTransactions.loadState.append is LoadState.Loading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate) {
    Text(
        text = relativeDateLabel(date),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SearchAndFilterBar(
    searchText: String,
    activeFilterCount: Int,
    onSearchChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onClearFilters: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = onSearchChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.transaction_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (searchText.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_clear),
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onOpenFilters) {
            Icon(
                Icons.Default.FilterList,
                contentDescription = stringResource(R.string.action_filter),
                tint = if (activeFilterCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (activeFilterCount > 0) {
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.action_clear))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTypeFilters(
    selected: TransactionType?,
    onSelect: (TransactionType?) -> Unit,
) {
    val options = listOf(
        null to R.string.budgets_all_spending,
        TransactionType.EXPENSE to R.string.transaction_expense,
        TransactionType.INCOME to R.string.transaction_income,
        TransactionType.TRANSFER to R.string.transaction_transfer,
    )

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        items(options) { (type, labelRes) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(stringResource(labelRes), maxLines = 1) },
            )
        }
    }
}

/**
 * The total for the current filter.
 *
 * Computed from a separate query rather than by summing loaded pages, so it does not creep upward
 * as the user scrolls — which would be a subtly wrong number in a finance app.
 */
@Composable
private fun FilterSummary(count: Int, total: ai.labs32.khaata.core.money.Money) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.transaction_expense),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = MoneyFormatter.plain(total),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    state: TransactionsUiState,
    viewModel: TransactionsViewModel,
    onDone: () -> Unit,
) {
    val spacing = KhaataTheme.spacing

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal)
            .padding(bottom = spacing.xlarge),
    ) {
        Text(
            text = stringResource(R.string.action_filter),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(spacing.default))

        FilterSection(stringResource(R.string.transaction_date)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DatePreset.entries.toList()) { preset ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(presetLabel(preset), maxLines = 1) },
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.default))

        FilterSection(stringResource(R.string.transaction_account)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.accounts, key = { it.id }) { account ->
                    FilterChip(
                        selected = account.id in state.filter.accountIds,
                        onClick = { viewModel.onAccountFilterToggle(account.id) },
                        label = { Text(account.name, maxLines = 1) },
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.default))

        // Filtered once per change rather than rebuilt on every recomposition of the sheet.
        val topLevelCategories = remember(state.categories) {
            state.categories.filter { it.parentId == null }
        }

        FilterSection(stringResource(R.string.transaction_category)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(topLevelCategories, key = { it.id }) { category ->
                    FilterChip(
                        selected = category.id in state.filter.categoryIds,
                        onClick = { viewModel.onCategoryFilterToggle(category.id) },
                        label = { Text(category.name, maxLines = 1) },
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.large))

        Row(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = {
                    viewModel.clearFilters()
                    onDone()
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_reset)) }

            Spacer(Modifier.width(spacing.small))

            androidx.compose.material3.Button(
                onClick = onDone,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_apply)) }
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun presetLabel(preset: DatePreset): String = stringResource(
    when (preset) {
        DatePreset.THIS_MONTH -> R.string.reports_period_this_month
        DatePreset.LAST_MONTH -> R.string.reports_period_last_month
        DatePreset.LAST_7_DAYS -> R.string.reports_period_7_days
        DatePreset.LAST_30_DAYS -> R.string.reports_period_30_days
        DatePreset.THIS_YEAR -> R.string.reports_period_year
        DatePreset.FINANCIAL_YEAR -> R.string.reports_period_financial_year
    },
)
