package ai.labs32.khaata.feature.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.Commitment
import ai.labs32.khaata.core.calc.CommitmentCalculator
import ai.labs32.khaata.core.calc.RecurrenceCalculator
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.DueOccurrence
import ai.labs32.khaata.data.repository.RecurringRepository
import ai.labs32.khaata.feature.shared.ChipSelector
import ai.labs32.khaata.feature.shared.DateField
import ai.labs32.khaata.feature.shared.ToggleRow
import ai.labs32.khaata.feature.shared.relativeDateLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A rule with the derived facts the list needs, so the composable does no calculation. */
data class RecurringRuleItem(
    val rule: RecurringRule,
    val nextDueOn: LocalDate?,
    val accountName: String?,
    val categoryName: String?,
)

data class RecurringEditorState(
    val id: String? = null,
    val name: String = "",
    val amountText: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val accountId: String? = null,
    val transferAccountId: String? = null,
    val categoryId: String? = null,
    val frequency: Frequency = Frequency.MONTHLY,
    val interval: Int = 1,
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val autoPost: Boolean = false,
    val reminderDaysBefore: Int = 1,
    val errors: Map<String, String> = emptyMap(),
) {
    val isEditing: Boolean get() = id != null
}

/** Just enough of a rule to name it in a confirmation dialog. */
data class RuleReference(val id: String, val name: String)

