package ai.labs32.khaata.feature.loans

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
import androidx.compose.material.icons.outlined.AccountBalance
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
import ai.labs32.khaata.core.calc.LoanCalculator
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Loan
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.core.validation.LoanValidator
import ai.labs32.khaata.core.validation.ValidationError
import ai.labs32.khaata.core.validation.ValidationResult
import ai.labs32.khaata.data.repository.LoanRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.feature.shared.ColorPicker
import ai.labs32.khaata.feature.shared.DateField
import ai.labs32.khaata.feature.shared.ToggleRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class LoanEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val name: String = "",
    val lender: String = "",
    val principalText: String = "",
    val ratePercentText: String = "",
    val tenureMonthsText: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val emiDayText: String = "",
    val emiOverrideText: String = "",
    val colorSeed: Int = 0,
    val isClosed: Boolean = false,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val errors: List<ValidationError> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val showDeleteConfirm: Boolean = false,
) {
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message

    /**
     * The instalment these terms imply, or null while they are incomplete.
     *
     * Shown live because the EMI is the number people actually recognise: someone knows their
     * instalment is about ₹12,500 long before they could tell you the tenure in months, so a
     * figure that comes out wrong catches a mistyped rate or tenure on the spot.
     */
    val emiPreview: Money?
        get() {
            val principal = MoneyParser.parse(principalText, currency)?.takeIf { it.isPositive }
                ?: return null
            val rate = ratePercentText.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
                ?: return null
            val tenure = tenureMonthsText.trim().toIntOrNull()?.takeIf { it in 1..600 }
                ?: return null
            return runCatching { LoanCalculator.computeEmi(principal, rate, tenure) }.getOrNull()
        }
}

/**
 * Creating and editing a loan.
 *
 * [LoanRepository] had `create`, `update` and `delete` with no callers anywhere in the app, and
 * `Routes.ADD_LOAN` was declared but never registered as a destination or navigated to. The loans
 * screen listed loans and offered no way to add one, so the only loan that could exist was the
 * demo seed. This is the screen behind that route.
 */
