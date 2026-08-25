package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class CommitmentCalculatorTest {

    // ---- Normalising a single amount ---------------------------------------------------------

    @Test
    fun `a monthly amount is itself`() {
        assertThat(CommitmentCalculator.perMonth(Money.of("2500"), Frequency.MONTHLY))
            .isEqualTo(Money.of("2500"))
    }

    @Test
    fun `a quarterly amount is a third per month`() {
        assertThat(CommitmentCalculator.perMonth(Money.of("3000"), Frequency.QUARTERLY))
            .isEqualTo(Money.of("1000"))
    }

    @Test
    fun `a yearly amount is a twelfth per month`() {
        assertThat(CommitmentCalculator.perMonth(Money.of("12000"), Frequency.YEARLY))
            .isEqualTo(Money.of("1000"))
    }

    /** Every-3-months expressed as MONTHLY × 3 must equal QUARTERLY × 1. */
    @Test
    fun `interval and frequency compose`() {
        val viaInterval = CommitmentCalculator.perMonth(Money.of("3000"), Frequency.MONTHLY, interval = 3)
        val viaFrequency = CommitmentCalculator.perMonth(Money.of("3000"), Frequency.QUARTERLY)
        assertThat(viaInterval).isEqualTo(viaFrequency)
    }

    @Test
    fun `a monthly amount is twelve times itself per year`() {
        assertThat(CommitmentCalculator.perYear(Money.of("649"), Frequency.MONTHLY))
            .isEqualTo(Money.of("7788"))
    }

    /**
     * A fortnight is not half a month, so this is approximate by construction. Pinned so a change
     * to the conversion factor is a deliberate decision rather than a silent drift.
     */
    @Test
    fun `a fortnightly amount converts approximately`() {
        val monthly = CommitmentCalculator.perMonth(Money.of("500"), Frequency.FORTNIGHTLY)
        assertThat(monthly).isEqualTo(Money.of("1000"))
    }

    @Test
    fun `an interval below one is rejected`() {
        runCatching { CommitmentCalculator.perMonth(Money.of("100"), Frequency.MONTHLY, interval = 0) }
            .also { assertThat(it.isFailure).isTrue() }
    }

    // ---- Summarising rules -------------------------------------------------------------------

    @Test
    fun `income and expense are kept apart`() {
        val rules = listOf(
            Fixtures.recurring(id = "r1", amount = "25000", type = TransactionType.EXPENSE),
            Fixtures.recurring(id = "r2", amount = "5000", type = TransactionType.EXPENSE),
            Fixtures.recurring(id = "r3", amount = "80000", type = TransactionType.INCOME),
        )

        val commitment = CommitmentCalculator.summarise(rules)

        assertThat(commitment.outgoingPerMonth).isEqualTo(Money.of("30000"))
        assertThat(commitment.incomingPerMonth).isEqualTo(Money.of("80000"))
        assertThat(commitment.netPerMonth).isEqualTo(Money.of("50000"))
        assertThat(commitment.isSustainable).isTrue()
    }

    /**
     * The rule this whole class exists to protect: a transfer between the user's own accounts is
     * not a commitment and not income. Counting it would inflate both sides at once.
     */
    @Test
    fun `a transfer counts as neither outgoing nor income`() {
        val rules = listOf(
            Fixtures.recurring(id = "r1", amount = "25000", type = TransactionType.EXPENSE),
            // Built directly rather than via the fixture: the model refuses to construct a
            // transfer without a destination, so `.copy()` after the fact is too late.
            RecurringRule(
                id = "r2",
                name = "Monthly savings",
                type = TransactionType.TRANSFER,
                amount = Money.of("20000"),
                accountId = "acc-hdfc",
                transferAccountId = "acc-savings",
                frequency = Frequency.MONTHLY,
                startDate = LocalDate.of(2026, 1, 5),
            ),
        )

        val commitment = CommitmentCalculator.summarise(rules)

        assertThat(commitment.outgoingPerMonth).isEqualTo(Money.of("25000"))
        assertThat(commitment.incomingPerMonth).isEqualTo(Money.zero())
    }

    @Test
    fun `a paused rule is not a commitment`() {
        val rules = listOf(
            Fixtures.recurring(id = "r1", amount = "25000", isActive = true),
            Fixtures.recurring(id = "r2", amount = "9000", isActive = false),
        )

        assertThat(CommitmentCalculator.summarise(rules).outgoingPerMonth)
            .isEqualTo(Money.of("25000"))
    }

    @Test
    fun `mixed frequencies are normalised before being added`() {
        val rules = listOf(
            Fixtures.recurring(id = "rent", amount = "25000", frequency = Frequency.MONTHLY),
            Fixtures.recurring(id = "insurance", amount = "24000", frequency = Frequency.YEARLY),
            Fixtures.recurring(id = "water", amount = "1500", frequency = Frequency.QUARTERLY),
        )

        // 25000 + 2000 + 500
        assertThat(CommitmentCalculator.summarise(rules).outgoingPerMonth)
            .isEqualTo(Money.of("27500"))
    }

    @Test
    fun `an empty set is zero rather than an error`() {
        val commitment = CommitmentCalculator.summarise(emptyList())
        assertThat(commitment.outgoingPerMonth).isEqualTo(Money.zero())
        assertThat(commitment.netPerMonth).isEqualTo(Money.zero())
    }

    @Test
    fun `outgoings exceeding income are reported as unsustainable`() {
        val rules = listOf(
            Fixtures.recurring(id = "r1", amount = "60000", type = TransactionType.EXPENSE),
            Fixtures.recurring(id = "r2", amount = "50000", type = TransactionType.INCOME),
        )

        val commitment = CommitmentCalculator.summarise(rules)

        assertThat(commitment.netPerMonth).isEqualTo(Money.of("-10000"))
        assertThat(commitment.isSustainable).isFalse()
    }

    // ---- Subscriptions -----------------------------------------------------------------------

    @Test
    fun `subscription totals cover both cadences`() {
        val subscriptions = listOf(
            Fixtures.subscription(id = "s1", amount = "649", cycle = Frequency.MONTHLY),
            Fixtures.subscription(id = "s2", amount = "1499", cycle = Frequency.YEARLY),
        )

        val totals = CommitmentCalculator.summariseSubscriptions(subscriptions)

        assertThat(totals.count).isEqualTo(2)
        // 649 + (1499 / 12)
        assertThat(totals.perMonth).isEqualTo(Money.of("773.92"))
        assertThat(totals.perYear).isEqualTo(Money.of("9287"))
    }

    /** A cancelled service stops costing anything, but is kept in the list for past reporting. */
    @Test
    fun `a cancelled subscription is excluded from the running cost`() {
        val subscriptions = listOf(
            Fixtures.subscription(id = "s1", amount = "649"),
            Fixtures.subscription(
                id = "s2",
                amount = "999",
                cancelledOn = LocalDate.of(2026, 2, 1),
                isActive = false,
            ),
        )

        val totals = CommitmentCalculator.summariseSubscriptions(subscriptions)

        assertThat(totals.count).isEqualTo(1)
        assertThat(totals.perMonth).isEqualTo(Money.of("649"))
    }

    /**
     * The two normalisations must agree: whatever a subscription costs per month, twelve of those
     * is what it costs per year. A user comparing the two figures on the same card would notice.
     */
    @Test
    fun `monthly times twelve matches the yearly figure for monthly cycles`() {
        val monthly = CommitmentCalculator.perMonth(Money.of("649"), Frequency.MONTHLY)
        val yearly = CommitmentCalculator.perYear(Money.of("649"), Frequency.MONTHLY)
        assertThat(monthly.times(12)).isEqualTo(yearly)
    }
}
