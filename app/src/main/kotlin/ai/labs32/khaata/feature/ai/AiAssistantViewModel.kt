package ai.labs32.khaata.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.BuildConfig
import ai.labs32.khaata.core.ai.AiAnswer
import ai.labs32.khaata.core.ai.AiConsentState
import ai.labs32.khaata.core.ai.AiContext
import ai.labs32.khaata.core.ai.FinancialAiService
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.calc.BalanceCalculator
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.di.LocalAi
import ai.labs32.khaata.core.entitlement.Feature
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.EntitlementRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.SubscriptionRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** One question and its answer. */
data class AiExchange(
    val id: String,
    val question: String,
    val answer: AiAnswer?,
)

data class AiAssistantUiState(
    val input: String = "",
    val exchanges: List<AiExchange> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val isThinking: Boolean = false,
    val providerName: String = "On-device",
    val isUsingCloud: Boolean = false,
    val cloudBlockedReason: String? = null,
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    @LocalAi private val localAi: FinancialAiService,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val entitlementRepository: EntitlementRepository,
    private val analytics: AnalyticsProvider,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiAssistantUiState())
    val uiState: StateFlow<AiAssistantUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val consent = currentConsent()
            _uiState.update {
                it.copy(
                    providerName = localAi.providerName,
                    // No cloud provider is wired up in this build, so the on-device service
                    // answers everything. The flag reflects reality rather than intent.
                    isUsingCloud = false,
                    cloudBlockedReason = consent.blockedReason(),
                    suggestions = localAi.suggestedQuestions(buildContext()),
                )
            }
        }
    }

    fun onInputChange(text: String) = _uiState.update { it.copy(input = text) }

    fun ask(question: String) {
        if (question.isBlank() || _uiState.value.isThinking) return

        val exchangeId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                input = "",
                isThinking = true,
                exchanges = it.exchanges + AiExchange(exchangeId, question.trim(), answer = null),
            )
        }

        viewModelScope.launch {
            val answer = try {
                // Only the on-device service is bound in this build. A cloud provider would be
                // selected here, gated on `currentConsent().canUseCloud`, and would fall back to
                // this same call on any failure.
                localAi.ask(question.trim(), buildContext())
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Assistant query failed", error)
                AiAnswer.Unavailable("The assistant could not answer that just now.")
            }

            analytics.track(
                AnalyticsEvent.AiAssistantUsed(
                    provider = localAi.providerName,
                    wasAnswered = answer is AiAnswer.Answered,
                ),
            )

            _uiState.update { state ->
                state.copy(
                    isThinking = false,
                    exchanges = state.exchanges.map {
                        if (it.id == exchangeId) it.copy(answer = answer) else it
                    },
                )
            }
        }
    }

    /**
     * Assembles the data the assistant is allowed to see.
     *
     * Built explicitly and passed in rather than letting the service reach into the database.
     * That keeps exactly what a cloud provider would receive visible at this one call site, which
     * is what makes the privacy dashboard's claims checkable.
     *
     * The window is bounded to a year: enough for every question the assistant answers, and far
     * less than the user's whole history.
     */
    private suspend fun buildContext(): AiContext {
        val today = clock.today()
        val window = DateRange(today.minusYears(1).withDayOfMonth(1), today)
        val profile = profileRepository.getOrCreate()
        val balances = accountRepository.observeBalances().first()

        return AiContext(
            transactions = transactionRepository.getInRange(window),
            categories = categoryRepository.getAll(),
            budgets = budgetRepository.getAll(),
            subscriptions = subscriptionRepository.getAll(),
            accountNames = balances.associate { it.account.id to it.account.name },
            today = today,
            currency = profile.currency,
            availableBalance = BalanceCalculator.availableToSpend(balances, profile.currency),
            monthlyIncome = profile.monthlyIncome,
        )
    }

    private suspend fun currentConsent(): AiConsentState = AiConsentState(
        cloudProcessingEnabled = settingsRepository.current().cloudAiEnabled,
        hasEntitlement = entitlementRepository.isUnlocked(Feature.CLOUD_AI_ASSISTANT),
        isConfigured = BuildConfig.CLOUD_AI_ENDPOINT.isNotBlank() &&
            BuildConfig.CLOUD_AI_API_KEY.isNotBlank(),
    )

    private companion object {
        const val TAG = "AiAssistantViewModel"
    }
}
