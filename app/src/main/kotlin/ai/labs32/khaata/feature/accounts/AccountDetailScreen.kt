package ai.labs32.khaata.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.model.AccountBalance
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.ErrorState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.AccountDeletionResult
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.TransactionFilter
import ai.labs32.khaata.data.repository.TransactionRepository
import ai.labs32.khaata.feature.shared.TransactionRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountDetailUiState(
    val isLoading: Boolean = true,
    val balance: AccountBalance? = null,
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val deletionBlockedCount: Int? = null,
    val isDeleted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountDetailUiState())
    val uiState: StateFlow<AccountDetailUiState> = _uiState.asStateFlow()

    private var accountId: String? = null

    fun load(id: String) {
        accountId = id
        viewModelScope.launch {
            accountRepository.observeBalances().collect { balances ->
                val balance = balances.firstOrNull { it.account.id == id }
                if (balance == null) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "This account no longer exists.")
                    }
                    return@collect
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        balance = balance,
                        categories = categoryRepository.getAll(),
                        transactions = transactionRepository.listFiltered(
                            TransactionFilter(accountIds = setOf(id)),
                        ).take(TRANSACTION_LIMIT),
                        error = null,
                    )
                }
            }
        }
    }

    fun toggleArchived() {
        val balance = _uiState.value.balance ?: return
        viewModelScope.launch {
            accountRepository.setArchived(balance.account.id, !balance.account.isArchived)
        }
    }

    /**
     * Attempts a delete.
     *
     * An account with transactions is never removed — deleting it would take history with it and
     * silently change every past total. The user is told how many rows are involved and offered
     * archiving instead.
     */
    fun delete() {
        val id = accountId ?: return
        viewModelScope.launch {
            when (val result = accountRepository.delete(id)) {
                is AccountDeletionResult.Deleted -> _uiState.update { it.copy(isDeleted = true) }
                is AccountDeletionResult.HasTransactions -> _uiState.update {
                    it.copy(deletionBlockedCount = result.transactionCount)
                }
                is AccountDeletionResult.NotFound -> _uiState.update { it.copy(isDeleted = true) }
            }
        }
    }

    fun dismissDeletionBlock() = _uiState.update { it.copy(deletionBlockedCount = null) }

    private companion object {
        const val TRANSACTION_LIMIT = 50
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    accountId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(accountId) { viewModel.load(accountId) }
    LaunchedEffect(state.isDeleted) { if (state.isDeleted) onBack() }

    // Indexed once instead of scanned per row — see TransactionsScreen for the same change.
    val categoriesById = remember(state.categories) { state.categories.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.balance?.account?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleArchived) {
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = stringResource(R.string.accounts_archive),
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            state.error != null -> ErrorState(
                message = state.error!!,
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load(accountId) },
            )

            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small),
            ) {
                item {
                    state.balance?.let { balance ->
                        KhaataCard {
                            CardHeader(
                                title = if (balance.account.isLiability) {
                                    stringResource(R.string.cards_outstanding)
                                } else {
                                    stringResource(R.string.accounts_opening_balance)
                                },
                                subtitle = balance.account.institution,
                            )
                            Spacer(Modifier.height(KhaataTheme.spacing.small))
                            MoneyText(
                                money = balance.displayBalance,
                                style = KhaataTextStyles.amountHero,
                                color = if (balance.account.isLiability) {
                                    KhaataTheme.money.expense
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        Spacer(Modifier.height(KhaataTheme.spacing.small))
                    }
                }

                items(state.transactions, key = { it.id }) { transaction ->
                    val category = categoriesById[transaction.categoryId]

                    TransactionRow(
                        transaction = transaction,
                        categoryName = category?.name,
                        accountName = null,
                        categoryColorSeed = category?.colorSeed ?: 0,
                        onClick = { onOpenTransaction(transaction.id) },
                    )
                }
            }
        }
    }

    state.deletionBlockedCount?.let { count ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeletionBlock,
            title = { Text(stringResource(R.string.accounts_delete_blocked_title)) },
            text = { Text(stringResource(R.string.accounts_delete_blocked_body, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissDeletionBlock()
                        viewModel.toggleArchived()
                    },
                ) { Text(stringResource(R.string.accounts_archive)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeletionBlock) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
