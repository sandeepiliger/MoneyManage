package ai.labs32.khaata.feature.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.CategoryIcons
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.KhaataCardTier
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.core.calc.CommitmentCalculator
import ai.labs32.khaata.core.calc.SubscriptionTotals
import ai.labs32.khaata.data.repository.SubscriptionRepository
import ai.labs32.khaata.feature.recurring.frequencyLabel
import ai.labs32.khaata.feature.shared.ChipSelector
import ai.labs32.khaata.feature.shared.DateField
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
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** A subscription with the derived facts the card shows. */
data class SubscriptionItem(
    val subscription: Subscription,
    val daysUntilRenewal: Long,
    val monthlyEquivalent: Money,
    val yearlyEquivalent: Money,
    val categoryName: String?,
)

data class SubscriptionEditorState(
    val id: String? = null,
    val name: String = "",
    val amountText: String = "",
    val cycle: Frequency = Frequency.MONTHLY,
    val nextPaymentDate: LocalDate = LocalDate.now(),
    val categoryId: String? = null,
    val accountId: String? = null,
    val reminderDaysBefore: Int = 2,
    val notes: String = "",
    val errors: Map<String, String> = emptyMap(),
) {
    val isEditing: Boolean get() = id != null
}

/** Just enough of a subscription to name it in a confirmation dialog. */
data class SubscriptionReference(val id: String, val name: String)

