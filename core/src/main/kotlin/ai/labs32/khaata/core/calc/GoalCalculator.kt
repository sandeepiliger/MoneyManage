package ai.labs32.khaata.core.calc

import ai.labs32.khaata.core.model.Goal
import ai.labs32.khaata.core.money.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Progress and pacing for savings goals.
 *
 * The number that actually changes behaviour is [GoalProgress.requiredMonthlyContribution] —
 * "₹8,400 a month to get there" is actionable in a way that "34% complete" is not. The reference
 * apps mostly show the percentage; we lead with the monthly figure.
 */
object GoalCalculator {

    fun progressOf(goal: Goal, asOf: LocalDate): GoalProgress {
        val remaining = (goal.targetAmount - goal.currentAmount).floorAtZero()
        val percent = goal.currentAmount.percentageOf(goal.targetAmount) ?: BigDecimal.ZERO

        val monthsRemaining = goal.targetDate?.let { target ->
            // Round part-months up: with 40 days left the user has two more contributions to
            // make, not one, and understating that is the failure mode that matters here.
            val days = ChronoUnit.DAYS.between(asOf, target)
            if (days <= 0) 0L else ((days + DAYS_PER_MONTH - 1) / DAYS_PER_MONTH)
        }

        val requiredMonthly = when {
            goal.isAchieved -> Money.zero(goal.targetAmount.currency)
            monthsRemaining == null -> null
            monthsRemaining <= 0L -> remaining // Target date has passed; it is all due now.
            else -> remaining / BigDecimal(monthsRemaining)
        }

        val elapsedMonths = ChronoUnit.MONTHS.between(goal.startedOn, asOf).coerceAtLeast(0)
        val pace = pace(goal, asOf)

        return GoalProgress(
            goal = goal,
            remaining = remaining,
            percentComplete = percent,
            monthsRemaining = monthsRemaining,
            requiredMonthlyContribution = requiredMonthly,
            isAchieved = goal.isAchieved,
            isOverdue = goal.targetDate != null && !goal.isAchieved && goal.targetDate.isBefore(asOf),
            pace = pace,
            projectedCompletionDate = projectCompletion(goal, asOf, elapsedMonths, remaining),
        )
    }

    /**
     * Whether the goal is keeping up with the pace its own timeline implies.
     *
     * Compares saved-so-far against what a straight line from start to target date would have
     * accumulated by today.
     */
    private fun pace(goal: Goal, asOf: LocalDate): GoalPace {
        if (goal.isAchieved) return GoalPace.ACHIEVED
        val targetDate = goal.targetDate ?: return GoalPace.NO_DEADLINE
        if (targetDate.isBefore(asOf)) return GoalPace.MISSED_DEADLINE

        val totalDays = ChronoUnit.DAYS.between(goal.startedOn, targetDate)
        if (totalDays <= 0) return GoalPace.NO_DEADLINE
        val elapsedDays = ChronoUnit.DAYS.between(goal.startedOn, asOf).coerceAtLeast(0)

        val expectedFraction = BigDecimal(elapsedDays)
            .divide(BigDecimal(totalDays), 6, RoundingMode.HALF_EVEN)
        val expectedAmount = goal.targetAmount.times(expectedFraction)

        return if (goal.currentAmount >= expectedAmount) GoalPace.ON_TRACK else GoalPace.BEHIND
    }

    /**
     * When the goal would complete at the rate saved so far.
     *
     * Null when there is not yet enough history to extrapolate, or when nothing has been saved —
     * an honest "not enough data yet" beats a made-up date.
     */
    private fun projectCompletion(
        goal: Goal,
        asOf: LocalDate,
        elapsedMonths: Long,
        remaining: Money,
    ): LocalDate? {
        if (goal.isAchieved) return goal.achievedOn ?: asOf
        if (elapsedMonths < 1) return null
        if (!goal.currentAmount.isPositive) return null

        val monthlyRate = goal.currentAmount / BigDecimal(elapsedMonths)
        if (!monthlyRate.isPositive) return null

        val monthsNeeded = remaining.amount
            .divide(monthlyRate.amount, 0, RoundingMode.CEILING)
            .toLong()
        // Cap the projection: "completes in 2183" is not information the user can use.
        if (monthsNeeded > MAX_PROJECTION_MONTHS) return null
        return asOf.plusMonths(monthsNeeded)
    }

    private const val DAYS_PER_MONTH = 30L
    private const val MAX_PROJECTION_MONTHS = 1200L
}

data class GoalProgress(
    val goal: Goal,
    val remaining: Money,
    val percentComplete: BigDecimal,
    /** Null when the goal has no target date. */
    val monthsRemaining: Long?,
    /** Null when the goal has no target date, so no monthly figure can be derived. */
    val requiredMonthlyContribution: Money?,
    val isAchieved: Boolean,
    val isOverdue: Boolean,
    val pace: GoalPace,
    val projectedCompletionDate: LocalDate?,
) {
    val percentCompleteClamped: Int
        get() = percentComplete.setScale(0, RoundingMode.HALF_EVEN).toInt().coerceIn(0, 100)
}

/**
 * How a goal is tracking.
 *
 * Each value maps to a distinct label and icon in the UI, never to colour alone.
 */
enum class GoalPace {
    ACHIEVED,
    ON_TRACK,
    BEHIND,
    MISSED_DEADLINE,
    NO_DEADLINE,
}
