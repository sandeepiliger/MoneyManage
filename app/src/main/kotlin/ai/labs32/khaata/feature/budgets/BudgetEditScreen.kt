package ai.labs32.khaata.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.entitlement.Feature
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.BudgetPeriod
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.validation.BudgetValidator
import ai.labs32.khaata.core.validation.ValidationError
import ai.labs32.khaata.core.validation.ValidationResult
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.EntitlementRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BudgetEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val name: String = "",
    val limitText: String = "",
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val selectedCategoryIds: Set<String> = emptySet(),
    val alertThresholdPercent: Int = Budget.DEFAULT_ALERT_THRESHOLD,
    val rollsOver: Boolean = false,
    val rolloverAvailable: Boolean = false,
    val categories: List<Category> = emptyList(),
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val errors: List<ValidationError> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
) {
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message
}

@HiltViewModel
class BudgetEditViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val profileRepository: ProfileRepository,
    private val entitlementRepository: EntitlementRepository,
    private val analytics: AnalyticsProvider,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BudgetEditUiState())
    val uiState: StateFlow<BudgetEditUiState> = _uiState.asStateFlow()

    private var editingId: String? = null

    fun initialise(budgetId: String?) {
        if (!_uiState.value.isLoading) return

        viewModelScope.launch {
            val categories = categoryRepository.observeActive().first().filter { it.parentId == null }
            val currency = profileRepository.currency()
            val rolloverAvailable = entitlementRepository.isUnlocked(Feature.BUDGET_ROLLOVER)

            if (budgetId == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        categories = categories,
                        currency = currency,
                        rolloverAvailable = rolloverAvailable,
                    )
                }
            } else {
                val budget = budgetRepository.findById(budgetId)
                editingId = budgetId
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isEditing = budget != null,
                        name = budget?.name.orEmpty(),
                        limitText = budget?.limit?.toPlainString().orEmpty(),
                        period = budget?.period ?: BudgetPeriod.MONTHLY,
                        selectedCategoryIds = budget?.categoryIds.orEmpty(),
                        alertThresholdPercent = budget?.alertThresholdPercent
                            ?: Budget.DEFAULT_ALERT_THRESHOLD,
                        rollsOver = budget?.rollsOver == true,
                        rolloverAvailable = rolloverAvailable,
                        categories = categories,
                        currency = currency,
                    )
                }
            }
        }
    }

    fun onNameChange(name: String) =
        _uiState.update { it.copy(name = name, errors = it.errors.filterNot { e -> e.field == "name" }) }

    fun onLimitChange(text: String) = _uiState.update {
        it.copy(
            limitText = text.filter { c -> c.isDigit() || c == '.' },
            errors = it.errors.filterNot { e -> e.field == "limit" },
        )
    }

    fun onPeriodChange(period: BudgetPeriod) = _uiState.update { it.copy(period = period) }

    fun onCategoryToggle(categoryId: String) = _uiState.update { state ->
        state.copy(
            selectedCategoryIds = if (categoryId in state.selectedCategoryIds) {
                state.selectedCategoryIds - categoryId
            } else {
                state.selectedCategoryIds + categoryId
            },
        )
    }

    fun onAlertThresholdChange(percent: Int) =
        _uiState.update { it.copy(alertThresholdPercent = percent.coerceIn(1, 100)) }

    fun onRolloverChange(enabled: Boolean) = _uiState.update { it.copy(rollsOver = enabled) }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return

        val validation = BudgetValidator.validate(
            name = state.name,
            limitText = state.limitText,
            currency = state.currency,
            alertThresholdPercent = state.alertThresholdPercent,
        )
        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(errors = validation.errors) }
            return
        }

        val limit = MoneyParser.parse(state.limitText, state.currency) ?: return
        _uiState.update { it.copy(isSaving = true, errors = emptyList()) }

        viewModelScope.launch {
            try {
                val id = editingId
                if (id != null) {
                    val existing = budgetRepository.findById(id)
                    if (existing != null) {
                        budgetRepository.update(
                            existing.copy(
                                name = state.name.trim(),
                                limit = limit,
                                period = state.period,
                                categoryIds = state.selectedCategoryIds,
                                alertThresholdPercent = state.alertThresholdPercent,
                                rollsOver = state.rollsOver && state.rolloverAvailable,
                            ),
                        )
                    }
                } else {
                    budgetRepository.create(
                        name = state.name.trim(),
                        limit = limit,
                        period = state.period,
                        categoryIds = state.selectedCategoryIds,
                        anchorDate = clock.today().withDayOfMonth(1),
                        alertThresholdPercent = state.alertThresholdPercent,
                        rollsOver = state.rollsOver && state.rolloverAvailable,
                    )
                    analytics.track(
                        AnalyticsEvent.BudgetCreated(
                            isCategoryScoped = state.selectedCategoryIds.isNotEmpty(),
                        ),
                    )
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Failed to save budget", error)
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
        const val TAG = "BudgetEditViewModel"
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BudgetEditScreen(
    budgetId: String?,
    onDone: () -> Unit,
    viewModel: BudgetEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = KhaataTheme.spacing

    LaunchedEffect(budgetId) { viewModel.initialise(budgetId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.budgets_edit else R.string.budgets_add,
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
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.budgets_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.errorFor("name") != null,
                supportingText = state.errorFor("name")?.let { { Text(it) } },
            )

            Spacer(Modifier.height(spacing.medium))

            OutlinedTextField(
                value = state.limitText,
                onValueChange = viewModel::onLimitChange,
                label = { Text(stringResource(R.string.budgets_limit)) },
                prefix = { Text(state.currency.symbol) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.errorFor("limit") != null,
                supportingText = state.errorFor("limit")?.let { { Text(it) } },
            )

            Spacer(Modifier.height(spacing.default))

            Text(
                text = stringResource(R.string.budgets_period),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                BudgetPeriod.entries.forEachIndexed { index, period ->
                    SegmentedButton(
                        selected = state.period == period,
                        onClick = { viewModel.onPeriodChange(period) },
                        shape = SegmentedButtonDefaults.itemShape(index, BudgetPeriod.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (period) {
                                    BudgetPeriod.WEEKLY -> R.string.budgets_period_weekly
                                    BudgetPeriod.MONTHLY -> R.string.budgets_period_monthly
                                },
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(spacing.default))

            Text(
                text = stringResource(R.string.budgets_categories),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // An empty selection is a valid, meaningful choice, so it is explained rather
                // than treated as an incomplete form.
                text = stringResource(R.string.budgets_all_spending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.categories.forEach { category ->
                    FilterChip(
                        selected = category.id in state.selectedCategoryIds,
                        onClick = { viewModel.onCategoryToggle(category.id) },
                        label = { Text(category.name, maxLines = 1) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.large))

            Text(
                text = "${stringResource(R.string.budgets_alert_threshold)} ${state.alertThresholdPercent}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = state.alertThresholdPercent.toFloat(),
                onValueChange = { viewModel.onAlertThresholdChange(it.toInt()) },
                valueRange = 50f..100f,
                steps = 9,
            )

            Spacer(Modifier.height(spacing.small))

            if (state.rolloverAvailable) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.budgets_rollover),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.budgets_rollover_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.rollsOver,
                        onCheckedChange = viewModel::onRolloverChange,
                    )
                }
            }

            state.errorFor("form")?.let {
                Spacer(Modifier.height(spacing.small))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(spacing.large))

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.action_save)) }

            Spacer(Modifier.height(spacing.xlarge))
        }
    }
}