data class SubscriptionsUiState(
    val isLoading: Boolean = true,
    val active: List<SubscriptionItem> = emptyList(),
    val cancelled: List<SubscriptionItem> = emptyList(),
    val cost: SubscriptionTotals? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val editor: SubscriptionEditorState? = null,
    val cancelTarget: SubscriptionReference? = null,
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionsUiState())
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

    init {
        combine(
            subscriptionRepository.observeAll(),
            subscriptionRepository.observeCostSummary(),
            accountRepository.observeActive(),
            categoryRepository.observeActive(),
        ) { subscriptions, cost, accounts, categories ->
            val today = clock.today()
            val categoryNames = categories.associate { it.id to it.name }

            fun item(subscription: Subscription) = SubscriptionItem(
                subscription = subscription,
                daysUntilRenewal = ChronoUnit.DAYS.between(today, subscription.nextPaymentDate),
                monthlyEquivalent = CommitmentCalculator.perMonth(
                    subscription.amount,
                    subscription.cycle,
                ),
                yearlyEquivalent = CommitmentCalculator.perYear(
                    subscription.amount,
                    subscription.cycle,
                ),
                categoryName = subscription.categoryId?.let { categoryNames[it] },
            )

            val (active, cancelled) = subscriptions.partition { it.isActive && it.cancelledOn == null }
            SubscriptionsUiState(
                isLoading = false,
                // Soonest renewal first: the one about to charge is the one worth a second look.
                active = active.map(::item).sortedBy { it.subscription.nextPaymentDate },
                cancelled = cancelled.map(::item)
                    .sortedByDescending { it.subscription.cancelledOn ?: LocalDate.MIN },
                cost = cost,
                accounts = accounts,
                categories = categories,
            )
        }
            .onEach { fresh ->
                _uiState.update { current ->
                    fresh.copy(editor = current.editor, cancelTarget = current.cancelTarget)
                }
            }
            .launchIn(viewModelScope)
    }

    // ---- Editor ------------------------------------------------------------------------------

    fun startCreate() = _uiState.update { state ->
        state.copy(
            editor = SubscriptionEditorState(
                accountId = state.accounts.firstOrNull()?.id,
                nextPaymentDate = clock.today().plusMonths(1),
            ),
        )
    }

    fun startEdit(subscription: Subscription) = _uiState.update {
        it.copy(
            editor = SubscriptionEditorState(
                id = subscription.id,
                name = subscription.name,
                amountText = subscription.amount.toPlainString(),
                cycle = subscription.cycle,
                nextPaymentDate = subscription.nextPaymentDate,
                categoryId = subscription.categoryId,
                accountId = subscription.accountId,
                reminderDaysBefore = subscription.reminderDaysBefore,
                notes = subscription.notes.orEmpty(),
            ),
        )
    }

    fun dismissEditor() = _uiState.update { it.copy(editor = null) }

    fun editName(value: String) = updateEditor { it.copy(name = value, errors = it.errors - "name") }

    fun editAmount(value: String) = updateEditor {
        it.copy(
            amountText = value.filter { ch -> ch.isDigit() || ch == '.' },
            errors = it.errors - "amount",
        )
    }

    fun editCycle(cycle: Frequency) = updateEditor { it.copy(cycle = cycle) }

    fun editNextPayment(date: LocalDate) =
        updateEditor { it.copy(nextPaymentDate = date, errors = it.errors - "nextPayment") }

    fun editCategory(id: String) =
        updateEditor { it.copy(categoryId = if (it.categoryId == id) null else id) }

    fun editAccount(id: String) =
        updateEditor { it.copy(accountId = if (it.accountId == id) null else id) }

    fun editReminderDays(days: Int) = updateEditor { it.copy(reminderDaysBefore = days) }

    fun editNotes(value: String) = updateEditor { it.copy(notes = value) }

    fun save() {
        val editor = _uiState.value.editor ?: return
        val currency = _uiState.value.accounts
            .firstOrNull { it.id == editor.accountId }?.currency
            ?: CurrencyCode.DEFAULT
        val amount = MoneyParser.parse(editor.amountText, currency)

        val errors = buildMap {
            if (editor.name.isBlank()) put("name", "name_required")
            when {
                editor.amountText.isBlank() -> put("amount", "amount_required")
                amount == null -> put("amount", "amount_invalid")
                !amount.isPositive -> put("amount", "amount_zero")
            }
        }
        if (errors.isNotEmpty()) {
            updateEditor { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            val existing = editor.id?.let { subscriptionRepository.findById(it) }
            if (existing != null) {
                subscriptionRepository.update(
                    existing.copy(
                        name = editor.name.trim(),
                        amount = amount!!,
                        cycle = editor.cycle,
                        nextPaymentDate = editor.nextPaymentDate,
                        categoryId = editor.categoryId,
                        accountId = editor.accountId,
                        reminderDaysBefore = editor.reminderDaysBefore,
                        notes = editor.notes.trim().ifBlank { null },
                    ),
                )
            } else {
                subscriptionRepository.create(
                    name = editor.name,
                    amount = amount!!,
                    cycle = editor.cycle,
                    nextPaymentDate = editor.nextPaymentDate,
                    categoryId = editor.categoryId,
                    accountId = editor.accountId,
                    reminderDaysBefore = editor.reminderDaysBefore,
                    notes = editor.notes.trim().ifBlank { null },
                )
            }
            _uiState.update { it.copy(editor = null) }
        }
    }

    // ---- Cancelling --------------------------------------------------------------------------

    fun requestCancel(id: String, name: String) =
        _uiState.update { it.copy(cancelTarget = SubscriptionReference(id, name)) }

    fun dismissCancel() = _uiState.update { it.copy(cancelTarget = null) }

    /**
     * Marks a service cancelled rather than deleting it.
     *
     * The point of tracking subscriptions is seeing what they cost over a year, and that number is
     * wrong the moment a cancelled service disappears from the record.
     */
    fun confirmCancel() {
        val target = _uiState.value.cancelTarget ?: return
        viewModelScope.launch {
            subscriptionRepository.cancel(target.id)
            _uiState.update { it.copy(cancelTarget = null, editor = null) }
        }
    }

    fun resume(subscription: Subscription) {
        viewModelScope.launch {
            subscriptionRepository.update(
                subscription.copy(cancelledOn = null, isActive = true),
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            subscriptionRepository.delete(id)
            _uiState.update { it.copy(editor = null) }
        }
    }

    private fun updateEditor(transform: (SubscriptionEditorState) -> SubscriptionEditorState) =
        _uiState.update { state -> state.copy(editor = state.editor?.let(transform)) }
}

/**
 * Subscriptions.
 *
 * The yearly total is given the same prominence as the monthly one, because ₹649 a month and
 * ₹7,788 a year are the same fact and only the second one changes anybody's mind. The reference
 * apps tend to show the monthly figure alone, which is the number that makes a subscription feel
 * affordable rather than the one that makes it feel like a decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.subscriptions_title)) },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::startCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.subscriptions_add)) },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            state.active.isEmpty() && state.cancelled.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Subscriptions,
                title = stringResource(R.string.subscriptions_empty_title),
                description = stringResource(R.string.subscriptions_empty_body),
                actionLabel = stringResource(R.string.subscriptions_add),
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
                state.cost?.let { cost ->
                    item(key = "cost") { CostCard(cost) }
                }

                items(state.active, key = { it.subscription.id }) { item ->
                    SubscriptionCard(
                        item = item,
                        onClick = { viewModel.startEdit(item.subscription) },
                    )
                }

                if (state.cancelled.isNotEmpty()) {
                    item(key = "cancelled-header") {
                        Text(
                            text = stringResource(R.string.subscriptions_cancelled),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = KhaataTheme.spacing.small),
                        )
                    }
                    items(state.cancelled, key = { it.subscription.id }) { item ->
                        CancelledCard(
                            item = item,
                            onResume = { viewModel.resume(item.subscription) },
                            onDelete = { viewModel.delete(item.subscription.id) },
                        )
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        SubscriptionEditorSheet(
            editor = editor,
            accounts = state.accounts,
            categories = state.categories,
            onDismiss = viewModel::dismissEditor,
            viewModel = viewModel,
        )
    }

    state.cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCancel,
            title = { Text(stringResource(R.string.subscriptions_cancel_title, target.name)) },
            // Stated plainly, because a user could reasonably expect this to cancel the service
            // itself. It does not, and it must not pretend to.
            text = { Text(stringResource(R.string.subscriptions_cancel_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCancel) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CostCard(cost: SubscriptionTotals) {
    KhaataCard(tier = KhaataCardTier.Emphasized) {
        CardHeader(
            title = pluralStringResource(
                R.plurals.subscriptions_tracked_count,
                cost.count,
                cost.count,
            ),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.default))
        StatPair(
            leadingLabel = stringResource(R.string.subscriptions_total_monthly),
            leadingValue = {
                MoneyText(money = cost.perMonth, style = KhaataTextStyles.amountMedium)
            },
            // The yearly figure is the hero, not a footnote.
            trailingLabel = stringResource(R.string.subscriptions_total_yearly),
            trailingValue = {
                MoneyText(money = cost.perYear, style = KhaataTextStyles.amountHero)
            },
        )
    }
}

@Composable
private fun SubscriptionCard(item: SubscriptionItem, onClick: () -> Unit) {
    val subscription = item.subscription
    val dateFormatter = DateTimeFormatter.ofPattern("d MMM")
    val isRenewingSoon = item.daysUntilRenewal in 0..RENEWAL_SOON_DAYS

    KhaataCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorBadge(
                icon = CategoryIcons[subscription.iconKey],
                colorSeed = subscription.colorSeed,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = subscription.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        frequencyLabel(subscription.cycle, 1),
                        item.categoryName,
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                MoneyText(money = subscription.amount, style = KhaataTextStyles.amountMedium)
                Text(
                    text = stringResource(
                        R.string.subscriptions_next_payment_on,
                        subscription.nextPaymentDate.format(dateFormatter),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(KhaataTheme.spacing.small))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Repeating the yearly cost on every card is the whole argument of this screen:
            // ₹199 a month reads as nothing, ₹2,388 a year reads as a decision.
            Text(
                text = stringResource(
                    R.string.subscriptions_yearly_cost,
                    MoneyFormatter.plain(item.yearlyEquivalent),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (isRenewingSoon) {
                Icon(
                    Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = KhaataTheme.money.warning,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.subscriptions_renewing_soon),
                    style = MaterialTheme.typography.labelSmall,
                    color = KhaataTheme.money.warning,
                )
            }
        }
    }
}

@Composable
private fun CancelledCard(
    item: SubscriptionItem,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    KhaataCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.subscription.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.subscriptions_cancelled_on,
                        item.subscription.cancelledOn
                            ?.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
                            .orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onResume) { Text(stringResource(R.string.action_restore)) }
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionEditorSheet(
    editor: SubscriptionEditorState,
    accounts: List<Account>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    viewModel: SubscriptionsViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = KhaataTheme.spacing
    val currency = accounts.firstOrNull { it.id == editor.accountId }?.currency
        ?: CurrencyCode.DEFAULT
    val parsedAmount = MoneyParser.parse(editor.amountText, currency)

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
                    if (editor.isEditing) R.string.subscriptions_edit else R.string.subscriptions_add,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = editor.name,
                onValueChange = viewModel::editName,
                label = { Text(stringResource(R.string.subscriptions_name)) },
                singleLine = true,
                isError = editor.errors.containsKey("name"),
                supportingText = editor.errors["name"]?.let { { Text(errorMessage(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = editor.amountText,
                onValueChange = viewModel::editAmount,
                label = { Text(stringResource(R.string.transaction_amount)) },
                prefix = { Text(currency.symbol) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = editor.errors.containsKey("amount"),
                supportingText = editor.errors["amount"]?.let { { Text(errorMessage(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )

            ChipSelector(
                label = stringResource(R.string.subscriptions_billing_cycle),
                options = Frequency.entries,
                selected = editor.cycle,
                optionLabel = { frequencyLabel(it, 1) },
                onSelect = viewModel::editCycle,
            )

            // The yearly consequence is shown while the form is still open, not after saving,
            // because that is when it can still change the answer.
            if (parsedAmount != null && parsedAmount.isPositive) {
                val yearly = parsedAmount.times(
                    java.math.BigDecimal(editor.cycle.occurrencesPerYear.toString()),
                )
                Text(
                    text = stringResource(
                        R.string.subscriptions_yearly_preview,
                        MoneyFormatter.plain(yearly),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            DateField(
                label = stringResource(R.string.subscriptions_next_payment),
                date = editor.nextPaymentDate,
                onPick = viewModel::editNextPayment,
            )

            ChipSelector(
                label = stringResource(R.string.transaction_category),
                options = categories,
                selected = categories.firstOrNull { it.id == editor.categoryId },
                optionLabel = { it.name },
                optionKey = { it.id },
                onSelect = { viewModel.editCategory(it.id) },
            )

            ChipSelector(
                label = stringResource(R.string.transaction_account),
                options = accounts,
                selected = accounts.firstOrNull { it.id == editor.accountId },
                optionLabel = { it.name },
                optionKey = { it.id },
                onSelect = { viewModel.editAccount(it.id) },
            )

            ChipSelector(
                label = stringResource(R.string.recurring_reminder_days),
                options = REMINDER_OPTIONS,
                selected = editor.reminderDaysBefore,
                optionLabel = { days ->
                    if (days == 0) {
                        stringResource(R.string.recurring_reminder_same_day)
                    } else {
                        pluralStringResource(R.plurals.recurring_reminder_days_before, days, days)
                    }
                },
                onSelect = viewModel::editReminderDays,
            )

            OutlinedTextField(
                value = editor.notes,
                onValueChange = viewModel::editNotes,
                label = { Text(stringResource(R.string.transaction_note)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editor.isEditing) {
                    TextButton(
                        onClick = { viewModel.requestCancel(editor.id!!, editor.name) },
                    ) { Text(stringResource(R.string.subscriptions_cancel)) }
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
private fun errorMessage(code: String): String = stringResource(
    when (code) {
        "name_required" -> R.string.validation_name_required
        "amount_required" -> R.string.validation_amount_required
        "amount_invalid" -> R.string.validation_amount_invalid
        "amount_zero" -> R.string.validation_amount_zero
        else -> R.string.state_error_generic
    },
)

private val REMINDER_OPTIONS = listOf(0, 1, 2, 3, 7)

/** Close enough to matter — a week is long enough to cancel before being charged. */
private const val RENEWAL_SOON_DAYS = 7L
