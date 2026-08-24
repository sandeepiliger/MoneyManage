package ai.labs32.khaata.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {

    @Test
    fun `amounts are normalised to the currency scale`() {
        assertThat(Money.of("100").toPlainString()).isEqualTo("100.00")
        assertThat(Money.of("100.005").toPlainString()).isEqualTo("100.00") // HALF_EVEN -> down
        assertThat(Money.of("100.015").toPlainString()).isEqualTo("100.02") // HALF_EVEN -> up
    }

    @Test
    fun `zero-decimal currencies keep no fraction`() {
        assertThat(Money.of("1234.6", CurrencyCode.JPY).toPlainString()).isEqualTo("1235")
    }

    @Test
    fun `addition and subtraction are exact`() {
        // The classic float trap: 0.1 + 0.2 must be exactly 0.30.
        val sum = Money.of("0.10") + Money.of("0.20")
        assertThat(sum.toPlainString()).isEqualTo("0.30")
        assertThat(sum).isEqualTo(Money.of("0.30"))

        val remaining = Money.of("10000") - Money.of("8499.99")
        assertThat(remaining.toPlainString()).isEqualTo("1500.01")
    }

    @Test
    fun `mixing currencies is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Money.of("100", CurrencyCode.INR) + Money.of("100", CurrencyCode.USD)
        }
        assertThat(error).hasMessageThat().contains("Currency mismatch")
    }

    @Test
    fun `minor unit round trip is lossless`() {
        val amount = Money.of("1428.57")
        assertThat(amount.minorUnits).isEqualTo(142857L)
        assertThat(Money.ofMinor(142857L)).isEqualTo(amount)
        assertThat(Money.fromStorageString(amount.toStorageString())).isEqualTo(amount)
    }

    @Test
    fun `malformed storage strings return null instead of throwing`() {
        assertThat(Money.fromStorageString(null)).isNull()
        assertThat(Money.fromStorageString("")).isNull()
        assertThat(Money.fromStorageString("1234")).isNull()
        assertThat(Money.fromStorageString("XYZ:100")).isNull()
        assertThat(Money.fromStorageString("INR:abc")).isNull()
        assertThat(Money.fromStorageString(":100")).isNull()
    }

    @Test
    fun `splitting preserves the total exactly`() {
        val shares = Money.of("100").split(3)
        assertThat(shares.map { it.toPlainString() })
            .containsExactly("33.34", "33.33", "33.33").inOrder()
        assertThat(shares.sumOrZero()).isEqualTo(Money.of("100"))
    }

    @Test
    fun `splitting a negative total preserves the total exactly`() {
        val shares = Money.of("-100").split(3)
        assertThat(shares.sumOrZero()).isEqualTo(Money.of("-100"))
    }

    @Test
    fun `weighted allocation preserves the total exactly`() {
        val shares = Money.of("1000").allocate(
            listOf(BigDecimal("1"), BigDecimal("1"), BigDecimal("1"), BigDecimal("4")),
        )
        assertThat(shares.sumOrZero()).isEqualTo(Money.of("1000"))
        // Shares are floored, then the leftover paise go to the leading shares, so the
        // trailing share keeps its floored value and the total still reconciles exactly.
        assertThat(shares.map { it.toPlainString() })
            .containsExactly("142.86", "142.86", "142.86", "571.42").inOrder()
    }

    @Test
    fun `allocation rejects degenerate weights`() {
        assertThrows(IllegalArgumentException::class.java) { Money.of("100").allocate(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            Money.of("100").allocate(listOf(BigDecimal.ZERO, BigDecimal.ZERO))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Money.of("100").allocate(listOf(BigDecimal("-1"), BigDecimal("2")))
        }
    }

    @Test
    fun `percentage of a total is reported to four places`() {
        val food = Money.of("8500")
        val total = Money.of("46000")
        assertThat(food.percentageOf(total)!!.toPlainString()).isEqualTo("18.4783")
    }

    @Test
    fun `percentage of zero is null rather than infinity`() {
        assertThat(Money.of("100").percentageOf(Money.zero())).isNull()
    }

    @Test
    fun `dividing by zero is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Money.of("100") / 0 }
        assertThrows(IllegalArgumentException::class.java) { Money.of("100").split(0) }
    }

    @Test
    fun `sign helpers normalise direction`() {
        assertThat(Money.of("850").asNegative()).isEqualTo(Money.of("-850"))
        assertThat(Money.of("-850").asNegative()).isEqualTo(Money.of("-850"))
        assertThat(Money.of("-850").asPositive()).isEqualTo(Money.of("850"))
        assertThat(Money.of("-850").floorAtZero()).isEqualTo(Money.zero())
        assertThat(Money.of("850").floorAtZero()).isEqualTo(Money.of("850"))
    }

    @Test
    fun `equality ignores representation but respects currency`() {
        assertThat(Money.of("100.00")).isEqualTo(Money.of(100))
        assertThat(Money.of("100.00").hashCode()).isEqualTo(Money.of(100).hashCode())
        assertThat(Money.of("100", CurrencyCode.INR)).isNotEqualTo(Money.of("100", CurrencyCode.USD))
    }

    @Test
    fun `summing an empty collection yields zero`() {
        assertThat(emptyList<Money>().sumOrZero()).isEqualTo(Money.zero())
    }
}
