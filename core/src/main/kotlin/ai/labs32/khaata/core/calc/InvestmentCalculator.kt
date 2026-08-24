package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Investment
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyMath
import ai.labs32.khaata.core.money.sumOfMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Profit, loss and returns for manually tracked holdings.
 *
 * There is no price feed here — [Investment.currentValue] is whatever the user last entered.
 * Everything below is arithmetic on that number, and [InvestmentPerformance.valuationAgeDays]
 * exists so the UI can say how stale the figure is instead of presenting it as live.
 *
 * Annualised return is reported only where it is meaningful (see [annualisedReturnPercent]).
 */
object InvestmentCalculator {

    private val HUNDRED = BigDecimal("100")
    private val DAYS_PER_YEAR = BigDecimal("365.25")

    fun performanceOf(investment: Investment, asOf: LocalDate): InvestmentPerformance {
        val gain = investment.currentValue - investment.investedAmount
        val absoluteReturn = absoluteReturnPercent(investment)
        val holdingDays = ChronoUnit.DAYS.between(investment.startedOn, investment.valuedOn)
            .coerceAtLeast(0)

        return InvestmentPerformance(
            investment = investment,
            gain = gain,
            absoluteReturnPercent = absoluteReturn,
            annualisedReturnPercent = annualisedReturnPercent(investment, holdingDays),
            holdingDays = holdingDays,
            valuationAgeDays = ChronoUnit.DAYS.between(investment.valuedOn, asOf).coerceAtLeast(0),
        )
    }

    /** Simple return: (current − invested) ÷ invested, as a percentage. Null if nothing invested. */
    fun absoluteReturnPercent(investment: Investment): BigDecimal? {
        if (investment.investedAmount.isZero) return null
        val gain = investment.currentValue - investment.investedAmount
        return gain.amount
            .multiply(HUNDRED)
            .divide(investment.investedAmount.amount, MoneyMath.RATIO_SCALE, RoundingMode.HALF_EVEN)
    }

    /**
     * Compound annual growth rate, as a percentage.
     *
     * Returns null when it would mislead rather than inform:
     *  - nothing invested, or the holding has lost all its value (the maths is undefined);
     *  - held for under a year, where annualising a short run produces absurd headline numbers
     *    like "412% p.a." from three weeks of movement.
     *
     * Note this is a CAGR on a single start value, not an XIRR. For a SIP with many instalments
     * it understates the true return, which is why the UI labels SIP entries with the amount
     * invested rather than leading with a rate.
     */
    fun annualisedReturnPercent(investment: Investment, holdingDays: Long): BigDecimal? {
        if (investment.investedAmount.isZero) return null
        if (!investment.currentValue.isPositive) return null
        if (holdingDays < MIN_DAYS_FOR_ANNUALISED) return null

        val ratio = investment.currentValue.amount
            .divide(investment.investedAmount.amount, MoneyMath.PRECISION)
        val years = BigDecimal(holdingDays).divide(DAYS_PER_YEAR, MoneyMath.PRECISION)

        // BigDecimal has no fractional pow, so the root is taken via doubles. The inputs here are
        // small, well-conditioned ratios and the result is a display percentage rounded to four
        // places, so double precision is not a correctness risk — unlike in the money maths,
        // where it would be. The result is returned to BigDecimal immediately.
        val growth = Math.pow(ratio.toDouble(), 1.0 / years.toDouble())
        if (!growth.isFinite()) return null

        return BigDecimal(growth - 1.0)
            .multiply(HUNDRED)
            .setScale(MoneyMath.RATIO_SCALE, RoundingMode.HALF_EVEN)
    }

