package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Loan
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyMath
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * EMI and amortisation maths for a reducing-balance loan.
 *
 * This is the standard Indian EMI formula:
 *
 *     EMI = P · r · (1 + r)^n / ((1 + r)^n − 1)
 *
 * where `r` is the monthly rate (annual ÷ 12 ÷ 100) and `n` the tenure in months. It is computed
 * in [BigDecimal] at [MoneyMath.PRECISION] and only rounded to rupees at the end, so the
 * schedule reconciles: the sum of the principal components equals the principal exactly, with the
 * final instalment absorbing the rounding drift the way a real lender's schedule does.
 *
 * This is a calculator, not advice. It tells the user what their existing loan costs; it does not
 * recommend loans, refinancing, or prepayment.
 */
object LoanCalculator {

    private val TWELVE = BigDecimal("12")
    private val HUNDRED = BigDecimal("100")

    /**
     * The monthly instalment for [loan].
     *
     * Returns [Loan.emiOverride] when set — lenders round differently and the user's actual
     * debit should win over our arithmetic.
     */
    fun emi(loan: Loan): Money {
        loan.emiOverride?.let { return it }
        return computeEmi(loan.principal, loan.annualInterestRatePercent, loan.tenureMonths)
    }

    /**
     * EMI from raw terms.
     *
     * A zero interest rate is handled separately: the general formula divides by zero there,
     * and interest-free instalment plans (no-cost EMI on consumer purchases) are common enough
     * in India that this is a real case rather than a defensive branch.
     */
    fun computeEmi(
        principal: Money,
        annualRatePercent: BigDecimal,
        tenureMonths: Int,
    ): Money {
        require(tenureMonths > 0) { "Tenure must be positive, got $tenureMonths" }
        if (annualRatePercent.signum() == 0) {
            return principal / tenureMonths
        }
        val monthlyRate = monthlyRate(annualRatePercent)
        val growth = onePlus(monthlyRate).pow(tenureMonths, MoneyMath.PRECISION)
        val numerator = principal.amount.multiply(monthlyRate).multiply(growth)
        val denominator = growth.subtract(BigDecimal.ONE)
        return Money.ofExact(
            numerator.divide(denominator, MoneyMath.PRECISION),
            principal.currency,
        )
    }

    /**
     * The full instalment-by-instalment schedule.
     *
     * The last row is adjusted so the principal components sum to exactly the principal borrowed;
     * without that the schedule ends a few rupees off and the "remaining balance" never reaches
     * zero, which users notice and rightly distrust.
     */
    fun schedule(loan: Loan): List<AmortisationEntry> {
        val currency = loan.principal.currency
        val instalment = emi(loan)
        val monthlyRate = monthlyRate(loan.annualInterestRatePercent)

        val entries = ArrayList<AmortisationEntry>(loan.tenureMonths)
        var outstanding = loan.principal
        var dueDate = firstInstalmentDate(loan)

        for (number in 1..loan.tenureMonths) {
            val isLast = number == loan.tenureMonths
            val interest = Money.ofExact(outstanding.amount.multiply(monthlyRate), currency)

            // The final instalment clears whatever is left rather than repeating the EMI, so
            // rounding drift accumulated over the tenure lands in one place and the balance
            // genuinely reaches zero.
            val principalPart = if (isLast) outstanding else (instalment - interest)
            val payment = if (isLast) outstanding + interest else instalment

            val closing = (outstanding - principalPart).floorAtZero()
            entries += AmortisationEntry(
                instalmentNumber = number,
                dueOn = dueDate,
                openingBalance = outstanding,
                payment = payment,
                principalComponent = principalPart,
                interestComponent = interest,
                closingBalance = closing,
            )
            outstanding = closing
            dueDate = nextInstalmentDate(dueDate, loan.emiDayOfMonth)

            // A rate high enough that interest exceeds the EMI would never amortise. The Loan
            // model rejects rates above 100% p.a., but stopping here keeps the loop finite for
            // any terms that slip through rather than emitting a nonsense schedule.
            if (outstanding.isZero) {
                break
            }
        }
        return entries
    }

