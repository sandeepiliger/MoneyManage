package ai.labs32.khaata.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.money.sumOfMoney
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.TransactionFilter
import ai.labs32.khaata.data.repository.TransactionRepository
import ai.labs32.khaata.data.repository.TransactionSort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class TransactionsUiState(
    val filter: TransactionFilter = TransactionFilter(),
    val searchText: String = "",
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val filteredTotal: Money? = null,
    val filteredCount: Int = 0,
    val showFilters: Boolean = false,
    /** The last deleted transaction, held so the undo snackbar can restore it. */
    val recentlyDeletedId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val profileRepository: ProfileRepository,
    private val analytics: AnalyticsProvider,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val filterFlow = MutableStateFlow(TransactionFilter())

    /**
     * The paged list.
     *
     * `cachedIn` keeps the loaded pages across configuration changes, so rotating the device does
     * not re-query and jump the user back to the top of a list they had scrolled.
     */
    val transactions: Flow<PagingData<Transaction>> = filterFlow
        .debounce { filter ->
            // Typing in the search box should not fire a query per keystroke; changing a chip
            // should feel immediate.
            if (filter.query.isNullOrBlank()) 0L else SEARCH_DEBOUNCE_MS
        }
        .distinctUntilChanged()
        .flatMapLatest { transactionRepository.pagedTransactions(it) }
        .cachedIn(viewModelScope)

    init {
        combine(
            accountRepository.observeActive(),
            categoryRepository.observeActive(),
            profileRepository.observe(),
        ) { accounts, categories, profile ->
            Triple(accounts, categories, profile?.currency ?: CurrencyCode.DEFAULT)
        }
            .onEach { (accounts, categories, currency) ->
                _uiState.update {
                    it.copy(accounts = accounts, categories = categories, currency = currency)
                }
            }
            .launchIn(viewModelScope)

        // The filtered total is a separate, non-paged query: the paged list only knows about the
        // pages it has loaded, so summing it would show a total that grows as the user scrolls.
        filterFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .onEach { filter -> refreshFilteredTotal(filter) }
            .launchIn(viewModelScope)
    }

    private suspend fun refreshFilteredTotal(filter: TransactionFilter) {
        if (!filter.isActive) {
            _uiState.update { it.copy(filteredTotal = null, filteredCount = 0) }
            return
        }
        val matching = transactionRepository.listFiltered(filter)
        val currency = _uiState.value.currency
        _uiState.update {
            it.copy(
                filteredTotal = matching
                    .filter { transaction -> transaction.countsAsSpending }
                    .sumOfMoney(currency) { transaction -> transaction.amount },
                filteredCount = matching.size,
            )
        }
    }

    // ---- Filters -----------------------------------------------------------------------------

    fun onSearchChange(text: String) {
        _uiState.update { it.copy(searchText = text) }
        updateFilter { it.copy(query = text.takeIf { q -> q.isNotBlank() }) }
    }

    fun onTypeFilterChange(type: TransactionType?) = updateFilter { it.copy(type = type) }

    fun onAccountFilterToggle(accountId: String) = updateFilter { filter ->
        filter.copy(
            accountIds = filter.accountIds.toggle(accountId),
        )
    }

    fun onCategoryFilterToggle(categoryId: String) = updateFilter { filter ->
        filter.copy(categoryIds = filter.categoryIds.toggle(categoryId))
    }

    fun onDateRangeChange(range: DateRange?) = updateFilter { it.copy(dateRange = range) }

    fun onAmountRangeChange(minText: String?, maxText: String?) = updateFilter { filter ->
        val currency = _uiState.value.currency
        filter.copy(
            minAmount = MoneyParser.parse(minText, currency),
            maxAmount = MoneyParser.parse(maxText, currency),
        )
    }

    fun onTagFilterChange(tag: String?) = updateFilter { filter ->
        filter.copy(tags = tag?.let { setOf(it) }.orEmpty())
    }

    fun onSortChange(sort: TransactionSort) = updateFilter { it.copy(sort = sort) }

    fun clearFilters() {
        _uiState.update { it.copy(searchText = "") }
        filterFlow.value = TransactionFilter()
        _uiState.update { it.copy(filter = TransactionFilter(), filteredTotal = null) }
    }

    fun setFiltersVisible(visible: Boolean) = _uiState.update { it.copy(showFilters = visible) }

    /** Common presets, so the frequent cases do not need the full filter sheet. */
    fun applyPreset(preset: DatePreset) {
        val today = clock.today()
        val range = when (preset) {
            DatePreset.THIS_MONTH -> DateRange.ofMonth(today)
            DatePreset.LAST_MONTH -> DateRange.ofMonth(today.minusMonths(1))
            DatePreset.LAST_7_DAYS -> DateRange.lastDays(today, 7)
            DatePreset.LAST_30_DAYS -> DateRange.lastDays(today, 30)
            DatePreset.THIS_YEAR -> DateRange.ofYear(today)
            DatePreset.FINANCIAL_YEAR -> DateRange.ofFinancialYear(today)
        }
        onDateRangeChange(range)
    }

    private fun updateFilter(transform: (TransactionFilter) -> TransactionFilter) {
        val updated = transform(filterFlow.value)
        filterFlow.value = updated
        _uiState.update { it.copy(filter = updated) }
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    // ---- Row actions -------------------------------------------------------------------------

    fun delete(transactionId: String) {
        viewModelScope.launch {
            transactionRepository.delete(transactionId)
            analytics.track(AnalyticsEvent.TransactionDeleted)
            _uiState.update { it.copy(recentlyDeletedId = transactionId) }
        }
    }

    fun undoDelete() {
        val id = _uiState.value.recentlyDeletedId ?: return
        viewModelScope.launch {
            transactionRepository.restore(id)
            _uiState.update { it.copy(recentlyDeletedId = null) }
        }
    }

    fun clearUndo() = _uiState.update { it.copy(recentlyDeletedId = null) }

    fun duplicate(transactionId: String, onDuplicated: (String) -> Unit) {
        viewModelScope.launch {
            transactionRepository.duplicate(transactionId)?.let(onDuplicated)
        }
    }

    private companion object {
        /** Long enough to avoid a query per keystroke, short enough to feel responsive. */
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

enum class DatePreset { THIS_MONTH, LAST_MONTH, LAST_7_DAYS, LAST_30_DAYS, THIS_YEAR, FINANCIAL_YEAR }
