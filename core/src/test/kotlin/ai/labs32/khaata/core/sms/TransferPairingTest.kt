package ai.labs32.khaata.core.sms

import ai.labs32.khaata.core.calc.BalanceCalculator
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TransferPairingTest {

    private val day = LocalDate.of(2026, 3, 10)

    private fun staged(
        id: String,
        type: TransactionType,
        amount: String,
        accountId: String,
        on: LocalDate = day,
        isPending: Boolean = true,
        source: TransactionSource = TransactionSource.SMS_IMPORT,
        reference: String? = null,
    ) = Transaction(
        id = id,
        type = type,
        amount = Money.of(amount),
        accountId = accountId,
        categoryId = "cat-other",
        occurredOn = on,
        source = source,
        referenceNumber = reference,
        isPending = isPending,
    )

    private fun counterpartOf(
        type: TransactionType,
        amount: String,
        accountId: String,
        candidates: List<Transaction>,
        on: LocalDate = day,
        reference: String? = null,
    ) = TransferPairing.counterpart(type, Money.of(amount), accountId, on, candidates, reference)

    @Test
    fun `a debit on one account pairs with the matching staged credit on another`() {
        val credit = staged("c", TransactionType.INCOME, "5000", "icici")
        assertThat(counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", listOf(credit))).isEqualTo(credit)
    }

    @Test
    fun `the legs may be posted a couple of days apart but no further`() {
        val twoDaysLater = staged("c", TransactionType.INCOME, "5000", "icici", on = day.plusDays(2))
        val threeDaysLater = staged("d", TransactionType.INCOME, "5000", "icici", on = day.plusDays(3))
        assertThat(counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", listOf(twoDaysLater))).isEqualTo(twoDaysLater)
        assertThat(counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", listOf(threeDaysLater))).isNull()
    }

    @Test
    fun `nothing pairs on the same account, in the same direction, or for a different amount`() {
        val candidates = listOf(
            staged("same-account", TransactionType.INCOME, "5000", "hdfc"),
            staged("same-direction", TransactionType.EXPENSE, "5000", "icici"),
            staged("different-amount", TransactionType.INCOME, "5000.50", "icici"),
        )
        assertThat(counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", candidates)).isNull()
    }

    @Test
    fun `a confirmed, manual, deleted or already-paired row is never rewritten`() {
        val candidates = listOf(
            staged("confirmed", TransactionType.INCOME, "5000", "icici", isPending = false),
            staged("manual", TransactionType.INCOME, "5000", "icici", source = TransactionSource.MANUAL),
            staged("deleted", TransactionType.INCOME, "5000", "icici")
                .copy(deletedAt = Instant.parse("2026-03-10T10:00:00Z")),
            staged("paired", TransactionType.EXPENSE, "5000", "sbi")
                .copy(type = TransactionType.TRANSFER, transferAccountId = "icici"),
        )
        assertThat(counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", candidates)).isNull()
    }

    /** Two equally good matches means the pairing would be a guess, and a guess hides real money. */
    @Test
    fun `an ambiguous match pairs with nothing`() {
        val candidates = listOf(
            staged("a", TransactionType.INCOME, "5000", "icici"),
            staged("b", TransactionType.INCOME, "5000", "sbi"),
        )
        assertThat(counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", candidates)).isNull()
    }

    @Test
    fun `a shared bank reference picks the leg out of several candidates`() {
        val other = staged("a", TransactionType.INCOME, "5000", "sbi", reference = null)
        val leg = staged("b", TransactionType.INCOME, "5000", "icici", reference = "412345678901")
        assertThat(
            counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", listOf(other, leg), reference = "412345678901"),
        ).isEqualTo(leg)
    }

    /** Two ₹5,000 payments quoting different references are two payments, not one transfer. */
    @Test
    fun `references that disagree rule a pairing out`() {
        val credit = staged("c", TransactionType.INCOME, "5000", "icici", reference = "111111111111")
        assertThat(
            counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", listOf(credit), reference = "222222222222"),
        ).isNull()
        // Only one side quoting a reference is the common card-payment shape and still pairs.
        assertThat(counterpartOf(TransactionType.EXPENSE, "5000", "hdfc", listOf(credit))).isEqualTo(credit)
    }

    @Test
    fun `merging a staged debit makes it a transfer out of that account into the credited one`() {
        val debit = staged("d", TransactionType.EXPENSE, "25000", "hdfc", reference = "UTR123")
        val merged = TransferPairing.merge(debit, "card", day.plusDays(1), incomingReference = "OTHER")

        assertThat(merged.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(merged.accountId).isEqualTo("hdfc")
        assertThat(merged.transferAccountId).isEqualTo("card")
        assertThat(merged.categoryId).isNull()
        assertThat(merged.occurredOn).isEqualTo(day)
        assertThat(merged.referenceNumber).isEqualTo("UTR123")
        assertThat(merged.isPending).isTrue()
    }

    @Test
    fun `merging a staged credit takes the direction and date from the incoming debit`() {
        val credit = staged("c", TransactionType.INCOME, "25000", "card", on = day.plusDays(1))
        val merged = TransferPairing.merge(credit, "hdfc", day, incomingReference = "UTR9")

        assertThat(merged.accountId).isEqualTo("hdfc")
        assertThat(merged.transferAccountId).isEqualTo("card")
        assertThat(merged.occurredOn).isEqualTo(day)
        assertThat(merged.referenceNumber).isEqualTo("UTR9")
    }

    /** The point of pairing: balances are exactly what the two separate legs gave, spending is not inflated. */
    @Test
    fun `a merged transfer leaves both balances where the two legs put them`() {
        val hdfc = Account(id = "hdfc", name = "HDFC", type = AccountType.BANK, openingBalance = Money.of("50000"))
        val card = Account(id = "card", name = "Card", type = AccountType.CREDIT_CARD, openingBalance = Money.of("-25000"))
        val debit = staged("d", TransactionType.EXPENSE, "25000", "hdfc", isPending = false)
        val credit = staged("c", TransactionType.INCOME, "25000", "card", isPending = false)
        val merged = TransferPairing.merge(debit.copy(isPending = true), "card", day, null).copy(isPending = false)

        val separate = BalanceCalculator.balances(listOf(hdfc, card), listOf(debit, credit))
        val paired = BalanceCalculator.balances(listOf(hdfc, card), listOf(merged))

        assertThat(paired.map { it.currentBalance }).isEqualTo(separate.map { it.currentBalance })
        assertThat(paired.map { it.currentBalance }).containsExactly(Money.of("25000"), Money.of("0")).inOrder()
        assertThat(merged.countsAsSpending).isFalse()
        assertThat(merged.countsAsIncome).isFalse()
    }
}
