package ai.labs32.khaata.core.validation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryValidatorTest {

    private fun validate(
        name: String? = "Chai",
        siblingNames: Set<String> = emptySet(),
        parentIsSubcategory: Boolean = false,
        hasChildren: Boolean = false,
        isBecomingSubcategory: Boolean = false,
    ) = CategoryValidator.validate(
        name = name,
        siblingNames = siblingNames,
        parentIsSubcategory = parentIsSubcategory,
        hasChildren = hasChildren,
        isBecomingSubcategory = isBecomingSubcategory,
    )

    private fun codes(result: ValidationResult<Unit>) = result.errorsOrEmpty().map { it.code }

    @Test
    fun `a plain name is accepted`() {
        assertThat(validate().isValid).isTrue()
    }

    @Test
    fun `a blank name is rejected`() {
        assertThat(codes(validate(name = "   "))).containsExactly("name_required")
        assertThat(codes(validate(name = null))).containsExactly("name_required")
    }

    @Test
    fun `an over-long name is rejected`() {
        assertThat(codes(validate(name = "x".repeat(41)))).containsExactly("name_too_long")
        assertThat(validate(name = "x".repeat(40)).isValid).isTrue()
    }

    @Test
    fun `a name is trimmed before it is compared`() {
        assertThat(codes(validate(name = "  Chai  ", siblingNames = setOf("Chai"))))
            .containsExactly("name_duplicate")
    }

    @Test
    fun `a duplicate among siblings is rejected regardless of case`() {
        assertThat(codes(validate(name = "chai", siblingNames = setOf("Chai"))))
            .containsExactly("name_duplicate")
    }

    /**
     * The rule most likely to be got wrong: uniqueness is per-parent, not global. "Insurance"
     * under Health and "Insurance" under Financial are things people genuinely track apart.
     */
    @Test
    fun `the same name under a different parent is allowed`() {
        assertThat(validate(name = "Insurance", siblingNames = setOf("Medicines", "Doctor")).isValid)
            .isTrue()
    }

    @Test
    fun `a third level is rejected`() {
        assertThat(codes(validate(parentIsSubcategory = true, isBecomingSubcategory = true)))
            .containsExactly("parent_too_deep")
    }

    @Test
    fun `a parent with children cannot itself become a subcategory`() {
        assertThat(codes(validate(hasChildren = true, isBecomingSubcategory = true)))
            .containsExactly("parent_has_children")
    }

    @Test
    fun `a parent with children can stay top-level`() {
        assertThat(validate(hasChildren = true, isBecomingSubcategory = false).isValid).isTrue()
    }

    @Test
    fun `all failures are reported together rather than one at a time`() {
        val result = validate(
            name = "",
            parentIsSubcategory = true,
            hasChildren = true,
            isBecomingSubcategory = true,
        )
        assertThat(codes(result))
            .containsExactly("name_required", "parent_too_deep", "parent_has_children")
    }
}
