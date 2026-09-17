package ai.labs32.khaata.core.validation

import ai.labs32.khaata.core.money.CurrencyCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * The rules behind the investment editor.
 *
 * Worth testing apart from the screen because `Investment`'s own `init` block throws on a
 * negative amount or a valuation dated before the start. Throwing is the right last line of
 * defence and the wrong thing to put in front of someone typing, so these rules exist to catch
 * the same cases first — and a gap between the two is a crash on save, not a validation message.
 */
class InvestmentValidatorTest {

    private val today = LocalDate.of(2026, 9, 17)
    private val started = LocalDate.of(2025, 1, 10)

    private fun validate(
        name: String? = "Index fund",
        invested: String? = "50000",
        currentValue: String? = "62000",
        units: String? = null,
        startedOn: LocalDate = started,
        valuedOn: LocalDate = today,
    ) = InvestmentValidator.validate(
        name = name,
        investedText = invested,
        currentValueText = currentValue,
        unitsText = units,
        currency = CurrencyCode.DEFAULT,
        startedOn = startedOn,
        valuedOn = valuedOn,
        today = today,
    )

    private fun codesFrom(result: ValidationResult<Unit>): List<String> =
        (result as ValidationResult.Invalid).errors.map { it.code }

    @Test
    fun `a complete holding is accepted`() {
        assertThat(validate()).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `a holding needs a name`() {
        assertThat(codesFrom(validate(name = " "))).contains("name_required")
    }

    @Test
    fun `the invested amount is required and must parse`() {
        assertThat(codesFrom(validate(invested = null))).contains("invested_required")
        assertThat(codesFrom(validate(invested = "not money"))).contains("invested_invalid")
    }

    @Test
    fun `the current value is required`() {
        // Without it there is no gain to show, which is the only reason to track a holding here.
        assertThat(codesFrom(validate(currentValue = ""))).contains("value_required")
    }

    @Test
    fun `a holding worth less than it cost is perfectly valid`() {
        // Losses are not an error state. Rejecting this would make the app unusable for anyone
        // whose fund is down, which is a large share of anyone who has ever held one.
        assertThat(validate(invested = "50000", currentValue = "31000"))
            .isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `a zero value is allowed`() {
        // A holding can genuinely go to zero, and someone may also be recording one before it is
        // funded. Neither is worth blocking a save over.
        assertThat(validate(currentValue = "0")).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `a valuation cannot predate the start`() {
        // This is the case the model throws on, so it must be caught here or saving crashes.
        val codes = codesFrom(
            validate(startedOn = LocalDate.of(2026, 5, 1), valuedOn = LocalDate.of(2026, 4, 1)),
        )
        assertThat(codes).contains("valued_before_start")
    }

    @Test
    fun `neither date may be in the future`() {
        assertThat(codesFrom(validate(startedOn = today.plusDays(1))))
            .contains("start_in_future")
        assertThat(codesFrom(validate(valuedOn = today.plusDays(1))))
            .contains("valued_in_future")
    }

    @Test
    fun `units are optional but must be a number when given`() {
        assertThat(validate(units = null)).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(validate(units = "12.345")).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(codesFrom(validate(units = "twelve"))).contains("units_invalid")
    }

    @Test
    fun `every problem is reported at once rather than one per save`() {
        // Fixing one field only to be told about the next is the worst version of a form.
        val codes = codesFrom(validate(name = "", invested = "", currentValue = ""))

        assertThat(codes).containsAtLeast("name_required", "invested_required", "value_required")
    }
}