    /** Portfolio totals across [investments]. */
    fun portfolio(
        investments: List<Investment>,
        asOf: LocalDate,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): PortfolioSummary {
        val open = investments.filter { !it.isClosed }
        val invested = open.sumOfMoney(currency) { it.investedAmount }
        val current = open.sumOfMoney(currency) { it.currentValue }
        val gain = current - invested

        val returnPercent = if (invested.isZero) {
            null
        } else {
            gain.amount.multiply(HUNDRED)
                .divide(invested.amount, MoneyMath.RATIO_SCALE, RoundingMode.HALF_EVEN)
        }

        val byKind = open.groupBy { it.kind }.mapValues { (_, group) ->
            val kindInvested = group.sumOfMoney(currency) { it.investedAmount }
            val kindCurrent = group.sumOfMoney(currency) { it.currentValue }
            KindAllocation(
                invested = kindInvested,
                currentValue = kindCurrent,
                gain = kindCurrent - kindInvested,
                shareOfPortfolioPercent = kindCurrent.percentageOf(current) ?: BigDecimal.ZERO,
            )
        }

        val stalest = open.minByOrNull { it.valuedOn }?.valuedOn

        return PortfolioSummary(
            invested = invested,
            currentValue = current,
            gain = gain,
            returnPercent = returnPercent,
            holdingsCount = open.size,
            allocationByKind = byKind,
            oldestValuationOn = stalest,
            performances = open.map { performanceOf(it, asOf) },
        )
    }

    /**
     * Value of a monthly SIP after [months] instalments at [annualRatePercent].
     *
     * Used by the goal planner to answer "what would ₹5,000 a month become?". Presented as an
     * illustration at a rate the user picks — never as a projection of what a specific fund will
     * do, and never as a recommendation.
     */
    fun futureValueOfSip(
        monthlyAmount: Money,
        annualRatePercent: BigDecimal,
        months: Int,
    ): Money {
        require(months > 0) { "SIP duration must be positive, got $months" }
        require(!monthlyAmount.isNegative) { "SIP amount cannot be negative" }
        if (annualRatePercent.signum() == 0) return monthlyAmount * months

        val monthlyRate = annualRatePercent
            .divide(HUNDRED, MoneyMath.PRECISION)
            .divide(BigDecimal("12"), MoneyMath.PRECISION)
        val growth = BigDecimal.ONE.add(monthlyRate).pow(months, MoneyMath.PRECISION)
        // FV of an annuity-due (instalment invested at the start of each month).
        val factor = growth.subtract(BigDecimal.ONE)
            .divide(monthlyRate, MoneyMath.PRECISION)
            .multiply(BigDecimal.ONE.add(monthlyRate))
        return Money.ofExact(monthlyAmount.amount.multiply(factor), monthlyAmount.currency)
    }

    /** Below a year, an annualised figure is noise rather than signal. */
    private const val MIN_DAYS_FOR_ANNUALISED = 365L
}

data class InvestmentPerformance(
    val investment: Investment,
    /** Positive for a gain, negative for a loss. */
    val gain: Money,
    val absoluteReturnPercent: BigDecimal?,
    /** Null for holdings under a year old, or where the maths would not be meaningful. */
    val annualisedReturnPercent: BigDecimal?,
    val holdingDays: Long,
    /** How many days old the user's last valuation is. */
    val valuationAgeDays: Long,
) {
    val isProfit: Boolean get() = gain.isPositive

    /** True when the valuation is old enough that the UI should prompt for a refresh. */
    val isValuationStale: Boolean get() = valuationAgeDays > STALE_AFTER_DAYS

    companion object {
        const val STALE_AFTER_DAYS = 30L
    }
}

data class PortfolioSummary(
    val invested: Money,
    val currentValue: Money,
    val gain: Money,
    val returnPercent: BigDecimal?,
    val holdingsCount: Int,
    val allocationByKind: Map<ai.labs32.khaata.core.model.InvestmentKind, KindAllocation>,
    val oldestValuationOn: LocalDate?,
    val performances: List<InvestmentPerformance>,
)

data class KindAllocation(
    val invested: Money,
    val currentValue: Money,
    val gain: Money,
    val shareOfPortfolioPercent: BigDecimal,
)
