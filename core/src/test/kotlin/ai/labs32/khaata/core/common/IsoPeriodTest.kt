package ai.labs32.khaata.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsoPeriodTest {

    @Test
    fun `days are read directly`() {
        assertThat(IsoPeriod.days("P7D")).isEqualTo(7)
        assertThat(IsoPeriod.days("P14D")).isEqualTo(14)
    }

    /** The form java.time.Period refuses, which is why this exists at all. */
    @Test
    fun `weeks are converted`() {
        assertThat(IsoPeriod.days("P1W")).isEqualTo(7)
        assertThat(IsoPeriod.days("P2W")).isEqualTo(14)
    }

    @Test
    fun `months and years are approximated`() {
        assertThat(IsoPeriod.days("P1M")).isEqualTo(30)
        assertThat(IsoPeriod.days("P1Y")).isEqualTo(365)
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertThat(IsoPeriod.days(" p7d ")).isEqualTo(7)
    }

    /** An unrecognised form must produce nothing, so the UI shows no badge rather than a wrong one. */
    @Test
    fun `unrecognised forms return null`() {
        assertThat(IsoPeriod.days(null)).isNull()
        assertThat(IsoPeriod.days("")).isNull()
        assertThat(IsoPeriod.days("7D")).isNull()
        assertThat(IsoPeriod.days("P1Y2M")).isNull()
        assertThat(IsoPeriod.days("PT30M")).isNull()
        assertThat(IsoPeriod.days("free trial")).isNull()
    }

    @Test
    fun `a zero or negative period is not a trial`() {
        assertThat(IsoPeriod.days("P0D")).isNull()
        assertThat(IsoPeriod.days("P-7D")).isNull()
    }
}
