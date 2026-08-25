package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class BalanceCalculatorTest {

    private val hdfc = Fixtures.account(id = "acc-hdfc", openingBalance = "50000")
    private val icici = Fixtures.account(id = "acc-icici", name = "ICICI", openingBalance = "10000")
    private val cash = Fixtures.account(id = "acc-cash", name = "Cash", type = AccountType.CASH, openingBalance = "2000")
    private val card = Fixtures.account(
        id = "acc-card",
        name = "HDFC Credit Card",
        type = AccountType.CREDIT_CARD,
        openingBalance = "0",
    )

    @Test
    fun `balance is opening balance plus postings`() {
        val transactions = listOf(
            Fixtures.expense(amount = "850", accountId = "acc-hdfc"),
            Fixtures.expense(amount = "1200", accountId = "acc-hdfc"),
            Fixtures.income(amount = "35000", accountId = "acc-hdfc"),
        )
        assertThat(BalanceCalculator.balanceOf(hdfc, transactions))
            .isEqualTo(Money.of("82950")) // 50000 - 850 - 1200 + 35000
    }

    @Test
    fun `a transfer moves money between both legs and nets to zero overall`() {
        val transactions = listOf(Fixtures.transfer(amount = "20000"))

        assertThat(BalanceCalculator.balanceOf(hdfc, transactions)).isEqualTo(Money.of("30000"))
        assertThat(BalanceCalculator.balanceOf(icici, transactions)).isEqualTo(Money.of("30000"))

        val balances = BalanceCalculator.balances(listOf(hdfc, icici), transactions)
        val total = balances.fold(Money.zero()) { sum, b -> sum + b.currentBalance }
        // Opening balances were 50,000 + 10,000; a transfer must not change the total.
        assertThat(total).isEqualTo(Money.of("60000"))
    }

    @Test
    fun `pending and deleted rows do not move balances`() {
        val transactions = listOf(
            Fixtures.expense(amount = "500", accountId = "acc-hdfc", isPending = true),
            Fixtures.expense(
                amount = "700",
                accountId = "acc-hdfc",
                deletedAt = Instant.parse("2026-03-01T00:00:00Z"),
            ),
            Fixtures.expense(amount = "300", accountId = "acc-hdfc"),
        )
        assertThat(BalanceCalculator.balanceOf(hdfc, transactions)).isEqualTo(Money.of("49700"))
    }

    @Test
    fun `transactions on other accounts are ignored`() {
        val transactions = listOf(Fixtures.expense(amount = "999", accountId = "acc-icici"))
        assertThat(BalanceCalculator.balanceOf(hdfc, transactions)).isEqualTo(Money.of("50000"))
    }

    @Test
    fun `balance as of a past date excludes later transactions`() {
        val transactions = listOf(
            Fixtures.expense(amount = "1000", accountId = "acc-hdfc", on = LocalDate.of(2026, 3, 1)),
            Fixtures.expense(amount = "2000", accountId = "acc-hdfc", on = LocalDate.of(2026, 3, 20)),
        )
        assertThat(BalanceCalculator.balanceAsOf(hdfc, transactions, LocalDate.of(2026, 3, 10)))
            .isEqualTo(Money.of("49000"))
    }

    @Test
    fun `batch balances match the single-account calculation`() {
        val transactions = listOf(
            Fixtures.expense(amount = "850", accountId = "acc-hdfc"),
            Fixtures.expense(amount = "200", accountId = "acc-cash"),
            Fixtures.transfer(amount = "5000", fromAccountId = "acc-hdfc", toAccountId = "acc-icici"),
        )
        val accounts = listOf(hdfc, icici, cash)
        val batch = BalanceCalculator.balances(accounts, transactions).associateBy { it.account.id }

        for (account in accounts) {
            assertThat(batch.getValue(account.id).currentBalance)
                .isEqualTo(BalanceCalculator.balanceOf(account, transactions))
        }
    }

    @Test
    fun `credit card spending reduces net worth`() {
        val transactions = listOf(Fixtures.expense(amount = "18000", accountId = "acc-card"))
        val balances = BalanceCalculator.balances(listOf(hdfc, card), transactions)
        val summary = BalanceCalculator.netWorth(balances)

        assertThat(summary.assets).isEqualTo(Money.of("50000"))
        assertThat(summary.liabilities).isEqualTo(Money.of("18000"))
        assertThat(summary.netWorth).isEqualTo(Money.of("32000"))
    }

    @Test
    fun `a card balance displays as the amount owed rather than a negative number`() {
        val transactions = listOf(Fixtures.expense(amount = "18000", accountId = "acc-card"))
        val cardBalance = BalanceCalculator.balances(listOf(card), transactions).single()

        assertThat(cardBalance.currentBalance).isEqualTo(Money.of("-18000"))
        assertThat(cardBalance.displayBalance).isEqualTo(Money.of("18000"))
    }

    @Test
    fun `accounts excluded from net worth are left out`() {
        val excluded = Fixtures.account(
            id = "acc-other",
            name = "Company Float",
            openingBalance = "999999",
            includeInNetWorth = false,
        )
        val balances = BalanceCalculator.balances(listOf(hdfc, excluded), emptyList())
        assertThat(BalanceCalculator.netWorth(balances).netWorth).isEqualTo(Money.of("50000"))
    }

    @Test
    fun `archived accounts are left out of net worth`() {
        val archived = Fixtures.account(id = "acc-old", openingBalance = "12345", isArchived = true)
        val balances = BalanceCalculator.balances(listOf(hdfc, archived), emptyList())
        assertThat(BalanceCalculator.netWorth(balances).netWorth).isEqualTo(Money.of("50000"))
    }

    @Test
    fun `available to spend excludes investment and card accounts`() {
        val investment = Fixtures.account(
            id = "acc-mf",
            name = "Mutual Funds",
            type = AccountType.INVESTMENT,
            openingBalance = "400000",
        )
        val balances = BalanceCalculator.balances(listOf(hdfc, cash, card, investment), emptyList())

        // Only the bank and cash accounts default to spendable.
        assertThat(BalanceCalculator.availableToSpend(balances)).isEqualTo(Money.of("52000"))
        // ...while net worth counts the investment too.
        assertThat(BalanceCalculator.netWorth(balances).netWorth).isEqualTo(Money.of("452000"))
    }

    @Test
    fun `net worth trend accumulates in date order`() {
        val transactions = listOf(
            Fixtures.income(amount = "35000", accountId = "acc-hdfc", on = LocalDate.of(2026, 1, 31)),
            Fixtures.expense(amount = "5000", accountId = "acc-hdfc", on = LocalDate.of(2026, 2, 10)),
            Fixtures.expense(amount = "1000", accountId = "acc-hdfc", on = LocalDate.of(2026, 3, 10)),
        )
        val trend = BalanceCalculator.netWorthTrend(
            accounts = listOf(hdfc),
            transactions = transactions,
            dates = listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
            ),
        )
        assertThat(trend.map { it.netWorth }).containsExactly(
            Money.of("85000"),
            Money.of("80000"),
            Money.of("79000"),
        ).inOrder()
    }

    @Test
    fun `net worth trend handles an empty ledger`() {
        val trend = BalanceCalculator.netWorthTrend(
            accounts = listOf(hdfc),
            transactions = emptyList(),
            dates = listOf(LocalDate.of(2026, 3, 31)),
        )
        assertThat(trend.single().netWorth).isEqualTo(Money.of("50000"))
    }

    @Test
    fun `net worth trend with no accounts yields zeroes rather than failing`() {
        val trend = BalanceCalculator.netWorthTrend(
            accounts = emptyList(),
            transactions = emptyList(),
            dates = listOf(LocalDate.of(2026, 3, 31)),
        )
        assertThat(trend.single().netWorth).isEqualTo(Money.zero())
    }

    @Test
    fun `percent change reports a gain to one decimal place`() {
        val result = BalanceCalculator.percentChange(previous = Money.of("1000"), current = Money.of("1124"))
        assertThat(result).isEqualTo(BigDecimal("12.4"))
    }

    @Test
    fun `percent change reports a loss as negative`() {
        val result = BalanceCalculator.percentChange(previous = Money.of("1000"), current = Money.of("900"))
        assertThat(result).isEqualTo(BigDecimal("-10.0"))
    }

    @Test
    fun `percent change is zero when nothing moved`() {
        val result = BalanceCalculator.percentChange(previous = Money.of("1000"), current = Money.of("1000"))
        assertThat(result).isEqualTo(BigDecimal("0.0"))
    }

    @Test
    fun `percent change from zero is undefined rather than infinite`() {
        val result = BalanceCalculator.percentChange(previous = Money.of("0"), current = Money.of("500"))
        assertThat(result).isNull()
    }

    @Test
    fun `percent change of a halved debt reads as a positive improvement`() {
        val result = BalanceCalculator.percentChange(previous = Money.of("-1000"), current = Money.of("-500"))
        assertThat(result).isEqualTo(BigDecimal("50.0"))
    }

    @Test
    fun `percent change of a doubled debt reads as a negative deterioration`() {
        val result = BalanceCalculator.percentChange(previous = Money.of("-1000"), current = Money.of("-2000"))
        assertThat(result).isEqualTo(BigDecimal("-100.0"))
    }
}
