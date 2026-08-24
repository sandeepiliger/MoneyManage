package ai.labs32.khaata.core.nlp

import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class NaturalLanguageParserTest {

    private val parser = NaturalLanguageParser()

    /** 15 March 2026 is a Sunday. */
    private val today = LocalDate.of(2026, 3, 15)

    private fun parseOne(input: String) = parser.parse(input, today).single()

    // ---- The headline cases ------------------------------------------------------------------

    @Test
    fun `parses a simple spend with a merchant and a relative date`() {
        val entry = parseOne("I spent 850 on Swiggy yesterday")

        assertThat(entry.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(entry.amount).isEqualTo(Money.of("850"))
        assertThat(entry.merchantKey).isEqualTo("swiggy")
        assertThat(entry.merchantDisplayName).isEqualTo("Swiggy")
        assertThat(entry.occurredOn).isEqualTo(LocalDate.of(2026, 3, 14))
        assertThat(entry.dateWasExplicit).isTrue()
    }

    @Test
    fun `parses income`() {
        val entry = parseOne("I paid 35000 salary today")

        assertThat(entry.type).isEqualTo(TransactionType.INCOME)
        assertThat(entry.amount).isEqualTo(Money.of("35000"))
        assertThat(entry.occurredOn).isEqualTo(today)
    }

    @Test
    fun `splits a sentence with two amounts into two drafts`() {
        val entries = parser.parse("Spent 1200 petrol and 850 groceries", today)

        assertThat(entries).hasSize(2)
        assertThat(entries[0].amount).isEqualTo(Money.of("1200"))
        assertThat(entries[0].merchantRaw).ignoringCase().contains("petrol")
        assertThat(entries[1].amount).isEqualTo(Money.of("850"))
        assertThat(entries[1].merchantRaw).ignoringCase().contains("groceries")
    }

    @Test
    fun `splits on a comma as well as on and`() {
        val entries = parser.parse("250 chai, 1800 dinner, 400 auto", today)

        assertThat(entries).hasSize(3)
        assertThat(entries.map { it.amount }).containsExactly(
            Money.of("250"), Money.of("1800"), Money.of("400"),
        ).inOrder()
    }

    // ---- Amounts -----------------------------------------------------------------------------

    @Test
    fun `understands rupee markers and indian shorthand`() {
        assertThat(parseOne("paid ₹1,250 for dinner").amount).isEqualTo(Money.of("1250"))
        assertThat(parseOne("spent Rs.499 on books").amount).isEqualTo(Money.of("499"))
        assertThat(parseOne("got 50k salary").amount).isEqualTo(Money.of("50000"))
        assertThat(parseOne("paid 2 lakh for the car").amount).isEqualTo(Money.of("200000"))
        assertThat(parseOne("spent 1.5cr on the flat").amount).isEqualTo(Money.of("15000000"))
    }

    @Test
    fun `a bare single digit is not treated as an amount`() {
        // "2 coffees" must not become a ₹2 expense.
        assertThat(parser.parse("bought 2 coffees", today)).isEmpty()
    }

    @Test
    fun `a currency-marked single digit is an amount`() {
        assertThat(parseOne("spent Rs.5 on parking").amount).isEqualTo(Money.of("5"))
    }

    @Test
    fun `text with no amount yields nothing`() {
        assertThat(parser.parse("went to the market", today)).isEmpty()
        assertThat(parser.parse("", today)).isEmpty()
        assertThat(parser.parse("   ", today)).isEmpty()
    }

    // ---- Direction ---------------------------------------------------------------------------

    @Test
    fun `defaults to an expense when no direction word is present`() {
        assertThat(parseOne("450 groceries").type).isEqualTo(TransactionType.EXPENSE)
    }

    @Test
    fun `recognises income words`() {
        assertThat(parseOne("received 12000 from client").type).isEqualTo(TransactionType.INCOME)
        assertThat(parseOne("got 500 refund").type).isEqualTo(TransactionType.INCOME)
        assertThat(parseOne("earned 3000 bonus").type).isEqualTo(TransactionType.INCOME)
    }

    @Test
    fun `recognises transfers and flags them for review`() {
        val entry = parseOne("transferred 20000 to savings")

        assertThat(entry.type).isEqualTo(TransactionType.TRANSFER)
        // A transfer needs a destination account we cannot infer, so the UI must ask.
        assertThat(entry.needsReview).isTrue()
    }

    // ---- Dates -------------------------------------------------------------------------------

    @Test
    fun `resolves relative day phrases`() {
        assertThat(parseOne("spent 100 today").occurredOn).isEqualTo(today)
        assertThat(parseOne("spent 100 yesterday").occurredOn).isEqualTo(today.minusDays(1))
        assertThat(parseOne("spent 100 day before yesterday").occurredOn)
            .isEqualTo(today.minusDays(2))
        assertThat(parseOne("spent 100 3 days ago").occurredOn).isEqualTo(today.minusDays(3))
    }

    @Test
    fun `resolves weekday phrases backwards`() {
        // 15 March 2026 is a Sunday, so the most recent Friday is the 13th.
        assertThat(parseOne("spent 100 on Friday").occurredOn).isEqualTo(LocalDate.of(2026, 3, 13))
        assertThat(parseOne("spent 100 last Friday").occurredOn)
            .isEqualTo(LocalDate.of(2026, 3, 13))
    }

    @Test
    fun `resolves explicit dates`() {
        assertThat(parseOne("spent 100 on 05/03").occurredOn).isEqualTo(LocalDate.of(2026, 3, 5))
        assertThat(parseOne("spent 100 on 05/03/2025").occurredOn)
            .isEqualTo(LocalDate.of(2025, 3, 5))
    }

    @Test
    fun `defaults to today and says so when no date is given`() {
        val entry = parseOne("spent 850 on Swiggy")

        assertThat(entry.occurredOn).isEqualTo(today)
        assertThat(entry.dateWasExplicit).isFalse()
    }

    @Test
    fun `an out-of-range explicit date is ignored rather than crashing`() {
        val entry = parseOne("spent 100 on 45/99")
        assertThat(entry.occurredOn).isEqualTo(today)
    }

    // ---- Merchants ---------------------------------------------------------------------------

    @Test
    fun `filler words are stripped from the merchant`() {
        val entry = parseOne("I spent 850 on Swiggy yesterday")
        assertThat(entry.merchantRaw).isEqualTo("Swiggy")
    }

    @Test
    fun `a description with no recognisable merchant is flagged for review`() {
        val entry = parseOne("spent 500")

        assertThat(entry.merchantKey).isNull()
        assertThat(entry.needsReview).isTrue()
        assertThat(entry.amount).isEqualTo(Money.of("500"))
    }

    @Test
    fun `multi-word merchants are preserved`() {
        val entry = parseOne("paid 3200 to Indian Oil")
        assertThat(entry.merchantKey).isEqualTo("indian_oil")
    }

    @Test
    fun `each draft records the fragment it came from`() {
        val entries = parser.parse("1200 petrol and 850 groceries", today)

        assertThat(entries[0].sourceText).ignoringCase().contains("petrol")
        assertThat(entries[1].sourceText).ignoringCase().contains("groceries")
    }
}
