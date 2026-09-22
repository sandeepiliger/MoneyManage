package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * An opening balance stated as "what is in this account now" is a dated snapshot.
 *
 * This is the bug a user found on a real phone: they typed their bank balance during onboarding,
 * the SMS inbox scan then staged months of bank messages, they tapped "Accept all", and their
 * balance went wrong by the net of every one of those transactions — each of which was already
 * inside the figure they had typed. The same thing happens without any SMS at all, the first time
 * someone logs yesterday's forgotten coffee into an account whose balance they entered today.
 */
class OpeningBalanceSnapshotTest {

    private val today = Fixtures.TODAY
    private val lastMonth = today.minusDays(30)

    private fun inr(amount: String) = Money.of(amount)

    @Test
    fun `transactions before a stated balance are not counted a second time`() {
        val account = Fixtures.account(openingBalance = "50000", openingBalanceDate = today)
        val backfilled = listOf(
            Fixtures.expense(amount = "12000", on = lastMonth),
            Fixtures.income(amount = "40000", on = lastMonth),
        )

        // ₹50,000 is what the bank said this morning; last month's salary and rent are in it.
        assertThat(BalanceCalculator.balanceOf(account, backfilled)).isEqualTo(inr("50000"))
    }

    @Test
    fun `transactions from the snapshot day onward still move the balance`() {
        val account = Fixtures.account(openingBalance = "50000", openingBalanceDate = today)
        val transactions = listOf(
            Fixtures.expense(amount = "300", on = today),
            Fixtures.expense(amount = "200", on = today.plusDays(1)),
        )

        // The snapshot day counts: the usual order is to enter the balance, then log the day.
        assertThat(BalanceCalculator.balanceOf(account, transactions)).isEqualTo(inr("49500"))
    }

    @Test
    fun `an account with no stated snapshot counts all of its history, as before`() {
        // Cards created at zero, demo accounts, and every account from before this field existed.
        val account = Fixtures.account(openingBalance = "1000", openingBalanceDate = null)
        val transactions = listOf(
            Fixtures.expense(amount = "400", on = lastMonth),
            Fixtures.expense(amount = "100", on = today),
        )

        assertThat(BalanceCalculator.balanceOf(account, transactions)).isEqualTo(inr("500"))
    }

    @Test
    fun `a card added without an outstanding still carries its imported spend`() {
        // Card outstanding is derived from this balance. Had the cutoff been the date the account
        // was created, a card added today would show nothing owed despite a month of imported
        // spending -- which is why the snapshot is only set when a balance is actually stated.
        val card = Fixtures.account(
            id = "acc-card",
            type = ai.labs32.khaata.core.model.AccountType.CREDIT_CARD,
            openingBalance = "0",
            openingBalanceDate = null,
        )
        val spend = listOf(Fixtures.expense(amount = "8000", accountId = "acc-card", on = lastMonth))

        assertThat(BalanceCalculator.balanceOf(card, spend)).isEqualTo(inr("-8000"))
    }

    @Test
    fun `a transfer counts on the side whose snapshot it follows, and only there`() {
        val snapshotted = Fixtures.account(
            id = "acc-hdfc",
            openingBalance = "50000",
            openingBalanceDate = today,
        )
        val open = Fixtures.account(id = "acc-icici", openingBalance = "0")
        val transfer = Fixtures.transfer(
            amount = "5000",
            fromAccountId = "acc-hdfc",
            toAccountId = "acc-icici",
            on = lastMonth,
        )

        val balances = BalanceCalculator.balances(listOf(snapshotted, open), listOf(transfer))
            .associateBy { it.account.id }

        // Already inside HDFC's stated balance; ICICI has no snapshot, so it arrives there.
        assertThat(balances.getValue("acc-hdfc").currentBalance).isEqualTo(inr("50000"))
        assertThat(balances.getValue("acc-icici").currentBalance).isEqualTo(inr("5000"))
    }

    @Test
    fun `pre-snapshot transactions still count as activity on the account`() {
        // They are listed and they are history; they just do not move the figure again.
        val account = Fixtures.account(openingBalance = "50000", openingBalanceDate = today)
        val balances = BalanceCalculator.balances(
            listOf(account),
            listOf(Fixtures.expense(amount = "100", on = lastMonth)),
        )

        assertThat(balances.single().transactionCount).isEqualTo(1)
        assertThat(balances.single().currentBalance).isEqualTo(inr("50000"))
    }

    @Test
    fun `the single-account and all-accounts paths agree`() {
        // Two implementations of one rule is exactly how the balance on one screen comes to
        // disagree with the balance on another.
        val account = Fixtures.account(openingBalance = "50000", openingBalanceDate = today)
        val transactions = listOf(
            Fixtures.expense(amount = "900", on = lastMonth),
            Fixtures.expense(amount = "250", on = today),
            Fixtures.income(amount = "1000", on = today.plusDays(2)),
        )

        assertThat(BalanceCalculator.balances(listOf(account), transactions).single().currentBalance)
            .isEqualTo(BalanceCalculator.balanceOf(account, transactions))
    }

    @Test
    fun `the balance on a past date holds at the snapshot rather than inventing history`() {
        val account = Fixtures.account(openingBalance = "50000", openingBalanceDate = today)
        val transactions = listOf(Fixtures.expense(amount = "900", on = lastMonth))

        assertThat(BalanceCalculator.balanceAsOf(account, transactions, lastMonth))
            .isEqualTo(inr("50000"))
    }

    @Test
    fun `the net worth trend is not bent by history the snapshot already contains`() {
        val account = Fixtures.account(openingBalance = "50000", openingBalanceDate = today)
        val transactions = listOf(
            Fixtures.expense(amount = "20000", on = lastMonth),
            Fixtures.expense(amount = "1000", on = today),
        )

        val points = BalanceCalculator.netWorthTrend(
            accounts = listOf(account),
            transactions = transactions,
            dates = listOf(lastMonth, today),
        )

        assertThat(points.map { it.netWorth }).containsExactly(inr("50000"), inr("49000")).inOrder()
    }
}
