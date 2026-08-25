package ai.labs32.khaata.core.common

import java.time.LocalDate
import java.time.YearMonth

/**
 * The periods reports can be run over.
 *
 * A short fixed list rather than a date-range picker as the primary control: nearly every question
 * people actually ask is "this month", "last month" or "this year", and a picker makes each of
 * those cost four taps. A custom range is offered separately for the rarer case.
 *
 * [FINANCIAL_YEAR] exists because in India that is the period that matters at tax time, and it
 * runs April to March. An app that only offers a calendar year is asking Indian users to do the
 * arithmetic themselves every time.
 *
 * Kept in `core` rather than in the reports feature so the ranges can be tested without an
 * emulator — an off-by-one on a financial-year boundary is silent and wrong for a whole quarter.
 */
enum class ReportPeriod {
    THIS_MONTH,
    LAST_MONTH,
    LAST_30_DAYS,
    LAST_3_MONTHS,
    LAST_6_MONTHS,
    THIS_YEAR,
    FINANCIAL_YEAR,
    ;

    fun range(today: LocalDate): DateRange = when (this) {
        THIS_MONTH -> DateRange.ofMonth(today)
        LAST_MONTH -> DateRange.ofMonth(YearMonth.from(today).minusMonths(1))
        LAST_30_DAYS -> DateRange.lastDays(today, 30)
        LAST_3_MONTHS -> DateRange.lastDays(today, 90)
        LAST_6_MONTHS -> DateRange.lastDays(today, 182)
        THIS_YEAR -> DateRange.ofYear(today)
        FINANCIAL_YEAR -> DateRange.ofFinancialYear(today)
    }

    /**
     * How many trailing months the trend charts draw alongside this period.
     *
     * Six for anything up to half a year, twelve for the year views: a twelve-bar chart of daily
     * data is unreadable on a phone, and a six-bar chart of a financial year hides the seasonality
     * the year view exists to show.
     */
    val trendMonths: Int
        get() = when (this) {
            THIS_MONTH, LAST_MONTH, LAST_30_DAYS, LAST_3_MONTHS, LAST_6_MONTHS -> 6
            THIS_YEAR, FINANCIAL_YEAR -> 12
        }
}
