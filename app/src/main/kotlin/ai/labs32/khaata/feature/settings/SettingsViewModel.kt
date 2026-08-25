package ai.labs32.khaata.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.entitlement.Tier
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.model.ThemePreference
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.notifications.KhaataNotifier
import ai.labs32.khaata.core.security.AppLockManager
import ai.labs32.khaata.core.security.BiometricAvailability
import ai.labs32.khaata.core.security.BiometricAuthenticator
import ai.labs32.khaata.data.demo.DemoDataManager
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.CreditCardRepository
import ai.labs32.khaata.data.repository.EntitlementRepository
import ai.labs32.khaata.data.repository.GoalRepository
import ai.labs32.khaata.data.repository.InvestmentRepository
import ai.labs32.khaata.data.repository.LoanRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.RecurringRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.SubscriptionRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A one-off confirmation for the snackbar. */
sealed interface SettingsMessage {
    data object DeletedEverything : SettingsMessage
    data object DemoLoaded : SettingsMessage
    data object DemoCleared : SettingsMessage
}

data class SettingsUiState(
    val displayName: String = "",
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val monthStartDay: Int = 1,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val lockMode: AppLockMode = AppLockMode.OFF,
    val lockAfterSeconds: Int = 30,
    val hideAmountsWhenLocked: Boolean = true,
    val budgetAlertsEnabled: Boolean = true,
    val billRemindersEnabled: Boolean = true,
    val dailyReminderEnabled: Boolean = false,
    val dailyReminderMinuteOfDay: Int = 21 * 60,
    val tier: Tier = Tier.FREE,
    val demoMode: Boolean = false,
    /** True when the user has records of their own, which blocks loading sample data over them. */
    val hasRealData: Boolean = false,
    val availableLockModes: List<AppLockMode> = listOf(AppLockMode.OFF, AppLockMode.PIN),
    val biometricUnavailableReason: String? = null,
    val showDeleteAllDialog: Boolean = false,
    val message: SettingsMessage? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val entitlementRepository: EntitlementRepository,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val recurringRepository: RecurringRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val creditCardRepository: CreditCardRepository,
    private val loanRepository: LoanRepository,
    private val investmentRepository: InvestmentRepository,
    private val goalRepository: GoalRepository,
    private val demoDataManager: DemoDataManager,
    private val appLockManager: AppLockManager,
    private val biometricAuthenticator: BiometricAuthenticator,
    private val notifier: KhaataNotifier,
    private val analytics: AnalyticsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        combine(
            settingsRepository.settings,
            profileRepository.observe(),
            entitlementRepository.observeTier(),
        ) { settings, profile, tier ->
            val availability = biometricAuthenticator.availability()
            SettingsUiState(
                displayName = profile?.displayName.orEmpty(),
                currency = profile?.currency ?: CurrencyCode.DEFAULT,
                monthStartDay = profile?.monthStartDay ?: 1,
                theme = settings.theme,
                lockMode = settings.lockMode,
                lockAfterSeconds = settings.lockAfterSeconds,
                hideAmountsWhenLocked = settings.hideAmountsWhenLocked,
                budgetAlertsEnabled = settings.budgetAlertsEnabled,
                billRemindersEnabled = settings.billRemindersEnabled,
                dailyReminderEnabled = settings.dailyReminderEnabled,
                dailyReminderMinuteOfDay = settings.dailyReminderMinuteOfDay,
                tier = tier,
                demoMode = profile?.isDemoMode == true,
                // Biometric is offered only when the device can actually do it, rather than
                // offered and then failing at the prompt.
                availableLockModes = buildList {
                    add(AppLockMode.OFF)
                    if (availability.canUse) add(AppLockMode.BIOMETRIC)
                    add(AppLockMode.PIN)
                },
                biometricUnavailableReason = biometricReason(availability),
            )
        }
            .onEach { fresh ->
                _uiState.update { current ->
                    fresh.copy(
                        hasRealData = current.hasRealData,
                        showDeleteAllDialog = current.showDeleteAllDialog,
                        message = current.message,
                    )
                }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch { refreshDataPresence() }
    }

    private fun biometricReason(availability: BiometricAvailability): String? = when (availability) {
        BiometricAvailability.AVAILABLE -> null
        BiometricAvailability.NOT_ENROLLED -> context.getString(R.string.biometric_not_enrolled)
        BiometricAvailability.NO_HARDWARE -> context.getString(R.string.biometric_no_hardware)
        BiometricAvailability.UPDATE_REQUIRED,
        BiometricAvailability.UNAVAILABLE,
        -> context.getString(R.string.biometric_no_hardware)
    }

    /**
     * Works out whether the user has records of their own.
     *
     * Derived from the ledger rather than from a flag: the ledger is the only thing that says
     * whether anything was actually recorded, and it is what loading sample data would pollute.
     */
    private suspend fun refreshDataPresence() {
        val isDemo = profileRepository.getOrCreate().isDemoMode
        val count = transactionRepository.count()
        _uiState.update { it.copy(hasRealData = !isDemo && count > 0) }
    }

    // ---- Profile -----------------------------------------------------------------------------

    fun setDisplayName(name: String) {
        _uiState.update { it.copy(displayName = name) }
        viewModelScope.launch { profileRepository.setDisplayName(name.ifBlank { null }) }
    }

    fun setMonthStartDay(day: Int) {
        viewModelScope.launch { profileRepository.setMonthStartDay(day) }
    }

    // ---- Appearance and security -------------------------------------------------------------

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    /**
     * Changes the app lock.
     *
     * Turning the lock off clears any stored PIN, so a re-enabled lock always asks for a new one
     * rather than silently reviving a PIN the user set months ago and has forgotten.
     */
    fun setLockMode(mode: AppLockMode) {
        viewModelScope.launch {
            if (mode == AppLockMode.OFF) appLockManager.clearPin()
            settingsRepository.setLockMode(mode)
            analytics.track(AnalyticsEvent.AppLockChanged(enabled = mode != AppLockMode.OFF))
        }
    }

    fun setLockAfterSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.setLockAfterSeconds(seconds) }
    }

    fun setHideAmountsWhenLocked(hide: Boolean) {
        viewModelScope.launch { settingsRepository.setHideAmountsWhenLocked(hide) }
    }

    // ---- Notifications -----------------------------------------------------------------------

    /**
     * Turning any of these three switches on is pointless without a granted POST_NOTIFICATIONS
     * permission: nothing here posts a notification without checking [KhaataNotifier.hasPermission]
     * first, so switching this on without the grant leaves it showing "on" forever while silently
     * doing nothing. The composable requests the permission before calling these with `true`; this
     * check is what stops the setting from lying if it is ever reached another way.
     */
    fun setBudgetAlerts(enabled: Boolean) {
        val effective = enabled && notifier.hasPermission()
        viewModelScope.launch { settingsRepository.setBudgetAlertsEnabled(effective) }
    }

    fun setBillReminders(enabled: Boolean) {
        val effective = enabled && notifier.hasPermission()
        viewModelScope.launch { settingsRepository.setBillRemindersEnabled(effective) }
    }

    fun setDailyReminder(enabled: Boolean) {
        val effective = enabled && notifier.hasPermission()
        viewModelScope.launch { settingsRepository.setDailyReminderEnabled(effective) }
    }

    fun setDailyReminderTime(minuteOfDay: Int) {
        viewModelScope.launch { settingsRepository.setDailyReminderTime(minuteOfDay) }
    }

    // ---- Demo mode ---------------------------------------------------------------------------

    fun toggleDemoMode() {
        viewModelScope.launch {
            val state = _uiState.value
            runCatching {
                if (state.demoMode) {
                    demoDataManager.clear()
                    analytics.track(AnalyticsEvent.DemoModeDisabled)
                    _uiState.update { it.copy(message = SettingsMessage.DemoCleared) }
                } else {
                    demoDataManager.load(currency = state.currency)
                    analytics.track(AnalyticsEvent.DemoModeEnabled)
                    _uiState.update { it.copy(message = SettingsMessage.DemoLoaded) }
                }
            }.onFailure { KhaataLog.e(TAG, "Demo mode toggle failed", it) }
            refreshDataPresence()
        }
    }

    // ---- Delete everything -------------------------------------------------------------------

    fun requestDeleteAll() = _uiState.update { it.copy(showDeleteAllDialog = true) }

    fun dismissDeleteAll() = _uiState.update { it.copy(showDeleteAllDialog = false) }

    /**
     * Removes every record from the device.
     *
     * Deliberately thorough and deliberately in this one place: partial deletion is worse than
     * none, because a user who asked for their data to be gone and finds half of it still there
     * has been misled. Settings themselves are reset too, so nothing survives that could identify
     * how the app was used. Order follows the foreign keys — children before parents.
     */
    fun confirmDeleteAll() {
        viewModelScope.launch {
            runCatching {
                transactionRepository.deleteAll()
                budgetRepository.deleteAll()
                recurringRepository.deleteAll()
                subscriptionRepository.deleteAll()
                creditCardRepository.deleteAll()
                loanRepository.deleteAll()
                investmentRepository.deleteAll()
                goalRepository.deleteAll()
                accountRepository.deleteAll()
                categoryRepository.deleteAll()
                profileRepository.deleteAll()
                appLockManager.clearPin()
                settingsRepository.resetAll()
                // Categories are re-seeded immediately: an app with no categories at all cannot
                // record a transaction, and the user asked to delete their data, not to brick it.
                categoryRepository.seedIfEmpty()
            }.onFailure { KhaataLog.e(TAG, "Delete-all failed", it) }

            _uiState.update {
                it.copy(
                    showDeleteAllDialog = false,
                    hasRealData = false,
                    message = SettingsMessage.DeletedEverything,
                )
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
