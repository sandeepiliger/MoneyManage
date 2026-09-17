package ai.labs32.khaata.feature.investments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.TrendingUp
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Investment
import ai.labs32.khaata.core.model.InvestmentKind
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.core.validation.InvestmentValidator
import ai.labs32.khaata.core.validation.ValidationError
import ai.labs32.khaata.core.validation.ValidationResult
import ai.labs32.khaata.data.repository.InvestmentRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.feature.shared.ChipSelector
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

data class InvestmentEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val name: String = "",
    val kind: InvestmentKind = InvestmentKind.MUTUAL_FUND,
    val investedText: String = "",
    val currentValueText: String = "",
    val startedOn: LocalDate = LocalDate.now(),
    val valuedOn: LocalDate = LocalDate.now(),
    val unitsText: String = "",
    val folioOrSymbol: String = "",
    val notes: String = "",
    val colorSeed: Int = 0,
    val isClosed: Boolean = false,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val errors: List<ValidationError> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val showDeleteConfirm: Boolean = false,
) {
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message

    /** Units and a folio number only mean something for a holding measured in units. */
    val showsUnits: Boolean
        get() = kind in setOf(InvestmentKind.MUTUAL_FUND, InvestmentKind.SIP, InvestmentKind.STOCK)
}

/**
 * Creating and editing an investment.
 *
 * [InvestmentRepository] had `create`, `update`, `updateValuation` and `delete`, and nothing in
 * the app called any of them: the investments screen listed holdings and offered no way to add
 * one, so the only holdings that could exist were the demo seeds. `Routes.ADD_INVESTMENT` was
 * declared and never registered or navigated to. This is the screen behind it.
 *
 * It doubles as the detail view rather than there being a separate read-only one, for the same
 * reason goals do: the numbers a detail screen would show — gain, return, how stale the valuation
 * is — are already on the card that leads here.
 */
