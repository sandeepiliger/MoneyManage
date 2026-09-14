package ai.labs32.khaata.feature.goals

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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.core.validation.GoalValidator
import ai.labs32.khaata.core.validation.ValidationError
import ai.labs32.khaata.core.validation.ValidationResult
import ai.labs32.khaata.data.repository.GoalRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.feature.shared.ColorPicker
import ai.labs32.khaata.feature.shared.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class GoalEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val name: String = "",
    val targetText: String = "",
    val currentText: String = "",
    val targetDate: LocalDate? = null,
    val colorSeed: Int = 0,
    val notes: String = "",
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val errors: List<ValidationError> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val showDeleteConfirm: Boolean = false,
) {
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message
}

/**
 * Creating and editing a savings goal.
 *
 * Goals had a complete data layer — [GoalRepository.create], `update`, `addProgress` and `delete`
 * — and no screen that called any of it, so the only goals that could exist were the three demo
 * mode seeds. This is that screen.
 *
 * It doubles as the goal's detail view rather than there being a separate read-only one: a goal
 * is four fields, and the progress a detail screen would show is already on the card that leads
 * here.
 */
@HiltViewModel
class GoalEditViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val profileRepository: ProfileRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GoalEditUiState())
    val uiState: StateFlow<GoalEditUiState> = _uiState.asStateFlow()

    private var editingId: String? = null

    fun initialise(goalId: String?) {
        if (!_uiState.value.isLoading) return

        viewModelScope.launch {
            val currency = profileRepository.currency()
            val goal = goalId?.let { goalRepository.findById(it) }
            editingId = goal?.id

            _uiState.update {
                it.copy(
                    isLoading = false,
                    isEditing = goal != null,
                    name = goal?.name.orEmpty(),
                    targetText = goal?.targetAmount?.toPlainString().orEmpty(),
                    currentText = goal?.currentAmount
                        ?.takeIf { amount -> !amount.isZero }
                        ?.toPlainString()
                        .orEmpty(),
                    targetDate = goal?.targetDate,
                    colorSeed = goal?.colorSeed ?: 0,
                    notes = goal?.notes.orEmpty(),
                    currency = currency,
                )
            }
        }
    }

    fun onNameChange(name: String) = _uiState.update {
        it.copy(name = name, errors = it.errors.filterNot { e -> e.field == "name" })
    }

    fun onTargetChange(text: String) = _uiState.update {
        it.copy(
            targetText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "target" },
        )
    }

    fun onCurrentChange(text: String) = _uiState.update {
        it.copy(
            currentText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "current" },
        )
    }

    fun onTargetDateChange(date: LocalDate?) = _uiState.update {
        it.copy(targetDate = date, errors = it.errors.filterNot { e -> e.field == "targetDate" })
    }

    fun onColorChange(seed: Int) = _uiState.update { it.copy(colorSeed = seed) }

    fun onNotesChange(notes: String) = _uiState.update { it.copy(notes = notes) }

    fun requestDelete() = _uiState.update { it.copy(showDeleteConfirm = true) }

    fun dismissDelete() = _uiState.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        val id = editingId ?: return
        viewModelScope.launch {
            runCatching { goalRepository.delete(id) }
                .onFailure { KhaataLog.e(TAG, "Failed to delete goal", it) }
            _uiState.update { it.copy(showDeleteConfirm = false, saved = true) }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return

        val validation = GoalValidator.validate(
            name = state.name,
            targetText = state.targetText,
            currentText = state.currentText,
            currency = state.currency,
            targetDate = state.targetDate,
            today = clock.today(),
        )
        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(errors = validation.errors) }
            return
        }

        val target = MoneyParser.parse(state.targetText, state.currency) ?: return
        // Blank means nothing saved yet, which is the common case for a new goal and is not the
        // same as an invalid entry — the validator has already rejected anything unparseable.
        val current = state.currentText.takeIf { it.isNotBlank() }
            ?.let { MoneyParser.parse(it, state.currency) }
            ?: Money.zero(state.currency)

        _uiState.update { it.copy(isSaving = true, errors = emptyList()) }

        viewModelScope.launch {
            try {
                val id = editingId
                if (id != null) {
                    val existing = goalRepository.findById(id)
                    if (existing != null) {
                        goalRepository.update(
                            existing.copy(
                                name = state.name.trim(),
                                targetAmount = target,
                                currentAmount = current,
                                targetDate = state.targetDate,
                                colorSeed = state.colorSeed,
                                notes = state.notes.trim().takeIf { it.isNotBlank() },
                                // Re-derived rather than carried over: editing the target down to
                                // what is already saved completes the goal, and editing it back up
                                // un-completes it. A stale achievedOn would leave a goal showing
                                // "Reached" with money still to find.
                                achievedOn = when {
                                    current < target -> null
                                    existing.achievedOn != null -> existing.achievedOn
                                    else -> clock.today()
                                },
                            ),
                        )
                    }
                } else {
                    goalRepository.create(
                        name = state.name.trim(),
                        targetAmount = target,
                        currentAmount = current,
                        targetDate = state.targetDate,
                        colorSeed = state.colorSeed,
                        notes = state.notes.trim().takeIf { it.isNotBlank() },
                    )
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Failed to save goal", error)
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
        const val TAG = "GoalEditViewModel"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalEditScreen(
    goalId: String?,
    onDone: () -> Unit,
    viewModel: GoalEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = KhaataTheme.spacing

    LaunchedEffect(goalId) { viewModel.initialise(goalId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.goals_edit else R.string.goals_add,
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

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.goals_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.errorFor("name") != null,
                supportingText = state.errorFor("name")?.let { { Text(it) } },
            )

            Spacer(Modifier.height(spacing.medium))

            OutlinedTextField(
                value = state.targetText,
                onValueChange = viewModel::onTargetChange,
                label = { Text(stringResource(R.string.goals_target)) },
                prefix = { Text(state.currency.symbol) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.errorFor("target") != null,
                supportingText = state.errorFor("target")?.let { { Text(it) } },
            )

            Spacer(Modifier.height(spacing.medium))

            OutlinedTextField(
                value = state.currentText,
                onValueChange = viewModel::onCurrentChange,
                label = { Text(stringResource(R.string.goals_current)) },
                prefix = { Text(state.currency.symbol) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.errorFor("current") != null,
                supportingText = state.errorFor("current")?.let { { Text(it) } },
            )

            Spacer(Modifier.height(spacing.medium))

            DateField(
                label = stringResource(R.string.goals_target_date),
                date = state.targetDate,
                onPick = viewModel::onTargetDateChange,
                onClear = { viewModel.onTargetDateChange(null) },
                error = state.errorFor("targetDate"),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.medium))

            ColorPicker(
                label = stringResource(R.string.categories_colour),
                selected = state.colorSeed,
                icon = Icons.Outlined.Flag,
                onSelect = viewModel::onColorChange,
            )

            Spacer(Modifier.height(spacing.medium))

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text(stringResource(R.string.goals_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            state.errorFor("form")?.let { message ->
                Spacer(Modifier.height(spacing.medium))
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
            ) {
                Text(stringResource(R.string.action_save))
            }

            Spacer(Modifier.height(spacing.bottomBarClearance))
        }
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.goals_delete_title)) },
            text = { Text(stringResource(R.string.goals_delete_body)) },
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

/** The "add money" dialog shown from a goal card. */
@Composable
fun AddGoalMoneyDialog(
    goalName: String,
    currency: CurrencyCode,
    onConfirm: (Money) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val amount = MoneyParser.parse(text, currency)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goals_add_money)) },
        text = {
            Column {
                Text(
                    text = goalName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(KhaataTheme.spacing.small))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(stringResource(R.string.goals_add_money_amount)) },
                    prefix = { Text(currency.symbol) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                // Zero is refused rather than accepted as a no-op: tapping Add and seeing nothing
                // change reads as a broken button.
                enabled = amount != null && amount.isPositive,
                onClick = { amount?.let(onConfirm) },
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
