package ai.labs32.khaata.feature.transactions

import ai.labs32.khaata.navigation.Routes
import ai.labs32.khaata.ui.ScrollToTopOnReselect
import androidx.compose.foundation.lazy.rememberLazyListState
import ai.labs32.khaata.core.ui.components.AnimatedListItem
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import ai.labs32.khaata.core.model.CategoryKind
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyStyle
import ai.labs32.khaata.core.money.SignStyle
import ai.labs32.khaata.core.ui.components.KhaataStatTile
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.data.repository.TransactionSort
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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ai.labs32.khaata.feature.reports.ReportsScreen
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
    onOpenPaywall: () -> Unit,
    onOpenInsights: () -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    // Which half of the tab is showing. Saved, so coming back from a transaction's detail screen
    // lands on the same view rather than resetting to the list.
    var showAnalysis by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ActivityHeader(showAnalysis = showAnalysis, onShowAnalysis = { showAnalysis = it })
        if (showAnalysis) {
            ReportsScreen(
                onBack = null,
                onOpenPaywall = onOpenPaywall,
                onOpenInsights = onOpenInsights,
            )
        } else {
            TransactionListPane(
                onOpenTransaction = onOpenTransaction,
                onAddTransaction = onAddTransaction,
                viewModel = viewModel,
            )
        }
    }
}

/**
 * The tab's title and its List / Analysis switch.
 *
 * Reports used to be a separate screen three levels down under More. They answer the same
 * question as the list -- where did the money go -- so they are the other half of this tab now,
 * one tap from the transactions they summarise.
 */
