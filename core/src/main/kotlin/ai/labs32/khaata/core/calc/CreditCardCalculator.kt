package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.model.AccountBalance
import ai.labs32.khaata.core.model.CreditCard
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.UtilisationBand
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.sumOfMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Statement cycles, utilisation and dues for a credit card.
 *
 * The genuinely useful thing an app can do here is answer "what will I actually be asked to pay,
 * and when?" — which requires getting the billing cycle right, including the case where the due
 * day falls in the month after the statement day. That boundary is where most homegrown
 * implementations go wrong, so it is modelled explicitly and tested.
 *
 * The outstanding amount comes from the linked account's derived balance, never from a stored
 * field, so it always agrees with the card's transaction list.
 */
object CreditCardCalculator {

    private val HUNDRED = BigDecimal("100")

    /** The statement cycle that [date] falls into. */
    fun cycleContaining(card: CreditCard, date: LocalDate): DateRange {
        val thisMonthStatement = clampDay(YearMonth.from(date), card.statementDayOfMonth)
        // A cycle runs from the day after one statement to the next statement, inclusive.
        return if (date.isAfter(thisMonthStatement)) {
            val next = clampDay(YearMonth.from(date).plusMonths(1), card.statementDayOfMonth)
            DateRange(thisMonthStatement.plusDays(1), next)
        } else {
            val previous = clampDay(YearMonth.from(date).minusMonths(1), card.statementDayOfMonth)
            DateRange(previous.plusDays(1), thisMonthStatement)
        }
    }

    /**
     * The payment due date for a statement generated on [statementDate].
     *
     * When the due day is on or before the statement day, payment falls in the following month —
     * a statement on the 25th with a due day of the 14th is due on the 14th of next month, which
     * is the common Indian card arrangement.
     */
    fun dueDateFor(card: CreditCard, statementDate: LocalDate): LocalDate {
        val sameMonth = clampDay(YearMonth.from(statementDate), card.dueDayOfMonth)
        return if (sameMonth.isAfter(statementDate)) {
            sameMonth
        } else {
            clampDay(YearMonth.from(statementDate).plusMonths(1), card.dueDayOfMonth)
        }
    }

    /**
     * The card's position as of [asOf].
     *
     * @param balance the linked account's derived balance. Negative means money is owed.
     */
    fun status(
        card: CreditCard,
        balance: AccountBalance,
        transactions: List<Transaction>,
        asOf: LocalDate,
    ): CreditCardStatus {
        val currency = card.creditLimit.currency
        // Spending pushes a card account negative; the amount owed is that magnitude.
        val outstanding = if (balance.currentBalance.isNegative) {
            balance.currentBalance.abs()
        } else {
            Money.zero(currency)
        }
        val availableCredit = (card.creditLimit - outstanding).floorAtZero()
        val utilisation = outstanding.percentageOf(card.creditLimit) ?: BigDecimal.ZERO

        val currentCycle = cycleContaining(card, asOf)
        val lastStatementDate = currentCycle.start.minusDays(1)
        val lastCycle = cycleContaining(card, lastStatementDate)

        val spendThisCycle = transactions
            .filter { it.countsAsSpending && it.accountId == card.accountId }
            .filter { it.occurredOn in currentCycle }
            .sumOfMoney(currency) { it.amount }

        // The statemented balance is what the last cycle closed at, which is the figure the user
        // is actually being billed for — distinct from today's running outstanding.
        val statementBalance = statementBalanceAt(card, balance, transactions, lastStatementDate)
        val minimumDue = minimumDue(card, statementBalance)

        return CreditCardStatus(
            card = card,
            outstanding = outstanding,
            availableCredit = availableCredit,
            utilisationPercent = utilisation,
            utilisationBand = bandFor(utilisation),
            currentCycle = currentCycle,
            lastStatementDate = lastStatementDate,
            lastStatementCycle = lastCycle,
            statementBalance = statementBalance,
            minimumDue = minimumDue,
            paymentDueOn = dueDateFor(card, lastStatementDate),
            spendThisCycle = spendThisCycle,
            nextStatementOn = currentCycle.endInclusive,
        )
    }

    /**
     * The balance that was outstanding when the statement closed on [statementDate].
     *
     * Derived by rewinding today's balance past everything that happened after the statement,
     * rather than storing statement snapshots — one source of truth, no reconciliation drift.
     */
    private fun statementBalanceAt(
        card: CreditCard,
        balance: AccountBalance,
        transactions: List<Transaction>,
        statementDate: LocalDate,
    ): Money {
        val currency = card.creditLimit.currency
        val since = transactions
            .filter { it.isEffective && it.occurredOn.isAfter(statementDate) }
            .sumOfMoney(currency) { it.signedAmountFor(card.accountId) }
        val atStatement = balance.currentBalance - since
        return if (atStatement.isNegative) atStatement.abs() else Money.zero(currency)
    }

    /**
     * Minimum payment: a percentage of the statement balance, subject to a floor.
     *
     * Issuers vary and some add interest and fees on top, so the UI presents this as an estimate.
     * We do not encourage paying it — [CreditCardStatus.interestWarning] exists because revolving
     * a balance at Indian card rates is expensive and the user deserves to see that plainly.
     */
    fun minimumDue(card: CreditCard, statementBalance: Money): Money {
        if (statementBalance.isZero) return Money.zero(card.creditLimit.currency)
        val percentage = statementBalance.percent(card.minimumDuePercent)
        val floored = if (percentage < card.minimumDueFloor) card.minimumDueFloor else percentage
        // Never ask for more than is owed.
        return if (floored > statementBalance) statementBalance else floored
    }

    private fun bandFor(utilisationPercent: BigDecimal): UtilisationBand = when {
        utilisationPercent >= HUNDRED -> UtilisationBand.OVER_LIMIT
        utilisationPercent > BigDecimal("70") -> UtilisationBand.HIGH
        utilisationPercent >= BigDecimal("30") -> UtilisationBand.ELEVATED
        else -> UtilisationBand.HEALTHY
    }

    private fun clampDay(month: YearMonth, day: Int): LocalDate =
        month.atDay(day.coerceAtMost(month.lengthOfMonth()))
}

/** A credit card's position at a point in time. */
data class CreditCardStatus(
    val card: CreditCard,
    /** Positive magnitude of what is currently owed. */
    val outstanding: Money,
    val availableCredit: Money,
    val utilisationPercent: BigDecimal,
    val utilisationBand: UtilisationBand,
    val currentCycle: DateRange,
    val lastStatementDate: LocalDate,
    val lastStatementCycle: DateRange,
    /** What the last statement closed at — the amount actually being billed. */
    val statementBalance: Money,
    val minimumDue: Money,
    val paymentDueOn: LocalDate,
    val spendThisCycle: Money,
    val nextStatementOn: LocalDate,
) {
    val utilisationPercentClamped: Int
        get() = utilisationPercent.setScale(0, RoundingMode.HALF_EVEN).toInt().coerceIn(0, 100)

    fun daysUntilDue(asOf: LocalDate): Long = ChronoUnit.DAYS.between(asOf, paymentDueOn)

    fun isOverdue(asOf: LocalDate): Boolean =
        statementBalance.isPositive && paymentDueOn.isBefore(asOf)

    /**
     * True when the user would be revolving a balance rather than clearing it.
     *
     * Surfaced as a plain factual note ("paying the minimum leaves ₹X accruing interest"), not
     * as advice about what to do.
     */
    val interestWarning: Boolean
        get() = statementBalance.isPositive && minimumDue < statementBalance
}
