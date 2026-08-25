package ai.labs32.khaata.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ReportPeriodTest {

    private val midMarch = LocalDate.of(2026, 3, 15)
    private val midApril = LocalDate.of(2026, 4, 15)

    @Test
    fun `this month spans the calendar month`() {
        val range = ReportPeriod.THIS_MONTH.range(midMarch)
        assertThat(range.start).isEqualTo(LocalDate.of(2026, 3, 1))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2026, 3, 31))
    }

    @Test
    fun `last month spans the previous calendar month`() {
        val range = ReportPeriod.LAST_MONTH.range(midMarch)
        assertThat(range.start).isEqualTo(LocalDate.of(2026, 2, 1))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2026, 2, 28))
    }

    /** Stepping back from the 31st must not produce an invalid date in a shorter month. */
    @Test
    fun `last month from the 31st lands on a real date`() {
        val range = ReportPeriod.LAST_MONTH.range(LocalDate.of(2026, 3, 31))
        assertThat(range.start).isEqualTo(LocalDate.of(2026, 2, 1))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2026, 2, 28))
    }

    @Test
    fun `last 30 days includes today and is exactly 30 days long`() {
        val range = ReportPeriod.LAST_30_DAYS.range(midMarch)
        assertThat(range.endInclusive).isEqualTo(midMarch)
        assertThat(range.dayCount).isEqualTo(30)
    }

    @Test
    fun `this year spans the calendar year`() {
        val range = ReportPeriod.THIS_YEAR.range(midMarch)
        assertThat(range.start).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2026, 12, 31))
    }

    /**
     * The reason this enum is tested at all: an Indian financial year runs 1 April to 31 March, so
     * a date in March belongs to the year that started the *previous* April. Getting this wrong is
     * silent and produces a wrong answer for a whole quarter.
     */
    @Test
    fun `March belongs to the financial year that started the previous April`() {
        val range = ReportPeriod.FINANCIAL_YEAR.range(midMarch)
        assertThat(range.start).isEqualTo(LocalDate.of(2025, 4, 1))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2026, 3, 31))
    }

    @Test
    fun `April starts a new financial year`() {
        val range = ReportPeriod.FINANCIAL_YEAR.range(midApril)
        assertThat(range.start).isEqualTo(LocalDate.of(2026, 4, 1))
        assertThat(range.endInclusive).isEqualTo(LocalDate.of(2027, 3, 31))
    }

    @Test
    fun `the financial year and the calendar year differ for a March date`() {
        assertThat(ReportPeriod.FINANCIAL_YEAR.range(midMarch))
            .isNotEqualTo(ReportPeriod.THIS_YEAR.range(midMarch))
    }

    @Test
    fun `every period produces a non-empty range containing a date within it`() {
        ReportPeriod.entries.forEach { period ->
            val range = period.range(midMarch)
            assertThat(range.dayCount).isGreaterThan(0)
            assertThat(range.start).isAtMost(range.endInclusive)
        }
    }

    @Test
    fun `year views draw a longer trend than month views`() {
        assertThat(ReportPeriod.THIS_MONTH.trendMonths).isEqualTo(6)
        assertThat(ReportPeriod.FINANCIAL_YEAR.trendMonths).isEqualTo(12)
    }
}
