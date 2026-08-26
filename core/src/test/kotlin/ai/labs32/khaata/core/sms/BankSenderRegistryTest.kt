package ai.labs32.khaata.core.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BankSenderRegistryTest {

    @Test
    fun `recognises a known bank with its DLT prefix`() {
        assertThat(BankSenderRegistry.nameFor("AD-HDFCBK")).isEqualTo("HDFC Bank")
        assertThat(BankSenderRegistry.nameFor("VM-SBIINB")).isEqualTo("State Bank of India")
        assertThat(BankSenderRegistry.nameFor("JD-ICICIB")).isEqualTo("ICICI Bank")
    }

    @Test
    fun `is case-insensitive`() {
        assertThat(BankSenderRegistry.nameFor("ad-hdfcbk")).isEqualTo("HDFC Bank")
    }

    @Test
    fun `works without a DLT prefix`() {
        assertThat(BankSenderRegistry.nameFor("HDFCBK")).isEqualTo("HDFC Bank")
    }

    @Test
    fun `returns null rather than guessing for an unrecognised sender`() {
        assertThat(BankSenderRegistry.nameFor("XY-RANDOM")).isNull()
        assertThat(BankSenderRegistry.nameFor("SOME-PROMO")).isNull()
    }

    @Test
    fun `returns null for a missing or blank sender`() {
        assertThat(BankSenderRegistry.nameFor(null)).isNull()
        assertThat(BankSenderRegistry.nameFor("")).isNull()
        assertThat(BankSenderRegistry.nameFor("   ")).isNull()
    }
}
