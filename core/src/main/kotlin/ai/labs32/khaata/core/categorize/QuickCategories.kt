package ai.labs32.khaata.core.categorize

import ai.labs32.khaata.core.model.Category

/**
 * Which categories to put within one tap on the entry screen.
 *
 * The picker used to show every top-level category and nothing else, in a horizontally scrolling
 * row of text labels. That has two problems and the second is the serious one: only three or four
 * chips are visible at a time, and the forty-two subcategories — Groceries, Cab, Electricity, the
 * specific ones people actually want — could not be reached from entry at all. Someone buying
 * vegetables could file it under "Food" and no closer.
 *
 * The fix is a short row of the categories this person actually uses, with everything else one tap
 * further into a searchable sheet. Which makes the ordering the important part, and the part worth
 * testing: a row that is not genuinely the user's own most-used is just a differently-arranged
 * guess.
 */
object QuickCategories {

    /** How many fit on one row on a phone without becoming a scroll again. */
    const val DEFAULT_LIMIT = 8

    /**
     * The quick row, most-useful first.
     *
     * In order: whatever is selected now, then what this person uses most, then sensible defaults
     * so a new install with no history still has a usable row rather than an empty one.
     *
     * @param available the categories valid for the current transaction direction.
     * @param recentlyUsedIds category ids in descending order of use.
     * @param selectedId the current choice, which is always shown so it cannot scroll out of sight.
     */
    fun forEntry(
        available: List<Category>,
        recentlyUsedIds: List<String>,
        selectedId: String?,
        fallbackIds: List<String> = DefaultCategories.ONBOARDING_SUGGESTIONS,
        limit: Int = DEFAULT_LIMIT,
    ): List<Category> {
        if (available.isEmpty() || limit <= 0) return emptyList()
        val byId = available.associateBy { it.id }

        val ordered = LinkedHashSet<String>()
        // The current choice first: a selected category that scrolls out of view reads as though
        // nothing is selected, and people re-tap something they had already chosen.
        selectedId?.let { if (byId.containsKey(it)) ordered.add(it) }
        recentlyUsedIds.forEach { if (byId.containsKey(it)) ordered.add(it) }
        fallbackIds.forEach { if (byId.containsKey(it)) ordered.add(it) }
        // Anything still missing, so a small or heavily customised category set fills the row.
        available.forEach { ordered.add(it.id) }

        return ordered.take(limit).mapNotNull { byId[it] }
    }

    /**
     * Categories matching [query], for the search field in the full picker.
     *
     * A subcategory also matches its parent's name, so typing "food" finds Groceries and Swiggy
     * rather than only the parent — which is what someone means when they type it.
     */
    fun search(available: List<Category>, query: String): List<Category> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return available

        val parentNames = available.filter { it.parentId == null }.associate { it.id to it.name }
        return available.filter { category ->
            category.name.contains(trimmed, ignoreCase = true) ||
                parentNames[category.parentId]?.contains(trimmed, ignoreCase = true) == true
        }
    }
}
