package ai.labs32.khaata.core.demo

import ai.labs32.khaata.core.calc.BalanceCalculator
import ai.labs32.khaata.core.calc.CashflowAnalyzer
import ai.labs32.khaata.core.calc.CreditCardCalculator
import ai.labs32.khaata.core.calc.LoanCalculator
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

/**
 * The demo dataset feeds screenshots, onboarding previews and several other tests, so it has to
 * be internally consistent — every reference resolving, every derived figure sane. A broken demo
 * dataset would show a first-time user a broken app.
 */
class DemoDataGeneratorTest {

    private val asOf = LocalDate.of(2026, 3, 15)
    private val dataset = DemoDataGenerator().generate(asOf, months = 6)

    @Test
    fun `generation is reproducible for a given seed`() {
        val again = DemoDataGenerator().generate(asOf, months = 6)
        assertThat(again.transactions.map { it.id to it.amount })
            .isEqualTo(dataset.transactions.map { it.id to it.amount })
    }

    @Test
    fun `a different seed produces different data`() {
        val other = DemoDataGenerator(seed = 99L).generate(asOf, months = 6)
        assertThat(other.transactions.map { it.amount })
            .isNotEqualTo(dataset.transactions.map { it.amount })
    }

    @Test
    fun `the dataset is substantial enough to be worth showing`() {
        assertThat(dataset.accounts).hasSize(6)
        assertThat(dataset.transactions.size).isAtLeast(200)
        assertThat(dataset.budgets).isNotEmpty()
        assertThat(dataset.goals).isNotEmpty()
        assertThat(dataset.subscriptions).isNotEmpty()
        assertThat(dataset.loans).isNotEmpty()
        assertThat(dataset.investments).isNotEmpty()
        assertThat(dataset.creditCards).isNotEmpty()
    }

    @Test
    fun `every row is marked as demo data so it can be removed cleanly`() {
        val ids = dataset.accounts.map { it.id } +
            dataset.transactions.map { it.id } +
            dataset.budgets.map { it.id } +
            dataset.goals.map { it.id } +
            dataset.subscriptions.map { it.id } +
            dataset.loans.map { it.id } +
            dataset.investments.map { it.id } +
            dataset.creditCards.map { it.id } +
            dataset.recurringRules.map { it.id }

        assertThat(ids.all { it.startsWith(DemoDataGenerator.DEMO_ID_PREFIX) }).isTrue()
        assertThat(dataset.transactions.all { it.source == TransactionSource.DEMO }).isTrue()
    }

    @Test
    fun `no transaction is dated in the future`() {
        assertThat(dataset.transactions.none { it.occurredOn.isAfter(asOf) }).isTrue()
    }

    @Test
    fun `every transaction references an account that exists`() {
        val accountIds = dataset.accounts.map { it.id }.toSet()
        for (transaction in dataset.transactions) {
            assertThat(accountIds).contains(transaction.accountId)
            transaction.transferAccountId?.let { assertThat(accountIds).contains(it) }
        }
    }

    @Test
    fun `every product references an account that exists`() {
        val accountIds = dataset.accounts.map { it.id }.toSet()
        dataset.creditCards.forEach { assertThat(accountIds).contains(it.accountId) }
        dataset.recurringRules.forEach { assertThat(accountIds).contains(it.accountId) }
        dataset.subscriptions.mapNotNull { it.accountId }
            .forEach { assertThat(accountIds).contains(it) }
    }

    @Test
    fun `derived balances are plausible`() {
        val balances = BalanceCalculator.balances(dataset.accounts, dataset.transactions)

        // The salaried user modelled here should not be running their current account dry.
        val hdfc = balances.single { it.account.id == DemoDataGenerator.ACC_HDFC }
        assertThat(hdfc.currentBalance).isGreaterThan(Money.zero())

        // Spending on the card leaves it owing money.
        val card = balances.single { it.account.id == DemoDataGenerator.ACC_CARD }
        assertThat(card.currentBalance).isLessThan(Money.zero())
    }

