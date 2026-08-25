package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyMath
import java.math.BigDecimal

/**
 * Normalises everything that repeats to a comparable per-month figure.
 *
 * Lives here rather than in a ViewModel because two screens ask the same question — "what am I
 * committed to each month?" — and the recurring screen and the dashboard giving different answers
 * for a quarterly insurance premium would be worse than either answer alone.
 *
 * Weekly, fortnightly and daily items convert approximately, by construction: a month is not a
 * whole number of weeks. That inexactness is real, so every caller labels the result as
 * approximate rather than presenting it as a total.
 */
object CommitmentCalculator {

    /**
     * The monthly equivalent of an amount repeating every [interval] × [frequency].
     *
     * A quarterly ₹3,000 is ₹1,000 a month; a fortnightly ₹500 is about ₹1,083.
     */
    fun perMonth(amount: Money, frequency: Frequency, interval: Int = 1): Money {
        require(interval >= 1) { "Interval must be at least 1, got $interval" }
        val monthsPerOccurrence = BigDecimal(frequency.approximateMonthsPerOccurrence.toString())
            .multiply(BigDecimal(interval))
        return amount.times(BigDecimal.ONE.divide(monthsPerOccurrence, MoneyMath.PRECISION))
    }

    /** The yearly equivalent — the figure that actually changes minds about a subscription. */
    fun perYear(amount: Money, frequency: Frequency, interval: Int = 1): Money {
        require(interval >= 1) { "Interval must be at least 1, got $interval" }
        val occurrencesPerYear = BigDecimal(frequency.occurrencesPerYear.toString())
            .divide(BigDecimal(interval), MoneyMath.PRECISION)
        return amount.times(occurrencesPerYear)
    }

    /**
     * What a set of recurring rules commits the user to each month.
     *
     * Inactive rules are ignored, and transfers are excluded from both sides: moving ₹10,000 from
     * a salary account into a savings account every month is neither an outgoing commitment nor
     * income, and counting it would inflate both figures at once.
     */
    fun summarise(
        rules: List<RecurringRule>,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): Commitment {
        var out = Money.zero(currency)
        var incoming = Money.zero(currency)

        for (rule in rules) {
            if (!rule.isActive) continue
            val monthly = perMonth(rule.amount, rule.frequency, rule.interval)
            when (rule.type) {
                TransactionType.EXPENSE -> out += monthly
                TransactionType.INCOME -> incoming += monthly
                TransactionType.TRANSFER -> Unit
            }
        }

        return Commitment(outgoingPerMonth = out, incomingPerMonth = incoming)
    }

    /**
     * What a set of subscriptions costs, per month and per year.
     *
     * Cancelled services are excluded from the running cost but are deliberately kept in the
     * user's list elsewhere, so past months stay accurate.
     */
    fun summariseSubscriptions(
        subscriptions: List<Subscription>,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): SubscriptionTotals {
        val active = subscriptions.filter { it.isActive && it.cancelledOn == null }
        var monthly = Money.zero(currency)
        var yearly = Money.zero(currency)

        for (subscription in active) {
            monthly += perMonth(subscription.amount, subscription.cycle)
            yearly += perYear(subscription.amount, subscription.cycle)
        }

        return SubscriptionTotals(count = active.size, perMonth = monthly, perYear = yearly)
    }
}

/** Recurring income and outgoings, normalised to a month. */
data class Commitment(
    val outgoingPerMonth: Money,
    val incomingPerMonth: Money,
) {
    /** Negative means the recurring commitments alone outstrip the recurring income. */
    val netPerMonth: Money get() = incomingPerMonth - outgoingPerMonth

    val isSustainable: Boolean get() = !netPerMonth.isNegative
}

data class SubscriptionTotals(
    val count: Int,
    val perMonth: Money,
    val perYear: Money,
)

/**
 * A subscription's cost normalised to a month.
 *
 * An extension rather than a member of [Subscription] so the arithmetic has exactly one home. The
 * model would otherwise carry a second copy that could quietly disagree with the calculator.
 */
fun Subscription.monthlyEquivalent(): Money = CommitmentCalculator.perMonth(amount, cycle)

/** A subscription's cost normalised to a year — the figure that changes minds. */
fun Subscription.yearlyEquivalent(): Money = CommitmentCalculator.perYear(amount, cycle)
