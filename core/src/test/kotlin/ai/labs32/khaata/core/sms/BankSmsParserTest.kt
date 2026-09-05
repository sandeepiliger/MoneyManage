package ai.labs32.khaata.core.sms

import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * All message bodies below are synthetic, written to match the *shape* of Indian bank SMS
 * (amount markers, direction words, masked suffixes, rail markers). They contain no real
 * account numbers, names or references.
 */
class BankSmsParserTest {

    private val received = LocalDate.of(2026, 3, 15)

    private fun parse(body: String, sender: String? = null) =
        BankSmsParser.parse(body, received, sender)

    // ---- UPI ---------------------------------------------------------------------------------

    @Test
    fun `parses a UPI debit`() {
        val parsed = parse(
            "Rs.850.00 debited from A/c XX4321 on 14-03-26 to VPA swiggy@hdfcbank " +
                "UPI Ref 412345678901. Avl Bal Rs.42,150.00",
        )!!

        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amount).isEqualTo(Money.of("850"))
        assertThat(parsed.merchantKey).isEqualTo("swiggy")
        assertThat(parsed.occurredOn).isEqualTo(LocalDate.of(2026, 3, 14))
        assertThat(parsed.accountSuffix).isEqualTo("4321")
        assertThat(parsed.rail).isEqualTo(PaymentRail.UPI)
        assertThat(parsed.referenceNumber).isEqualTo("412345678901")
        assertThat(parsed.availableBalance).isEqualTo(Money.of("42150"))
    }

    @Test
    fun `parses a UPI credit`() {
        val parsed = parse(
            "INR 2,500.00 credited to A/c XX8899 from VPA rahul@okaxis on 15-03-2026. UPI Ref 998877665544",
        )!!

        assertThat(parsed.type).isEqualTo(TransactionType.INCOME)
        assertThat(parsed.amount).isEqualTo(Money.of("2500"))
        assertThat(parsed.merchantKey).isEqualTo("rahul")
    }

    @Test
    fun `parses a bank-worded UPI sent debit`() {
        // The shape a bank itself sends, as opposed to the UPI app's own confirmation below --
        // "Sent" rather than "debited" as the direction word.
        val parsed = parse(
            "Sent Rs.500.00 From HDFC Bank A/C x4321 To john@okhdfcbank On 26-08-26 " +
                "Ref 412345678999 Not You? Call 18002586161",
        )!!

        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amount).isEqualTo(Money.of("500"))
        assertThat(parsed.accountSuffix).isEqualTo("4321")
    }

    @Test
    fun `parses a UPI app's own 'you sent' confirmation, with no account suffix at all`() {
        // GPay/PhonePe-style confirmations rarely quote a masked account -- they know it, but
        // don't say it. Direction and amount must still be extracted from wording alone.
        val parsed = parse("You sent ₹500 to John Doe using UPI. UPI transaction ID 412345678999.")!!

        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amount).isEqualTo(Money.of("500"))
        assertThat(parsed.accountSuffix).isNull()
    }

    @Test
    fun `parses 'money sent successfully' phrasing`() {
        val parsed = parse("Money sent successfully! Rs 500 to Jane via UPI. Txn ID 412345678999")!!

        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amount).isEqualTo(Money.of("500"))
    }

    // ---- Cards -------------------------------------------------------------------------------

    @Test
    fun `parses a card POS purchase`() {
        val parsed = parse(
            "Rs 1,249.00 spent on your Credit Card ending 4321 at AMAZON on 12-03-2026. " +
                "Not you? Call us.",
        )!!

        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amount).isEqualTo(Money.of("1249"))
        assertThat(parsed.merchantKey).isEqualTo("amazon")
        assertThat(parsed.accountSuffix).isEqualTo("4321")
    }

    @Test
    fun `a credit card purchase with no direction word is a spend, not income`() {
        // The single most common card message in an Indian inbox, and it carries no direction
        // word: the only candidate is the "Credit" of "Credit Card", which names the card. Read
        // literally it used to file a ₹500 purchase as ₹500 of income.
        val parsed = parse(
            "Thank you for using your HDFC Bank Credit Card ending 1234 for Rs.500.00 " +
                "at AMAZON on 05-09-26.",
        )!!

        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amount).isEqualTo(Money.of("500"))
        assertThat(parsed.merchantKey).isEqualTo("amazon")
    }

    @Test
    fun `a debit card purchase with no direction word is still a spend`() {
        val parsed = parse(
            "Your Debit Card XX4321 has been used for Rs.500.00 at BIGBAZAAR on 05-09-26.",
        )!!

        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amount).isEqualTo(Money.of("500"))
    }

    @Test
    fun `a refund to a card is still income, despite the card being mentioned`() {
        // The card-usage fallback must not outrank a real direction word.
        val parsed = parse(
            "Rs.500.00 refunded to your HDFC Bank Credit Card XX1234 by AMAZON on 05-09-26.",
        )!!

        assertThat(parsed.type).isEqualTo(TransactionType.INCOME)
    }

    @Test
    fun `money credited to an account is unaffected by the card wording rule`() {
        val parsed = parse("INR 2,500.00 credited to A/c XX8899 on 15-03-2026.")!!

        assertThat(parsed.type).isEqualTo(TransactionType.INCOME)
    }

    @Test
    fun `parses an ATM withdrawal`() {
        val parsed = parse("Rs.5000 withdrawn from A/c XX1234 at ATM on 10-03-2026. Avl Bal Rs.20,000")!!

        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amount).isEqualTo(Money.of("5000"))
        assertThat(parsed.rail).isEqualTo(PaymentRail.ATM)
    }

    // ---- Other rails -------------------------------------------------------------------------

    @Test
    fun `parses NEFT and IMPS rails`() {
        assertThat(parse("INR 35000 credited to A/c XX1234 by NEFT on 01-03-2026")!!.rail)
            .isEqualTo(PaymentRail.NEFT)
        assertThat(parse("Rs.7500 debited from A/c XX1234 via IMPS to Ramesh on 02-03-2026")!!.rail)
            .isEqualTo(PaymentRail.IMPS)
    }

    @Test
    fun `parses an EMI debit`() {
        val parsed = parse("Rs.16,607 debited from A/c XX1234 towards EMI on 15-03-2026")!!

        assertThat(parsed.rail).isEqualTo(PaymentRail.EMI)
        assertThat(parsed.amount).isEqualTo(Money.of("16607"))
    }

    // ---- The balance trap --------------------------------------------------------------------

    @Test
    fun `the available balance is never mistaken for the transaction amount`() {
        // The balance is much larger than the spend, so a "biggest number wins" parser fails here.
        val parsed = parse(
            "Rs.120.00 debited from A/c XX4321 at CHAI POINT on 15-03-2026. Avl Bal Rs.1,42,850.00",
        )!!

        assertThat(parsed.amount).isEqualTo(Money.of("120"))
        assertThat(parsed.availableBalance).isEqualTo(Money.of("142850"))
    }

    // ---- Non-transactions --------------------------------------------------------------------

    @Test
    fun `an OTP message is not a transaction`() {
        assertThat(
            parse("123456 is your OTP for a transaction of Rs.5000. Do not share it with anyone."),
        ).isNull()
    }

    @Test
    fun `a promotional message is not a transaction`() {
        assertThat(parse("Congratulations! You are eligible for a pre-approved loan of Rs.5,00,000. Apply now."))
            .isNull()
        assertThat(parse("Get 10% cashback offer up to Rs.500 on your next purchase. Click here"))
            .isNull()
    }

    @Test
    fun `a future-dated reminder is not a transaction`() {
        assertThat(parse("Your bill of Rs.2,340 is due on 20-03-2026. Please pay to avoid charges."))
            .isNull()
        assertThat(parse("Rs.649 will be debited from your A/c XX4321 towards Netflix on 20-03-2026"))
            .isNull()
    }

    @Test
    fun `a collect request is not a transaction`() {
        assertThat(parse("merchant@paytm has been requested Rs.499 via UPI. Approve in your app."))
            .isNull()
    }

    @Test
    fun `a failed transaction is not recorded`() {
        assertThat(parse("Your payment of Rs.1200 to AMAZON has failed. Amount will be reversed."))
            .isNull()
    }

    @Test
    fun `a message with no amount is rejected`() {
        assertThat(parse("Your account statement is now available.")).isNull()
    }

    @Test
    fun `a message with no direction word is rejected`() {
        assertThat(parse("Balance in A/c XX4321 is Rs.42,150.00 as on 15-03-2026")).isNull()
    }

    @Test
    fun `a zero amount is rejected`() {
        assertThat(parse("Rs.0.00 debited from A/c XX4321 on 15-03-2026")).isNull()
    }

    @Test
    fun `blank input is rejected`() {
        assertThat(parse("")).isNull()
        assertThat(parse("   ")).isNull()
    }

    // ---- Dates -------------------------------------------------------------------------------

    @Test
    fun `the received date is used when the message carries none`() {
        val parsed = parse("Rs.450 debited from A/c XX4321 at BIGBASKET")!!
        assertThat(parsed.occurredOn).isEqualTo(received)
    }

    @Test
    fun `several date formats are understood`() {
        assertThat(parse("Rs.100 debited at SHOP on 05-01-2026")!!.occurredOn)
            .isEqualTo(LocalDate.of(2026, 1, 5))
        assertThat(parse("Rs.100 debited at SHOP on 05/01/2026")!!.occurredOn)
            .isEqualTo(LocalDate.of(2026, 1, 5))
        assertThat(parse("Rs.100 debited at SHOP on 05-Jan-2026")!!.occurredOn)
            .isEqualTo(LocalDate.of(2026, 1, 5))
        assertThat(parse("Rs.100 debited at SHOP on 05-Jan-26")!!.occurredOn)
            .isEqualTo(LocalDate.of(2026, 1, 5))
    }

    @Test
    fun `an unparseable date falls back to the received date rather than dropping the parse`() {
        val parsed = parse("Rs.100 debited at SHOP on 45-99-2026")!!
        assertThat(parsed.occurredOn).isEqualTo(received)
        assertThat(parsed.amount).isEqualTo(Money.of("100"))
    }

    // ---- Confidence --------------------------------------------------------------------------

    @Test
    fun `a rich message scores higher than a sparse one`() {
        val rich = parse(
            "Rs.850.00 debited from A/c XX4321 on 14-03-26 to VPA swiggy@hdfcbank UPI Ref 412345678901",
        )!!
        val sparse = parse("Rs.450 debited")!!

        assertThat(rich.confidence).isGreaterThan(sparse.confidence)
        assertThat(rich.needsCloserReview).isFalse()
        assertThat(sparse.needsCloserReview).isTrue()
    }

    @Test
    fun `direction is taken from the word describing the user's own account`() {
        // "debited ... credited to beneficiary" must read as an expense for the sender.
        val parsed = parse(
            "Rs.5000 debited from A/c XX4321 and credited to beneficiary Ramesh on 15-03-2026",
        )!!
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
    }

    @Test
    fun `the sender id is carried through for account matching`() {
        val parsed = parse("Rs.100 debited from A/c XX4321 at SHOP", sender = "AD-HDFCBK")!!
        assertThat(parsed.sender).isEqualTo("AD-HDFCBK")
    }

    // ---- Account suffix kind -------------------------------------------------------------------

    @Test
    fun `a bank-account suffix is distinguished from a card suffix`() {
        val parsed = parse("Rs.850.00 debited from A/c XX4321 at SHOP")!!
        assertThat(parsed.accountSuffix).isEqualTo("4321")
        assertThat(parsed.accountSuffixKind).isEqualTo(AccountSuffixKind.BANK)
    }

    @Test
    fun `a card suffix is flagged distinctly from a bank account`() {
        val parsed = parse("Rs 1,249.00 spent on your Credit Card ending 4321 at AMAZON")!!
        assertThat(parsed.accountSuffix).isEqualTo("4321")
        assertThat(parsed.accountSuffixKind).isEqualTo(AccountSuffixKind.CARD)
    }

    @Test
    fun `no suffix at all leaves the kind null too`() {
        val parsed = parse("You sent Rs.500 to John Doe using UPI. UPI transaction ID 412345678999.")!!
        assertThat(parsed.accountSuffix).isNull()
        assertThat(parsed.accountSuffixKind).isNull()
    }

    @Test
    fun `when both a bank account and a card are mentioned, the earlier one wins`() {
        val parsed = parse(
            "Rs.500 debited from A/c XX1234 and sent to card ending 9999 as bill payment",
        )!!
        assertThat(parsed.accountSuffix).isEqualTo("1234")
        assertThat(parsed.accountSuffixKind).isEqualTo(AccountSuffixKind.BANK)
    }
}