    @Test
    fun `net worth is positive and available-to-spend is smaller than it`() {
        val balances = BalanceCalculator.balances(dataset.accounts, dataset.transactions)
        val netWorth = BalanceCalculator.netWorth(balances)
        val available = BalanceCalculator.availableToSpend(balances)

        assertThat(netWorth.netWorth).isGreaterThan(Money.zero())
        assertThat(netWorth.liabilities).isGreaterThan(Money.zero())
        assertThat(available).isLessThan(netWorth.assets)
    }

    @Test
    fun `the modelled user saves rather than overspends`() {
        // A demo showing a deficit every month would misrepresent what the app is for.
        val lastFullMonth = DateRange.ofMonth(asOf.minusMonths(1))
        val summary = CashflowAnalyzer.summarise(dataset.transactions, lastFullMonth)

        assertThat(summary.income).isGreaterThan(Money.zero())
        assertThat(summary.expense).isGreaterThan(Money.zero())
        assertThat(summary.net).isGreaterThan(Money.zero())
        assertThat(summary.savingsRatePercent!!.toDouble()).isGreaterThan(5.0)
    }

    @Test
    fun `transfers exist but are excluded from spending totals`() {
        val transfers = dataset.transactions.filter { it.type == TransactionType.TRANSFER }
        assertThat(transfers).isNotEmpty()

        val month = DateRange.ofMonth(asOf.minusMonths(1))
        val summary = CashflowAnalyzer.summarise(dataset.transactions, month)
        val transfersInMonth = transfers.filter { it.occurredOn in month }
        assertThat(transfersInMonth).isNotEmpty()

        val spendRows = dataset.transactions.count { it.countsAsSpending && it.occurredOn in month }
        assertThat(summary.expenseCount).isEqualTo(spendRows)
    }

    @Test
    fun `spending is spread across several categories`() {
        val month = DateRange.ofMonth(asOf.minusMonths(1))
        val breakdown = CashflowAnalyzer.categoryBreakdown(
            dataset.transactions,
            ai.labs32.khaata.core.categorize.DefaultCategories.ALL,
            month,
        )
        assertThat(breakdown.size).isAtLeast(6)
    }

    @Test
    fun `every transaction category exists in the default set`() {
        val categoryIds = ai.labs32.khaata.core.categorize.DefaultCategories.ALL
            .map { it.id }.toSet()
        val used = dataset.transactions.mapNotNull { it.categoryId }.toSet()

        assertThat(categoryIds).containsAtLeastElementsIn(used)
    }

    @Test
    fun `the demo loan produces a sane amortisation status`() {
        val loan = dataset.loans.single()
        val status = LoanCalculator.status(loan, asOf)

        assertThat(status.instalmentsPaid).isGreaterThan(0)
        assertThat(status.instalmentsRemaining).isGreaterThan(0)
        assertThat(status.outstandingPrincipal).isLessThan(loan.principal)
        assertThat(status.principalRepaid + status.outstandingPrincipal).isEqualTo(loan.principal)
    }

    @Test
    fun `the demo card produces a sane statement position`() {
        val card = dataset.creditCards.single()
        val balance = BalanceCalculator.balances(dataset.accounts, dataset.transactions)
            .single { it.account.id == card.accountId }
        val status = CreditCardCalculator.status(card, balance, dataset.transactions, asOf)

        assertThat(status.outstanding).isGreaterThan(Money.zero())
        assertThat(status.availableCredit).isLessThan(card.creditLimit)
        assertThat(status.utilisationPercentClamped).isIn(0..100)
        assertThat(status.paymentDueOn).isNotNull()
    }

    @Test
    fun `goals cover in-progress and achieved states`() {
        assertThat(dataset.goals.any { !it.isAchieved }).isTrue()
        assertThat(dataset.goals.any { it.isAchieved }).isTrue()
    }

    @Test
    fun `subscriptions renew on or after today`() {
        assertThat(dataset.subscriptions.none { it.nextPaymentDate.isBefore(asOf) }).isTrue()
    }

    @Test
    fun `a short history is still coherent`() {
        val short = DemoDataGenerator().generate(asOf, months = 1)

        assertThat(short.transactions).isNotEmpty()
        assertThat(short.transactions.none { it.occurredOn.isAfter(asOf) }).isTrue()
    }

    @Test
    fun `an unreasonable history length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DemoDataGenerator().generate(asOf, months = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DemoDataGenerator().generate(asOf, months = 99)
        }
    }
}
