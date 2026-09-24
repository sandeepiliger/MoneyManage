package ai.labs32.khaata.feature.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.calc.BalanceCalculator
import ai.labs32.khaata.core.calc.CreditCardStatus
import ai.labs32.khaata.core.calc.LoanStatus
import ai.labs32.khaata.core.calc.NetWorthSummary
import ai.labs32.khaata.core.calc.OffLedgerHoldings
import ai.labs32.khaata.core.calc.PortfolioSummary
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.AccountBalance
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CreditCardRepository
import ai.labs32.khaata.data.repository.InvestmentRepository
import ai.labs32.khaata.data.repository.LoanRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

/** Everything the Money tab shows. */
data class MoneyUiState(
    val isLoading: Boolean = true,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val netWorth: NetWorthSummary? = null,
    val netWorthTrend: List<Float> = emptyList(),
    val netWorthChangePercent: BigDecimal? = null,
    /** Bank, savings, current, cash and wallet accounts -- the money that can be spent. */
    val spendable: List<AccountBalance> = emptyList(),
    val cards: List<CreditCardStatus> = emptyList(),
    /** Credit-card accounts with no card set up behind them, so no cycle or limit to show. */
    val unlinkedCardAccounts: List<AccountBalance> = emptyList(),
    val loans: List<LoanStatus> = emptyList(),
    /** Loan accounts not already represented by a loan above. */
    val unlinkedLoanAccounts: List<AccountBalance> = emptyList(),
    val portfolio: PortfolioSummary? = null,
    val investmentAccounts: List<AccountBalance> = emptyList(),
    val otherAccounts: List<AccountBalance> = emptyList(),
) {
    val hasCards: Boolean get() = cards.isNotEmpty() || unlinkedCardAccounts.isNotEmpty()
    val hasLoans: Boolean get() = loans.isNotEmpty() || unlinkedLoanAccounts.isNotEmpty()
    val hasInvestments: Boolean
        get() = (portfolio?.holdingsCount ?: 0) > 0 || investmentAccounts.isNotEmpty()
}

/**
 * Net worth and everything it is made of.
 *
 * Accounts, cards, loans and investments used to be four screens under More, each with its own
 * total and none with the whole picture. Here they are one list grouped the way people think
 * about them -- what I can spend, what I owe on cards, what I am paying off, what is growing --
 * under the one figure they add up to.
 */
@HiltViewModel
class MoneyViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val creditCardRepository: CreditCardRepository,
    private val loanRepository: LoanRepository,
    private val investmentRepository: InvestmentRepository,
    private val profileRepository: ProfileRepository,
    private val transactionRepository: TransactionRepository,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoneyUiState())
    val uiState: StateFlow<MoneyUiState> = _uiState.asStateFlow()

    init {
        // Same inputs, in the same pairing, as the dashboard's net worth, so the two screens can
        // never disagree about the figure.
        val balancesAndHoldings = combine(
            accountRepository.observeBalances(),
            investmentRepository.observeOpen(),
            loanRepository.observeOpen(),
        ) { balances, investments, loans -> balances to OffLedgerHoldings(investments, loans) }

        combine(
            balancesAndHoldings,
            creditCardRepository.observeStatuses(),
            loanRepository.observeStatuses(),
            investmentRepository.observePortfolio(),
            profileRepository.observe(),
        ) { (balances, holdings), cards, loans, portfolio, profile ->
            val currency = profile?.currency ?: CurrencyCode.DEFAULT
            val active = balances.filter { !it.account.isArchived }
            val cardAccountIds = cards.map { it.card.accountId }.toSet()
            val openLoans = loans.filter { !it.isClosed }
            val loanAccountIds = openLoans.mapNotNull { it.loan.accountId }.toSet()

            MoneyUiState(
                isLoading = false,
                currency = currency,
                netWorth = BalanceCalculator.netWorth(balances, currency, holdings, asOf = clock.today()),
                spendable = active.filter { it.account.type in SPENDABLE_TYPES },
                cards = cards,
                unlinkedCardAccounts = active.filter {
                    it.account.type == AccountType.CREDIT_CARD && it.account.id !in cardAccountIds
                },
                loans = openLoans,
                unlinkedLoanAccounts = active.filter {
                    it.account.type == AccountType.LOAN && it.account.id !in loanAccountIds
                },
                portfolio = portfolio,
                investmentAccounts = active.filter { it.account.type == AccountType.INVESTMENT },
                otherAccounts = active.filter { it.account.type == AccountType.OTHER },
            )
        }
            .flowOn(Dispatchers.Default)
            .onEach { data ->
                // Field by field, so the trend written by refreshTrend is never overwritten.
                _uiState.update {
                    data.copy(
                        netWorthTrend = it.netWorthTrend,
                        netWorthChangePercent = it.netWorthChangePercent,
                    )
                }
            }
            .catch { error ->
                KhaataLog.e(TAG, "Money stream failed", error)
                _uiState.update { it.copy(isLoading = false) }
            }
            .launchIn(viewModelScope)

        refreshTrend()
    }

    /**
     * Six months of net worth, for the sparkline and the change figure. Computed the same way as
     * on Home: every point is the opening balances plus the whole history up to that date.
     */
    private fun refreshTrend() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val today = clock.today()
                val months = DateRange.trailingMonths(today, TREND_MONTHS)
                val points = BalanceCalculator.netWorthTrend(
                    accounts = accountRepository.getAll(),
                    transactions = transactionRepository.getInRange(DateRange(HISTORY_START, today)),
                    dates = months.map { minOf(it.endInclusive, today) },
                    currency = _uiState.value.currency,
                    holdings = OffLedgerHoldings(investmentRepository.getAll(), loanRepository.getAll()),
                )
                val change = if (points.size >= 2) {
                    BalanceCalculator.percentChange(
                        previous = points[points.size - 2].netWorth,
                        current = points.last().netWorth,
                    )
                } else {
                    null
                }
                _uiState.update { state ->
                    state.copy(
                        netWorthTrend = points.map { it.netWorth.amount.toFloat() },
                        netWorthChangePercent = change,
                    )
                }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Net worth trend failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "MoneyViewModel"
        const val TREND_MONTHS = 6

        /** Earlier than any transaction anyone will have: "the whole history" as a range. */
        val HISTORY_START: LocalDate = LocalDate.of(1970, 1, 1)

        val SPENDABLE_TYPES = setOf(
            AccountType.BANK,
            AccountType.SAVINGS,
            AccountType.CURRENT,
            AccountType.CASH,
            AccountType.WALLET,
        )
    }
}
