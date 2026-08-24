package ai.labs32.khaata.core.common

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * An inclusive range of dates.
 *
 * Inclusive on both ends because that is how people talk about statements and budgets ("1st to
 * 31st"), and off-by-one errors at month boundaries are one of the easiest ways for a finance
 * app to quietly report the wrong total.
 */
@Serializable
data class DateRange(
    @Serializable(with = LocalDateSerializer::class) val start: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val endInclusive: LocalDate,
) {
    init {
        require(!endInclusive.isBefore(start)) { "DateRange end $endInclusive precedes start $start" }
    }

    val dayCount: Int get() = (ChronoUnit.DAYS.between(start, endInclusive) + 1).toInt()

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(start) && !date.isAfter(endInclusive)

    fun overlaps(other: DateRange): Boolean =
        !start.isAfter(other.endInclusive) && !other.start.isAfter(endInclusive)

    /** Days elapsed within this range as of [asOf], clamped to the range itself. */
    fun elapsedDays(asOf: LocalDate): Int = when {
        asOf.isBefore(start) -> 0
        asOf.isAfter(endInclusive) -> dayCount
        else -> (ChronoUnit.DAYS.between(start, asOf) + 1).toInt()
    }

    /** Days still to come in this range as of [asOf]. */
    fun remainingDays(asOf: LocalDate): Int = (dayCount - elapsedDays(asOf)).coerceAtLeast(0)

    /** The equivalent range one period earlier, used for month-over-month comparisons. */
    fun previousPeriod(): DateRange {
        val length = dayCount.toLong()
        // Calendar months are compared to calendar months, not to a fixed day count, so
        // "vs last month" stays correct across February.
        if (isWholeMonth()) {
            val previousMonth = YearMonth.from(start).minusMonths(1)
            return ofMonth(previousMonth)
        }
        return DateRange(start.minusDays(length), start.minusDays(1))
    }

    fun isWholeMonth(): Boolean {
        val month = YearMonth.from(start)
        return start.dayOfMonth == 1 &&
            YearMonth.from(endInclusive) == month &&
            endInclusive.dayOfMonth == month.lengthOfMonth()
    }

    companion object {
        fun ofMonth(month: YearMonth): DateRange =
            DateRange(month.atDay(1), month.atEndOfMonth())

        fun ofMonth(date: LocalDate): DateRange = ofMonth(YearMonth.from(date))

        /** The week containing [date]. Weeks start on Monday, matching Indian convention. */
        fun ofWeek(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.MONDAY): DateRange {
            var start = date
            while (start.dayOfWeek != firstDay) start = start.minusDays(1)
            return DateRange(start, start.plusDays(6))
        }

        /** The Indian financial year (1 April – 31 March) containing [date]. */
        fun ofFinancialYear(date: LocalDate): DateRange {
            val startYear = if (date.monthValue >= 4) date.year else date.year - 1
            return DateRange(LocalDate.of(startYear, 4, 1), LocalDate.of(startYear + 1, 3, 31))
        }

        fun ofYear(date: LocalDate): DateRange =
            DateRange(LocalDate.of(date.year, 1, 1), LocalDate.of(date.year, 12, 31))

        /** [days] days ending on [endInclusive], inclusive of both ends. */
        fun lastDays(endInclusive: LocalDate, days: Int): DateRange {
            require(days > 0) { "lastDays requires a positive day count" }
            return DateRange(endInclusive.minusDays(days - 1L), endInclusive)
        }

        /** The [count] whole months ending with the month containing [date], oldest first. */
        fun trailingMonths(date: LocalDate, count: Int): List<DateRange> {
            require(count > 0) { "trailingMonths requires a positive count" }
            val thisMonth = YearMonth.from(date)
            return (count - 1 downTo 0).map { ofMonth(thisMonth.minusMonths(it.toLong())) }
        }
    }
}
