package ai.labs32.khaata.core.sms

import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.model.TransactionType.EXPENSE
import ai.labs32.khaata.core.model.TransactionType.INCOME
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDate

/**
 * Money in and money out, read from the message shapes Indian banks actually send.
 *
 * The direction is the one thing the parser must never get wrong: a spend read as income is
 * off by twice the amount, in the balance and in every report. Each case below states the
 * direction, the amount and, where the bank quotes one, the balance.
 */
class RealWorldSmsDirectionTest {

    private data class Case(
        val label: String,
        val body: String,
        val type: TransactionType,
        val amount: String,
        val balance: String? = null,
        val reference: String? = null,
    )

    private val cases = listOf(
        // ---- Money in -------------------------------------------------------------------------
        Case(
            "HDFC salary by NEFT",
            "Update! INR 85,000.00 deposited in HDFC Bank A/c XX4321 on 01-MAR-26 for NEFT Cr-HDFC0000123-ACME CORP-SALARY MAR 2026.Avl bal INR 1,23,456.78. Cheque deposits in A/C are subject to clearing",
            INCOME, "85000", balance = "123456.78",
        ),
        Case(
            "ICICI UPI credit",
            "Dear Customer, Acct XX123 is credited with Rs 2500.00 on 05-Mar-26 from rahul@okaxis. UPI:412345678901-ICICI Bank.",
            INCOME, "2500", reference = "412345678901",
        ),
        Case(
            "SBI transfer credit",
            "Dear SBI User, your A/c X1234-credited by Rs.5000 on 05Mar26 transfer from RAHUL KUMAR Ref No 412345678901 -SBI",
            INCOME, "5000", reference = "412345678901",
        ),
        Case(
            "Axis UPI credit",
            "INR 1,000.00 credited to A/c no. XX1234 on 05-03-26 at 10:15:22 IST. Info- UPI/P2A/412345678901/RAHUL. Avl Bal- INR 45,000.00 - Axis Bank",
            INCOME, "1000", balance = "45000", reference = "412345678901",
        ),
        Case(
            "Kotak UPI received",
            "Received Rs.500.00 in your Kotak Bank a/c XX1234 from rahul@okicici on 05-03-26.UPI Ref:412345678901.",
            INCOME, "500",
        ),
        Case(
            "IMPS credit",
            "Rs 10000.00 credited to a/c XXXXXX1234 on 05/03/26 by a/c linked to mobile 9XXXXXX999 (IMPS Ref no 412345678901). Available balance Rs 50,000.00",
            INCOME, "10000", balance = "50000",
        ),
        Case(
            "Cash deposit",
            "Rs.10,000.00 has been deposited in cash to your A/c XX1234 on 05-03-26. Avl Bal Rs.55,000.00",
            INCOME, "10000", balance = "55000",
        ),
        Case(
            "Cheque deposit",
            "Cheque No 123456 for Rs 20,000.00 deposited in your a/c XX1234 has been credited on 05-03-26",
            INCOME, "20000",
        ),
        Case("Interest", "Interest of Rs 1,234.00 credited to your a/c XX1234 on 31-03-2026.", INCOME, "1234"),
        Case("Dividend", "Dividend of Rs.350.00 credited to your A/c XX1234 on 05-03-26 from INFOSYS LTD", INCOME, "350"),
        Case(
            "Refund",
            "Refund of Rs.499.00 for your Amazon order has been credited to your a/c XX1234 on 05-03-26.",
            INCOME, "499",
        ),
        Case("Cashback", "Cashback of Rs.50 credited to your Amazon Pay balance", INCOME, "50"),
        Case("PhonePe received", "Received ₹1,500 from Rahul via PhonePe. UPI Ref 412345678901", INCOME, "1500"),
        Case(
            "Abbreviated Cr",
            "Your A/c XX1234 Cr with Rs.85000.00 on 01-03-26 by NEFT. Avl Bal Rs.1,00,000.00",
            INCOME, "85000", balance = "100000",
        ),
        Case(
            "Added to your account",
            "INR 60,000.00 has been added to your account XX1234 via NEFT from EMPLOYER on 01-03-26. Available Balance: INR 70,000.00",
            INCOME, "60000", balance = "70000",
        ),
        Case(
            "Reversal of a failed payment",
            "Rs.500.00 reversed to your A/c XX1234 on 05-03-26 against failed UPI txn Ref 412345678901",
            INCOME, "500",
        ),
        Case(
            "Debit credited back",
            "Amount of Rs.750.00 debited on 03-03-26 has been credited back to your A/c XX1234. Ref 412345678902",
            INCOME, "750",
        ),
        Case(
            "Card bill payment received on the card",
            "Payment of Rs. 25,000.00 has been received towards your HDFC Bank Credit Card ending 1234 on 05-03-2026. Thank you",
            INCOME, "25000",
        ),

        // ---- Money out ------------------------------------------------------------------------
        Case(
            "SBI UPI debit with no currency marker",
            "Dear UPI user A/C X1234 debited by 250.0 on date 05Mar26 trf to SWIGGY Refno 412345678901. If not u? call 1800111109. -SBI",
            EXPENSE, "250",
        ),
        Case(
            "Card spend quoting available credit limit",
            "Thank you for using your ICICI Bank Credit Card XX1234 for INR 2,500.00 at FLIPKART on 05-Mar-26. Available Credit Limit: INR 47,500.00",
            EXPENSE, "2500",
        ),
        Case(
            "Card transaction quoting available credit",
            "INR 2,500.00 transaction on your Axis Bank Card no. XX1234 at SWIGGY on 05-03-26. Available credit: INR 97,500.00",
            EXPENSE, "2500",
        ),
        Case(
            "Card spend quoting Avl Lmt",
            "Rs.1,299.00 spent on your SBI Credit Card ending 1234 at AMAZON on 05/03/26. Avl Lmt Rs.48,701.00",
            EXPENSE, "1299",
        ),
        Case("ATM withdrawal", "Rs.2000.00 withdrawn at ATM from A/c XX1234 on 05-03-26. Avl Bal Rs.40,000.00", EXPENSE, "2000", balance = "40000"),
        Case(
            "NACH EMI",
            "Your a/c XX1234 has been debited with INR 12,345.00 towards NACH ECS for HDFC LOAN EMI on 05-03-26.",
            EXPENSE, "12345",
        ),
        Case("POS", "Rs 450.00 debited from a/c **1234 on 05-03-26 to POS DMART. Avl bal Rs 9,550.00", EXPENSE, "450", balance = "9550"),
        Case(
            "Debited here, credited to the beneficiary",
            "Your A/c XX1234 is debited for Rs.500.00 on 05-03-26 and credited to A/c XX9876 (UPI Ref No 412345678901)",
            EXPENSE, "500",
        ),
        Case("Abbreviated Dr", "A/c XX1234 Dr with INR 500.00 on 05-03-26 for UPI to swiggy. Bal INR 9,500.00", EXPENSE, "500", balance = "9500"),
        Case("Wallet payment", "Paid Rs.200 to Chai Point from Paytm Wallet. Txn ID 12345678901", EXPENSE, "200"),
        Case(
            "Balance quoted before the amount",
            "Avl Bal Rs 9,000.00 after Rs 1,000.00 debited from A/c XX1234 on 05-03-26",
            EXPENSE, "1000", balance = "9000",
        ),
    )

