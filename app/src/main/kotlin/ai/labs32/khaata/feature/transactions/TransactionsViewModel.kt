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
import kotlinx.coroutines.flow.map
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
    /** Every tag in use, for the filter sheet. */
    val tags: List<String> = emptyList(),
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    /** Money out for the summary strip: the filter's rows, or this month's when nothing is filtered. */
    val filteredTotal: Money? = null,
    /** Money in, on the same basis as [filteredTotal]. */
    val filteredIncome: Money? = null,
    val filteredCount: Int = 0,
    /** True when no filter is set and the strip is showing the current month instead. */
    val summaryIsThisMonth: Boolean = true,
    /** Each day's net, for the total on its date header. */
    val dailyNet: Map<LocalDate, Money> = emptyMap(),
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

        transactionRepository.observeAllTags()
            .onEach { tags -> _uiState.update { it.copy(tags = tags) } }
            .launchIn(viewModelScope)

        // The totals are separate, non-paged queries: the paged list only knows about the pages it
        // has loaded, so summing it would show a total that grows as the user scrolls. They are
        // streams, so adding or deleting a transaction moves them without touching the filter.
        combine(
            filterFlow.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
            profileRepository.observe().map { it?.currency ?: CurrencyCode.DEFAULT }.distinctUntilChanged(),
        ) { filter, currency -> filter to currency }
            .flatMapLatest { (filter, currency) ->
                // With nothing filtered, the strip shows this month rather than all of history:
                // "₹38 lakh out since 2019" answers nothing anyone asks.
                val thisMonth = !filter.isActive
                val summaryFilter = if (thisMonth) {
                    filter.copy(dateRange = DateRange.ofMonth(clock.today()))
                } else {
                    filter
                }
                combine(
                    transactionRepository.observeFilteredTotal(summaryFilter, currency),
                    transactionRepository.observeDailyNet(filter, currency),
                ) { total, daily -> Triple(total, daily, thisMonth) }
            }
            .onEach { (total, daily, thisMonth) ->
                _uiState.update {
                    it.copy(
                        filteredTotal = total.total,
                        filteredIncome = total.income,
                        filteredCount = total.count,
                        summaryIsThisMonth = thisMonth,
                        dailyNet = daily,
                    )
                }
            }
            .launchIn(viewModelScope)
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
        _uiState.update { it.copy(filter = TransactionFilter()) }
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

    /**
     * Moves a transaction to another category, from a swipe on the list. Teaches the merchant
     * rule too, exactly as picking the category in the editor would.
     */
    fun recategorise(transactionId: String, categoryId: String) {
        viewModelScope.launch {
            val transaction = transactionRepository.findById(transactionId) ?: return@launch
            if (transaction.categoryId == categoryId) return@launch
            transactionRepository.update(transaction.copy(categoryId = categoryId))
        }
    }

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
