package ai.labs32.khaata.core.categorize

import ai.labs32.khaata.core.model.MerchantRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MerchantNormaliserTest {

    @Test
    fun `payment rail noise is stripped to a stable key`() {
        // The same merchant arriving three different ways must normalise to one key.
        val forms = listOf(
            "SWIGGY",
            "UPI-SWIGGY@HDFCBANK",
            "POS 4321 SWIGGY BLR",
            "swiggy*bangalore",
        )
        assertThat(forms.map { MerchantNormaliser.normalise(it) }.distinct())
            .containsExactly("swiggy")
    }

    @Test
    fun `multi word merchants keep two identifying tokens`() {
        assertThat(MerchantNormaliser.normalise("INDIAN OIL CORPORATION LTD"))
            .isEqualTo("indian_oil")
        assertThat(MerchantNormaliser.normalise("BIGBASKET")).isEqualTo("bigbasket")
    }

    @Test
    fun `long reference numbers are not treated as identity`() {
        assertThat(MerchantNormaliser.normalise("UPI 412345678901 SWIGGY")).isEqualTo("swiggy")
    }

    @Test
    fun `input with nothing identifying returns null`() {
        assertThat(MerchantNormaliser.normalise("412345678901")).isNull()
        assertThat(MerchantNormaliser.normalise("UPI REF TXN")).isNull()
        assertThat(MerchantNormaliser.normalise("")).isNull()
        assertThat(MerchantNormaliser.normalise(null)).isNull()
    }

    @Test
    fun `display names are readable`() {
        assertThat(MerchantNormaliser.displayName("UPI-SWIGGY-BLR")).isEqualTo("Swiggy")
        assertThat(MerchantNormaliser.displayName("POS 1234 KFC MUMBAI")).isEqualTo("KFC")
        assertThat(MerchantNormaliser.displayName("INDIAN OIL CORPORATION")).isEqualTo("Indian Oil")
    }

    @Test
    fun `same merchant comparison ignores formatting`() {
        assertThat(MerchantNormaliser.sameMerchant("SWIGGY", "swiggy@ybl")).isTrue()
        assertThat(MerchantNormaliser.sameMerchant("SWIGGY", "ZOMATO")).isFalse()
        assertThat(MerchantNormaliser.sameMerchant(null, "SWIGGY")).isFalse()
    }
}

class MerchantCategorizerTest {

    private val categorizer = MerchantCategorizer()
    private var idCounter = 0
    private val newId: () -> String = { "rule-${idCounter++}" }

    @Test
    fun `a fresh install already knows common indian merchants`() {
        val swiggy = categorizer.suggest("Swiggy", emptyList())!!
        assertThat(swiggy.categoryId).isEqualTo(DefaultCategories.FOOD_DELIVERY)
        assertThat(swiggy.source).isEqualTo(SuggestionSource.SEEDED)

        assertThat(categorizer.suggest("UBER", emptyList())!!.categoryId)
            .isEqualTo(DefaultCategories.CAB)
        assertThat(categorizer.suggest("AMAZON", emptyList())!!.categoryId)
            .isEqualTo(DefaultCategories.SHOPPING)
        assertThat(categorizer.suggest("NETFLIX", emptyList())!!.categoryId)
            .isEqualTo(DefaultCategories.SUBSCRIPTIONS)
    }

    @Test
    fun `an unknown merchant produces no guess rather than a wrong one`() {
        assertThat(categorizer.suggest("Some Local Shop", emptyList())).isNull()
    }

    @Test
    fun `a user rule beats the shipped default`() {
        val rules = listOf(
            MerchantRule(
                id = "r1",
                merchantKey = "amazon",
                categoryId = DefaultCategories.GROCERIES,
                isUserDefined = true,
            ),
        )
        val suggestion = categorizer.suggest("AMAZON", rules)!!

        assertThat(suggestion.categoryId).isEqualTo(DefaultCategories.GROCERIES)
        assertThat(suggestion.source).isEqualTo(SuggestionSource.USER_RULE)
    }

    @Test
    fun `a user rule beats a more-confirmed learned rule`() {
        val rules = listOf(
            MerchantRule(id = "r1", merchantKey = "amazon", categoryId = DefaultCategories.SHOPPING, confidence = 20),
            MerchantRule(id = "r2", merchantKey = "amazon", categoryId = DefaultCategories.GROCERIES, confidence = 1, isUserDefined = true),
        )
        assertThat(categorizer.suggest("AMAZON", rules)!!.categoryId)
            .isEqualTo(DefaultCategories.GROCERIES)
    }

    @Test
    fun `learning creates a rule for a new merchant`() {
        val rules = categorizer.learn(
            merchantText = "Local Kirana",
            categoryId = DefaultCategories.GROCERIES,
            accountId = "acc-cash",
            rules = emptyList(),
            isExplicitUserChoice = true,
            newRuleId = newId,
        )
        assertThat(rules).hasSize(1)
        assertThat(rules.single().merchantKey).isEqualTo("local_kirana")
        assertThat(rules.single().isUserDefined).isTrue()

        assertThat(categorizer.suggest("LOCAL KIRANA", rules)!!.categoryId)
            .isEqualTo(DefaultCategories.GROCERIES)
    }