    private val notTransactions = listOf(
        "OTP" to "123456 is your OTP for txn of Rs 500 at AMAZON. Do not share.",
        "Balance enquiry" to "Your A/c XX1234 balance is Rs 10,000.00 as on 05-03-26",
        "Statement" to "Your HDFC Credit Card XX1234 statement: Total due Rs 12,000, min due Rs 600, due on 15-03-26",
        "Promised reversal" to "Your txn of Rs.500 could not be completed. Amount will be reversed within 5 days.",
        "Failed payment" to "Your UPI payment of Rs.500 to SWIGGY has failed. No amount was debited.",
    )

    @Test
    fun `every real-world shape is read in the right direction with the right amount`() {
        cases.forEach { case ->
            val parsed = BankSmsParser.parse(case.body, LocalDate.of(2026, 3, 5), "AD-XXBANK")
            assertWithMessage("${case.label}: parsed").that(parsed).isNotNull()
            parsed!!
            assertWithMessage("${case.label}: direction").that(parsed.type).isEqualTo(case.type)
            assertWithMessage("${case.label}: amount").that(parsed.amount).isEqualTo(Money.of(case.amount))
            case.balance?.let {
                assertWithMessage("${case.label}: balance").that(parsed.availableBalance).isEqualTo(Money.of(it))
            }
            case.reference?.let {
                assertWithMessage("${case.label}: reference").that(parsed.referenceNumber).isEqualTo(it)
            }
        }
    }

    @Test
    fun `messages that move no money are never imported`() {
        notTransactions.forEach { (label, body) ->
            assertWithMessage(label).that(BankSmsParser.parse(body, LocalDate.of(2026, 3, 5))).isNull()
        }
    }

    /**
     * Credit card messages must be told apart from bank and debit card ones: a credit card spend
     * is card debt, and filed against a bank account it took money out that never left.
     */
    @Test
    fun `credit card messages are recognised as such, debit card and bank messages are not`() {
        fun isCredit(body: String) = BankSmsParser.parse(body, LocalDate.of(2026, 3, 5))!!.isCreditCard
        assertWithMessage("credit card spend").that(
            isCredit("Rs.1,299.00 spent on your SBI Credit Card ending 1234 at AMAZON on 05/03/26. Avl Lmt Rs.48,701.00"),
        ).isTrue()
        assertWithMessage("credit card payment received").that(
            isCredit("Payment of Rs. 25,000.00 has been received towards your HDFC Bank Credit Card ending 1234 on 05-03-2026."),
        ).isTrue()
        assertWithMessage("debit card spend").that(
            isCredit("Rs.450.00 spent on your Debit Card XX5678 at DMART on 05-03-26. Avl Bal Rs.9,550.00"),
        ).isFalse()
        // A bank debit that pays a card bill names the card but is money leaving the bank.
        assertWithMessage("bank debit paying the card").that(
            isCredit("Rs.25,000.00 debited from A/c XX4321 on 05-03-26 towards your HDFC Credit Card."),
        ).isFalse()
    }
}
