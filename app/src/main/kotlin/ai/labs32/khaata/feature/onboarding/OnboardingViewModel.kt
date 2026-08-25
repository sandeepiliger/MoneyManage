package ai.labs32.khaata.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.categorize.DefaultCategories
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.data.demo.DemoDataManager
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The onboarding steps.
 *
 * Only [WELCOME] and [ACCOUNT] are load-bearing; everything else can be skipped. A first-run flow
 * that demands a dozen decisions before showing anything is where a large share of installs are
 * lost, so each screen states what it is for and offers a way past.
 */
enum class OnboardingStep {
    WELCOME,
    WHY,
    CURRENCY,
    LANGUAGE,
    ACCOUNT,
    INCOME,
    CATEGORIES,
    BUDGET,
    NOTIFICATIONS,
    LOCK,
    SMS,
    FINISH,
    ;

    val canSkip: Boolean get() = this != WELCOME && this != ACCOUNT
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val languageTag: String = "en",
    val displayName: String = "",

    val accountName: String = "",
    val accountType: AccountType = AccountType.BANK,
    val openingBalanceText: String = "",
    val accountError: String? = null,

    val monthlyIncomeText: String = "",
    val selectedCategoryIds: Set<String> = emptySet(),
    val budgetLimitText: String = "",
    val budgetCategoryId: String? = null,

    val lockMode: AppLockMode = AppLockMode.OFF,
    val smsImportEnabled: Boolean = false,
    val notificationsRequested: Boolean = false,

