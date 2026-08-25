package ai.labs32.khaata.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.analytics.EntryMethod
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.nlp.NaturalLanguageParser
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** A parsed transaction awaiting the user's confirmation. Never written until they say so. */
data class TransactionDraft(
    val id: String,
    val type: TransactionType,
    val amount: Money,
    val merchantRaw: String?,
    val merchantDisplayName: String?,
    val categoryId: String?,
    val accountId: String?,
    val occurredOn: LocalDate,
    val sourceText: String,
    val needsReview: Boolean,
    val isSelected: Boolean = true,
)

data class NaturalLanguageEntryUiState(
    val input: String = "",
    val drafts: List<TransactionDraft> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isSaving: Boolean = false,
    val savedCount: Int = 0,
    val error: String? = null,
) {
    val selectedCount: Int get() = drafts.count { it.isSelected }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class NaturalLanguageEntryViewModel @Inject constructor(
    private val parser: NaturalLanguageParser,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val analytics: AnalyticsProvider,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NaturalLanguageEntryUiState())
    val uiState: StateFlow<NaturalLanguageEntryUiState> = _uiState.asStateFlow()

    private val inputFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    accounts = accountRepository.observeActive().first(),
                    categories = categoryRepository.observeActive().first(),
                )
            }
        }

        // Re-parsing on every keystroke would rebuild the draft list under the user's finger and
        // discard any category they had just corrected.
        inputFlow
            .debounce(PARSE_DEBOUNCE_MS)
            .onEach { text -> parse(text) }
            .launchIn(viewModelScope)
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
        inputFlow.value = text
    }

    private suspend fun parse(text: String) {
        if (text.isBlank()) {
            _uiState.update { it.copy(drafts = emptyList()) }
            return
        }

        val defaultAccountId = _uiState.value.accounts.firstOrNull()?.id
        val entries = parser.parse(text, clock.today())

        val drafts = entries.mapIndexed { index, entry ->
            val suggestion = categoryRepository.suggestFor(entry.merchantRaw)
            TransactionDraft(
                id = "draft-$index",
                type = entry.type,
                amount = entry.amount,
                merchantRaw = entry.merchantRaw,
                merchantDisplayName = entry.merchantDisplayName ?: entry.merchantRaw,
                categoryId = suggestion?.categoryId,
                accountId = suggestion?.accountId ?: defaultAccountId,
                occurredOn = entry.occurredOn,
                sourceText = entry.sourceText,
                // Flagged when the parse is least sure, or when a category could not be guessed:
                // both are cases where the user should look before saving.
                needsReview = entry.needsReview || suggestion == null,
            )
        }
        _uiState.update { it.copy(drafts = drafts) }
    }

    fun toggleDraft(draftId: String) = _uiState.update { state ->
        state.copy(
            drafts = state.drafts.map {
                if (it.id == draftId) it.copy(isSelected = !it.isSelected) else it
            },
        )
    }

    fun setDraftCategory(draftId: String, categoryId: String) = _uiState.update { state ->
        state.copy(
            drafts = state.drafts.map {
                if (it.id == draftId) it.copy(categoryId = categoryId, needsReview = false) else it
            },
        )
    }

    fun setDraftAccount(draftId: String, accountId: String) = _uiState.update { state ->
        state.copy(
            drafts = state.drafts.map {
                if (it.id == draftId) it.copy(accountId = accountId) else it
            },
        )
    }

    /**
     * Writes the selected drafts.
     *
     * Category learning is on here: the user has looked at each draft and confirmed or corrected
     * it, which makes this a reliable signal — unlike an unreviewed import.
     */
    fun saveSelected() {
        val state = _uiState.value
        val selected = state.drafts.filter { it.isSelected && it.accountId != null }
        if (selected.isEmpty() || state.isSaving) return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            try {
                selected.forEach { draft ->
                    transactionRepository.create(
                        type = draft.type,
                        amount = draft.amount,
                        accountId = draft.accountId!!,
                        categoryId = draft.categoryId,
                        merchant = draft.merchantRaw,
                        occurredOn = draft.occurredOn,
                        source = TransactionSource.NATURAL_LANGUAGE,
                        learnCategory = true,
                    )
                    analytics.track(
                        AnalyticsEvent.TransactionCreated(
                            entryMethod = EntryMethod.NATURAL_LANGUAGE,
                            type = draft.type.name,
                            hadCategorySuggestion = !draft.needsReview,
                        ),
                    )
                }
                _uiState.update { it.copy(isSaving = false, savedCount = selected.size) }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Failed to save parsed transactions", error)
                analytics.track(AnalyticsEvent.ErrorEncountered("nl_entry_save"))
                _uiState.update {
                    it.copy(isSaving = false, error = "We could not save those. Please try again.")
                }
            }
        }
    }

    private companion object {
        const val TAG = "NaturalLanguageEntryViewModel"
        const val PARSE_DEBOUNCE_MS = 350L
    }
}
