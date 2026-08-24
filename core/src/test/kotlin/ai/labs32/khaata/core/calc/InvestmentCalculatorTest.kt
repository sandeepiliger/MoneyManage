package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.InvestmentKind
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class InvestmentCalculatorTest {

    private val today = LocalDate.of(2026, 3, 15)

    @Test
    fun `gain and absolute return are reported for a profitable holding`() {
        val investment = Fixtures.investment(invested = "100000", currentValue = "118000")
        val performance = InvestmentCalculator.performanceOf(investment, today)

        assertThat(performance.gain).isEqualTo(Money.of("18000"))
        assertThat(performance.absoluteReturnPercent).isEqualTo(BigDecimal("18.0000"))
        assertThat(performance.isProfit).isTrue()
    }

    @Test
    fun `a loss is reported as a negative gain`() {
        val investment = Fixtures.investment(invested = "100000", currentValue = "82000")
        val performance = InvestmentCalculator.performanceOf(investment, today)

        assertThat(performance.gain).isEqualTo(Money.of("-18000"))
        assertThat(performance.absoluteReturnPercent).isEqualTo(BigDecimal("-18.0000"))
        assertThat(performance.isProfit).isFalse()
    }

    @Test
    fun `a holding under a year old reports no annualised figure`() {
        // Annualising three months of movement produces a headline number that misinforms.
        val young = Fixtures.investment(
            invested = "100000",
            currentValue = "112000",
            startedOn = LocalDate.of(2026, 1, 1),
            valuedOn = today,
        )
        val performance = InvestmentCalculator.performanceOf(young, today)

        assertThat(performance.absoluteReturnPercent).isEqualTo(BigDecimal("12.0000"))
        assertThat(performance.annualisedReturnPercent).isNull()
    }

    @Test
    fun `a two-year holding reports a plausible annualised figure`() {
        // 100,000 -> 121,000 over two years is close to 10% a year compounded.
        val investment = Fixtures.investment(
            invested = "100000",
            currentValue = "121000",
            startedOn = LocalDate.of(2024, 3, 15),
            valuedOn = LocalDate.of(2026, 3, 15),
        )
        val annualised = InvestmentCalculator.performanceOf(investment, today).annualisedReturnPercent

        assertThat(annualised).isNotNull()
        assertThat(annualised!!.toDouble()).isWithin(0.2).of(10.0)
    }

    @Test
    fun `a wiped-out holding reports no annualised figure rather than negative infinity`() {
        val wipeout = Fixtures.investment(
            invested = "100000",
            currentValue = "0",
            startedOn = LocalDate.of(2023, 1, 1),
            valuedOn = today,
        )
        assertThat(InvestmentCalculator.performanceOf(wipeout, today).annualisedReturnPercent)
            .isNull()
    }

    @Test
    fun `a zero-cost holding reports no return percentages`() {
        val gifted = Fixtures.investment(invested = "0", currentValue = "5000")
        val performance = InvestmentCalculator.performanceOf(gifted, today)

        assertThat(performance.absoluteReturnPercent).isNull()
        assertThat(performance.annualisedReturnPercent).isNull()
        assertThat(performance.gain).isEqualTo(Money.of("5000"))
    }

    @Test
    fun `a stale valuation is flagged so the UI can prompt for a refresh`() {
        val stale = Fixtures.investment(valuedOn = LocalDate.of(2026, 1, 1))
        val performance = InvestmentCalculator.performanceOf(stale, today)

        assertThat(performance.valuationAgeDays).isEqualTo(73)
        assertThat(performance.isValuationStale).isTrue()

        val fresh = Fixtures.investment(valuedOn = LocalDate.of(2026, 3, 10))
        assertThat(InvestmentCalculator.performanceOf(fresh, today).isValuationStale).isFalse()
    }

    @Test
    fun `portfolio totals aggregate across holdings`() {
        val holdings = listOf(
            Fixtures.investment(id = "a", invested = "100000", currentValue = "118000"),
            Fixtures.investment(
                id = "b", kind = InvestmentKind.FIXED_DEPOSIT,
                invested = "200000", currentValue = "214000",
            ),
        )
        val portfolio = InvestmentCalculator.portfolio(holdings, today)

        assertThat(portfolio.invested).isEqualTo(Money.of("300000"))
        assertThat(portfolio.currentValue).isEqualTo(Money.of("332000"))
        assertThat(portfolio.gain).isEqualTo(Money.of("32000"))
        assertThat(portfolio.returnPercent).isEqualTo(BigDecimal("10.6667"))
        assertThat(portfolio.holdingsCount).isEqualTo(2)
    }

    @Test
    fun `portfolio allocation shares are computed per kind`() {
        val holdings = listOf(
            Fixtures.investment(id = "a", kind = InvestmentKind.MUTUAL_FUND, invested = "50000", currentValue = "75000"),
            Fixtures.investment(id = "b", kind = InvestmentKind.GOLD, invested = "20000", currentValue = "25000"),
        )
        val portfolio = InvestmentCalculator.portfolio(holdings, today)
        val gold = portfolio.allocationByKind.getValue(InvestmentKind.GOLD)

        assertThat(gold.currentValue).isEqualTo(Money.of("25000"))
        assertThat(gold.shareOfPortfolioPercent).isEqualTo(BigDecimal("25.0000"))
    }

    @Test
    fun `closed holdings are excluded from the portfolio`() {
        val holdings = listOf(
            Fixtures.investment(id = "a", invested = "100000", currentValue = "118000"),
            Fixtures.investment(id = "b", invested = "500000", currentValue = "600000").copy(isClosed = true),
        )
        assertThat(InvestmentCalculator.portfolio(holdings, today).invested)
            .isEqualTo(Money.of("100000"))
    }

    @Test
    fun `an empty portfolio reports zeroes rather than failing`() {
        val portfolio = InvestmentCalculator.portfolio(emptyList(), today)

        assertThat(portfolio.invested).isEqualTo(Money.zero())
        assertThat(portfolio.returnPercent).isNull()
        assertThat(portfolio.holdingsCount).isEqualTo(0)
        assertThat(portfolio.oldestValuationOn).isNull()
    }

    @Test
    fun `SIP future value compounds each instalment`() {
        // ₹5,000/month for 12 months at 12% p.a. exceeds the ₹60,000 contributed.
        val value = InvestmentCalculator.futureValueOfSip(Money.of("5000"), BigDecimal("12"), 12)

        assertThat(value).isGreaterThan(Money.of("60000"))
        assertThat(value).isLessThan(Money.of("65000"))
    }

    @Test
    fun `a zero rate SIP simply sums the instalments`() {
        assertThat(InvestmentCalculator.futureValueOfSip(Money.of("5000"), BigDecimal.ZERO, 12))
            .isEqualTo(Money.of("60000"))
    }

    @Test
    fun `invalid SIP inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentCalculator.futureValueOfSip(Money.of("5000"), BigDecimal("12"), 0)
        }
    }

    @Test
    fun `a valuation dated before the purchase is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fixtures.investment(startedOn = LocalDate.of(2026, 3, 1), valuedOn = LocalDate.of(2026, 1, 1))
        }
    }
}