    val isSaving: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null,
) {
    val stepIndex: Int get() = OnboardingStep.entries.indexOf(step)
    val stepCount: Int get() = OnboardingStep.entries.size
    val progress: Float get() = (stepIndex + 1f) / stepCount

    /** The account step is the only one with a hard requirement. */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.ACCOUNT -> accountName.isNotBlank()
            else -> true
        }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val settingsRepository: SettingsRepository,
    private val demoDataManager: DemoDataManager,
    private val analytics: AnalyticsProvider,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        analytics.track(AnalyticsEvent.OnboardingStarted)
        viewModelScope.launch { categoryRepository.seedIfEmpty() }
    }

    // ---- Navigation --------------------------------------------------------------------------

    fun next() {
        val state = _uiState.value
        if (!state.canAdvance) return

        if (state.step == OnboardingStep.FINISH) {
            finish()
            return
        }
        val nextStep = OnboardingStep.entries.getOrNull(state.stepIndex + 1) ?: return
        _uiState.update { it.copy(step = nextStep, error = null) }
    }

    fun back() {
        val previous = OnboardingStep.entries.getOrNull(_uiState.value.stepIndex - 1) ?: return
        _uiState.update { it.copy(step = previous, error = null) }
    }

    fun skip() {
        if (_uiState.value.step.canSkip) next()
    }

    // ---- Field updates -----------------------------------------------------------------------

    fun onCurrencyChange(currency: CurrencyCode) = _uiState.update { it.copy(currency = currency) }

    fun onLanguageChange(tag: String) = _uiState.update { it.copy(languageTag = tag) }

    fun onNameChange(name: String) = _uiState.update { it.copy(displayName = name) }

    fun onAccountNameChange(name: String) =
        _uiState.update { it.copy(accountName = name, accountError = null) }

    fun onAccountTypeChange(type: AccountType) = _uiState.update { state ->
        state.copy(
            accountType = type,
            // Prefill the name with the type when the user has not typed one, so the common case
            // is zero typing.
            accountName = state.accountName.ifBlank { "" },
        )
    }

    fun onOpeningBalanceChange(text: String) = _uiState.update {
        it.copy(openingBalanceText = text.filter { c -> c.isDigit() || c == '.' })
    }

    fun onIncomeChange(text: String) = _uiState.update {
        it.copy(monthlyIncomeText = text.filter { c -> c.isDigit() || c == '.' })
    }

    fun onCategoryToggle(categoryId: String) = _uiState.update { state ->
        state.copy(
            selectedCategoryIds = if (categoryId in state.selectedCategoryIds) {
                state.selectedCategoryIds - categoryId
            } else {
                state.selectedCategoryIds + categoryId
            },
        )
    }

    fun onBudgetLimitChange(text: String) = _uiState.update {
        it.copy(budgetLimitText = text.filter { c -> c.isDigit() || c == '.' })
    }

    fun onBudgetCategoryChange(categoryId: String) =
        _uiState.update { it.copy(budgetCategoryId = categoryId) }

    fun onLockModeChange(mode: AppLockMode) = _uiState.update { it.copy(lockMode = mode) }

    fun onSmsImportChange(enabled: Boolean) = _uiState.update { it.copy(smsImportEnabled = enabled) }

    fun onNotificationsRequested() = _uiState.update { it.copy(notificationsRequested = true) }

    /**
     * A budget suggestion derived from declared income.
     *
     * Roughly a quarter of monthly income for the chosen category — a defensible starting point
     * that the user immediately sees and can change, rather than a number pulled from nowhere.
     */
    fun suggestedBudget(): Money? {
        val income = MoneyParser.parse(_uiState.value.monthlyIncomeText, _uiState.value.currency)
            ?: return null
        if (!income.isPositive) return null
        return income / 4
    }

    // ---- Completion --------------------------------------------------------------------------

    private fun finish() {
        val state = _uiState.value
        if (state.isSaving) return
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            try {
                profileRepository.update(
                    profileRepository.getOrCreate().copy(
                        displayName = state.displayName.trim().takeIf { it.isNotBlank() },
                        currency = state.currency,
                        languageTag = state.languageTag,
                        monthlyIncome = MoneyParser.parse(state.monthlyIncomeText, state.currency),
                    ),
                )

                val openingBalance = MoneyParser.parse(state.openingBalanceText, state.currency)
                    ?: Money.zero(state.currency)

                accountRepository.create(
                    name = state.accountName.trim(),
                    type = state.accountType,
                    openingBalance = openingBalance,
                    currency = state.currency,
                )

                var budgetsCreated = 0
                val budgetLimit = MoneyParser.parse(state.budgetLimitText, state.currency)
                if (budgetLimit != null && budgetLimit.isPositive) {
                    val categoryId = state.budgetCategoryId ?: DefaultCategories.FOOD
                    val categoryName = categoryRepository.findById(categoryId)?.name ?: "Spending"
                    budgetRepository.create(
                        name = categoryName,
                        limit = budgetLimit,
                        categoryIds = setOf(categoryId),
                        anchorDate = clock.today().withDayOfMonth(1),
                    )
                    budgetsCreated = 1
                }

                settingsRepository.setLockMode(state.lockMode)
                settingsRepository.setSmsImportEnabled(state.smsImportEnabled)

                profileRepository.markOnboardingComplete()
                analytics.track(
                    AnalyticsEvent.OnboardingCompleted(
                        accountsCreated = 1,
                        budgetsCreated = budgetsCreated,
                    ),
                )

                _uiState.update { it.copy(isSaving = false, isFinished = true) }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Onboarding completion failed", error)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = "We could not finish setting up. Please try again.",
                    )
                }
            }
        }
    }

    /**
     * Loads the demo dataset and skips the rest of setup.
     *
     * Everything it writes is prefixed so it can be removed cleanly later, and the dashboard
     * shows a banner throughout so demo figures are never mistaken for real ones.
     */
    fun loadDemoData() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            try {
                demoDataManager.load(currency = _uiState.value.currency)
                profileRepository.markOnboardingComplete()
                analytics.track(AnalyticsEvent.DemoModeEnabled)

                _uiState.update { it.copy(isSaving = false, isFinished = true) }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Demo data load failed", error)
                _uiState.update {
                    it.copy(isSaving = false, error = "We could not load the sample data.")
                }
            }
        }
    }

    private companion object {
        const val TAG = "OnboardingViewModel"
    }
}
