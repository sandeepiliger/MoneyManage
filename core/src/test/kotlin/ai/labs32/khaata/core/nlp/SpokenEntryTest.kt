package ai.labs32.khaata.core.nlp

import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Dictated entries: what a speech recogniser actually writes down, read the way it was meant.
 *
 * Each case is a phrasing that came back wrong or empty before -- an amount said in words, a
 * currency said after the number, a time of day read as money, or a recogniser whose first guess
 * was not the sensible one.
 */
class SpokenEntryTest {

    private val parser = NaturalLanguageParser()
    private val today = LocalDate.of(2026, 3, 15)

    private fun one(input: String) = parser.parse(input, today).single()

    // ---- Amounts said in words ---------------------------------------------------------------

    @Test
    fun `number words become the amount they say`() {
        val cases = mapOf(
            "spent five hundred on groceries" to "500",
            "paid fifteen hundred for petrol" to "1500",
            "two hundred and fifty on lunch" to "250",
            "twenty five hundred rent deposit" to "2500",
            "two thousand five hundred for shoes" to "2500",
            "one lakh twenty thousand for the bike" to "120000",
            "paid a hundred for parking" to "100",
            "ninety nine for chai and samosa" to "99",
            "2 thousand five hundred at Decathlon" to "2500",
            // Prices as they are said here.
            "four fifty on swiggy" to "450",
            "twelve fifty for the cylinder" to "1250",
            "two twenty five auto" to "225",
            "one fifty parking" to "150",
            "one thousand two hundred fifty on Amazon" to "1250",
            // Hinglish scale words.
            "5 sau chai" to "500",
            "2 hazaar rent advance" to "2000",
            "paanch sau rupaye petrol" to "500",
            "do hazaar paanch sau bijli bill" to "2500",
        )
        cases.forEach { (said, amount) ->
            assertThat(one(said).amount).isEqualTo(Money.of(amount))
        }
    }

    @Test
    fun `a lone small number word stays a word`() {
        // A quantity, not ₹2, and a phone brand, not ₹1.
        val coffee = one("two coffees 180")
        assertThat(coffee.amount).isEqualTo(Money.of("180"))
        assertThat(coffee.merchantRaw).contains("two")

        // Hinglish words outside an amount are words too.
        assertThat(one("do the laundry 120").merchantRaw).contains("do")

        val phone = one("bought one plus charger 1499")
        assertThat(phone.amount).isEqualTo(Money.of("1499"))
        assertThat(phone.merchantRaw).contains("one plus")
    }

    @Test
    fun `and between two things still separates them`() {
        val entries = parser.parse("chai fifty and samosa thirty", today)
        assertThat(entries.map { it.amount }).containsExactly(Money.of("50"), Money.of("30")).inOrder()
    }

    /** Two sentences dictated one after the other arrive with no "and" between them. */
    @Test
    fun `amounts with no separator keep their whole words`() {
        fun read(said: String) = parser.parse(said, today).map { it.amount.toString() + " " + it.merchantRaw }
        assertThat(read("chai 20 petrol 1200"))
            .containsExactly("${Money.of("20")} chai", "${Money.of("1200")} petrol").inOrder()
        assertThat(read("1200 petrol 850 groceries"))
            .containsExactly("${Money.of("1200")} petrol", "${Money.of("850")} groceries").inOrder()
        assertThat(read("spent 20 on chai 30 on samosa"))
            .containsExactly("${Money.of("20")} chai", "${Money.of("30")} samosa").inOrder()
    }

    // ---- Currency said after the number ------------------------------------------------------

    @Test
    fun `the currency word after the number is understood`() {
        val cases = mapOf(
            "450 rupees swiggy" to "450",
            "spent 5 rupees on a toffee" to "5",
            "rupees 300 auto" to "300",
            "80 rs chai" to "80",
            "paid 1200 bucks for the gym" to "1200",
            "five hundred rupees petrol" to "500",
            "Rs. 1250/- electricity" to "1250",
        )
        cases.forEach { (said, amount) ->
            val entry = one(said)
            assertThat(entry.amount).isEqualTo(Money.of(amount))
            assertThat(entry.merchantRaw?.lowercase().orEmpty()).doesNotContain("rupee")
        }
    }

    // ---- Times of day ------------------------------------------------------------------------

    @Test
    fun `a time of day is not read as money`() {
        listOf(
            "dinner 450 at 10:30",
            "dinner 450 at 10:30 pm",
            "dinner 450 at 9 pm",
            "dinner at 10 o'clock 450",
        ).forEach { said ->
            val entry = one(said)
            assertThat(entry.amount).isEqualTo(Money.of("450"))
        }
    }

    // ---- Typed input is unchanged ------------------------------------------------------------

    @Test
    fun `typed sentences read exactly as before`() {
        assertThat(one("I spent 850 on Swiggy yesterday").amount).isEqualTo(Money.of("850"))
        assertThat(one("received 35000 salary").type).isEqualTo(TransactionType.INCOME)
        assertThat(one("2.5 lakh bonus").amount).isEqualTo(Money.of("250000"))
        assertThat(parser.parse("1200 petrol and 850 groceries", today).map { it.amount })
            .containsExactly(Money.of("1200"), Money.of("850")).inOrder()
    }

    // ---- Choosing between recogniser alternatives ---------------------------------------------

    @Test
    fun `the alternative that reads as a spend wins over the recogniser's first guess`() {
        val best = parser.parseBest(
            // "four fifty" heard as "for five": no amount at all in the first guess.
            listOf("spent for five on swiggy", "spent four fifty on swiggy", "spent 4 50 on swiggy"),
            today,
        )!!
        assertThat(best.text).isEqualTo("spent four fifty on swiggy")
        assertThat(best.entries.single().amount).isEqualTo(Money.of("450"))
    }

    @Test
    fun `with several good readings the recogniser's own order decides`() {
        val best = parser.parseBest(listOf("uber 320", "uber 330"), today)!!
        assertThat(best.text).isEqualTo("uber 320")
    }

    @Test
    fun `a reading that names what the money was for beats one that does not`() {
        val best = parser.parseBest(listOf("250", "250 swiggy"), today)!!
        assertThat(best.text).isEqualTo("250 swiggy")
    }

    @Test
    fun `nothing that parses still returns what was heard`() {
        val best = parser.parseBest(listOf("hello there", "hello bear"), today)!!
        assertThat(best.text).isEqualTo("hello there")
        assertThat(best.entries).isEmpty()
        assertThat(parser.parseBest(emptyList(), today)).isNull()
    }
}
