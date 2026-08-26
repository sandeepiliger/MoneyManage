package ai.labs32.khaata.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.ui.components.ErrorState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.TransactionAmountText
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class TransactionDetailUiState(
    val isLoading: Boolean = true,
    val transaction: Transaction? = null,
    val account: Account? = null,
    val transferAccount: Account? = null,
    val category: Category? = null,
    val isDeleted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionDetailUiState())
    val uiState: StateFlow<TransactionDetailUiState> = _uiState.asStateFlow()

    fun load(transactionId: String) {
        viewModelScope.launch {
            transactionRepository.observeById(transactionId).collect { transaction ->
                if (transaction == null) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "This transaction no longer exists.")
                    }
                    return@collect
                }
                val accounts = accountRepository.observeAll().first()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        transaction = transaction,
                        account = accounts.firstOrNull { a -> a.id == transaction.accountId },
                        transferAccount = transaction.transferAccountId?.let { id ->
                            accounts.firstOrNull { a -> a.id == id }
                        },
                        category = transaction.categoryId?.let { categoryRepository.findById(it) },
                        error = null,
                    )
                }
            }
        }
    }

    fun delete() {
        val id = _uiState.value.transaction?.id ?: return
        viewModelScope.launch {
            transactionRepository.delete(id)
            _uiState.update { it.copy(isDeleted = true) }
        }
    }

    fun duplicate(onDuplicated: () -> Unit) {
        val id = _uiState.value.transaction?.id ?: return
        viewModelScope.launch {
            transactionRepository.duplicate(id)
            onDuplicated()
        }
    }
}

/**
 * A single transaction.
 *
 * Deliberately shows the source and the audit timestamps. For an imported row, knowing it came
 * from a bank SMS rather than being typed is the difference between trusting a figure and
 * double-checking it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transactionId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(transactionId) { viewModel.load(transactionId) }
    LaunchedEffect(state.isDeleted) { if (state.isDeleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.transaction_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.duplicate(onBack) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.action_duplicate),
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                        )
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
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
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))
            state.error != null -> ErrorState(
                message = state.error!!,
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load(transactionId) },
            )
            state.transaction != null -> DetailContent(
                state = state,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.transaction_delete_confirm_title)) },
            text = { Text(stringResource(R.string.transaction_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.delete()
                    },
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailContent(state: TransactionDetailUiState, modifier: Modifier = Modifier) {
    val transaction = state.transaction ?: return
    val spacing = KhaataTheme.spacing
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy") }
    val timestampFormatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal),
    ) {
        Spacer(Modifier.height(spacing.large))

        TransactionAmountText(
            amount = transaction.amount,
            type = transaction.type,
            style = KhaataTextStyles.amountHero,
        )

        Spacer(Modifier.height(spacing.large))

        KhaataCard {
            DetailRow(
                label = stringResource(R.string.transaction_date),
                value = transaction.occurredOn.format(dateFormatter),
            )
            DetailRow(
                label = stringResource(R.string.transaction_account),
                value = state.account?.name ?: "—",
            )
            if (transaction.type == TransactionType.TRANSFER) {
                DetailRow(
                    label = stringResource(R.string.transaction_to_account),
                    value = state.transferAccount?.name ?: "—",
                )
            } else {
                DetailRow(
                    label = stringResource(R.string.transaction_category),
                    value = state.category?.name
                        ?: stringResource(R.string.categories_uncategorised),
                )
            }
            transaction.merchant?.let {
                DetailRow(label = stringResource(R.string.transaction_merchant), value = it)
            }
            transaction.note?.let {
                DetailRow(label = stringResource(R.string.transaction_note), value = it)
            }
            if (transaction.tags.isNotEmpty()) {
                DetailRow(
                    label = stringResource(R.string.transaction_tags),
                    value = transaction.tags.joinToString(", "),
                )
            }
            transaction.referenceNumber?.let {
                DetailRow(label = stringResource(R.string.transaction_reference), value = it)
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // Provenance. An imported figure deserves a different level of scrutiny from one the
        // user typed, so where it came from is stated rather than hidden.
        KhaataCard {
            DetailRow(
                label = stringResource(R.string.transaction_recorded_via),
                value = sourceLabel(transaction.source),
            )
            DetailRow(
                label = stringResource(R.string.transaction_added),
                value = transaction.createdAt.atZone(ZoneId.systemDefault()).format(timestampFormatter),
            )
            if (transaction.updatedAt != transaction.createdAt) {
                DetailRow(
                    label = stringResource(R.string.transaction_last_edited),
                    value = transaction.updatedAt.atZone(ZoneId.systemDefault()).format(timestampFormatter),
                )
            }
        }

        Spacer(Modifier.height(spacing.xlarge))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
private fun sourceLabel(source: ai.labs32.khaata.core.model.TransactionSource): String = when (source) {
    ai.labs32.khaata.core.model.TransactionSource.SMS_IMPORT,
    ai.labs32.khaata.core.model.TransactionSource.NOTIFICATION_IMPORT,
    -> stringResource(R.string.transaction_source_sms)
    ai.labs32.khaata.core.model.TransactionSource.CSV_IMPORT ->
        stringResource(R.string.transaction_source_import)
    ai.labs32.khaata.core.model.TransactionSource.RECURRING ->
        stringResource(R.string.transaction_source_recurring)
    ai.labs32.khaata.core.model.TransactionSource.NATURAL_LANGUAGE ->
        stringResource(R.string.quick_add_natural_language)
    ai.labs32.khaata.core.model.TransactionSource.DEMO ->
        stringResource(R.string.demo_badge)
    else -> stringResource(R.string.transaction_add_title)
}
