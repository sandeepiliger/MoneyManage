package ai.labs32.khaata.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoneyParserTest {

    @Test
    fun `plain and grouped numbers parse`() {
        assertThat(MoneyParser.parse("1234.50")).isEqualTo(Money.of("1234.50"))
        assertThat(MoneyParser.parse("1,234.50")).isEqualTo(Money.of("1234.50"))
        assertThat(MoneyParser.parse("1,42,850")).isEqualTo(Money.of("142850"))
    }

    @Test
    fun `currency symbols and rupee prefixes are stripped`() {
        assertThat(MoneyParser.parse("₹850")).isEqualTo(Money.of("850"))
        assertThat(MoneyParser.parse("Rs.850")).isEqualTo(Money.of("850"))
        assertThat(MoneyParser.parse("Rs 850")).isEqualTo(Money.of("850"))
        assertThat(MoneyParser.parse("INR 850")).isEqualTo(Money.of("850"))
        assertThat(MoneyParser.parse("850 rupees")).isNull() // suffix form is not an amount field
    }

    @Test
    fun `indian shorthand multipliers are applied`() {
        assertThat(MoneyParser.parse("50k")).isEqualTo(Money.of("50000"))
        assertThat(MoneyParser.parse("1.2k")).isEqualTo(Money.of("1200"))
        assertThat(MoneyParser.parse("2 lakh")).isEqualTo(Money.of("200000"))
        assertThat(MoneyParser.parse("2.5L")).isEqualTo(Money.of("250000"))
        assertThat(MoneyParser.parse("1.5cr")).isEqualTo(Money.of("15000000"))
        assertThat(MoneyParser.parse("3 crore")).isEqualTo(Money.of("30000000"))
    }

    @Test
    fun `words that merely end in a multiplier letter are not amounts`() {
        assertThat(MoneyParser.parse("lunch")).isNull()
        assertThat(MoneyParser.parse("hotel")).isNull()
        assertThat(MoneyParser.parse("milk")).isNull()
    }

    @Test
    fun `invalid input returns null rather than throwing`() {
        assertThat(MoneyParser.parse(null)).isNull()
        assertThat(MoneyParser.parse("")).isNull()
        assertThat(MoneyParser.parse("   ")).isNull()
        assertThat(MoneyParser.parse("abc")).isNull()
        assertThat(MoneyParser.parse("12.34.56")).isNull()
        assertThat(MoneyParser.parse("₹")).isNull()
    }

    @Test
    fun `negative input is rejected because sign comes from transaction type`() {
        assertThat(MoneyParser.parse("-850")).isNull()
    }

    @Test
    fun `absurdly large values are rejected`() {
        assertThat(MoneyParser.parse("99999999999999999999")).isNull()
    }
}
