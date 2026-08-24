package ai.labs32.khaata.core.model

import ai.labs32.khaata.core.common.LocalDateSerializer
import ai.labs32.khaata.core.money.Money
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * A spending limit over a repeating period.
 *
 * A budget is defined by a rule, not by a pre-generated row per month: the period is computed
 * from [anchorDate] and [period] whenever it is needed. That means a budget created in March is
 * automatically correct in December without a background job having to materialise anything.
 */
@Serializable
data class Budget(
    val id: String,
    val name: String,
    val limit: Money,
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    /**
     * Categories this budget covers. Empty means "everything" — an overall spending limit.
     * Subcategory spend rolls up into a parent category listed here.
     */
    val categoryIds: Set<String> = emptySet(),
    /** Limits the budget to specific accounts. Empty means all accounts. */
    val accountIds: Set<String> = emptySet(),
    /** The date the budget starts from; period boundaries are derived from it. */
    @Serializable(with = LocalDateSerializer::class) val anchorDate: LocalDate,
    /** Optional end. Null means the budget repeats indefinitely. */
    @Serializable(with = LocalDateSerializer::class) val endDate: LocalDate? = null,
    /** Percentage of the limit at which the user is warned. */
    val alertThresholdPercent: Int = DEFAULT_ALERT_THRESHOLD,
    /**
     * Carries an unspent remainder into the next period.
     *
     * Off by default: rollover is genuinely useful for irregular categories like shopping, but
     * it makes "how much is left?" harder to reason about, so the user opts in per budget.
     */
    val rollsOver: Boolean = false,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
) {
    init {
        require(limit.isPositive) { "A budget limit must be positive, got $limit" }
        require(alertThresholdPercent in 1..100) {
            "Alert threshold must be a percentage between 1 and 100, got $alertThresholdPercent"
        }
        require(endDate == null || !endDate.isBefore(anchorDate)) {
            "Budget end date $endDate precedes its anchor $anchorDate"
        }
    }

    val isOverallLimit: Boolean get() = categoryIds.isEmpty()

    companion object {
        const val DEFAULT_ALERT_THRESHOLD = 85
    }
}

@Serializable
enum class BudgetPeriod { WEEKLY, MONTHLY }

/**
 * How severe a budget's position is.
 *
 * Deliberately more than a red/green split: [ON_TRACK] and [PROJECTED_OVER] can both sit under
 * 100% spent, but only one of them needs the user's attention today. Each level also carries a
 * distinct icon and label in the UI so status never depends on colour alone.
 */
enum class BudgetStatus {
    /** Comfortably within the limit and on pace. */
    ON_TRACK,

    /** Under the limit but spending faster than the period allows. */
    PROJECTED_OVER,

    /** Past the user's alert threshold but not yet over the limit. */
    NEARING_LIMIT,

    /** Spent the entire limit. */
    EXHAUSTED,

    /** Spent more than the limit. */
    OVERSPENT,
    ;

    val needsAttention: Boolean get() = this != ON_TRACK
}
