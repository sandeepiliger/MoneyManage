package ai.labs32.khaata.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import ai.labs32.khaata.feature.shared.TransactionRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewListUiState(
    val transactions: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Imported transactions awaiting confirmation.
 *
 * Nothing here affects a balance until the user accepts it. That is the whole point of the
 * pending state: an SMS parser will occasionally be wrong, and a wrong figure the user never
 * agreed to is worse than a missed one.
 */
@HiltViewModel
class PendingImportsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    private val analytics: AnalyticsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewListUiState())
    val uiState: StateFlow<ReviewListUiState> = _uiState.asStateFlow()

    init {
        combine(
            transactionRepository.observePending(),
            accountRepository.observeAll(),
            categoryRepository.observeAll(),
        ) { pending, accounts, categories ->
            ReviewListUiState(pending, accounts, categories, isLoading = false)
        }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    fun accept(transactionId: String) {
        viewModelScope.launch {
            transactionRepository.confirmPending(transactionId)
            analytics.track(AnalyticsEvent.SmsImportReviewed(accepted = true))
        }
    }

    fun reject(transactionId: String) {
        viewModelScope.launch {
            transactionRepository.delete(transactionId)
            analytics.track(AnalyticsEvent.SmsImportReviewed(accepted = false))
        }
    }

    fun acceptAll() {
        viewModelScope.launch {
            _uiState.value.transactions.forEach {
                transactionRepository.confirmPending(it.id)
                analytics.track(AnalyticsEvent.SmsImportReviewed(accepted = true))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingImportsScreen(
    onBack: () -> Unit,
    viewModel: PendingImportsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sms_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.transactions.size > 1) {
                        TextButton(onClick = viewModel::acceptAll) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.transactions.isEmpty() && !state.isLoading) {
            EmptyState(
                icon = Icons.Outlined.MarkEmailRead,
                title = stringResource(R.string.sms_no_messages),
                description = stringResource(R.string.sms_permission_denied),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        // Indexed once instead of scanned per row — see TransactionsScreen for the same change.
        val categoriesById = remember(state.categories) { state.categories.associateBy { it.id } }
        val accountsById = remember(state.accounts) { state.accounts.associateBy { it.id } }

        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small),
        ) {
            item {
                Text(
                    text = stringResource(R.string.sms_review_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(KhaataTheme.spacing.small))
            }

            items(state.transactions, key = { it.id }) { transaction ->
                val category = categoriesById[transaction.categoryId]

                KhaataCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    TransactionRow(
                        transaction = transaction,
                        categoryName = category?.name,
                        accountName = accountsById[transaction.accountId]?.name,
                        categoryColorSeed = category?.colorSeed ?: 0,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.reject(transaction.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.sms_review_reject)) }

                        Button(
                            onClick = { viewModel.accept(transaction.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.sms_review_accept)) }
                    }
                }
            }
        }
    }
}

/**
 * Recently deleted transactions.
 *
 * Deleted rows are kept for 30 days rather than removed immediately. An accidental swipe on a
 * list of financial records should be recoverable, and a "recently deleted" bin costs nothing
 * next to the alternative.
 */
@HiltViewModel
class RecentlyDeletedViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewListUiState())
    val uiState: StateFlow<ReviewListUiState> = _uiState.asStateFlow()

    init {
        combine(
            transactionRepository.observeDeleted(),
            accountRepository.observeAll(),
            categoryRepository.observeAll(),
        ) { deleted, accounts, categories ->
            ReviewListUiState(deleted, accounts, categories, isLoading = false)
        }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    fun restore(transactionId: String) {
        viewModelScope.launch { transactionRepository.restore(transactionId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyDeletedScreen(
    onBack: () -> Unit,
    viewModel: RecentlyDeletedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_recently_deleted)) },
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
        if (state.transactions.isEmpty() && !state.isLoading) {
            EmptyState(
                icon = Icons.Outlined.DeleteOutline,
                title = stringResource(R.string.settings_recently_deleted),
                description = stringResource(R.string.transaction_delete_confirm_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        val categoriesById = remember(state.categories) { state.categories.associateBy { it.id } }
        val accountsById = remember(state.accounts) { state.accounts.associateBy { it.id } }

        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = KhaataTheme.spacing.small),
        ) {
            items(state.transactions, key = { it.id }) { transaction ->
                val category = categoriesById[transaction.categoryId]

                Column {
                    TransactionRow(
                        transaction = transaction,
                        categoryName = category?.name,
                        accountName = accountsById[transaction.accountId]?.name,
                        categoryColorSeed = category?.colorSeed ?: 0,
                    )
                    TextButton(
                        onClick = { viewModel.restore(transaction.id) },
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    ) { Text(stringResource(R.string.action_restore)) }
                }
            }
        }
    }
}
