package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class RecurrenceCalculatorTest {

    @Test
    fun `monthly occurrences land on the anchor day`() {
        val rule = Fixtures.recurring(startDate = LocalDate.of(2026, 1, 5))
        val dates = RecurrenceCalculator.occurrencesBetween(
            rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30),
        )
        assertThat(dates).containsExactly(
            LocalDate.of(2026, 1, 5),
            LocalDate.of(2026, 2, 5),
            LocalDate.of(2026, 3, 5),
            LocalDate.of(2026, 4, 5),
        ).inOrder()
    }

    @Test
    fun `a month-end anchor clamps in February and recovers afterwards`() {
        // The bug this guards against: naive month-adding drags the 31st permanently to the 28th.
        val rule = Fixtures.recurring(startDate = LocalDate.of(2026, 1, 31))
        val dates = RecurrenceCalculator.occurrencesBetween(
            rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
        )
        assertThat(dates).containsExactly(
            LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 30),
            LocalDate.of(2026, 5, 31),
            LocalDate.of(2026, 6, 30),
        ).inOrder()
    }

    @Test
    fun `a month-end anchor uses 29 February in a leap year`() {
        val rule = Fixtures.recurring(startDate = LocalDate.of(2028, 1, 31))
        val dates = RecurrenceCalculator.occurrencesBetween(
            rule, LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29),
        )
        assertThat(dates).containsExactly(LocalDate.of(2028, 2, 29))
    }

    @Test
    fun `an interval of three months produces a quarterly series`() {
        val rule = Fixtures.recurring(
            startDate = LocalDate.of(2026, 1, 15),
            frequency = Frequency.MONTHLY,
            interval = 3,
        )
        val dates = RecurrenceCalculator.occurrencesBetween(
            rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
        )
        assertThat(dates).containsExactly(
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 4, 15),
            LocalDate.of(2026, 7, 15),
            LocalDate.of(2026, 10, 15),
        ).inOrder()
    }

    @Test
    fun `weekly and fortnightly frequencies step correctly`() {
        val weekly = Fixtures.recurring(
            startDate = LocalDate.of(2026, 3, 2),
            frequency = Frequency.WEEKLY,
        )
        assertThat(
            RecurrenceCalculator.occurrencesBetween(weekly, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
        ).containsExactly(
            LocalDate.of(2026, 3, 2),
            LocalDate.of(2026, 3, 9),
            LocalDate.of(2026, 3, 16),
            LocalDate.of(2026, 3, 23),
            LocalDate.of(2026, 3, 30),
        ).inOrder()

        val fortnightly = weekly.copy(frequency = Frequency.FORTNIGHTLY)
        assertThat(
            RecurrenceCalculator.occurrencesBetween(fortnightly, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
        ).containsExactly(
            LocalDate.of(2026, 3, 2),
            LocalDate.of(2026, 3, 16),
            LocalDate.of(2026, 3, 30),
        ).inOrder()
    }

    @Test
    fun `yearly frequency steps a year at a time`() {
        val rule = Fixtures.recurring(
            startDate = LocalDate.of(2026, 4, 1),
            frequency = Frequency.YEARLY,
        )
        assertThat(
            RecurrenceCalculator.occurrencesBetween(rule, LocalDate.of(2026, 1, 1), LocalDate.of(2029, 12, 31)),
        ).containsExactly(
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2027, 4, 1),
            LocalDate.of(2028, 4, 1),
            LocalDate.of(2029, 4, 1),
        ).inOrder()
    }

    @Test
    fun `an end date stops the series`() {
        val rule = Fixtures.recurring(
            startDate = LocalDate.of(2026, 1, 5),
            endDate = LocalDate.of(2026, 3, 10),
        )
        assertThat(
            RecurrenceCalculator.occurrencesBetween(rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
        ).hasSize(3)
    }

    @Test
    fun `a maximum occurrence count stops the series`() {
        val rule = Fixtures.recurring(startDate = LocalDate.of(2026, 1, 5), maxOccurrences = 2)
        assertThat(
            RecurrenceCalculator.occurrencesBetween(rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
        ).containsExactly(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5)).inOrder()
    }

    @Test
    fun `an inactive rule produces nothing`() {
        val rule = Fixtures.recurring(isActive = false)
        assertThat(
            RecurrenceCalculator.occurrencesBetween(rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
        ).isEmpty()
        assertThat(RecurrenceCalculator.nextOccurrenceAfter(rule, LocalDate.of(2026, 1, 1))).isNull()
    }

    @Test
    fun `a window entirely before the rule starts produces nothing`() {
        val rule = Fixtures.recurring(startDate = LocalDate.of(2026, 6, 1))
        assertThat(
            RecurrenceCalculator.occurrencesBetween(rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1)),
        ).isEmpty()
    }

    @Test
    fun `next occurrence is strictly after the given date`() {
        val rule = Fixtures.recurring(startDate = LocalDate.of(2026, 1, 5))

        assertThat(RecurrenceCalculator.nextOccurrenceAfter(rule, LocalDate.of(2026, 3, 5)))
            .isEqualTo(LocalDate.of(2026, 4, 5))
        assertThat(RecurrenceCalculator.nextOccurrenceAfter(rule, LocalDate.of(2026, 3, 4)))
            .isEqualTo(LocalDate.of(2026, 3, 5))
    }

    @Test
    fun `next occurrence is null once the rule has ended`() {
        val rule = Fixtures.recurring(
            startDate = LocalDate.of(2026, 1, 5),
            endDate = LocalDate.of(2026, 3, 10),
        )
        assertThat(RecurrenceCalculator.nextOccurrenceAfter(rule, LocalDate.of(2026, 4, 1))).isNull()
    }

    // ---- Posting -----------------------------------------------------------------------------

    @Test
    fun `due postings cover everything not yet written`() {
        val rule = Fixtures.recurring(startDate = LocalDate.of(2026, 1, 5), lastPostedOn = null)
        assertThat(RecurrenceCalculator.duePostings(rule, LocalDate.of(2026, 3, 15)))
            .containsExactly(
                LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 2, 5),
                LocalDate.of(2026, 3, 5),
            ).inOrder()
    }

    @Test
    fun `posting is idempotent - already posted dates are not repeated`() {
        val rule = Fixtures.recurring(
            startDate = LocalDate.of(2026, 1, 5),
            lastPostedOn = LocalDate.of(2026, 2, 5),
        )
        assertThat(RecurrenceCalculator.duePostings(rule, LocalDate.of(2026, 3, 15)))
            .containsExactly(LocalDate.of(2026, 3, 5))
    }

    @Test
    fun `nothing is due when everything is already posted`() {
        val rule = Fixtures.recurring(
            startDate = LocalDate.of(2026, 1, 5),
            lastPostedOn = LocalDate.of(2026, 3, 5),
        )
        assertThat(RecurrenceCalculator.duePostings(rule, LocalDate.of(2026, 3, 15))).isEmpty()
    }

    // ---- Upcoming lists ----------------------------------------------------------------------

    @Test
    fun `upcoming reminders are ordered by due date across rules`() {
        val rent = Fixtures.recurring(id = "r1", name = "Rent", startDate = LocalDate.of(2026, 1, 5))
        val sip = Fixtures.recurring(id = "r2", name = "SIP", startDate = LocalDate.of(2026, 1, 2))

        val upcoming = RecurrenceCalculator.upcomingFromRules(
            listOf(rent, sip), LocalDate.of(2026, 3, 1), days = 10,
        )
        assertThat(upcoming.map { it.name }).containsExactly("SIP", "Rent").inOrder()
        assertThat(upcoming.first().dueOn).isEqualTo(LocalDate.of(2026, 3, 2))
    }

    @Test
    fun `upcoming subscriptions expand from their next payment date`() {
        val netflix = Fixtures.subscription(nextPaymentDate = LocalDate.of(2026, 3, 20))
        val upcoming = RecurrenceCalculator.upcomingFromSubscriptions(
            listOf(netflix), LocalDate.of(2026, 3, 1), days = 60,
        )
        assertThat(upcoming.map { it.dueOn })
            .containsExactly(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 4, 20)).inOrder()
    }

    @Test
    fun `cancelled subscriptions are excluded from upcoming charges`() {
        val cancelled = Fixtures.subscription(cancelledOn = LocalDate.of(2026, 3, 1))
        assertThat(
            RecurrenceCalculator.upcomingFromSubscriptions(
                listOf(cancelled), LocalDate.of(2026, 3, 1), days = 60,
            ),
        ).isEmpty()
    }

    @Test
    fun `advancing a subscription rolls its date past today`() {
        val stale = Fixtures.subscription(nextPaymentDate = LocalDate.of(2026, 1, 20))
        assertThat(RecurrenceCalculator.advanceSubscription(stale, LocalDate.of(2026, 3, 15)))
            .isEqualTo(LocalDate.of(2026, 3, 20))
    }

    @Test
    fun `advancing a current subscription leaves it unchanged`() {
        val current = Fixtures.subscription(nextPaymentDate = LocalDate.of(2026, 3, 20))
        assertThat(RecurrenceCalculator.advanceSubscription(current, LocalDate.of(2026, 3, 15)))
            .isEqualTo(LocalDate.of(2026, 3, 20))
    }
}
