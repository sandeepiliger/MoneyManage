package ai.labs32.khaata.core.validation

import ai.labs32.khaata.core.money.CurrencyCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rules behind the loan and credit card editors.
 *
 * Both models reject bad input by throwing from `init`. That is correct as a last line of defence
 * and useless as user feedback, so these rules exist to catch the same cases first — and anything
 * the model rejects that these do not is a crash on save rather than a message under a field.
 * Each test below names the model requirement it shadows.
 */
class LoanAndCardValidatorTest {

    private fun codesFrom(result: ValidationResult<Unit>): List<String> =
        (result as ValidationResult.Invalid).errors.map { it.code }

    // ---- Loans ---------------------------------------------------------------------------------

    private fun loan(
        name: String? = "Car loan",
        principal: String? = "500000",
        rate: String? = "9.5",
        tenure: String? = "60",
        emiDay: String? = "5",
        emiOverride: String? = null,
    ) = LoanValidator.validate(
        name = name,
        principalText = principal,
        ratePercentText = rate,
        tenureMonthsText = tenure,
        emiDayText = emiDay,
        emiOverrideText = emiOverride,
        currency = CurrencyCode.DEFAULT,
    )

    @Test
    fun `a complete loan is accepted`() {
        assertThat(loan()).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `a loan principal must be positive`() {
        // Model: require(principal.isPositive)
        assertThat(codesFrom(loan(principal = "0"))).contains("principal_not_positive")
        assertThat(codesFrom(loan(principal = null))).contains("principal_required")
    }

    @Test
    fun `an interest rate of zero is allowed but a negative one is not`() {
        // Model: require(rate.signum() >= 0). An interest-free loan from family is a real thing.
        assertThat(loan(rate = "0")).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(codesFrom(loan(rate = "-1"))).contains("rate_negative")
    }

    @Test
    fun `an interest rate of a hundred percent or more is rejected as a typo`() {
        // Model: require(rate < 100)
        assertThat(codesFrom(loan(rate = "100"))).contains("rate_too_high")
        assertThat(codesFrom(loan(rate = "1200"))).contains("rate_too_high")
        assertThat(loan(rate = "99.99")).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `tenure must be between one and six hundred months`() {
        // Model: require(tenureMonths in 1..600)
        assertThat(codesFrom(loan(tenure = "0"))).contains("tenure_out_of_range")
        assertThat(codesFrom(loan(tenure = "601"))).contains("tenure_out_of_range")
        assertThat(codesFrom(loan(tenure = "5.5"))).contains("tenure_invalid")
        assertThat(loan(tenure = "600")).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `the emi day must be a real day of the month`() {
        // Model: require(emiDayOfMonth in 1..31)
        assertThat(codesFrom(loan(emiDay = "0"))).contains("emi_day_out_of_range")
        assertThat(codesFrom(loan(emiDay = "32"))).contains("emi_day_out_of_range")
    }

    @Test
    fun `an emi override is optional but must be positive when given`() {
        // Model: require(emiOverride == null || emiOverride.isPositive)
        assertThat(loan(emiOverride = null)).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(loan(emiOverride = "")).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(loan(emiOverride = "12500")).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(codesFrom(loan(emiOverride = "0"))).contains("emi_not_positive")
    }

    // ---- Credit cards --------------------------------------------------------------------------

    private fun card(
        cardName: String? = "HDFC Regalia",
        issuer: String? = "HDFC Bank",
        limit: String? = "250000",
        statementDay: String? = "25",
        dueDay: String? = "14",
        minimumDue: String? = "5",
        lastFour: String? = "7712",
    ) = CreditCardValidator.validate(
        cardName = cardName,
        issuer = issuer,
        creditLimitText = limit,
        statementDayText = statementDay,
        dueDayText = dueDay,
        minimumDuePercentText = minimumDue,
        lastFourDigits = lastFour,
        currency = CurrencyCode.DEFAULT,
    )

    @Test
    fun `a complete card is accepted`() {
        assertThat(card()).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `a card needs a name and an issuer`() {
        assertThat(codesFrom(card(cardName = " "))).contains("name_required")
        assertThat(codesFrom(card(issuer = ""))).contains("issuer_required")
    }

    @Test
    fun `the credit limit must be positive`() {
        // Model: require(creditLimit.isPositive)
        assertThat(codesFrom(card(limit = "0"))).contains("limit_not_positive")
    }

    @Test
    fun `statement and due days must be real days of the month`() {
        // Model: require(statementDayOfMonth in 1..31) and require(dueDayOfMonth in 1..31)
        assertThat(codesFrom(card(statementDay = "0"))).contains("statement_day_out_of_range")
        assertThat(codesFrom(card(statementDay = "32"))).contains("statement_day_out_of_range")
        assertThat(codesFrom(card(dueDay = "45"))).contains("due_day_out_of_range")
    }

    @Test
    fun `a due day before the statement day is normal, not an error`() {
        // It falls in the month after the statement, which is how nearly every Indian card works:
        // statement on the 25th, payment due on the 14th of the following month.
        assertThat(card(statementDay = "25", dueDay = "14"))
            .isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `the minimum due percentage must be within nought to a hundred`() {
        // Model: require(minimumDuePercent > 0 && <= 100)
        assertThat(codesFrom(card(minimumDue = "0"))).contains("minimum_out_of_range")
        assertThat(codesFrom(card(minimumDue = "101"))).contains("minimum_out_of_range")
        assertThat(card(minimumDue = "100")).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `the last four digits are optional and must be exactly four digits`() {
        // Model: require(lastFourDigits == null || matches four digits). Refused rather than
        // truncated, so a full card number pasted in is rejected instead of half-stored.
        assertThat(card(lastFour = null)).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(card(lastFour = "")).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(codesFrom(card(lastFour = "123"))).contains("last_four_invalid")
        assertThat(codesFrom(card(lastFour = "4111111111111111"))).contains("last_four_invalid")
        assertThat(codesFrom(card(lastFour = "abcd"))).contains("last_four_invalid")
    }

    @Test
    fun `every problem is reported at once rather than one per save`() {
        val codes = codesFrom(card(cardName = "", issuer = "", limit = "", statementDay = ""))

        assertThat(codes).containsAtLeast(
            "name_required",
            "issuer_required",
            "limit_required",
            "statement_day_required",
        )
    }
}