@HiltViewModel
class LoanEditViewModel @Inject constructor(
    private val loanRepository: LoanRepository,
    private val profileRepository: ProfileRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoanEditUiState())
    val uiState: StateFlow<LoanEditUiState> = _uiState.asStateFlow()

    private var editing: Loan? = null

    /** True until the EMI day is typed in, so it can follow the start date the way the model does. */
    private var emiDayFollowsStart = true

    fun initialise(loanId: String?) {
        if (!_uiState.value.isLoading) return

        viewModelScope.launch {
            val currency = profileRepository.currency()
            val loan = loanId?.let { loanRepository.findById(it) }
            editing = loan
            emiDayFollowsStart = loan == null

            _uiState.update {
                if (loan == null) {
                    val today = clock.today()
                    it.copy(
                        isLoading = false,
                        currency = currency,
                        startDate = today,
                        emiDayText = today.dayOfMonth.toString(),
                    )
                } else {
                    it.copy(
                        isLoading = false,
                        isEditing = true,
                        name = loan.name,
                        lender = loan.lender.orEmpty(),
                        principalText = loan.principal.toPlainString(),
                        ratePercentText = loan.annualInterestRatePercent.toPlainString(),
                        tenureMonthsText = loan.tenureMonths.toString(),
                        startDate = loan.startDate,
                        emiDayText = loan.emiDayOfMonth.toString(),
                        emiOverrideText = loan.emiOverride?.toPlainString().orEmpty(),
                        colorSeed = loan.colorSeed,
                        isClosed = loan.isClosed,
                        currency = currency,
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) = _uiState.update {
        it.copy(name = value, errors = it.errors.filterNot { e -> e.field == "name" })
    }

    fun onLenderChange(value: String) = _uiState.update { it.copy(lender = value) }

    fun onPrincipalChange(text: String) = _uiState.update {
        it.copy(
            principalText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "principal" },
        )
    }

    fun onRateChange(text: String) = _uiState.update {
        it.copy(
            ratePercentText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "rate" },
        )
    }

    fun onTenureChange(text: String) = _uiState.update {
        it.copy(
            tenureMonthsText = text.filter { c -> c.isDigit() },
            errors = it.errors.filterNot { e -> e.field == "tenure" },
        )
    }

    fun onStartDateChange(date: LocalDate) = _uiState.update {
        it.copy(
            startDate = date,
            // The model defaults the EMI day to the start date's day, so the field follows suit
            // until it is deliberately changed.
            emiDayText = if (emiDayFollowsStart) date.dayOfMonth.toString() else it.emiDayText,
            errors = it.errors.filterNot { e -> e.field == "emiDay" },
        )
    }

    fun onEmiDayChange(text: String) {
        emiDayFollowsStart = false
        _uiState.update {
            it.copy(
                emiDayText = text.filter { c -> c.isDigit() }.take(2),
                errors = it.errors.filterNot { e -> e.field == "emiDay" },
            )
        }
    }

    fun onEmiOverrideChange(text: String) = _uiState.update {
        it.copy(
            emiOverrideText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "emiOverride" },
        )
    }

    fun onColorChange(seed: Int) = _uiState.update { it.copy(colorSeed = seed) }

    fun onClosedChange(closed: Boolean) = _uiState.update { it.copy(isClosed = closed) }

    fun requestDelete() = _uiState.update { it.copy(showDeleteConfirm = true) }

    fun dismissDelete() = _uiState.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        val id = editing?.id ?: return
        viewModelScope.launch {
            runCatching { loanRepository.delete(id) }
                .onFailure { KhaataLog.e(TAG, "Failed to delete loan", it) }
            _uiState.update { it.copy(showDeleteConfirm = false, saved = true) }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return

        val validation = LoanValidator.validate(
            name = state.name,
            principalText = state.principalText,
            ratePercentText = state.ratePercentText,
            tenureMonthsText = state.tenureMonthsText,
            emiDayText = state.emiDayText,
            emiOverrideText = state.emiOverrideText,
            currency = state.currency,
        )
        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(errors = validation.errors) }
            return
        }

        val principal = MoneyParser.parse(state.principalText, state.currency) ?: return
        val rate = state.ratePercentText.trim().toBigDecimalOrNull() ?: return
        val tenure = state.tenureMonthsText.trim().toIntOrNull() ?: return
        val emiDay = state.emiDayText.trim().toIntOrNull() ?: return
        _uiState.update { it.copy(isSaving = true, errors = emptyList()) }

        viewModelScope.launch {
            val existing = editing
            val loan = Loan(
                id = existing?.id.orEmpty(),
                name = state.name.trim(),
                lender = state.lender.trim().takeIf { it.isNotBlank() },
                principal = principal,
                annualInterestRatePercent = rate,
                tenureMonths = tenure,
                startDate = state.startDate,
                emiOverride = state.emiOverrideText.takeIf { it.isNotBlank() }
                    ?.let { MoneyParser.parse(it, state.currency) },
                emiDayOfMonth = emiDay,
                // Carried through: the editor does not offer a linked account or category, and
                // rewriting a loan must not quietly unlink one that already exists.
                accountId = existing?.accountId,
                categoryId = existing?.categoryId,
                colorSeed = state.colorSeed,
                isClosed = state.isClosed,
            )

            runCatching {
                if (existing != null) loanRepository.update(loan) else loanRepository.create(loan)
            }
                .onSuccess { _uiState.update { it.copy(isSaving = false, saved = true) } }
                .onFailure { error ->
                    KhaataLog.e(TAG, "Failed to save loan", error)
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
        const val TAG = "LoanEditViewModel"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanEditScreen(
    loanId: String?,
    onDone: () -> Unit,
    viewModel: LoanEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(loanId) { viewModel.initialise(loanId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.loans_edit
                            else R.string.loans_add,
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
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.loans_name)) },
                singleLine = true,
                isError = state.errorFor("name") != null,
                supportingText = state.errorFor("name")?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.lender,
                onValueChange = viewModel::onLenderChange,
                label = { Text(stringResource(R.string.loans_lender)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.principalText,
                onValueChange = viewModel::onPrincipalChange,
                label = { Text(stringResource(R.string.loans_principal)) },
                prefix = { Text(state.currency.symbol) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.errorFor("principal") != null,
                supportingText = state.errorFor("principal")?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = state.ratePercentText,
                    onValueChange = viewModel::onRateChange,
                    label = { Text(stringResource(R.string.loans_interest_rate)) },
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = state.errorFor("rate") != null,
                    supportingText = state.errorFor("rate")?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.small))
                OutlinedTextField(
                    value = state.tenureMonthsText,
                    onValueChange = viewModel::onTenureChange,
                    label = { Text(stringResource(R.string.loans_tenure)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.errorFor("tenure") != null,
                    supportingText = state.errorFor("tenure")?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
            }

            // The instalment these terms imply, as they are typed. A figure that looks wrong is
            // the fastest way to notice a rate entered as 95 instead of 9.5.
            state.emiPreview?.let { emi ->
                Spacer(Modifier.height(spacing.default))
                KhaataCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.loans_emi_preview),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        MoneyText(money = emi, style = KhaataTextStyles.amountMedium)
                    }
                }
            }

            Spacer(Modifier.height(spacing.default))

            DateField(
                label = stringResource(R.string.loans_start_date),
                date = state.startDate,
                onPick = viewModel::onStartDateChange,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.emiDayText,
                onValueChange = viewModel::onEmiDayChange,
                label = { Text(stringResource(R.string.loans_emi_day)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.errorFor("emiDay") != null,
                supportingText = {
                    Text(state.errorFor("emiDay") ?: stringResource(R.string.loans_emi_day_help))
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.emiOverrideText,
                onValueChange = viewModel::onEmiOverrideChange,
                label = { Text(stringResource(R.string.loans_emi_override)) },
                prefix = { Text(state.currency.symbol) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.errorFor("emiOverride") != null,
                supportingText = {
                    Text(
                        state.errorFor("emiOverride")
                            ?: stringResource(R.string.loans_emi_override_help),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            ColorPicker(
                label = stringResource(R.string.loans_colour),
                selected = state.colorSeed,
                icon = Icons.Outlined.AccountBalance,
                onSelect = viewModel::onColorChange,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.isEditing) {
                Spacer(Modifier.height(spacing.default))

                ToggleRow(
                    title = stringResource(R.string.loans_closed),
                    subtitle = stringResource(R.string.loans_closed_help),
                ) {
                    Switch(checked = state.isClosed, onCheckedChange = viewModel::onClosedChange)
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
            title = { Text(stringResource(R.string.loans_delete_title)) },
            text = { Text(stringResource(R.string.loans_delete_body)) },
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