@HiltViewModel
class InvestmentEditViewModel @Inject constructor(
    private val investmentRepository: InvestmentRepository,
    private val profileRepository: ProfileRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InvestmentEditUiState())
    val uiState: StateFlow<InvestmentEditUiState> = _uiState.asStateFlow()

    private var editing: Investment? = null

    /**
     * True until the current value has been typed in.
     *
     * A new holding is almost always worth what was just put into it, so the value field mirrors
     * the invested amount until someone edits it — one field to fill instead of the same number
     * twice. Editing it stops the mirroring for good, so a deliberate value is never overwritten.
     */
    private var valueFollowsInvested = true

    fun initialise(investmentId: String?) {
        if (!_uiState.value.isLoading) return

        viewModelScope.launch {
            val currency = profileRepository.currency()
            val investment = investmentId?.let { investmentRepository.findById(it) }
            editing = investment
            valueFollowsInvested = investment == null

            _uiState.update {
                if (investment == null) {
                    it.copy(
                        isLoading = false,
                        currency = currency,
                        startedOn = clock.today(),
                        valuedOn = clock.today(),
                    )
                } else {
                    it.copy(
                        isLoading = false,
                        isEditing = true,
                        name = investment.name,
                        kind = investment.kind,
                        investedText = investment.investedAmount.toPlainString(),
                        currentValueText = investment.currentValue.toPlainString(),
                        startedOn = investment.startedOn,
                        valuedOn = investment.valuedOn,
                        unitsText = investment.units?.toPlainString().orEmpty(),
                        folioOrSymbol = investment.folioOrSymbol.orEmpty(),
                        notes = investment.notes.orEmpty(),
                        colorSeed = investment.colorSeed,
                        isClosed = investment.isClosed,
                        currency = currency,
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) = _uiState.update {
        it.copy(name = value, errors = it.errors.filterNot { e -> e.field == "name" })
    }

    fun onKindChange(kind: InvestmentKind) = _uiState.update { it.copy(kind = kind) }

    fun onInvestedChange(text: String) {
        val cleaned = text.filter { it.isDigit() || it == '.' }
        _uiState.update {
            it.copy(
                investedText = cleaned,
                currentValueText = if (valueFollowsInvested) cleaned else it.currentValueText,
                errors = it.errors.filterNot { e -> e.field == "invested" || e.field == "currentValue" },
            )
        }
    }

    fun onCurrentValueChange(text: String) {
        valueFollowsInvested = false
        _uiState.update {
            it.copy(
                currentValueText = text.filter { c -> c.isDigit() || c == '.' },
                errors = it.errors.filterNot { e -> e.field == "currentValue" },
            )
        }
    }

    fun onUnitsChange(text: String) = _uiState.update {
        it.copy(
            unitsText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "units" },
        )
    }

    fun onFolioChange(value: String) = _uiState.update { it.copy(folioOrSymbol = value) }

    fun onStartedOnChange(date: LocalDate) = _uiState.update {
        it.copy(startedOn = date, errors = it.errors.filterNot { e -> e.field == "startedOn" })
    }

    fun onValuedOnChange(date: LocalDate) = _uiState.update {
        it.copy(valuedOn = date, errors = it.errors.filterNot { e -> e.field == "valuedOn" })
    }

    fun onNotesChange(value: String) = _uiState.update { it.copy(notes = value) }

    fun onColorChange(seed: Int) = _uiState.update { it.copy(colorSeed = seed) }

    fun onClosedChange(closed: Boolean) = _uiState.update { it.copy(isClosed = closed) }

    fun requestDelete() = _uiState.update { it.copy(showDeleteConfirm = true) }

    fun dismissDelete() = _uiState.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        val id = editing?.id ?: return
        viewModelScope.launch {
            runCatching { investmentRepository.delete(id) }
                .onFailure { KhaataLog.e(TAG, "Failed to delete investment", it) }
            _uiState.update { it.copy(showDeleteConfirm = false, saved = true) }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return

        val validation = InvestmentValidator.validate(
            name = state.name,
            investedText = state.investedText,
            currentValueText = state.currentValueText,
            unitsText = state.unitsText.takeIf { state.showsUnits },
            currency = state.currency,
            startedOn = state.startedOn,
            valuedOn = state.valuedOn,
            today = clock.today(),
        )
        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(errors = validation.errors) }
            return
        }

        val invested = MoneyParser.parse(state.investedText, state.currency) ?: return
        val currentValue = MoneyParser.parse(state.currentValueText, state.currency) ?: return
        _uiState.update { it.copy(isSaving = true, errors = emptyList()) }

        viewModelScope.launch {
            val existing = editing
            val investment = Investment(
                id = existing?.id.orEmpty(),
                name = state.name.trim(),
                kind = state.kind,
                investedAmount = invested,
                currentValue = currentValue,
                startedOn = state.startedOn,
                valuedOn = state.valuedOn,
                // Carried through rather than dropped: the editor does not offer a funding
                // account, and rewriting an existing holding must not quietly clear one.
                accountId = existing?.accountId,
                units = state.unitsText.takeIf { state.showsUnits && it.isNotBlank() }
                    ?.toBigDecimalOrNull(),
                folioOrSymbol = state.folioOrSymbol.trim()
                    .takeIf { state.showsUnits && it.isNotBlank() },
                notes = state.notes.trim().takeIf { it.isNotBlank() },
                colorSeed = state.colorSeed,
                isClosed = state.isClosed,
            )

            val result = runCatching {
                if (existing != null) {
                    investmentRepository.update(investment)
                } else {
                    investmentRepository.create(investment)
                }
            }

            result
                .onSuccess { _uiState.update { it.copy(isSaving = false, saved = true) } }
                .onFailure { error ->
                    KhaataLog.e(TAG, "Failed to save investment", error)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errors = listOf(
                                ValidationError(
                                    "form",
                                    "save_failed",
                                    "We could not save that. Please try again.",
                                ),
                            ),
                        )
                    }
                }
        }
    }

    private companion object {
        const val TAG = "InvestmentEditViewModel"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentEditScreen(
    investmentId: String?,
    onDone: () -> Unit,
    viewModel: InvestmentEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(investmentId) { viewModel.initialise(investmentId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.investments_edit
                            else R.string.investments_add,
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
                label = { Text(stringResource(R.string.investments_name)) },
                singleLine = true,
                isError = state.errorFor("name") != null,
                supportingText = state.errorFor("name")?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            ChipSelector(
                label = stringResource(R.string.investments_kind),
                options = InvestmentKind.entries,
                selected = state.kind,
                optionLabel = { investmentKindLabel(it) },
                onSelect = viewModel::onKindChange,
                optionKey = { it.name },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.investedText,
                onValueChange = viewModel::onInvestedChange,
                label = { Text(stringResource(R.string.investments_invested)) },
                prefix = { Text(state.currency.symbol) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.errorFor("invested") != null,
                supportingText = state.errorFor("invested")?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.currentValueText,
                onValueChange = viewModel::onCurrentValueChange,
                label = { Text(stringResource(R.string.investments_current_value)) },
                prefix = { Text(state.currency.symbol) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.errorFor("currentValue") != null,
                supportingText = {
                    Text(
                        state.errorFor("currentValue")
                            ?: stringResource(R.string.investments_current_value_help),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            DateField(
                label = stringResource(R.string.investments_started_on),
                date = state.startedOn,
                onPick = viewModel::onStartedOnChange,
                error = state.errorFor("startedOn"),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            DateField(
                label = stringResource(R.string.investments_valued_on_label),
                date = state.valuedOn,
                onPick = viewModel::onValuedOnChange,
                error = state.errorFor("valuedOn"),
                modifier = Modifier.fillMaxWidth(),
            )

            // Units and a folio number are meaningless for a fixed deposit or PPF, so they appear
            // only for the kinds actually measured in units.
            if (state.showsUnits) {
                Spacer(Modifier.height(spacing.default))

                OutlinedTextField(
                    value = state.unitsText,
                    onValueChange = viewModel::onUnitsChange,
                    label = { Text(stringResource(R.string.investments_units)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = state.errorFor("units") != null,
                    supportingText = state.errorFor("units")?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(spacing.default))

                OutlinedTextField(
                    value = state.folioOrSymbol,
                    onValueChange = viewModel::onFolioChange,
                    label = { Text(stringResource(R.string.investments_folio)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(spacing.default))

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text(stringResource(R.string.investments_notes)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            ColorPicker(
                label = stringResource(R.string.investments_colour),
                selected = state.colorSeed,
                icon = Icons.Outlined.TrendingUp,
                onSelect = viewModel::onColorChange,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.isEditing) {
                Spacer(Modifier.height(spacing.default))

                ToggleRow(
                    title = stringResource(R.string.investments_closed),
                    subtitle = stringResource(R.string.investments_closed_help),
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
            title = { Text(stringResource(R.string.investments_delete_title)) },
            text = { Text(stringResource(R.string.investments_delete_body)) },
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
