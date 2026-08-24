package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.CreditCard
import ai.labs32.khaata.core.model.UtilisationBand
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CreditCardCalculatorTest {

    private val cardAccount = Fixtures.account(
        id = "acc-card",
        name = "HDFC Millennia",
        type = AccountType.CREDIT_CARD,
        openingBalance = "0",
    )

    /** Statement on the 25th, payment due on the 14th of the following month. */
    private val card = CreditCard(
        id = "cc-1",
        accountId = "acc-card",
        cardName = "HDFC Millennia",
        issuer = "HDFC Bank",
        creditLimit = Money.of("200000"),
        statementDayOfMonth = 25,
        dueDayOfMonth = 14,
        lastFourDigits = "4321",
    )

    private fun statusOn(date: LocalDate, transactions: List<ai.labs32.khaata.core.model.Transaction>) =
        CreditCardCalculator.status(
            card = card,
            balance = BalanceCalculator.balances(listOf(cardAccount), transactions).single(),
            transactions = transactions,
            asOf = date,
        )

    // ---- Cycles ------------------------------------------------------------------------------

    @Test
    fun `a date before the statement day falls in the cycle closing this month`() {
        val cycle = CreditCardCalculator.cycleContaining(card, LocalDate.of(2026, 3, 10))
        assertThat(cycle.start).isEqualTo(LocalDate.of(2026, 2, 26))
        assertThat(cycle.endInclusive).isEqualTo(LocalDate.of(2026, 3, 25))
    }

    @Test
    fun `a date after the statement day falls in the next cycle`() {
        val cycle = CreditCardCalculator.cycleContaining(card, LocalDate.of(2026, 3, 26))
        assertThat(cycle.start).isEqualTo(LocalDate.of(2026, 3, 26))
        assertThat(cycle.endInclusive).isEqualTo(LocalDate.of(2026, 4, 25))
    }

    @Test
    fun `the statement day itself closes the cycle`() {
        val cycle = CreditCardCalculator.cycleContaining(card, LocalDate.of(2026, 3, 25))
        assertThat(cycle.endInclusive).isEqualTo(LocalDate.of(2026, 3, 25))
    }

    @Test
    fun `a statement day of 31 clamps into February`() {
        val monthEndCard = card.copy(statementDayOfMonth = 31)
        val cycle = CreditCardCalculator.cycleContaining(monthEndCard, LocalDate.of(2026, 2, 10))
        assertThat(cycle.endInclusive).isEqualTo(LocalDate.of(2026, 2, 28))
    }

    // ---- Due dates ---------------------------------------------------------------------------

    @Test
    fun `a due day before the statement day rolls into the following month`() {
        // This is the common Indian arrangement and the case most implementations get wrong.
        assertThat(CreditCardCalculator.dueDateFor(card, LocalDate.of(2026, 3, 25)))
            .isEqualTo(LocalDate.of(2026, 4, 14))
    }

    @Test
    fun `a due day after the statement day stays in the same month`() {
        val earlyStatement = card.copy(statementDayOfMonth = 5, dueDayOfMonth = 25)
        assertThat(CreditCardCalculator.dueDateFor(earlyStatement, LocalDate.of(2026, 3, 5)))
            .isEqualTo(LocalDate.of(2026, 3, 25))
    }

    // ---- Outstanding and utilisation ---------------------------------------------------------

    @Test
    fun `outstanding is reported as a positive amount owed`() {
        val transactions = listOf(
            Fixtures.expense(amount = "18000", accountId = "acc-card", on = LocalDate.of(2026, 3, 5)),
        )
        val status = statusOn(LocalDate.of(2026, 3, 10), transactions)

        assertThat(status.outstanding).isEqualTo(Money.of("18000"))
        assertThat(status.availableCredit).isEqualTo(Money.of("182000"))
    }

    @Test
    fun `paying the card down reduces the outstanding amount`() {
        val transactions = listOf(
            Fixtures.expense(amount = "18000", accountId = "acc-card", on = LocalDate.of(2026, 3, 5)),
            // A payment is a transfer from the bank account into the card account.
            Fixtures.transfer(
                amount = "10000",
                fromAccountId = "acc-hdfc",
                toAccountId = "acc-card",
                on = LocalDate.of(2026, 3, 8),
            ),
        )
        val status = statusOn(LocalDate.of(2026, 3, 10), transactions)
        assertThat(status.outstanding).isEqualTo(Money.of("8000"))
    }

    @Test
    fun `a card in credit reports zero outstanding and full available credit`() {
        val transactions = listOf(
            Fixtures.transfer(
                amount = "5000",
                fromAccountId = "acc-hdfc",
                toAccountId = "acc-card",
                on = LocalDate.of(2026, 3, 8),
            ),
        )
        val status = statusOn(LocalDate.of(2026, 3, 10), transactions)

        assertThat(status.outstanding).isEqualTo(Money.zero())
        assertThat(status.availableCredit).isEqualTo(card.creditLimit)
    }

    @Test
    fun `utilisation bands follow standard thresholds`() {
        fun bandAt(spend: String): UtilisationBand = statusOn(
            LocalDate.of(2026, 3, 10),
            listOf(Fixtures.expense(amount = spend, accountId = "acc-card", on = LocalDate.of(2026, 3, 5))),
        ).utilisationBand

        assertThat(bandAt("20000")).isEqualTo(UtilisationBand.HEALTHY)      // 10%
        assertThat(bandAt("60000")).isEqualTo(UtilisationBand.ELEVATED)     // 30%
        assertThat(bandAt("150000")).isEqualTo(UtilisationBand.HIGH)        // 75%
        assertThat(bandAt("200000")).isEqualTo(UtilisationBand.OVER_LIMIT)  // 100%
    }

    @Test
    fun `available credit never goes negative when over the limit`() {
        val transactions = listOf(
            Fixtures.expense(amount = "220000", accountId = "acc-card", on = LocalDate.of(2026, 3, 5)),
        )
        val status = statusOn(LocalDate.of(2026, 3, 10), transactions)

        assertThat(status.availableCredit).isEqualTo(Money.zero())
        assertThat(status.utilisationPercentClamped).isEqualTo(100)
    }

    // ---- Statement balance and minimum due ---------------------------------------------------

    @Test
    fun `the statement balance reflects the closed cycle, not today's spending`() {
        val transactions = listOf(
            // Inside the cycle that closed on 25 Feb.
            Fixtures.expense(amount = "12000", accountId = "acc-card", on = LocalDate.of(2026, 2, 10)),
            // After the statement — belongs to the next bill, not this one.
            Fixtures.expense(amount = "5000", accountId = "acc-card", on = LocalDate.of(2026, 3, 3)),
        )
        val status = statusOn(LocalDate.of(2026, 3, 10), transactions)

        assertThat(status.lastStatementDate).isEqualTo(LocalDate.of(2026, 2, 25))
        assertThat(status.statementBalance).isEqualTo(Money.of("12000"))
        assertThat(status.outstanding).isEqualTo(Money.of("17000"))
        assertThat(status.spendThisCycle).isEqualTo(Money.of("5000"))
        assertThat(status.paymentDueOn).isEqualTo(LocalDate.of(2026, 3, 14))
    }

    @Test
    fun `minimum due is five percent of the statement balance`() {
        assertThat(CreditCardCalculator.minimumDue(card, Money.of("12000")))
            .isEqualTo(Money.of("600"))
    }

    @Test
    fun `minimum due respects the issuer floor on small balances`() {
        // 5% of ₹500 is ₹25, below the ₹200 floor.
        assertThat(CreditCardCalculator.minimumDue(card, Money.of("500")))
            .isEqualTo(Money.of("200"))
    }

    @Test
    fun `minimum due never exceeds what is actually owed`() {
        assertThat(CreditCardCalculator.minimumDue(card, Money.of("150")))
            .isEqualTo(Money.of("150"))
    }

    @Test
    fun `a cleared card has no minimum due`() {
        assertThat(CreditCardCalculator.minimumDue(card, Money.zero())).isEqualTo(Money.zero())
    }

    @Test
    fun `revolving a balance is flagged`() {
        val transactions = listOf(
            Fixtures.expense(amount = "12000", accountId = "acc-card", on = LocalDate.of(2026, 2, 10)),
        )
        val status = statusOn(LocalDate.of(2026, 3, 10), transactions)

        assertThat(status.interestWarning).isTrue()
        assertThat(status.minimumDue).isLessThan(status.statementBalance)
    }

    @Test
    fun `an unpaid bill past its due date is overdue`() {
        val transactions = listOf(
            Fixtures.expense(amount = "12000", accountId = "acc-card", on = LocalDate.of(2026, 2, 10)),
        )
        assertThat(statusOn(LocalDate.of(2026, 3, 20), transactions).isOverdue(LocalDate.of(2026, 3, 20)))
            .isTrue()
        assertThat(statusOn(LocalDate.of(2026, 3, 10), transactions).isOverdue(LocalDate.of(2026, 3, 10)))
            .isFalse()
    }

    // ---- Validation --------------------------------------------------------------------------

    @Test
    fun `invalid card terms are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { card.copy(statementDayOfMonth = 0) }
        assertThrows(IllegalArgumentException::class.java) { card.copy(dueDayOfMonth = 32) }
        assertThrows(IllegalArgumentException::class.java) {
            card.copy(creditLimit = Money.zero())
        }
        assertThrows(IllegalArgumentException::class.java) {
            card.copy(minimumDuePercent = BigDecimal.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) { card.copy(lastFourDigits = "12") }
    }
}
