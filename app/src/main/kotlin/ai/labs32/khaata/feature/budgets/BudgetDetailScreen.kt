package ai.labs32.khaata.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.BudgetCalculator
import ai.labs32.khaata.core.calc.BudgetProgress
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.KhaataCardTier
import ai.labs32.khaata.core.ui.components.LabelledProgress
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import ai.labs32.khaata.feature.shared.TransactionRow
import ai.labs32.khaata.feature.shared.budgetStatusColor
import ai.labs32.khaata.feature.shared.budgetStatusLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BudgetDetailUiState(
    val isLoading: Boolean = true,
    val progress: BudgetProgress? = null,
    val transactions: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isDeleted: Boolean = false,
)

@HiltViewModel
class BudgetDetailViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BudgetDetailUiState())
    val uiState: StateFlow<BudgetDetailUiState> = _uiState.asStateFlow()

    private var budgetId: String? = null

    fun load(id: String) {
        budgetId = id
        viewModelScope.launch {
            val progress = budgetRepository.progressFor(id)
            val categories = categoryRepository.getAll()
            val accounts = accountRepository.observeAll().first()

            // Only the transactions this budget actually counts, so the list explains the number
            // above it exactly.
            val matching = if (progress != null) {
                val rollup = BudgetCalculator.buildCategoryRollup(categories)
                transactionRepository.getInRange(progress.period)
                    .filter { BudgetCalculator.matches(progress.budget, it, rollup, progress.period) }
                    .sortedByDescending { it.amount.amount }
            } else {
                emptyList()
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    progress = progress,
                    transactions = matching,
                    accounts = accounts,
                    categories = categories,
                )
            }
        }
    }

    fun delete() {
        val id = budgetId ?: return
        viewModelScope.launch {
            budgetRepository.delete(id)
            _uiState.update { it.copy(isDeleted = true) }
        }
    }
}

/**
 * A budget's detail.
 *
 * Shows every transaction counted against it, ordered by size. "You are over budget" is only
 * useful if the user can see what did it, and the biggest contributors are what they will act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetDetailScreen(
    budgetId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    viewModel: BudgetDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(budgetId) { viewModel.load(budgetId) }
    LaunchedEffect(state.isDeleted) { if (state.isDeleted) onBack() }

    // Indexed once instead of scanned per row — see TransactionsScreen for the same change.
    val categoriesById = remember(state.categories) { state.categories.associateBy { it.id } }
    val accountsById = remember(state.accounts) { state.accounts.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(state.progress?.budget?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                        )
                    }
                    IconButton(onClick = viewModel::delete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val progress = state.progress
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))
            progress == null -> ai.labs32.khaata.core.ui.components.ErrorState(
                message = stringResource(R.string.state_error_generic),
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load(budgetId) },
            )
            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
            ) {
                item { BudgetSummaryCard(progress) }

                if (state.transactions.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.dashboard_recent_transactions),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = KhaataTheme.spacing.small),
                        )
                    }
                    items(state.transactions, key = { it.id }) { transaction ->
                        val category = categoriesById[transaction.categoryId]

                        TransactionRow(
                            transaction = transaction,
                            categoryName = category?.name,
                            accountName = accountsById[transaction.accountId]?.name,
                            categoryColorSeed = category?.colorSeed ?: 0,
                            categoryIconKey = category?.iconKey,
                            onClick = { onOpenTransaction(transaction.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetSummaryCard(progress: BudgetProgress) {
    val statusColor = budgetStatusColor(progress.status)

    KhaataCard(tier = KhaataCardTier.Emphasized) {
        CardHeader(
            title = budgetStatusLabel(progress.status),
            subtitle = pluralStringResource(
                R.plurals.budgets_days_left,
                progress.daysRemaining,
                progress.daysRemaining,
            ),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.default))

        MoneyText(
            money = progress.remaining.floorAtZero(),
            style = KhaataTextStyles.amountHero,
            color = statusColor,
        )
        Text(
            text = stringResource(R.string.dashboard_budget_remaining),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        LabelledProgress(
            progressPercent = progress.percentUsedClamped,
            statusLabel = budgetStatusLabel(progress.status),
            progressColor = statusColor,
            height = 12.dp,
        )

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.dashboard_expenses),
            leadingValue = {
                MoneyText(money = progress.spent, style = KhaataTextStyles.amountMedium)
            },
            trailingLabel = stringResource(R.string.budgets_limit),
            trailingValue = {
                MoneyText(money = progress.limit, style = KhaataTextStyles.amountMedium)
            },
        )

        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        // The projection is the part that changes behaviour while there is still time to act.
        Text(
            text = stringResource(
                R.string.budgets_projected,
                MoneyFormatter.plain(progress.projectedSpend),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        progress.safeDailySpend?.takeIf { !progress.isOverspent }?.let { daily ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.budgets_safe_daily, MoneyFormatter.plain(daily)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
