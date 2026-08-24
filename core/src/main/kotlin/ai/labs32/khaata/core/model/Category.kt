package ai.labs32.khaata.core.model

import kotlinx.serialization.Serializable

/**
 * A spending or income category.
 *
 * Two levels only — group → category. The reference apps allow arbitrarily deep trees, which
 * looks flexible but makes the picker slow to scan and the reports hard to read. A fixed depth
 * keeps categorising a transaction a one-tap decision, which is what actually matters when you
 * are standing at a counter.
 */
@Serializable
data class Category(
    val id: String,
    val name: String,
    val group: CategoryGroup,
    /** Null for a top-level category; set for a subcategory such as "Swiggy" under "Food". */
    val parentId: String? = null,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val iconKey: String = "category",
    val colorSeed: Int = 0,
    /** System categories can be renamed and hidden but not deleted, so reports never orphan. */
    val isSystem: Boolean = false,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
) {
    val isSubcategory: Boolean get() = parentId != null
}

/** Whether a category applies to money going out, coming in, or both. */
@Serializable
enum class CategoryKind { EXPENSE, INCOME, BOTH }

/**
 * The top-level grouping used in pickers and reports.
 *
 * Chosen for how Indian household budgets are actually discussed rather than for accounting
 * tidiness: [FINANCIAL] holds EMI/SIP/insurance, and [FAMILY] exists because supporting parents
 * and children is a normal, sizeable line item here and does not belong under "Lifestyle".
 */
@Serializable
enum class CategoryGroup(val defaultColorSeed: Int) {
    FOOD(0),
    TRANSPORT(1),
    BILLS(2),
    LIFESTYLE(3),
    FINANCIAL(4),
    FAMILY(5),
    INCOME(6),
    TRANSFER(7),
    OTHER(8),
    ;

    val isSpending: Boolean get() = this != INCOME && this != TRANSFER
}

/**
 * A category with its subcategories attached, for pickers and grouped reports.
 */
data class CategoryTree(
    val parent: Category,
    val children: List<Category>,
) {
    /** Every category id in this branch — used to roll a subcategory's spend up to its parent. */
    val allIds: Set<String> get() = buildSet {
        add(parent.id)
        children.forEach { add(it.id) }
    }
}
