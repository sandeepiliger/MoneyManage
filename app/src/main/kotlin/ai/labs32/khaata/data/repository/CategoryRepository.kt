package ai.labs32.khaata.data.repository

import ai.labs32.khaata.core.categorize.DefaultCategories
import ai.labs32.khaata.core.categorize.MerchantCategorizer
import ai.labs32.khaata.core.categorize.SeedMerchantRules
import ai.labs32.khaata.core.categorize.CategorySuggestion
import ai.labs32.khaata.core.database.dao.CategoryDao
import ai.labs32.khaata.core.database.dao.MerchantRuleDao
import ai.labs32.khaata.core.database.dao.TransactionDao
import ai.labs32.khaata.core.database.entity.MerchantRuleEntity
import ai.labs32.khaata.core.database.toDomain
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.model.CategoryKind
import ai.labs32.khaata.core.model.CategoryTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Categories and the merchant rules that make picking one usually unnecessary.
 *
 * The two live together because they are the same job from the user's point of view: getting a
 * transaction filed correctly with as few taps as possible.
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val merchantRuleDao: MerchantRuleDao,
    private val transactionDao: TransactionDao,
    private val categorizer: MerchantCategorizer,
) {

    fun observeActive(): Flow<List<Category>> =
        categoryDao.observeActive().map { list -> list.map { it.toDomain() } }

    fun observeAll(): Flow<List<Category>> =
        categoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Categories grouped into parent → children, which is how the picker is laid out. */
    fun observeTrees(kind: CategoryKind? = null): Flow<List<CategoryTree>> =
        observeActive().map { categories ->
            val relevant = categories.filter { kind == null || it.kind == kind || it.kind == CategoryKind.BOTH }
            val childrenByParent = relevant.filter { it.parentId != null }.groupBy { it.parentId }
            relevant.filter { it.parentId == null }
                .sortedBy { it.sortOrder }
                .map { parent ->
                    CategoryTree(
                        parent = parent,
                        children = childrenByParent[parent.id].orEmpty().sortedBy { it.sortOrder },
                    )
                }
        }

    suspend fun getAll(): List<Category> = categoryDao.getAll().map { it.toDomain() }

    suspend fun findById(id: String): Category? = categoryDao.findById(id)?.toDomain()

    /** Subcategory id → parent id, which budget and report roll-ups need. */
    suspend fun categoryRollup(): Map<String, String> =
        getAll().mapNotNull { category -> category.parentId?.let { category.id to it } }.toMap()

    // ---- Merchant rules ----------------------------------------------------------------------

    fun observeMerchantRules(): Flow<List<ai.labs32.khaata.core.model.MerchantRule>> =
        merchantRuleDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * Suggests a category for [merchant], or null when nothing matches.
     *
     * A null result leaves the picker unselected rather than guessing, because a wrong
     * preselection the user has to notice and undo costs more than the tap it saved.
     */
    suspend fun suggestFor(merchant: String?): CategorySuggestion? {
        if (merchant.isNullOrBlank()) return null
        val rules = merchantRuleDao.getAll().map { it.toDomain() }
        return categorizer.suggest(merchant, rules)
    }

    /** Records an explicit user choice, which outranks every learned and seeded rule. */
    suspend fun setUserRule(merchant: String, categoryId: String, accountId: String?) {
        val rules = merchantRuleDao.getAll().map { it.toDomain() }
        val updated = categorizer.learn(
            merchantText = merchant,
            categoryId = categoryId,
            accountId = accountId,
            rules = rules,
            isExplicitUserChoice = true,
            newRuleId = { UUID.randomUUID().toString() },
        )
        val key = ai.labs32.khaata.core.categorize.MerchantNormaliser.normalise(merchant) ?: return
        updated.firstOrNull { it.merchantKey == key }?.let { merchantRuleDao.upsert(it.toEntity()) }
    }

    suspend fun deleteMerchantRule(id: String) {
        merchantRuleDao.getAll().firstOrNull { it.id == id }?.let { merchantRuleDao.delete(it) }
    }

    /** Clears learned rules while keeping the shipped set — "forget what you've learned". */
    suspend fun forgetLearnedRules() = merchantRuleDao.deleteLearned()

    suspend fun userDefinedRuleCount(): Int = merchantRuleDao.userDefinedCount()

    // ---- Writes ------------------------------------------------------------------------------

    suspend fun create(
        name: String,
        group: CategoryGroup,
        parentId: String? = null,
        kind: CategoryKind = CategoryKind.EXPENSE,
        iconKey: String = "category",
        colorSeed: Int = group.defaultColorSeed,
    ): String {
        val category = Category(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            group = group,
            parentId = parentId,
            kind = kind,
            iconKey = iconKey,
            colorSeed = colorSeed,
            isSystem = false,
            sortOrder = DEFAULT_USER_SORT_ORDER,
        )
        categoryDao.upsert(category.toEntity())
        return category.id
    }

    suspend fun update(category: Category) = categoryDao.upsert(category.toEntity())

    suspend fun setArchived(id: String, archived: Boolean) = categoryDao.setArchived(id, archived)

    /**
     * Deletes a category, or reports why it cannot be.
     *
     * System categories are archived rather than deleted so historical reports never lose their
     * labels. A category with transactions can be deleted — the foreign key sets those rows to
     * uncategorised — but the caller is told first so the user can decide.
     */
    suspend fun delete(id: String): CategoryDeletionResult {
        val entity = categoryDao.findById(id) ?: return CategoryDeletionResult.NotFound
        if (entity.isSystem) return CategoryDeletionResult.SystemCategory
        val count = transactionDao.countForCategory(id)
        categoryDao.delete(entity)
        return CategoryDeletionResult.Deleted(orphanedTransactions = count)
    }

    // ---- Seeding -----------------------------------------------------------------------------

    /**
     * Installs the default categories and merchant rules on a fresh database.
     *
     * Both use insert-if-absent keyed on stable ids, so re-running on upgrade adds anything new
     * without overwriting a user's renames or duplicating what is already there.
     */
    suspend fun seedIfEmpty() {
        if (categoryDao.count() == 0) {
            categoryDao.insertIfAbsent(DefaultCategories.ALL.map { it.toEntity() })
        }
        seedMerchantRules()
    }

    private suspend fun seedMerchantRules() {
        val existingKeys = merchantRuleDao.getAll().map { it.merchantKey }.toSet()
        val missing = SeedMerchantRules.RULES
            .filterKeys { it !in existingKeys }
            .map { (key, categoryId) ->
                MerchantRuleEntity(
                    id = "seed-$key",
                    merchantKey = key,
                    categoryId = categoryId,
                    accountId = null,
                    confidence = 1,
                    isUserDefined = false,
                    isSeeded = true,
                )
            }
        if (missing.isNotEmpty()) merchantRuleDao.insertIfAbsent(missing)
    }

    suspend fun upsertAll(categories: List<Category>) =
        categoryDao.upsertAll(categories.map { it.toEntity() })

    suspend fun deleteAll() {
        categoryDao.deleteAll()
        merchantRuleDao.deleteAll()
    }

    private companion object {
        /** User categories sort after the seeded ones, which occupy 0-999. */
        const val DEFAULT_USER_SORT_ORDER = 1_000
    }
}

sealed interface CategoryDeletionResult {
    /** [orphanedTransactions] rows are now uncategorised rather than deleted. */
    data class Deleted(val orphanedTransactions: Int) : CategoryDeletionResult
    data object NotFound : CategoryDeletionResult
    data object SystemCategory : CategoryDeletionResult
}
