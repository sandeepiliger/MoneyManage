package ai.labs32.khaata.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal

class MoneyFormatterTest {

    @Test
    fun `indian grouping uses the lakh crore system`() {
        assertThat(MoneyFormatter.plain(Money.of("142850"))).isEqualTo("₹1,42,850")
        assertThat(MoneyFormatter.plain(Money.of("1000"))).isEqualTo("₹1,000")
        assertThat(MoneyFormatter.plain(Money.of("100000"))).isEqualTo("₹1,00,000")
        assertThat(MoneyFormatter.plain(Money.of("10000000"))).isEqualTo("₹1,00,00,000")
        assertThat(MoneyFormatter.plain(Money.of("999"))).isEqualTo("₹999")
    }

    @Test
    fun `western grouping is used for western currencies`() {
        assertThat(MoneyFormatter.plain(Money.of("1428500", CurrencyCode.USD)))
            .isEqualTo("$1,428,500")
    }

    @Test
    fun `smart style keeps decimals only when they carry information`() {
        assertThat(MoneyFormatter.plain(Money.of("850"))).isEqualTo("₹850")
        assertThat(MoneyFormatter.plain(Money.of("850.50"))).isEqualTo("₹850.50")
    }

    @Test
    fun `signed style marks both directions`() {
        assertThat(MoneyFormatter.signed(Money.of("-850"))).isEqualTo("-₹850")
        assertThat(MoneyFormatter.signed(Money.of("35000"))).isEqualTo("+₹35,000")
    }

    @Test
    fun `compact style abbreviates with indian units`() {
        assertThat(MoneyFormatter.compact(Money.of("14300"))).isEqualTo("₹14.3K")
        assertThat(MoneyFormatter.compact(Money.of("142850"))).isEqualTo("₹1.4L")
        assertThat(MoneyFormatter.compact(Money.of("23000000"))).isEqualTo("₹2.3Cr")
        assertThat(MoneyFormatter.compact(Money.of("20000000"))).isEqualTo("₹2Cr")
        assertThat(MoneyFormatter.compact(Money.of("850"))).isEqualTo("₹850")
    }

    @Test
    fun `percentages render with a fallback for missing values`() {
        assertThat(MoneyFormatter.percentage(BigDecimal("18.4783"))).isEqualTo("18.5%")
        assertThat(MoneyFormatter.percentage(BigDecimal("18.4783"), decimals = 0)).isEqualTo("18%")
        assertThat(MoneyFormatter.percentage(null)).isEqualTo("—")
    }

    @Test
    fun `accessible descriptions spell the amount out`() {
        assertThat(MoneyFormatter.accessibleDescription(Money.of("-850"), "spent", "received"))
            .isEqualTo("850 indian rupees spent")
        assertThat(MoneyFormatter.accessibleDescription(Money.of("35000"), "spent", "received"))
            .isEqualTo("35000 indian rupees received")
    }

    @Test
    fun `zero renders without a sign`() {
        assertThat(MoneyFormatter.plain(Money.zero())).isEqualTo("₹0")
    }
}