data class RecurringUiState(
    val isLoading: Boolean = true,
    val due: List<DueOccurrence> = emptyList(),
    val active: List<RecurringRuleItem> = emptyList(),
    val paused: List<RecurringRuleItem> = emptyList(),
    val commitment: Commitment? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val editor: RecurringEditorState? = null,
    val deleteTarget: RuleReference? = null,
    val postedMessage: Boolean = false,
)

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringRepository: RecurringRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecurringUiState())
    val uiState: StateFlow<RecurringUiState> = _uiState.asStateFlow()

    init {
        combine(
            recurringRepository.observeAll(),
            recurringRepository.observeAwaitingConfirmation(),
            accountRepository.observeActive(),
            categoryRepository.observeActive(),
        ) { rules, due, accounts, categories ->
            val today = clock.today()
            val accountNames = accounts.associate { it.id to it.name }
            val categoryNames = categories.associate { it.id to it.name }

            fun item(rule: RecurringRule) = RecurringRuleItem(
                rule = rule,
                nextDueOn = RecurrenceCalculator.nextOccurrenceAfter(rule, today.minusDays(1)),
                accountName = accountNames[rule.accountId],
                categoryName = rule.categoryId?.let { categoryNames[it] },
            )

            val (active, paused) = rules.partition { it.isActive }
            RecurringUiState(
                isLoading = false,
                // Only the nearest few per rule are shown; confirming one reveals the next.
                due = due.take(MAX_DUE_SHOWN),
                active = active.map(::item).sortedBy { it.nextDueOn ?: LocalDate.MAX },
                paused = paused.map(::item),
                commitment = CommitmentCalculator.summarise(active),
                accounts = accounts,
                categories = categories,
            )
        }
            .onEach { fresh ->
                // The editor and dialogs are user state, not data, so a refresh must not close them.
                _uiState.update { current ->
                    fresh.copy(
                        editor = current.editor,
                        deleteTarget = current.deleteTarget,
                        postedMessage = current.postedMessage,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    // ---- Due occurrences ---------------------------------------------------------------------

    fun confirm(occurrence: DueOccurrence) {
        viewModelScope.launch {
            recurringRepository.postOccurrence(occurrence.rule.id, occurrence.dueOn)
            _uiState.update { it.copy(postedMessage = true) }
        }
    }

    fun skip(occurrence: DueOccurrence) {
        viewModelScope.launch {
            recurringRepository.skipOccurrence(occurrence.rule.id, occurrence.dueOn)
        }
    }

    fun consumePostedMessage() = _uiState.update { it.copy(postedMessage = false) }

    // ---- Rules -------------------------------------------------------------------------------

    fun togglePaused(rule: RecurringRule) {
        viewModelScope.launch { recurringRepository.setActive(rule.id, !rule.isActive) }
    }

    fun requestDelete(id: String, name: String) =
        _uiState.update { it.copy(deleteTarget = RuleReference(id, name)) }

    fun dismissDelete() = _uiState.update { it.copy(deleteTarget = null) }

    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        viewModelScope.launch {
            recurringRepository.delete(target.id)
            _uiState.update { it.copy(deleteTarget = null, editor = null) }
        }
    }

    // ---- Editor ------------------------------------------------------------------------------

    fun startCreate() = _uiState.update { state ->
        state.copy(
            editor = RecurringEditorState(
                accountId = state.accounts.firstOrNull()?.id,
                startDate = clock.today(),
            ),
        )
    }

    fun startEdit(rule: RecurringRule) = _uiState.update {
        it.copy(
            editor = RecurringEditorState(
                id = rule.id,
                name = rule.name,
                amountText = rule.amount.toPlainString(),
                type = rule.type,
                accountId = rule.accountId,
                transferAccountId = rule.transferAccountId,
                categoryId = rule.categoryId,
                frequency = rule.frequency,
                interval = rule.interval,
                startDate = rule.startDate,
                endDate = rule.endDate,
                autoPost = rule.autoPost,
                reminderDaysBefore = rule.reminderDaysBefore,
            ),
        )
    }

    fun dismissEditor() = _uiState.update { it.copy(editor = null) }

    fun editName(value: String) = updateEditor { it.copy(name = value, errors = it.errors - "name") }

    fun editAmount(value: String) = updateEditor {
        it.copy(amountText = value.filter { ch -> ch.isDigit() || ch == '.' }, errors = it.errors - "amount")
    }

    fun editType(type: TransactionType) = updateEditor {
        it.copy(
            type = type,
            // A transfer has no category, and an expense has no destination account. Clearing the
            // irrelevant field prevents a stale value being saved with the rule.
            categoryId = if (type == TransactionType.TRANSFER) null else it.categoryId,
            transferAccountId = if (type == TransactionType.TRANSFER) it.transferAccountId else null,
            errors = emptyMap(),
        )
    }

    fun editAccount(id: String) = updateEditor { it.copy(accountId = id, errors = it.errors - "account") }

    fun editTransferAccount(id: String) =
        updateEditor { it.copy(transferAccountId = id, errors = it.errors - "transferAccount") }

    fun editCategory(id: String) =
        updateEditor { it.copy(categoryId = if (it.categoryId == id) null else id) }

    fun editFrequency(frequency: Frequency) = updateEditor { it.copy(frequency = frequency) }

    fun editInterval(interval: Int) = updateEditor { it.copy(interval = interval.coerceIn(1, 12)) }

    fun editStartDate(date: LocalDate) =
        updateEditor { it.copy(startDate = date, errors = it.errors - "endDate") }

    fun editEndDate(date: LocalDate?) =
        updateEditor { it.copy(endDate = date, errors = it.errors - "endDate") }

    fun editAutoPost(enabled: Boolean) = updateEditor { it.copy(autoPost = enabled) }

    fun editReminderDays(days: Int) = updateEditor { it.copy(reminderDaysBefore = days) }

    fun save() {
        val editor = _uiState.value.editor ?: return
        val currency = _uiState.value.accounts
            .firstOrNull { it.id == editor.accountId }?.currency
            ?: CurrencyCode.DEFAULT
        val amount = MoneyParser.parse(editor.amountText, currency)

        val errors = buildMap {
            if (editor.name.isBlank()) put("name", ERROR_NAME_REQUIRED)
            when {
                editor.amountText.isBlank() -> put("amount", ERROR_AMOUNT_REQUIRED)
                amount == null -> put("amount", ERROR_AMOUNT_INVALID)
                !amount.isPositive -> put("amount", ERROR_AMOUNT_ZERO)
            }
            if (editor.accountId == null) put("account", ERROR_ACCOUNT_REQUIRED)
            if (editor.type == TransactionType.TRANSFER) {
                when {
                    editor.transferAccountId == null -> put("transferAccount", ERROR_TRANSFER_REQUIRED)
                    editor.transferAccountId == editor.accountId ->
                        put("transferAccount", ERROR_TRANSFER_SAME)
                }
            }
            if (editor.endDate != null && editor.endDate.isBefore(editor.startDate)) {
                put("endDate", ERROR_END_BEFORE_START)
            }
        }
        if (errors.isNotEmpty()) {
            updateEditor { it.copy(errors = errors) }
            return
        }

        val rule = RecurringRule(
            id = editor.id.orEmpty(),
            name = editor.name.trim(),
            type = editor.type,
            amount = amount!!,
            accountId = editor.accountId!!,
            transferAccountId = editor.transferAccountId,
            categoryId = editor.categoryId,
            frequency = editor.frequency,
            interval = editor.interval,
            startDate = editor.startDate,
            endDate = editor.endDate,
            autoPost = editor.autoPost,
            reminderDaysBefore = editor.reminderDaysBefore,
            // Editing an existing rule must not resurrect occurrences it has already posted, so
            // the posting watermark is carried over rather than reset.
            lastPostedOn = _uiState.value.active.plus(_uiState.value.paused)
                .firstOrNull { it.rule.id == editor.id }?.rule?.lastPostedOn,
            isActive = _uiState.value.paused.none { it.rule.id == editor.id },
        )

        viewModelScope.launch {
            if (editor.isEditing) recurringRepository.update(rule) else recurringRepository.create(rule)
            _uiState.update { it.copy(editor = null) }
        }
    }

    private fun updateEditor(transform: (RecurringEditorState) -> RecurringEditorState) =
        _uiState.update { state -> state.copy(editor = state.editor?.let(transform)) }

    private companion object {
        const val MAX_DUE_SHOWN = 10

        const val ERROR_NAME_REQUIRED = "name_required"
        const val ERROR_AMOUNT_REQUIRED = "amount_required"
        const val ERROR_AMOUNT_INVALID = "amount_invalid"
        const val ERROR_AMOUNT_ZERO = "amount_zero"
        const val ERROR_ACCOUNT_REQUIRED = "account_required"
        const val ERROR_TRANSFER_REQUIRED = "transfer_required"
        const val ERROR_TRANSFER_SAME = "transfer_same"
        const val ERROR_END_BEFORE_START = "end_before_start"
    }
}

/**
 * Recurring rules — rent, salary, EMIs, SIPs, insurance.
 *
 * The screen leads with what is waiting on the user rather than with the list of rules, because
 * an unconfirmed rent payment is the only thing here that needs a decision today. That section
 * exists at all because auto-posting is off by default: the app knows rent was *due* on the 5th,
 * not that it actually went out, and a balance built on an assumption is a balance nobody can
 * reconcile against their bank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    viewModel: RecurringViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val postedText = stringResource(R.string.recurring_posted)

    LaunchedEffect(state.postedMessage) {
        if (state.postedMessage) {
            snackbarHostState.showSnackbar(postedText)
            viewModel.consumePostedMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recurring_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::startCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.recurring_add)) },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            state.active.isEmpty() && state.paused.isEmpty() && state.due.isEmpty() -> EmptyState(
                icon = Icons.Outlined.EventRepeat,
                title = stringResource(R.string.recurring_empty_title),
                description = stringResource(R.string.recurring_empty_body),
                actionLabel = stringResource(R.string.recurring_add),
                onAction = viewModel::startCreate,
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = KhaataTheme.spacing.screenHorizontal,
                    end = KhaataTheme.spacing.screenHorizontal,
                    bottom = KhaataTheme.spacing.bottomBarClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
            ) {
                if (state.due.isNotEmpty()) {
                    item(key = "due-header") {
                        SectionHeader(
                            text = pluralStringResource(
                                R.plurals.recurring_awaiting_confirmation,
                                state.due.size,
                                state.due.size,
                            ),
                        )
                    }
                    items(state.due, key = { "${it.rule.id}-${it.dueOn}" }) { occurrence ->
                        DueCard(
                            occurrence = occurrence,
                            onConfirm = { viewModel.confirm(occurrence) },
                            onSkip = { viewModel.skip(occurrence) },
                        )
                    }
                }

                state.commitment?.let { commitment ->
                    item(key = "commitment") { CommitmentCard(commitment) }
                }

                if (state.active.isNotEmpty()) {
                    item(key = "active-header") {
                        SectionHeader(stringResource(R.string.recurring_active))
                    }
                    items(state.active, key = { it.rule.id }) { item ->
                        RuleCard(
                            item = item,
                            onClick = { viewModel.startEdit(item.rule) },
                            onTogglePaused = { viewModel.togglePaused(item.rule) },
                        )
                    }
                }

                if (state.paused.isNotEmpty()) {
                    item(key = "paused-header") {
                        SectionHeader(stringResource(R.string.recurring_paused))
                    }
                    items(state.paused, key = { it.rule.id }) { item ->
                        RuleCard(
                            item = item,
                            onClick = { viewModel.startEdit(item.rule) },
                            onTogglePaused = { viewModel.togglePaused(item.rule) },
                        )
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        RecurringEditorSheet(
            editor = editor,
            accounts = state.accounts,
            categories = state.categories,
            onDismiss = viewModel::dismissEditor,
            viewModel = viewModel,
        )
    }

    state.deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.recurring_delete_title, target.name)) },
            text = { Text(stringResource(R.string.recurring_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = KhaataTheme.spacing.small),
    )
}

/**
 * One occurrence waiting on the user.
 *
 * "Record it" and "Didn't happen" are given equal weight deliberately. Making the confirm button
 * the obvious one would push people into recording payments that never left the account, which is
 * the exact failure this whole flow exists to avoid.
 */
@Composable
private fun DueCard(
    occurrence: DueOccurrence,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    val rule = occurrence.rule
    val money = KhaataTheme.money

    KhaataCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.recurring_was_due,
                        relativeDateLabel(occurrence.dueOn),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(
                money = rule.amount,
                style = KhaataTextStyles.amountMedium,
                color = if (rule.type == TransactionType.INCOME) money.income else money.expense,
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        Row(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.recurring_confirm_post))
            }
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.recurring_skip))
            }
        }
    }
}