    /**
     * Where a loan stands as of [asOf], assuming instalments were paid on schedule.
     *
     * "As scheduled" is stated explicitly in the UI: we do not attempt to reconcile against
     * actual bank debits, so a missed payment is not reflected here.
     */
    fun status(loan: Loan, asOf: LocalDate): LoanStatus {
        val currency = loan.principal.currency
        val schedule = schedule(loan)
        val paid = schedule.filter { !it.dueOn.isAfter(asOf) }

        val principalRepaid = paid.fold(Money.zero(currency)) { sum, e -> sum + e.principalComponent }
        val interestPaid = paid.fold(Money.zero(currency)) { sum, e -> sum + e.interestComponent }
        val totalInterest = schedule.fold(Money.zero(currency)) { sum, e -> sum + e.interestComponent }
        val outstanding = (loan.principal - principalRepaid).floorAtZero()

        val next = schedule.firstOrNull { it.dueOn.isAfter(asOf) }
        val percentRepaid = principalRepaid.percentageOf(loan.principal) ?: BigDecimal.ZERO

        return LoanStatus(
            loan = loan,
            emi = emi(loan),
            instalmentsPaid = paid.size,
            instalmentsRemaining = (schedule.size - paid.size).coerceAtLeast(0),
            principalRepaid = principalRepaid,
            outstandingPrincipal = outstanding,
            interestPaid = interestPaid,
            interestRemaining = (totalInterest - interestPaid).floorAtZero(),
            totalInterest = totalInterest,
            totalPayable = loan.principal + totalInterest,
            percentRepaid = percentRepaid,
            nextInstalment = next,
            closesOn = schedule.lastOrNull()?.dueOn,
        )
    }

    /** Total interest over the life of the loan. */
    fun totalInterest(loan: Loan): Money =
        schedule(loan).fold(Money.zero(loan.principal.currency)) { sum, e -> sum + e.interestComponent }

    private fun monthlyRate(annualRatePercent: BigDecimal): BigDecimal =
        annualRatePercent.divide(HUNDRED, MoneyMath.PRECISION).divide(TWELVE, MoneyMath.PRECISION)

    private fun onePlus(rate: BigDecimal): BigDecimal = BigDecimal.ONE.add(rate)

    private fun firstInstalmentDate(loan: Loan): LocalDate {
        val start = loan.startDate
        val candidate = clampDay(start.year, start.monthValue, loan.emiDayOfMonth)
        // The first EMI falls on or after the disbursement date.
        return if (candidate.isBefore(start)) {
            val next = start.plusMonths(1)
            clampDay(next.year, next.monthValue, loan.emiDayOfMonth)
        } else {
            candidate
        }
    }

    private fun nextInstalmentDate(current: LocalDate, day: Int): LocalDate {
        val next = current.plusMonths(1)
        return clampDay(next.year, next.monthValue, day)
    }

    /** Clamps the EMI day into a short month — the 31st becomes the 28th or 29th in February. */
    private fun clampDay(year: Int, month: Int, day: Int): LocalDate {
        val yearMonth = java.time.YearMonth.of(year, month)
        return yearMonth.atDay(day.coerceAtMost(yearMonth.lengthOfMonth()))
    }
}

/** One row of an amortisation schedule. */
data class AmortisationEntry(
    val instalmentNumber: Int,
    val dueOn: LocalDate,
    val openingBalance: Money,
    val payment: Money,
    val principalComponent: Money,
    val interestComponent: Money,
    val closingBalance: Money,
)

/** A loan's position at a point in time, assuming payments were made on schedule. */
data class LoanStatus(
    val loan: Loan,
    val emi: Money,
    val instalmentsPaid: Int,
    val instalmentsRemaining: Int,
    val principalRepaid: Money,
    val outstandingPrincipal: Money,
    val interestPaid: Money,
    val interestRemaining: Money,
    val totalInterest: Money,
    val totalPayable: Money,
    val percentRepaid: BigDecimal,
    val nextInstalment: AmortisationEntry?,
    val closesOn: LocalDate?,
) {
    val isClosed: Boolean get() = outstandingPrincipal.isZero

    val percentRepaidClamped: Int
        get() = percentRepaid.setScale(0, RoundingMode.HALF_EVEN).toInt().coerceIn(0, 100)

    fun monthsRemaining(asOf: LocalDate): Long =
        closesOn?.let { ChronoUnit.MONTHS.between(asOf, it).coerceAtLeast(0) } ?: 0
}
