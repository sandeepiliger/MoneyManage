package ai.labs32.khaata.core.validation

import ai.labs32.khaata.core.money.CurrencyCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * The rules behind the goal editor.
 *
 * [GoalValidator] existed before any screen called it, so none of this was covered. These pin the
 * behaviour the editor relies on — particularly that a blank "saved so far" is a valid new goal
 * rather than an error, which is the common case and the one easiest to break.
 */
class GoalValidatorTest {

    private val today = LocalDate.of(2026, 3, 15)
    private val inr = CurrencyCode.INR

    private fun validate(
        name: String? = "Emergency fund",
        target: String? = "100000",
        current: String? = null,
        targetDate: LocalDate? = null,
    ) = GoalValidator.validate(
        name = name,
        targetText = target,
        currentText = current,
        currency = inr,
        targetDate = targetDate,
        today = today,
    )

    private fun codes(result: ValidationResult<Unit>): List<String> =
        (result as ValidationResult.Invalid).errors.map { it.code }

    @Test
    fun `a name and a target are enough`() {
        assertThat(validate()).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `a blank saved-so-far is accepted rather than treated as invalid`() {
        // The common case for a brand new goal: a target, and nothing put aside yet.
        assertThat(validate(current = "")).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(validate(current = null)).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `a blank name is rejected`() {
        assertThat(codes(validate(name = "  "))).contains("name_required")
    }

    @Test
    fun `a missing target is rejected`() {
        assertThat(codes(validate(target = ""))).contains("target_required")
    }

    @Test
    fun `an unparseable target is rejected`() {
        assertThat(codes(validate(target = "abc"))).contains("target_invalid")
    }

    @Test
    fun `a zero target is rejected`() {
        // Money's own invariant refuses a non-positive goal target, so letting this through would
        // throw at construction rather than show the user a message.
        assertThat(codes(validate(target = "0"))).contains("target_zero")
    }

    @Test
    fun `saving more than the target is rejected`() {
        assertThat(codes(validate(target = "1000", current = "1500")))
            .contains("current_exceeds_target")
    }

    @Test
    fun `saving exactly the target is allowed`() {
        // A goal can be created already complete — someone recording a pot they have already
        // filled — so the boundary is not an error.
        assertThat(validate(target = "1000", current = "1000"))
            .isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `an unparseable saved amount is rejected`() {
        assertThat(codes(validate(current = "abc"))).contains("current_invalid")
    }

    @Test
    fun `a target date in the past is rejected`() {
        assertThat(codes(validate(targetDate = today.minusDays(1)))).contains("date_in_past")
    }

    @Test
    fun `today is an acceptable target date`() {
        assertThat(validate(targetDate = today)).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `no target date at all is acceptable`() {
        assertThat(validate(targetDate = null)).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `every problem is reported together rather than one at a time`() {
        val result = validate(name = "", target = "", targetDate = today.minusYears(1))
        assertThat(codes(result))
            .containsAtLeast("name_required", "target_required", "date_in_past")
    }
}