@Composable
private fun CommitmentCard(commitment: Commitment) {
    KhaataCard {
        CardHeader(
            title = stringResource(R.string.recurring_commitment_title),
            // "About" is not hedging for its own sake: weekly and daily rules genuinely do not
            // divide into months evenly.
            subtitle = stringResource(R.string.recurring_commitment_approximate),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.default))
        StatPair(
            leadingLabel = stringResource(R.string.recurring_going_out),
            leadingValue = {
                MoneyText(
                    money = commitment.outgoingPerMonth,
                    style = KhaataTextStyles.amountMedium,
                    color = KhaataTheme.money.expense,
                )
            },
            trailingLabel = stringResource(R.string.recurring_coming_in),
            trailingValue = {
                MoneyText(
                    money = commitment.incomingPerMonth,
                    style = KhaataTextStyles.amountMedium,
                    color = KhaataTheme.money.income,
                )
            },
        )
    }
}

@Composable
private fun RuleCard(
    item: RecurringRuleItem,
    onClick: () -> Unit,
    onTogglePaused: () -> Unit,
) {
    val rule = item.rule
    val money = KhaataTheme.money
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM") }

    KhaataCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorBadge(
                icon = Icons.Default.EventRepeat,
                colorSeed = rule.name.hashCode(),
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        frequencyLabel(rule.frequency, rule.interval),
                        item.categoryName,
                        item.accountName,
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                MoneyText(
                    money = rule.amount,
                    style = KhaataTextStyles.amountMedium,
                    color = if (rule.type == TransactionType.INCOME) money.income else money.expense,
                )
                Text(
                    text = when {
                        !rule.isActive -> stringResource(R.string.recurring_paused_badge)
                        item.nextDueOn != null ->
                            stringResource(
                                R.string.recurring_next_due,
                                item.nextDueOn.format(dateFormatter),
                            )

                        else -> stringResource(R.string.recurring_finished)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onTogglePaused) {
                Icon(
                    imageVector = if (rule.isActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (rule.isActive) R.string.recurring_pause else R.string.recurring_resume,
                        rule.name,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Auto-posting is flagged on the card, because a rule that writes to the ledger by itself
        // is a materially different thing from one that asks first.
        if (rule.autoPost && rule.isActive) {
            Spacer(Modifier.height(KhaataTheme.spacing.small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.recurring_auto_post_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringEditorSheet(
    editor: RecurringEditorState,
    accounts: List<Account>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    viewModel: RecurringViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = KhaataTheme.spacing
    val currencySymbol = accounts
        .firstOrNull { it.id == editor.accountId }?.currency?.symbol
        ?: CurrencyCode.DEFAULT.symbol

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal)
                .padding(bottom = spacing.xlarge),
            verticalArrangement = Arrangement.spacedBy(spacing.default),
        ) {
            Text(
                text = stringResource(
                    if (editor.isEditing) R.string.recurring_edit else R.string.recurring_add,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TransactionType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = editor.type == type,
                        onClick = { viewModel.editType(type) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index,
                            TransactionType.entries.size,
                        ),
                    ) { Text(transactionTypeLabel(type)) }
                }
            }

            OutlinedTextField(
                value = editor.name,
                onValueChange = viewModel::editName,
                label = { Text(stringResource(R.string.recurring_name)) },
                singleLine = true,
                isError = editor.errors.containsKey("name"),
                supportingText = editor.errors["name"]?.let { { Text(errorMessage(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = editor.amountText,
                onValueChange = viewModel::editAmount,
                label = { Text(stringResource(R.string.transaction_amount)) },
                prefix = { Text(currencySymbol) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = editor.errors.containsKey("amount"),
                supportingText = editor.errors["amount"]?.let { { Text(errorMessage(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )

            ChipSelector(
                label = stringResource(
                    if (editor.type == TransactionType.INCOME) {
                        R.string.transaction_to_account
                    } else {
                        R.string.transaction_from_account
                    },
                ),
                options = accounts,
                selected = accounts.firstOrNull { it.id == editor.accountId },
                optionLabel = { it.name },
                optionKey = { it.id },
                onSelect = { viewModel.editAccount(it.id) },
                error = editor.errors["account"]?.let { errorMessage(it) },
            )

            if (editor.type == TransactionType.TRANSFER) {
                ChipSelector(
                    label = stringResource(R.string.transaction_to_account),
                    options = accounts.filter { it.id != editor.accountId },
                    selected = accounts.firstOrNull { it.id == editor.transferAccountId },
                    optionLabel = { it.name },
                    optionKey = { it.id },
                    onSelect = { viewModel.editTransferAccount(it.id) },
                    error = editor.errors["transferAccount"]?.let { errorMessage(it) },
                )
            } else {
                val relevant = categories.filter {
                    when (editor.type) {
                        TransactionType.INCOME -> it.kind != ai.labs32.khaata.core.model.CategoryKind.EXPENSE
                        else -> it.kind != ai.labs32.khaata.core.model.CategoryKind.INCOME
                    }
                }
                ChipSelector(
                    label = stringResource(R.string.transaction_category),
                    options = relevant,
                    selected = relevant.firstOrNull { it.id == editor.categoryId },
                    optionLabel = { it.name },
                    optionKey = { it.id },
                    onSelect = { viewModel.editCategory(it.id) },
                )
            }

            ChipSelector(
                label = stringResource(R.string.recurring_frequency),
                options = Frequency.entries,
                selected = editor.frequency,
                optionLabel = { frequencyLabel(it, 1) },
                onSelect = viewModel::editFrequency,
            )

            ChipSelector(
                label = stringResource(R.string.recurring_interval),
                options = INTERVAL_OPTIONS,
                selected = editor.interval,
                optionLabel = { intervalLabel(it, editor.frequency) },
                onSelect = viewModel::editInterval,
            )

            DateField(
                label = stringResource(R.string.recurring_start_date),
                date = editor.startDate,
                onPick = viewModel::editStartDate,
            )

            DateField(
                label = stringResource(R.string.recurring_end_date),
                date = editor.endDate,
                onPick = { viewModel.editEndDate(it) },
                onClear = { viewModel.editEndDate(null) },
                error = editor.errors["endDate"]?.let { errorMessage(it) },
            )

            ToggleRow(
                title = stringResource(R.string.recurring_auto_post),
                subtitle = stringResource(R.string.recurring_auto_post_help),
                control = {
                    Switch(checked = editor.autoPost, onCheckedChange = viewModel::editAutoPost)
                },
            )

            ChipSelector(
                label = stringResource(R.string.recurring_reminder_days),
                options = REMINDER_OPTIONS,
                selected = editor.reminderDaysBefore,
                optionLabel = { reminderLabel(it) },
                onSelect = viewModel::editReminderDays,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editor.isEditing) {
                    TextButton(
                        onClick = { viewModel.requestDelete(editor.id!!, editor.name) },
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun transactionTypeLabel(type: TransactionType): String = stringResource(
    when (type) {
        TransactionType.EXPENSE -> R.string.transaction_expense
        TransactionType.INCOME -> R.string.transaction_income
        TransactionType.TRANSFER -> R.string.transaction_transfer
    },
)

/** "Monthly", or "Every 3 months" once an interval is involved. */
@Composable
internal fun frequencyLabel(frequency: Frequency, interval: Int): String = if (interval <= 1) {
    stringResource(
        when (frequency) {
            Frequency.DAILY -> R.string.frequency_daily
            Frequency.WEEKLY -> R.string.frequency_weekly
            Frequency.FORTNIGHTLY -> R.string.frequency_fortnightly
            Frequency.MONTHLY -> R.string.frequency_monthly
            Frequency.QUARTERLY -> R.string.frequency_quarterly
            Frequency.HALF_YEARLY -> R.string.frequency_half_yearly
            Frequency.YEARLY -> R.string.frequency_yearly
        },
    )
} else {
    intervalLabel(interval, frequency)
}

@Composable
private fun intervalLabel(interval: Int, frequency: Frequency): String = if (interval <= 1) {
    stringResource(R.string.recurring_interval_every_one)
} else {
    pluralStringResource(
        when (frequency) {
            Frequency.DAILY -> R.plurals.recurring_interval_days
            Frequency.WEEKLY -> R.plurals.recurring_interval_weeks
            Frequency.FORTNIGHTLY -> R.plurals.recurring_interval_fortnights
            Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.HALF_YEARLY ->
                R.plurals.recurring_interval_months

            Frequency.YEARLY -> R.plurals.recurring_interval_years
        },
        interval,
        interval,
    )
}

@Composable
private fun reminderLabel(days: Int): String = when (days) {
    0 -> stringResource(R.string.recurring_reminder_same_day)
    else -> pluralStringResource(R.plurals.recurring_reminder_days_before, days, days)
}

@Composable
private fun errorMessage(code: String): String = stringResource(
    when (code) {
        "name_required" -> R.string.validation_name_required
        "amount_required" -> R.string.validation_amount_required
        "amount_invalid" -> R.string.validation_amount_invalid
        "amount_zero" -> R.string.validation_amount_zero
        "account_required" -> R.string.validation_account_required
        "transfer_required" -> R.string.validation_transfer_account_required
        "transfer_same" -> R.string.validation_transfer_same_account
        "end_before_start" -> R.string.validation_end_before_start
        else -> R.string.state_error_generic
    },
)

private val INTERVAL_OPTIONS = listOf(1, 2, 3, 4, 6, 12)

/** Zero means "on the day itself", which is a real preference and not the same as no reminder. */
private val REMINDER_OPTIONS = listOf(0, 1, 2, 3, 7)