@Composable
private fun ActivityHeader(showAnalysis: Boolean, onShowAnalysis: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = KhaataTheme.spacing.screenHorizontal,
                end = KhaataTheme.spacing.screenHorizontal,
                top = KhaataTheme.spacing.default,
                bottom = KhaataTheme.spacing.tiny,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.nav_activity),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        val options = listOf(R.string.activity_view_list, R.string.activity_view_analysis)
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, labelRes ->
                val selected = (index == 1) == showAnalysis
                SegmentedButton(
                    selected = selected,
                    onClick = { onShowAnalysis(index == 1) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    // No checkmark: on a two-way switch the filled segment already says which is on,
                    // and the tick pushed "Analysis" into truncating on a compact phone.
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(stringResource(labelRes), maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionListPane(
    onOpenTransaction: (String) -> Unit,
    onAddTransaction: () -> Unit,
    viewModel: TransactionsViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagedTransactions = viewModel.transactions.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }

    val undoLabel = stringResource(R.string.action_undo)
    val deletedMessage = stringResource(R.string.transaction_deleted)
    // The row a right swipe asked to recategorise, while its category sheet is open.
    var recategorising by remember { mutableStateOf<Transaction?>(null) }

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
        // The outer chrome Scaffold (MainActivity) already reserves the status-bar inset once,
        // since this screen has no topBar of its own to consume it. Without zeroing this
        // Scaffold's own contentWindowInsets -- which defaults to the status bar too, independent
        // of whether there's a topBar -- that inset is reserved a second time here, which is the
        // unexplained gap above the search field. Same bug, same fix, as the doubled gap on every
        // screen with its own TopAppBar; this one just has no TopAppBar to zero out instead.
        contentWindowInsets = WindowInsets(0),
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

            state.filteredTotal?.let { spent ->
                SummaryStrip(
                    thisMonth = state.summaryIsThisMonth,
                    count = state.filteredCount,
                    spent = spent,
                    received = state.filteredIncome ?: Money.zero(spent.currency),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            TransactionList(
                pagedTransactions = pagedTransactions,
                state = state,
                onOpenTransaction = onOpenTransaction,
                onAddTransaction = onAddTransaction,
                onClearFilters = viewModel::clearFilters,
                onDelete = viewModel::delete,
                onRecategorise = { recategorising = it },
            )
        }
    }

    recategorising?.let { transaction ->
        val relevant = remember(state.categories, transaction.type) {
            state.categories.filter { category ->
                when (transaction.type) {
                    TransactionType.INCOME -> category.kind != CategoryKind.EXPENSE
                    else -> category.kind != CategoryKind.INCOME
                }
            }
        }
        CategoryPickerSheet(
            categories = relevant,
            selectedId = transaction.categoryId,
            onSelect = { categoryId ->
                viewModel.recategorise(transaction.id, categoryId)
                recategorising = null
            },
            onDismiss = { recategorising = null },
        )
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
    onDelete: (String) -> Unit,
    onRecategorise: (Transaction) -> Unit,
) {
    val refreshState = pagedTransactions.loadState.refresh

    // Indexed once per change of the underlying lists rather than scanned per row. Looking these
    // up with `firstOrNull` inside the row made every visible row walk the whole category and
    // account list on every frame, which is what a long ledger felt slow scrolling through.
    val listState = rememberLazyListState()
    ScrollToTopOnReselect(Routes.TRANSACTIONS, listState)
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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = KhaataTheme.spacing.bottomBarClearance),
        ) {
            items(
                count = pagedTransactions.itemCount,
                key = pagedTransactions.itemKey { it.id },
            ) { index ->
                val transaction = pagedTransactions[index] ?: return@items
                AnimatedListItem {

                    // A date header whenever the day changes. Computed from the neighbouring row
                    // rather than by pre-grouping, so it works with paging.
                    val previous = if (index > 0) pagedTransactions.peek(index - 1) else null
                    if (previous == null || previous.occurredOn != transaction.occurredOn) {
                        DateHeader(
                            date = transaction.occurredOn,
                            // Only when sorted by date: sorted by amount, one day's rows are scattered
                            // and a day total above one of them would describe rows that are not there.
                            net = if (state.filter.sort == TransactionSort.DATE_DESC) {
                                state.dailyNet[transaction.occurredOn]
                            } else {
                                null
                            },
                        )
                    }

                    val category = categoriesById[transaction.categoryId]

                    SwipeableRow(
                        canRecategorise = transaction.type != TransactionType.TRANSFER,
                        onDelete = { onDelete(transaction.id) },
                        onRecategorise = { onRecategorise(transaction) },
                    ) {
                        TransactionRow(
                            transaction = transaction,
                            categoryName = category?.name,
                            accountName = accountsById[transaction.accountId]?.name,
                            transferAccountName = transaction.transferAccountId?.let { accountsById[it]?.name },
                            categoryColorSeed = category?.colorSeed ?: 0,
                            categoryIconKey = category?.iconKey,
                            onClick = { onOpenTransaction(transaction.id) },
                            showDate = false,
                            modifier = Modifier.background(MaterialTheme.colorScheme.background),
                        )
                    }
                }
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

/**
 * The date above a day's rows, with that day's net beside it -- "what did I spend on Tuesday?"
 * answered without adding the rows up by eye.
 */
@Composable
private fun DateHeader(date: LocalDate, net: Money?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = relativeDateLabel(date),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (net != null && !net.isZero) {
            MoneyText(
                money = net,
                signStyle = SignStyle.ALWAYS,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A row that swipes: left to delete, with the usual undo; right to change its category.
 *
 * The right swipe never dismisses -- it opens the category sheet and the row springs back -- so
 * only delete ever removes a row from under the finger. Both are also on the transaction's own
 * screen, for anyone who does not swipe.
 */
@Composable
private fun SwipeableRow(
    canRecategorise: Boolean,
    onDelete: () -> Unit,
    onRecategorise: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onRecategorise()
                    false
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = canRecategorise,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val deleting = direction == SwipeToDismissBoxValue.EndToStart
            val color = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = if (deleting) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        imageVector = if (deleting) Icons.Outlined.Delete else Icons.Outlined.Category,
                        contentDescription = null,
                        tint = if (deleting) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }
        },
    ) {
        content()
    }
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
        null to R.string.activity_filter_all,
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
                // primaryContainer for "selected", matching the bottom nav and every other
                // filter chip in the app -- secondaryContainer (brass) is reserved for warning.
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/**
 * Money in, money out and the difference, for whatever the list is showing -- this month when
 * nothing is filtered, the filter's rows otherwise.
 *
 * From separate queries rather than summed from loaded pages, so the figures do not creep upward
 * as the user scrolls. Transfers count in neither: moving money between two of your own accounts
 * is not money in or out.
 */
@Composable
private fun SummaryStrip(
    thisMonth: Boolean,
    count: Int,
    spent: Money,
    received: Money,
) {
    val money = KhaataTheme.money
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = if (thisMonth) {
                stringResource(R.string.reports_period_this_month)
            } else {
                stringResource(R.string.activity_matching, count)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KhaataStatTile(
                label = stringResource(R.string.activity_in),
                tint = money.income,
                modifier = Modifier.weight(1f),
            ) {
                MoneyText(money = received, moneyStyle = MoneyStyle.COMPACT, style = KhaataTextStyles.amountMedium, color = money.income)
            }
            KhaataStatTile(
                label = stringResource(R.string.activity_out),
                tint = money.expense,
                modifier = Modifier.weight(1f),
            ) {
                MoneyText(money = spent, moneyStyle = MoneyStyle.COMPACT, style = KhaataTextStyles.amountMedium, color = money.expense)
            }
            KhaataStatTile(
                label = stringResource(R.string.activity_net),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            ) {
                MoneyText(
                    money = received - spent,
                    moneyStyle = MoneyStyle.COMPACT,
                    signStyle = SignStyle.ALWAYS,
                    style = KhaataTextStyles.amountMedium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
                        colors = ai.labs32.khaata.core.ui.components.khaataChipColors(),
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
                        colors = ai.labs32.khaata.core.ui.components.khaataChipColors(),
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
                        colors = ai.labs32.khaata.core.ui.components.khaataChipColors(),
                        selected = category.id in state.filter.categoryIds,
                        onClick = { viewModel.onCategoryFilterToggle(category.id) },
                        label = { Text(category.name, maxLines = 1) },
                    )
                }
            }
        }

        // One tag at a time: see TransactionFilter.tagPattern. Tapping the selected one clears it.
        if (state.tags.isNotEmpty()) {
            Spacer(Modifier.height(spacing.default))
            FilterSection(stringResource(R.string.transaction_tags)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.tags, key = { it }) { tag ->
                        val selected = tag in state.filter.tags
                        FilterChip(
                            colors = ai.labs32.khaata.core.ui.components.khaataChipColors(),
                            selected = selected,
                            onClick = { viewModel.onTagFilterChange(if (selected) null else tag) },
                            label = { Text(tag, maxLines = 1) },
                        )
                    }
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
