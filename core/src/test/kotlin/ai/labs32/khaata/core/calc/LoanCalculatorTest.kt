package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Loan
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.sumOfMoney
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LoanCalculatorTest {

    /** ₹30,00,000 home loan at 8.5% for 20 years — a representative Indian home loan. */
    private val homeLoan = Loan(
        id = "loan-home",
        name = "Home Loan",
        lender = "HDFC",
        principal = Money.of("3000000"),
        annualInterestRatePercent = BigDecimal("8.5"),
        tenureMonths = 240,
        startDate = LocalDate.of(2026, 1, 10),
    )

    /** ₹5,00,000 personal loan at 12% for 3 years. */
    private val personalLoan = Loan(
        id = "loan-personal",
        name = "Personal Loan",
        principal = Money.of("500000"),
        annualInterestRatePercent = BigDecimal("12"),
        tenureMonths = 36,
        startDate = LocalDate.of(2026, 1, 15),
    )

    @Test
    fun `EMI matches the standard reducing-balance formula`() {
        // P=3,000,000, monthly rate 8.5/12/100 = 0.00708333…, n=240.
        // EMI = P·r·(1+r)^n / ((1+r)^n − 1) = 26,034.697… -> 26,034.70 at rupee scale.
        val emi = LoanCalculator.emi(homeLoan)
        assertThat(emi.toPlainString()).isEqualTo("26034.70")
    }

    @Test
    fun `EMI matches for a shorter higher-rate loan`() {
        // P=500000, monthly rate 1%, n=36 -> ₹16,607.15.
        assertThat(LoanCalculator.emi(personalLoan).toPlainString()).isEqualTo("16607.15")
    }

    @Test
    fun `a zero interest loan divides the principal evenly`() {
        val noCostEmi = Loan(
            id = "loan-nocost",
            name = "No-cost EMI",
            principal = Money.of("36000"),
            annualInterestRatePercent = BigDecimal.ZERO,
            tenureMonths = 6,
            startDate = LocalDate.of(2026, 3, 1),
        )
        assertThat(LoanCalculator.emi(noCostEmi)).isEqualTo(Money.of("6000"))
        assertThat(LoanCalculator.totalInterest(noCostEmi)).isEqualTo(Money.zero())
    }

    @Test
    fun `a lender-supplied EMI overrides our computed figure`() {
        val withOverride = homeLoan.copy(emiOverride = Money.of("26035"))
        assertThat(LoanCalculator.emi(withOverride)).isEqualTo(Money.of("26035"))
    }

    @Test
    fun `the schedule has one row per instalment`() {
        assertThat(LoanCalculator.schedule(personalLoan)).hasSize(36)
    }

    @Test
    fun `principal components sum to exactly the principal borrowed`() {
        // This is the property that makes the schedule trustworthy: no drift, no leftover paise.
        for (loan in listOf(homeLoan, personalLoan)) {
            val schedule = LoanCalculator.schedule(loan)
            val repaid = schedule.sumOfMoney { it.principalComponent }
            assertThat(repaid).isEqualTo(loan.principal)
        }
    }

    @Test
    fun `the schedule closes at exactly zero`() {
        val schedule = LoanCalculator.schedule(personalLoan)
        assertThat(schedule.last().closingBalance).isEqualTo(Money.zero())
    }

    @Test
    fun `each row reconciles - payment equals principal plus interest`() {
        for (entry in LoanCalculator.schedule(personalLoan)) {
            assertThat(entry.principalComponent + entry.interestComponent).isEqualTo(entry.payment)
            assertThat(entry.openingBalance - entry.principalComponent)
                .isEqualTo(entry.closingBalance)
        }
    }

    @Test
    fun `interest falls and principal rises across the tenure`() {
        val schedule = LoanCalculator.schedule(homeLoan)
        val first = schedule.first()
        val last = schedule.last()

        assertThat(first.interestComponent).isGreaterThan(first.principalComponent)
        assertThat(last.principalComponent).isGreaterThan(last.interestComponent)
        assertThat(last.interestComponent).isLessThan(first.interestComponent)
    }

    @Test
    fun `the first instalment falls on the EMI day on or after disbursement`() {
        // Disbursed 10 Jan with an EMI day of 10 -> first instalment is 10 Jan.
        assertThat(LoanCalculator.schedule(homeLoan).first().dueOn)
            .isEqualTo(LocalDate.of(2026, 1, 10))

        // Disbursed 20 Jan with an EMI day of 5 -> first instalment rolls to 5 Feb.
        val fifthOfMonth = homeLoan.copy(startDate = LocalDate.of(2026, 1, 20), emiDayOfMonth = 5)
        assertThat(LoanCalculator.schedule(fifthOfMonth).first().dueOn)
            .isEqualTo(LocalDate.of(2026, 2, 5))
    }

    @Test
    fun `an EMI day of 31 clamps into short months`() {
        val monthEnd = personalLoan.copy(
            startDate = LocalDate.of(2026, 1, 31),
            emiDayOfMonth = 31,
            tenureMonths = 4,
        )
        assertThat(LoanCalculator.schedule(monthEnd).map { it.dueOn }).containsExactly(
            LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 30),
        ).inOrder()
    }

    @Test
    fun `total interest equals total payable minus principal`() {
        val status = LoanCalculator.status(personalLoan, LocalDate.of(2026, 1, 1))
        assertThat(status.totalPayable - personalLoan.principal).isEqualTo(status.totalInterest)
    }

    @Test
    fun `status reports progress partway through the tenure`() {
        // Disbursed 15 Jan 2026, so instalments fall on the 15th from Jan 2026 onward.
        // By 20 Jan 2027 that is Jan 2026 through Jan 2027 inclusive: 13 instalments.
        val status = LoanCalculator.status(personalLoan, LocalDate.of(2027, 1, 20))

        assertThat(status.instalmentsPaid).isEqualTo(13)
        assertThat(status.instalmentsRemaining).isEqualTo(23)
        assertThat(status.outstandingPrincipal).isLessThan(personalLoan.principal)
        assertThat(status.outstandingPrincipal).isGreaterThan(Money.zero())
        assertThat(status.principalRepaid + status.outstandingPrincipal)
            .isEqualTo(personalLoan.principal)
        assertThat(status.nextInstalment!!.dueOn).isEqualTo(LocalDate.of(2027, 2, 15))
    }

    @Test
    fun `status before the first instalment shows nothing repaid`() {
        val status = LoanCalculator.status(personalLoan, LocalDate.of(2026, 1, 1))

        assertThat(status.instalmentsPaid).isEqualTo(0)
        assertThat(status.principalRepaid).isEqualTo(Money.zero())
        assertThat(status.outstandingPrincipal).isEqualTo(personalLoan.principal)
        assertThat(status.percentRepaidClamped).isEqualTo(0)
    }

    @Test
    fun `status after the tenure shows the loan fully repaid`() {
        val status = LoanCalculator.status(personalLoan, LocalDate.of(2030, 1, 1))

        assertThat(status.isClosed).isTrue()
        assertThat(status.outstandingPrincipal).isEqualTo(Money.zero())
        assertThat(status.interestRemaining).isEqualTo(Money.zero())
        assertThat(status.percentRepaidClamped).isEqualTo(100)
        assertThat(status.nextInstalment).isNull()
    }

    @Test
    fun `invalid loan terms are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            homeLoan.copy(tenureMonths = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            homeLoan.copy(annualInterestRatePercent = BigDecimal("-1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            homeLoan.copy(annualInterestRatePercent = BigDecimal("150"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            homeLoan.copy(emiDayOfMonth = 32)
        }
    }

    @Test
    fun `a single-instalment loan repays the whole principal at once`() {
        val bullet = Loan(
            id = "loan-bullet",
            name = "One instalment",
            principal = Money.of("10000"),
            annualInterestRatePercent = BigDecimal("12"),
            tenureMonths = 1,
            startDate = LocalDate.of(2026, 3, 1),
        )
        val schedule = LoanCalculator.schedule(bullet)

        assertThat(schedule).hasSize(1)
        assertThat(schedule.single().principalComponent).isEqualTo(Money.of("10000"))
        assertThat(schedule.single().closingBalance).isEqualTo(Money.zero())
        assertThat(schedule.single().interestComponent).isEqualTo(Money.of("100"))
    }
}
