package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.Loan
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Net worth counts what the user tracks on the Investments and Loans screens, once.
 */
class NetWorthHoldingsTest {

    private val today = LocalDate.of(2026, 3, 15)
    private val bank = Fixtures.account(id = "acc-bank", openingBalance = "100000")
    private val card = Fixtures.account(id = "acc-card", type = AccountType.CREDIT_CARD, openingBalance = "-20000")
    private val balances = BalanceCalculator.balances(listOf(bank, card), emptyList())

    private val fund = Fixtures.investment(invested = "100000", currentValue = "118000", valuedOn = today)
    private val homeLoan = Loan(
        id = "loan-home",
        name = "Home loan",
        principal = Money.of("3000000"),
        annualInterestRatePercent = BigDecimal("8.5"),
        tenureMonths = 240,
        startDate = LocalDate.of(2025, 3, 5),
    )

    @Test
    fun `investments add and loans subtract`() {
        val outstanding = LoanCalculator.status(homeLoan, today).outstandingPrincipal
        val summary = BalanceCalculator.netWorth(
            balances,
            CurrencyCode.INR,
            OffLedgerHoldings(listOf(fund), listOf(homeLoan)),
            asOf = today,
        )

        assertThat(summary.investments).isEqualTo(Money.of("118000"))
        assertThat(summary.loans).isEqualTo(outstanding)
        assertThat(summary.assets).isEqualTo(Money.of("218000"))
        assertThat(summary.liabilities).isEqualTo(Money.of("20000") + outstanding)
        assertThat(summary.netWorth).isEqualTo(Money.of("198000") - outstanding)
        assertThat(summary.assets - summary.liabilities).isEqualTo(summary.netWorth)
    }

    /** A holding linked to an account is already in that account's balance. */
    @Test
    fun `anything linked to an account is not counted a second time`() {
        val summary = BalanceCalculator.netWorth(
            balances,
            CurrencyCode.INR,
            OffLedgerHoldings(
                listOf(fund.copy(accountId = "acc-bank")),
                listOf(homeLoan.copy(accountId = "acc-card")),
            ),
            asOf = today,
        )
        assertThat(summary.netWorth).isEqualTo(Money.of("80000"))
    }

    @Test
    fun `closed holdings and ones not yet started count for nothing`() {
        val summary = BalanceCalculator.netWorth(
            balances,
            CurrencyCode.INR,
            OffLedgerHoldings(
                listOf(fund.copy(isClosed = true), fund.copy(id = "future", startedOn = today.plusDays(1), valuedOn = today.plusDays(1))),
                listOf(homeLoan.copy(isClosed = true), homeLoan.copy(id = "future", startDate = today.plusDays(1))),
            ),
            asOf = today,
        )
        assertThat(summary.netWorth).isEqualTo(Money.of("80000"))
    }

    @Test
    fun `without holdings net worth is exactly what it was`() {
        assertThat(BalanceCalculator.netWorth(balances).netWorth).isEqualTo(Money.of("80000"))
    }

    /** The trend's last point is the figure shown above it. */
    @Test
    fun `the trend includes holdings and ends at today's net worth`() {
        val holdings = OffLedgerHoldings(
            listOf(fund.copy(startedOn = LocalDate.of(2026, 1, 10), valuedOn = LocalDate.of(2026, 3, 1))),
            listOf(homeLoan),
        )
        val points = BalanceCalculator.netWorthTrend(
            accounts = listOf(bank, card),
            transactions = emptyList(),
            dates = listOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 31), today),
            currency = CurrencyCode.INR,
            holdings = holdings,
        )
        val loanAt = { d: LocalDate -> LoanCalculator.status(homeLoan, d).outstandingPrincipal }

        // Before the fund was bought: accounts less the loan.
        assertThat(points[0].netWorth).isEqualTo(Money.of("80000") - loanAt(LocalDate.of(2025, 12, 31)))
        // Bought but not yet revalued: counted at what was invested.
        assertThat(points[1].netWorth).isEqualTo(Money.of("180000") - loanAt(LocalDate.of(2026, 1, 31)))
        // Today, the same figure the summary reports.
        val summary = BalanceCalculator.netWorth(balances, CurrencyCode.INR, holdings, asOf = today)
        assertThat(points[2].netWorth).isEqualTo(summary.netWorth)
    }
}
