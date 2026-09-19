package ai.labs32.khaata.core.categorize

import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CategoryGroup
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The ordering behind the quick category row.
 *
 * Worth testing on its own because every failure here is silent: a row that is not really the
 * user's most-used still looks like a row, and the person just goes on tapping "More" every time
 * without knowing it was supposed to save them the trip.
 */
class QuickCategoriesTest {

    private fun cat(id: String, name: String, parent: String? = null) = Category(
        id = id,
        name = name,
        group = CategoryGroup.OTHER,
        parentId = parent,
    )

    private val food = cat("cat_food", "Food")
    private val groceries = cat("cat_groceries", "Groceries", parent = "cat_food")
    private val swiggy = cat("cat_food_delivery", "Swiggy", parent = "cat_food")
    private val transport = cat("cat_transport", "Transport")
    private val cab = cat("cat_cab", "Cab", parent = "cat_transport")
    private val rent = cat("cat_rent", "Rent")

    private val all = listOf(food, groceries, swiggy, transport, cab, rent)

    @Test
    fun `most used comes first`() {
        val row = QuickCategories.forEntry(
            available = all,
            recentlyUsedIds = listOf("cat_cab", "cat_groceries"),
            selectedId = null,
            fallbackIds = emptyList(),
            limit = 3,
        )

        assertThat(row.map { it.id }).containsExactly("cat_cab", "cat_groceries", "cat_food")
            .inOrder()
    }

    @Test
    fun `the selected category is always shown, even if rarely used`() {
        // Otherwise it scrolls out of sight and reads as though nothing is selected, and people
        // re-tap a category they had already chosen.
        val row = QuickCategories.forEntry(
            available = all,
            recentlyUsedIds = listOf("cat_cab", "cat_groceries", "cat_food"),
            selectedId = "cat_rent",
            fallbackIds = emptyList(),
            limit = 3,
        )

        assertThat(row.first().id).isEqualTo("cat_rent")
        assertThat(row).hasSize(3)
    }

    @Test
    fun `subcategories can appear in the row`() {
        // The whole point: the old picker showed top-level only, so Groceries and Cab were
        // unreachable from the entry screen entirely.
        val row = QuickCategories.forEntry(
            available = all,
            recentlyUsedIds = listOf("cat_groceries", "cat_cab"),
            selectedId = null,
            fallbackIds = emptyList(),
        )

        assertThat(row.map { it.id }).containsAtLeast("cat_groceries", "cat_cab")
    }

    @Test
    fun `a fresh install with no history still gets a useful row`() {
        val row = QuickCategories.forEntry(
            available = all,
            recentlyUsedIds = emptyList(),
            selectedId = null,
            fallbackIds = listOf("cat_groceries", "cat_rent"),
            limit = 2,
        )

        assertThat(row.map { it.id }).containsExactly("cat_groceries", "cat_rent").inOrder()
    }

    @Test
    fun `nothing is ever listed twice`() {
        val row = QuickCategories.forEntry(
            available = all,
            recentlyUsedIds = listOf("cat_food", "cat_food", "cat_cab"),
            selectedId = "cat_food",
            fallbackIds = listOf("cat_food", "cat_cab"),
        )

        assertThat(row.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `ids that no longer exist are ignored rather than crashing the row`() {
        // A deleted or archived category can still sit in the usage history.
        val row = QuickCategories.forEntry(
            available = all,
            recentlyUsedIds = listOf("cat_deleted", "cat_cab"),
            selectedId = "cat_also_gone",
            fallbackIds = listOf("cat_missing"),
            limit = 4,
        )

        assertThat(row.map { it.id }).doesNotContain("cat_deleted")
        assertThat(row.map { it.id }).doesNotContain("cat_also_gone")
        assertThat(row.first().id).isEqualTo("cat_cab")
    }

    @Test
    fun `an empty category set produces an empty row rather than an exception`() {
        assertThat(
            QuickCategories.forEntry(emptyList(), listOf("cat_cab"), "cat_food"),
        ).isEmpty()
    }

    // ---- search --------------------------------------------------------------------------------

    @Test
    fun `search matches on the category's own name`() {
        assertThat(QuickCategories.search(all, "groc").map { it.id })
            .containsExactly("cat_groceries")
    }

    @Test
    fun `search is case insensitive and matches partially`() {
        assertThat(QuickCategories.search(all, "CAB").map { it.id }).containsExactly("cat_cab")
    }

    @Test
    fun `searching a parent name finds its children`() {
        // Typing "food" means "show me the food ones", not "show me the row literally named Food".
        val ids = QuickCategories.search(all, "food").map { it.id }

        assertThat(ids).containsAtLeast("cat_food", "cat_groceries", "cat_food_delivery")
    }

    @Test
    fun `an empty query returns everything`() {
        assertThat(QuickCategories.search(all, "   ")).hasSize(all.size)
    }

    @Test
    fun `a query matching nothing returns nothing rather than everything`() {
        assertThat(QuickCategories.search(all, "zzzz")).isEmpty()
    }
}
