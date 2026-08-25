package ai.labs32.khaata.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.core.validation.AccountValidator
import ai.labs32.khaata.core.validation.ValidationError
import ai.labs32.khaata.core.validation.ValidationResult
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.EntitlementRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val name: String = "",
    val institution: String = "",
    val type: AccountType = AccountType.BANK,
    val openingBalanceText: String = "",
    val maskedIdentifier: String = "",
    val includeInNetWorth: Boolean = true,
    val includeInAvailableBalance: Boolean = true,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val existingNames: Set<String> = emptySet(),
    val errors: List<ValidationError> = emptyList(),
    val blockedByLimit: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
) {
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message
}

@HiltViewModel
class AccountEditViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
    private val entitlementRepository: EntitlementRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountEditUiState())
    val uiState: StateFlow<AccountEditUiState> = _uiState.asStateFlow()

    private var editing: Account? = null

    fun initialise(accountId: String?) {
        if (!_uiState.value.isLoading) return

        viewModelScope.launch {
            val accounts = accountRepository.observeAll().first()
            val currency = profileRepository.currency()

            if (accountId == null) {
                val activeCount = accounts.count { !it.isArchived }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currency = currency,
                        existingNames = accounts.map { a -> a.name }.toSet(),
                        blockedByLimit = !entitlementRepository.canAddAccount(activeCount),
                    )
                }
            } else {
                val account = accounts.firstOrNull { it.id == accountId }
                editing = account
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isEditing = account != null,
                        name = account?.name.orEmpty(),
                        institution = account?.institution.orEmpty(),
                        type = account?.type ?: AccountType.BANK,
                        openingBalanceText = account?.openingBalance?.toPlainString().orEmpty(),
                        maskedIdentifier = account?.maskedIdentifier.orEmpty(),
                        includeInNetWorth = account?.includeInNetWorth ?: true,
                        includeInAvailableBalance = account?.includeInAvailableBalance ?: true,
                        currency = account?.currency ?: currency,
                        existingNames = accounts.map { a -> a.name }.toSet(),
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) =
        _uiState.update { it.copy(name = value, errors = it.errors.filterNot { e -> e.field == "name" }) }

    fun onInstitutionChange(value: String) = _uiState.update { it.copy(institution = value) }

    fun onTypeChange(type: AccountType) = _uiState.update {
        it.copy(
            type = type,
            // The sensible defaults differ by type: a card is a liability and not spendable, an
            // investment account counts towards net worth but not towards today's spending.
            includeInAvailableBalance = type.countsAsSpendableByDefault,
        )
    }

    fun onOpeningBalanceChange(value: String) = _uiState.update {
        it.copy(
            openingBalanceText = value.filter { c -> c.isDigit() || c == '.' || c == '-' },
            errors = it.errors.filterNot { e -> e.field == "openingBalance" },
        )
    }

    /**
     * Accepts only the last four digits.
     *
     * Filtered as it is typed rather than validated on save, so a full card number pasted into
     * the field cannot reach the database even for an instant.
     */
    fun onMaskedIdentifierChange(value: String) = _uiState.update {
        it.copy(maskedIdentifier = value.filter { c -> c.isDigit() }.takeLast(4))
    }

    fun onIncludeInNetWorthChange(value: Boolean) =
        _uiState.update { it.copy(includeInNetWorth = value) }

    fun onIncludeInAvailableChange(value: Boolean) =
        _uiState.update { it.copy(includeInAvailableBalance = value) }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return

        val validation = AccountValidator.validate(
            name = state.name,
            openingBalanceText = state.openingBalanceText,
            currency = state.currency,
            existingNames = state.existingNames,
            isEditingExisting = state.isEditing,
        )
        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(errors = validation.errors) }
            return
        }

        _uiState.update { it.copy(isSaving = true, errors = emptyList()) }

        viewModelScope.launch {
            try {
                val isNegative = state.openingBalanceText.trim().startsWith("-")
                val magnitude = MoneyParser.parse(
                    state.openingBalanceText.removePrefix("-"),
                    state.currency,
                ) ?: Money.zero(state.currency)
                val openingBalance = if (isNegative) -magnitude else magnitude

                val existing = editing
                if (existing != null) {
                    accountRepository.update(
                        existing.copy(
                            name = state.name.trim(),
                            institution = state.institution.trim().takeIf { it.isNotBlank() },
                            type = state.type,
                            openingBalance = openingBalance,
                            maskedIdentifier = state.maskedIdentifier.takeIf { it.length == 4 },
                            includeInNetWorth = state.includeInNetWorth,
                            includeInAvailableBalance = state.includeInAvailableBalance,
                        ),
                    )
                } else {
                    accountRepository.create(
                        name = state.name.trim(),
                        type = state.type,
                        openingBalance = openingBalance,
                        currency = state.currency,
                        institution = state.institution.trim().takeIf { it.isNotBlank() },
                        maskedIdentifier = state.maskedIdentifier.takeIf { it.length == 4 },
                        includeInNetWorth = state.includeInNetWorth,
                        includeInAvailableBalance = state.includeInAvailableBalance,
                    )
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Failed to save account", error)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errors = listOf(
                            ValidationError("form", "save_failed", "We could not save that."),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "AccountEditViewModel"
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AccountEditScreen(
    accountId: String?,
    onDone: () -> Unit,
    viewModel: AccountEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = KhaataTheme.spacing

    LaunchedEffect(accountId) { viewModel.initialise(accountId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.accounts_edit else R.string.accounts_add,
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
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal),
        ) {
            if (state.blockedByLimit) {
                Text(
                    text = stringResource(R.string.accounts_limit_reached_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = spacing.small),
                )
            }

            Text(
                text = stringResource(R.string.accounts_type),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountType.ONBOARDING_ORDER.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.onTypeChange(type) },
                        label = { Text(accountTypeLabel(type)) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.accounts_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.errorFor("name") != null,
                supportingText = state.errorFor("name")?.let { { Text(it) } },
            )

            Spacer(Modifier.height(spacing.medium))

            OutlinedTextField(
                value = state.institution,
                onValueChange = viewModel::onInstitutionChange,
                label = { Text(stringResource(R.string.accounts_institution)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(spacing.medium))

            OutlinedTextField(
                value = state.openingBalanceText,
                onValueChange = viewModel::onOpeningBalanceChange,
                label = { Text(stringResource(R.string.accounts_opening_balance)) },
                prefix = { Text(state.currency.symbol) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.errorFor("openingBalance") != null,
                supportingText = state.errorFor("openingBalance")?.let { { Text(it) } },
            )

            Spacer(Modifier.height(spacing.medium))

            OutlinedTextField(
                value = state.maskedIdentifier,
                onValueChange = viewModel::onMaskedIdentifierChange,
                label = { Text(stringResource(R.string.accounts_last_four)) },
                supportingText = { Text(stringResource(R.string.accounts_last_four_help)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(spacing.default))

            ToggleRow(
                label = stringResource(R.string.accounts_include_net_worth),
                checked = state.includeInNetWorth,
                onCheckedChange = viewModel::onIncludeInNetWorthChange,
            )
            ToggleRow(
                label = stringResource(R.string.accounts_include_available),
                checked = state.includeInAvailableBalance,
                onCheckedChange = viewModel::onIncludeInAvailableChange,
            )

            state.errorFor("form")?.let {
                Spacer(Modifier.height(spacing.small))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(spacing.large))

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving && !state.blockedByLimit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.action_save)) }

            Spacer(Modifier.height(spacing.default))

            Text(
                text = stringResource(R.string.accounts_never_credentials),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(spacing.xlarge))
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun accountTypeLabel(type: AccountType): String = stringResource(
    when (type) {
        AccountType.CASH -> R.string.account_type_cash
        AccountType.BANK -> R.string.account_type_bank
        AccountType.SAVINGS -> R.string.account_type_savings
        AccountType.CURRENT -> R.string.account_type_current
        AccountType.CREDIT_CARD -> R.string.account_type_credit_card
        AccountType.WALLET -> R.string.account_type_wallet
        AccountType.INVESTMENT -> R.string.account_type_investment
        AccountType.LOAN -> R.string.account_type_loan
        AccountType.OTHER -> R.string.account_type_other
    },
)
