package ai.labs32.khaata.core.locale

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.labs32.khaata.core.categorize.DefaultCategories
import ai.labs32.khaata.core.categorize.MerchantCategorizer
import ai.labs32.khaata.core.categorize.QuickCategories
import ai.labs32.khaata.core.database.KhaataDatabase
import ai.labs32.khaata.data.repository.CategoryRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Built-in categories are shown in the app's language and stored in English.
 *
 * The stored English name is what makes this safe: a backup, a restore on a phone set to another
 * language, and a later switch of language all start from the same value. So these pin both halves
 * -- what the user sees, and what reaches the database.
 */
@RunWith(RobolectricTestRunner::class)
class BuiltInCategoryNamesTest {

    @Before
    fun setUp() {
        BuiltInCategoryNames.install(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `in English every built-in category shows exactly its seeded name`() {
        DefaultCategories.ALL.forEach { category ->
            assertThat(BuiltInCategoryNames.display(category.id, category.name, isSystem = true))
                .isEqualTo(category.name)
        }
    }

    @Test
    @Config(qualifiers = "kn")
    fun `in Kannada a built-in category is shown translated`() {
        assertThat(BuiltInCategoryNames.display(DefaultCategories.GROCERIES, "Groceries", isSystem = true))
            .isEqualTo("ದಿನಸಿ")
        assertThat(BuiltInCategoryNames.display(DefaultCategories.FUEL, "Fuel", isSystem = true))
            .isEqualTo("ಇಂಧನ")
    }

    @Test
    @Config(qualifiers = "ta")
    fun `every built-in category has a translation`() {
        DefaultCategories.ALL.filter { it.id != DefaultCategories.SIP }.forEach { category ->
            assertThat(BuiltInCategoryNames.display(category.id, category.name, isSystem = true))
                .isNotEqualTo(category.name)
        }
    }

    @Test
    @Config(qualifiers = "te")
    fun `a renamed or user-made category is shown as typed`() {
        assertThat(BuiltInCategoryNames.display(DefaultCategories.GROCERIES, "Kirana store", isSystem = true))
            .isEqualTo("Kirana store")
        assertThat(BuiltInCategoryNames.display("user-1", "Groceries", isSystem = false))
            .isEqualTo("Groceries")
    }

    @Test
    @Config(qualifiers = "hi")
    fun `a translated name is stored back in English`() {
        assertThat(BuiltInCategoryNames.stored(DefaultCategories.RENT, "किराया", isSystem = true)).isEqualTo("Rent")
        assertThat(BuiltInCategoryNames.stored(DefaultCategories.RENT, "Flat rent", isSystem = true)).isEqualTo("Flat rent")
        assertThat(BuiltInCategoryNames.stored("user-1", "किराया", isSystem = false)).isEqualTo("किराया")
    }

    /** End to end through Room: read in Kannada, edit, and the row still says "Groceries". */
    @Test
    @Config(qualifiers = "kn")
    fun `editing a built-in category in Kannada keeps its stored name English`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KhaataDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = CategoryRepository(
                database.categoryDao(),
                database.merchantRuleDao(),
                database.transactionDao(),
                MerchantCategorizer(),
            )
            repository.seedIfEmpty()

            val groceries = repository.findById(DefaultCategories.GROCERIES)!!
            assertThat(groceries.name).isEqualTo("ದಿನಸಿ")

            repository.update(groceries.copy(colorSeed = groceries.colorSeed + 1))

            assertThat(database.categoryDao().getAll().single { it.id == DefaultCategories.GROCERIES }.name)
                .isEqualTo("Groceries")
            assertThat(repository.findById(DefaultCategories.GROCERIES)!!.name).isEqualTo("ದಿನಸಿ")
        } finally {
            database.close()
        }
    }

    @Test
    @Config(qualifiers = "ta")
    fun `searching in English still finds a translated category`() {
        val shown = DefaultCategories.ALL.map {
            it.copy(name = BuiltInCategoryNames.display(it.id, it.name, it.isSystem))
        }
        val found = QuickCategories.search(shown, "fuel").map { it.id }
        assertThat(found).contains(DefaultCategories.FUEL)
        // And a subcategory by its parent's English name.
        assertThat(QuickCategories.search(shown, "transport").map { it.id }).contains(DefaultCategories.CAB)
    }
}
