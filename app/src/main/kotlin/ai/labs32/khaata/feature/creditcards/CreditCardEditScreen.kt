package ai.labs32.khaata.feature.creditcards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.CreditCard
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.core.validation.CreditCardValidator
import ai.labs32.khaata.core.validation.ValidationError
import ai.labs32.khaata.core.validation.ValidationResult
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CreditCardRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.feature.shared.ChipSelector
import ai.labs32.khaata.feature.shared.ColorPicker
import ai.labs32.khaata.feature.shared.ToggleRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreditCardEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val cardName: String = "",
    val issuer: String = "",
    val creditLimitText: String = "",
    val statementDayText: String = "",
    val dueDayText: String = "",
    val minimumDuePercentText: String = "5",
    val minimumDueFloorText: String = "200",
    val lastFourDigits: String = "",
    val colorSeed: Int = 0,
    val isActive: Boolean = true,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    /** Card-type accounts not already backing another card, offered for linking. */
    val linkableAccounts: List<Account> = emptyList(),
    /** Null means "create a new account for this card". */
    val selectedAccountId: String? = null,
    /** The account already backing this card, when editing. */
    val linkedAccountName: String? = null,
    val errors: List<ValidationError> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val showDeleteConfirm: Boolean = false,
) {
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message
}

/**
 * Creating and editing a credit card.
 *
 * [CreditCardRepository] had `create`, `update` and `delete` with no callers, and
 * `Routes.ADD_CREDIT_CARD` was declared and never registered or navigated to, so the only card
 * that could exist was the demo seed.
 *
 * A card must be backed by an account — that account's balance *is* the outstanding amount, which
 * is why `CreditCard.accountId` is not nullable. Requiring the user to go and create a credit-card
 * account first would be a dead end dressed up as a prerequisite, so this screen creates one when
 * there is nothing suitable to link to, and offers existing unlinked card accounts when there are.
 *
 * On edit the link is shown but not changeable: repointing a card at a different account would
 * silently change what every past transaction on it meant.
 */