    @Test
    fun `confirming the same pairing strengthens it`() {
        var rules = categorizer.learn("Swiggy", DefaultCategories.FOOD_DELIVERY, null, emptyList(), false, newId)
        rules = categorizer.learn("Swiggy", DefaultCategories.FOOD_DELIVERY, null, rules, false, newId)
        rules = categorizer.learn("Swiggy", DefaultCategories.FOOD_DELIVERY, null, rules, false, newId)

        assertThat(rules).hasSize(1)
        assertThat(rules.single().confidence).isEqualTo(3)
    }

    @Test
    fun `a correction takes effect immediately rather than after repetition`() {
        var rules = categorizer.learn("Amazon", DefaultCategories.SHOPPING, null, emptyList(), false, newId)
        repeat(9) {
            rules = categorizer.learn("Amazon", DefaultCategories.SHOPPING, null, rules, false, newId)
        }
        assertThat(rules.single().confidence).isEqualTo(10)

        // One correction must win, not be outvoted by ten prior confirmations.
        rules = categorizer.learn("Amazon", DefaultCategories.GROCERIES, null, rules, true, newId)

        assertThat(rules).hasSize(1)
        assertThat(categorizer.suggest("Amazon", rules)!!.categoryId)
            .isEqualTo(DefaultCategories.GROCERIES)
    }

    @Test
    fun `confidence is capped`() {
        var rules = categorizer.learn("Swiggy", DefaultCategories.FOOD_DELIVERY, null, emptyList(), false, newId)
        repeat(100) {
            rules = categorizer.learn("Swiggy", DefaultCategories.FOOD_DELIVERY, null, rules, false, newId)
        }
        assertThat(rules.single().confidence).isEqualTo(MerchantCategorizer.MAX_CONFIDENCE)
    }

    @Test
    fun `learning from unusable merchant text is a no-op`() {
        val rules = categorizer.learn("412345678901", DefaultCategories.SHOPPING, null, emptyList(), true, newId)
        assertThat(rules).isEmpty()
    }

    @Test
    fun `a preferred account is remembered alongside the category`() {
        val rules = categorizer.learn("Swiggy", DefaultCategories.FOOD_DELIVERY, "acc-card", emptyList(), true, newId)
        assertThat(categorizer.suggest("Swiggy", rules)!!.accountId).isEqualTo("acc-card")
    }

    @Test
    fun `known recurring services are recognised as subscriptions`() {
        assertThat(categorizer.looksLikeSubscription("Netflix")).isTrue()
        assertThat(categorizer.looksLikeSubscription("Spotify")).isTrue()
        assertThat(categorizer.looksLikeSubscription("BigBasket")).isFalse()
        assertThat(categorizer.looksLikeSubscription(null)).isFalse()
    }
}

class DefaultCategoriesTest {

    @Test
    fun `every category id is unique`() {
        val ids = DefaultCategories.ALL.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `every parent reference resolves and appears before its children`() {
        val seen = mutableSetOf<String>()
        for (category in DefaultCategories.ALL) {
            category.parentId?.let { parentId ->
                assertThat(seen).contains(parentId)
            }
            seen += category.id
        }
    }

    @Test
    fun `subcategories are never nested more than one level deep`() {
        val byId = DefaultCategories.ALL.associateBy { it.id }
        for (category in DefaultCategories.ALL) {
            val parent = category.parentId?.let { byId.getValue(it) } ?: continue
            assertThat(parent.parentId).isNull()
        }
    }

    @Test
    fun `every seeded merchant rule points at a real category`() {
        val ids = DefaultCategories.ALL.map { it.id }.toSet()
        val unknownCategories = SeedMerchantRules.RULES
            .filterValues { it !in ids }
            .map { (merchant, categoryId) -> "$merchant -> $categoryId" }

        assertThat(unknownCategories).isEmpty()
    }

    @Test
    fun `no seeded merchant rule is unreachable`() {
        // A rule keyed on something the normaliser would never produce can never fire, so it is
        // dead weight that silently does nothing. Every mismatch is reported at once.
        val unreachable = SeedMerchantRules.RULES.keys
            .mapNotNull { key ->
                val normalised = MerchantNormaliser.normalise(key)
                if (normalised == key) null else "$key normalises to $normalised"
            }

        assertThat(unreachable).isEmpty()
    }

    @Test
    fun `subscription merchant keys are all present in the rule set`() {
        assertThat(SeedMerchantRules.RULES.keys)
            .containsAtLeastElementsIn(SeedMerchantRules.SUBSCRIPTION_MERCHANTS)
    }

    @Test
    fun `onboarding and budget suggestions reference real categories`() {
        val ids = DefaultCategories.ALL.map { it.id }.toSet()
        assertThat(ids).containsAtLeastElementsIn(DefaultCategories.ONBOARDING_SUGGESTIONS)
        assertThat(ids).containsAtLeastElementsIn(DefaultCategories.BUDGET_SUGGESTIONS)
    }

    @Test
    fun `budget suggestions are all top-level categories`() {
        val topLevelIds = DefaultCategories.TOP_LEVEL.map { it.id }.toSet()
        assertThat(topLevelIds).containsAtLeastElementsIn(DefaultCategories.BUDGET_SUGGESTIONS)
    }
}