@HiltViewModel
class CreditCardEditViewModel @Inject constructor(
    private val creditCardRepository: CreditCardRepository,
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreditCardEditUiState())
    val uiState: StateFlow<CreditCardEditUiState> = _uiState.asStateFlow()

    private var editing: CreditCard? = null

    fun initialise(cardId: String?) {
        if (!_uiState.value.isLoading) return

        viewModelScope.launch {
            val currency = profileRepository.currency()
            val card = cardId?.let { creditCardRepository.findById(it) }
            editing = card

            if (card != null) {
                val account = accountRepository.findById(card.accountId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isEditing = true,
                        cardName = card.cardName,
                        issuer = card.issuer,
                        creditLimitText = card.creditLimit.toPlainString(),
                        statementDayText = card.statementDayOfMonth.toString(),
                        dueDayText = card.dueDayOfMonth.toString(),
                        minimumDuePercentText = card.minimumDuePercent.toPlainString(),
                        minimumDueFloorText = card.minimumDueFloor.toPlainString(),
                        lastFourDigits = card.lastFourDigits.orEmpty(),
                        colorSeed = card.colorSeed,
                        isActive = card.isActive,
                        currency = currency,
                        linkedAccountName = account?.name,
                    )
                }
                return@launch
            }

            // Only card-type accounts, and only those no other card already claims -- offering an
            // account that is already backing a different card would produce two cards reporting
            // the same balance.
            val taken = creditCardRepository.getAll().map { existing -> existing.accountId }.toSet()
            val linkable = accountRepository.observeActive().first()
                .filter { it.type == AccountType.CREDIT_CARD && it.id !in taken }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    currency = currency,
                    linkableAccounts = linkable,
                    // Defaults to creating a fresh account, which is right for the common case of
                    // someone adding their first card.
                    selectedAccountId = null,
                )
            }
        }
    }

    fun onCardNameChange(value: String) = _uiState.update {
        it.copy(cardName = value, errors = it.errors.filterNot { e -> e.field == "cardName" })
    }

    fun onIssuerChange(value: String) = _uiState.update {
        it.copy(issuer = value, errors = it.errors.filterNot { e -> e.field == "issuer" })
    }

    fun onCreditLimitChange(text: String) = _uiState.update {
        it.copy(
            creditLimitText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "creditLimit" },
        )
    }

    fun onStatementDayChange(text: String) = _uiState.update {
        it.copy(
            statementDayText = text.filter { c -> c.isDigit() }.take(2),
            errors = it.errors.filterNot { e -> e.field == "statementDay" },
        )
    }

    fun onDueDayChange(text: String) = _uiState.update {
        it.copy(
            dueDayText = text.filter { c -> c.isDigit() }.take(2),
            errors = it.errors.filterNot { e -> e.field == "dueDay" },
        )
    }

    fun onMinimumDueChange(text: String) = _uiState.update {
        it.copy(
            minimumDuePercentText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "minimumDue" },
        )
    }

    fun onMinimumFloorChange(text: String) = _uiState.update {
        it.copy(minimumDueFloorText = text.filter { c -> c.isDigit() || c == '.' })
    }

    fun onLastFourChange(text: String) = _uiState.update {
        it.copy(
            // Four digits, never more: the field cannot hold a full card number, so one cannot be
            // pasted in and stored by accident.
            lastFourDigits = text.filter { c -> c.isDigit() }.take(4),
            errors = it.errors.filterNot { e -> e.field == "lastFour" },
        )
    }

    fun onAccountChange(accountId: String?) = _uiState.update {
        it.copy(selectedAccountId = accountId)
    }

    fun onColorChange(seed: Int) = _uiState.update { it.copy(colorSeed = seed) }

    fun onActiveChange(active: Boolean) = _uiState.update { it.copy(isActive = active) }

    fun requestDelete() = _uiState.update { it.copy(showDeleteConfirm = true) }

    fun dismissDelete() = _uiState.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        val id = editing?.id ?: return
        viewModelScope.launch {
            // The backing account is deliberately left alone: it holds the transaction history,
            // and deleting the card record is not a statement about the spending on it.
            runCatching { creditCardRepository.delete(id) }
                .onFailure { KhaataLog.e(TAG, "Failed to delete credit card", it) }
            _uiState.update { it.copy(showDeleteConfirm = false, saved = true) }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return

        val validation = CreditCardValidator.validate(
            cardName = state.cardName,
            issuer = state.issuer,
            creditLimitText = state.creditLimitText,
            statementDayText = state.statementDayText,
            dueDayText = state.dueDayText,
            minimumDuePercentText = state.minimumDuePercentText,
            lastFourDigits = state.lastFourDigits,
            currency = state.currency,
        )
        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(errors = validation.errors) }
            return
        }

        val limit = MoneyParser.parse(state.creditLimitText, state.currency) ?: return
        val statementDay = state.statementDayText.trim().toIntOrNull() ?: return
        val dueDay = state.dueDayText.trim().toIntOrNull() ?: return
        val minimumPercent = state.minimumDuePercentText.trim().toBigDecimalOrNull() ?: return
        val floor = MoneyParser.parse(state.minimumDueFloorText, state.currency)
            ?: Money.zero(state.currency)
        _uiState.update { it.copy(isSaving = true, errors = emptyList()) }

        viewModelScope.launch {
            val existing = editing
            val result = runCatching {
                val accountId = existing?.accountId
                    ?: state.selectedAccountId
                    ?: accountRepository.create(
                        name = state.cardName.trim(),
                        type = AccountType.CREDIT_CARD,
                        // A card's outstanding amount comes from its transactions, so the account
                        // starts empty rather than pretending to know a balance.
                        openingBalance = Money.zero(state.currency),
                        currency = state.currency,
                        institution = state.issuer.trim(),
                        maskedIdentifier = state.lastFourDigits.takeIf { it.isNotBlank() },
                        colorSeed = state.colorSeed,
                    )

                val card = CreditCard(
                    id = existing?.id.orEmpty(),
                    accountId = accountId,
                    cardName = state.cardName.trim(),
                    issuer = state.issuer.trim(),
                    creditLimit = limit,
                    statementDayOfMonth = statementDay,
                    dueDayOfMonth = dueDay,
                    minimumDuePercent = minimumPercent,
                    minimumDueFloor = floor,
                    lastFourDigits = state.lastFourDigits.takeIf { it.isNotBlank() },
                    colorSeed = state.colorSeed,
                    isActive = state.isActive,
                )

                if (existing != null) {
                    creditCardRepository.update(card)
                } else {
                    creditCardRepository.create(card)
                }
            }

            result
                .onSuccess { _uiState.update { it.copy(isSaving = false, saved = true) } }
                .onFailure { error ->
                    KhaataLog.e(TAG, "Failed to save credit card", error)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errors = listOf(
                                ValidationError("form", "save_failed", "We could not save that. Please try again."),
                            ),
                        )
                    }
                }
        }
    }

    private companion object {
        const val TAG = "CreditCardEditViewModel"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditCardEditScreen(
    cardId: String?,
    onDone: () -> Unit,
    viewModel: CreditCardEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(cardId) { viewModel.initialise(cardId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.cards_edit
                            else R.string.cards_add,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = viewModel::requestDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }

        val spacing = KhaataTheme.spacing
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(spacing.small))

            OutlinedTextField(
                value = state.cardName,
                onValueChange = viewModel::onCardNameChange,
                label = { Text(stringResource(R.string.cards_name)) },
                singleLine = true,
                isError = state.errorFor("cardName") != null,
                supportingText = state.errorFor("cardName")?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.issuer,
                onValueChange = viewModel::onIssuerChange,
                label = { Text(stringResource(R.string.cards_issuer)) },
                singleLine = true,
                isError = state.errorFor("issuer") != null,
                supportingText = state.errorFor("issuer")?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.creditLimitText,
                onValueChange = viewModel::onCreditLimitChange,
                label = { Text(stringResource(R.string.cards_limit)) },
                prefix = { Text(state.currency.symbol) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.errorFor("creditLimit") != null,
                supportingText = state.errorFor("creditLimit")?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = state.statementDayText,
                    onValueChange = viewModel::onStatementDayChange,
                    label = { Text(stringResource(R.string.cards_statement_day)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.errorFor("statementDay") != null,
                    supportingText = state.errorFor("statementDay")?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.small))
                OutlinedTextField(
                    value = state.dueDayText,
                    onValueChange = viewModel::onDueDayChange,
                    label = { Text(stringResource(R.string.cards_due_day)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.errorFor("dueDay") != null,
                    supportingText = state.errorFor("dueDay")?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = stringResource(R.string.cards_days_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.lastFourDigits,
                onValueChange = viewModel::onLastFourChange,
                label = { Text(stringResource(R.string.cards_last_four)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.errorFor("lastFour") != null,
                supportingText = {
                    Text(state.errorFor("lastFour") ?: stringResource(R.string.cards_last_four_help))
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = state.minimumDuePercentText,
                    onValueChange = viewModel::onMinimumDueChange,
                    label = { Text(stringResource(R.string.cards_minimum_percent)) },
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = state.errorFor("minimumDue") != null,
                    supportingText = state.errorFor("minimumDue")?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.small))
                OutlinedTextField(
                    value = state.minimumDueFloorText,
                    onValueChange = viewModel::onMinimumFloorChange,
                    label = { Text(stringResource(R.string.cards_minimum_floor)) },
                    prefix = { Text(state.currency.symbol) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            // Which account carries the outstanding balance. Shown on edit and not changeable:
            // repointing a card would silently change what its past transactions meant.
            if (state.isEditing) {
                state.linkedAccountName?.let { name ->
                    Spacer(Modifier.height(spacing.default))
                    Text(
                        text = stringResource(R.string.cards_linked_account, name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.linkableAccounts.isNotEmpty()) {
                Spacer(Modifier.height(spacing.default))
                ChipSelector(
                    label = stringResource(R.string.cards_account),
                    // Null is the "create a new one" option and comes first, since that is the
                    // common case; existing unlinked card accounts follow.
                    options = listOf<String?>(null) + state.linkableAccounts.map { it.id },
                    selected = state.selectedAccountId,
                    optionLabel = { id ->
                        if (id == null) {
                            stringResource(R.string.cards_account_new)
                        } else {
                            state.linkableAccounts.firstOrNull { it.id == id }?.name.orEmpty()
                        }
                    },
                    onSelect = viewModel::onAccountChange,
                    optionKey = { it ?: "new" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(spacing.default))

            ColorPicker(
                label = stringResource(R.string.cards_colour),
                selected = state.colorSeed,
                icon = Icons.Outlined.CreditCard,
                onSelect = viewModel::onColorChange,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.isEditing) {
                Spacer(Modifier.height(spacing.default))

                ToggleRow(
                    title = stringResource(R.string.cards_active),
                    subtitle = stringResource(R.string.cards_active_help),
                ) {
                    Switch(checked = state.isActive, onCheckedChange = viewModel::onActiveChange)
                }
            }

            state.errorFor("form")?.let { message ->
                Spacer(Modifier.height(spacing.small))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(spacing.large))

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }

            Spacer(Modifier.height(spacing.xlarge))
        }
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.cards_delete_title)) },
            text = { Text(stringResource(R.string.cards_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.action_delete))
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
