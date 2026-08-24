package ai.labs32.khaata.core.categorize

import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.model.CategoryKind

/**
 * The category set a new install starts with.
 *
 * Chosen for how money is actually spent in urban and semi-urban India rather than for
 * accounting completeness. A first-time user should recognise nearly every line without having
 * to create anything, because the moment someone has to stop and build a taxonomy before logging
 * their first chai, they stop.
 *
 * Ids are stable string constants rather than generated UUIDs so that seeded merchant rules,
 * demo data and budget suggestions can reference them, and so a re-seed on upgrade updates rows
 * rather than duplicating them.
 */
object DefaultCategories {

    // ---- Ids -------------------------------------------------------------------------------
    // Stable across versions. Never renumber these; archive instead.

    const val FOOD = "cat_food"
    const val GROCERIES = "cat_groceries"
    const val RESTAURANTS = "cat_restaurants"
    const val FOOD_DELIVERY = "cat_food_delivery"
    const val TEA_COFFEE = "cat_tea_coffee"
    const val SNACKS = "cat_snacks"

    const val TRANSPORT = "cat_transport"
    const val FUEL = "cat_fuel"
    const val CAB = "cat_cab"
    const val PUBLIC_TRANSPORT = "cat_public_transport"
    const val AUTO_RICKSHAW = "cat_auto"
    const val PARKING = "cat_parking"
    const val TOLL_FASTAG = "cat_toll"
    const val VEHICLE_MAINTENANCE = "cat_vehicle_maintenance"

    const val BILLS = "cat_bills"
    const val ELECTRICITY = "cat_electricity"
    const val WATER = "cat_water"
    const val INTERNET = "cat_internet"
    const val MOBILE_RECHARGE = "cat_mobile"
    const val GAS_CYLINDER = "cat_gas"
    const val DTH_CABLE = "cat_dth"
    const val MAINTENANCE = "cat_society_maintenance"

    const val HOME = "cat_home"
    const val RENT = "cat_rent"
    const val HOUSEHOLD_HELP = "cat_household_help"
    const val REPAIRS = "cat_repairs"

    const val LIFESTYLE = "cat_lifestyle"
    const val SHOPPING = "cat_shopping"
    const val ENTERTAINMENT = "cat_entertainment"
    const val SUBSCRIPTIONS = "cat_subscriptions"
    const val TRAVEL = "cat_travel"
    const val FITNESS = "cat_fitness"
    const val PERSONAL_CARE = "cat_personal_care"
    const val GIFTS_FESTIVALS = "cat_gifts_festivals"

    const val HEALTH = "cat_health"
    const val MEDICINES = "cat_medicines"
    const val DOCTOR = "cat_doctor"
    const val HEALTH_INSURANCE = "cat_health_insurance"

    const val FINANCIAL = "cat_financial"
    const val EMI = "cat_emi"
    const val LOAN_REPAYMENT = "cat_loan_repayment"
    const val INSURANCE = "cat_insurance"
    const val INVESTMENT = "cat_investment"
    const val SIP = "cat_sip"
    const val BANK_CHARGES = "cat_bank_charges"
    const val TAX = "cat_tax"

    const val FAMILY = "cat_family"
    const val CHILDREN = "cat_children"
    const val EDUCATION = "cat_education"
    const val PARENTS = "cat_parents"

    const val INCOME_SALARY = "cat_salary"
    const val INCOME_BUSINESS = "cat_business_income"
    const val INCOME_FREELANCE = "cat_freelance"
    const val INCOME_INTEREST = "cat_interest_income"
    const val INCOME_RENT = "cat_rental_income"
    const val INCOME_REFUND = "cat_refund"
    const val INCOME_OTHER = "cat_other_income"

    const val UNCATEGORISED = "cat_uncategorised"

    /**
     * Every seeded category, parents before children so a foreign key on `parentId` can be
     * satisfied by inserting the list in order.
     */
    val ALL: List<Category> = buildList {
        // ---- Food ----
        parent(FOOD, "Food & Drink", CategoryGroup.FOOD, "food", 0)
        child(GROCERIES, "Groceries", FOOD, CategoryGroup.FOOD, "groceries", 1)
        child(RESTAURANTS, "Restaurants", FOOD, CategoryGroup.FOOD, "restaurant", 2)
        child(FOOD_DELIVERY, "Food Delivery", FOOD, CategoryGroup.FOOD, "delivery", 3)
        child(TEA_COFFEE, "Tea & Coffee", FOOD, CategoryGroup.FOOD, "coffee", 4)
        child(SNACKS, "Snacks", FOOD, CategoryGroup.FOOD, "snacks", 5)

        // ---- Transport ----
        parent(TRANSPORT, "Transport", CategoryGroup.TRANSPORT, "transport", 10)
        child(FUEL, "Fuel", TRANSPORT, CategoryGroup.TRANSPORT, "fuel", 11)
        child(CAB, "Cabs", TRANSPORT, CategoryGroup.TRANSPORT, "cab", 12)
        child(AUTO_RICKSHAW, "Auto", TRANSPORT, CategoryGroup.TRANSPORT, "auto", 13)
        child(PUBLIC_TRANSPORT, "Metro & Bus", TRANSPORT, CategoryGroup.TRANSPORT, "metro", 14)
        child(PARKING, "Parking", TRANSPORT, CategoryGroup.TRANSPORT, "parking", 15)
        child(TOLL_FASTAG, "Toll & FASTag", TRANSPORT, CategoryGroup.TRANSPORT, "toll", 16)
        child(VEHICLE_MAINTENANCE, "Vehicle Service", TRANSPORT, CategoryGroup.TRANSPORT, "car_service", 17)

        // ---- Bills ----
        parent(BILLS, "Bills & Utilities", CategoryGroup.BILLS, "bills", 20)
        child(ELECTRICITY, "Electricity", BILLS, CategoryGroup.BILLS, "electricity", 21)
        child(WATER, "Water", BILLS, CategoryGroup.BILLS, "water", 22)
        child(INTERNET, "Internet", BILLS, CategoryGroup.BILLS, "wifi", 23)
        child(MOBILE_RECHARGE, "Mobile", BILLS, CategoryGroup.BILLS, "mobile", 24)
        child(GAS_CYLINDER, "Cooking Gas", BILLS, CategoryGroup.BILLS, "gas", 25)
        child(DTH_CABLE, "DTH & Cable", BILLS, CategoryGroup.BILLS, "tv", 26)
        child(MAINTENANCE, "Society Maintenance", BILLS, CategoryGroup.BILLS, "building", 27)

        // ---- Home ----
        parent(HOME, "Home", CategoryGroup.FAMILY, "home", 30)
        child(RENT, "Rent", HOME, CategoryGroup.FAMILY, "rent", 31)
        child(HOUSEHOLD_HELP, "Household Help", HOME, CategoryGroup.FAMILY, "help", 32)
        child(REPAIRS, "Repairs", HOME, CategoryGroup.FAMILY, "repair", 33)

        // ---- Lifestyle ----
        parent(LIFESTYLE, "Lifestyle", CategoryGroup.LIFESTYLE, "lifestyle", 40)
        child(SHOPPING, "Shopping", LIFESTYLE, CategoryGroup.LIFESTYLE, "shopping", 41)
        child(ENTERTAINMENT, "Entertainment", LIFESTYLE, CategoryGroup.LIFESTYLE, "entertainment", 42)
        child(SUBSCRIPTIONS, "Subscriptions", LIFESTYLE, CategoryGroup.LIFESTYLE, "subscription", 43)
        child(TRAVEL, "Travel", LIFESTYLE, CategoryGroup.LIFESTYLE, "travel", 44)
        child(FITNESS, "Fitness", LIFESTYLE, CategoryGroup.LIFESTYLE, "fitness", 45)
        child(PERSONAL_CARE, "Personal Care", LIFESTYLE, CategoryGroup.LIFESTYLE, "personal_care", 46)
        child(GIFTS_FESTIVALS, "Gifts & Festivals", LIFESTYLE, CategoryGroup.LIFESTYLE, "gift", 47)

        // ---- Health ----
        parent(HEALTH, "Health", CategoryGroup.LIFESTYLE, "health", 50)
        child(MEDICINES, "Medicines", HEALTH, CategoryGroup.LIFESTYLE, "medicine", 51)
        child(DOCTOR, "Doctor & Tests", HEALTH, CategoryGroup.LIFESTYLE, "doctor", 52)
        child(HEALTH_INSURANCE, "Health Insurance", HEALTH, CategoryGroup.LIFESTYLE, "insurance", 53)

        // ---- Financial ----
        parent(FINANCIAL, "Financial", CategoryGroup.FINANCIAL, "financial", 60)
        child(EMI, "EMI", FINANCIAL, CategoryGroup.FINANCIAL, "emi", 61)
        child(LOAN_REPAYMENT, "Loan Repayment", FINANCIAL, CategoryGroup.FINANCIAL, "loan", 62)
        child(INSURANCE, "Insurance", FINANCIAL, CategoryGroup.FINANCIAL, "insurance", 63)
        child(INVESTMENT, "Investments", FINANCIAL, CategoryGroup.FINANCIAL, "investment", 64)
        child(SIP, "SIP", FINANCIAL, CategoryGroup.FINANCIAL, "sip", 65)
        child(BANK_CHARGES, "Bank Charges", FINANCIAL, CategoryGroup.FINANCIAL, "bank", 66)
        child(TAX, "Tax", FINANCIAL, CategoryGroup.FINANCIAL, "tax", 67)

        // ---- Family ----
        parent(FAMILY, "Family", CategoryGroup.FAMILY, "family", 70)
        child(CHILDREN, "Children", FAMILY, CategoryGroup.FAMILY, "children", 71)
        child(EDUCATION, "Education & Fees", FAMILY, CategoryGroup.FAMILY, "education", 72)
        child(PARENTS, "Parents", FAMILY, CategoryGroup.FAMILY, "parents", 73)

        // ---- Income ----
        income(INCOME_SALARY, "Salary", "salary", 80)
        income(INCOME_BUSINESS, "Business", "business", 81)
        income(INCOME_FREELANCE, "Freelance", "freelance", 82)
        income(INCOME_INTEREST, "Interest & Dividend", "interest", 83)
        income(INCOME_RENT, "Rental Income", "rental", 84)
        income(INCOME_REFUND, "Refunds & Cashback", "refund", 85)
        income(INCOME_OTHER, "Other Income", "other_income", 86)

        // ---- Fallback ----
        add(
            Category(
                id = UNCATEGORISED,
                name = "Uncategorised",
                group = CategoryGroup.OTHER,
                kind = CategoryKind.BOTH,
                iconKey = "unknown",
                isSystem = true,
                sortOrder = 999,
            ),
        )
    }

    /** Top-level categories only, for the first level of the picker. */
    val TOP_LEVEL: List<Category> = ALL.filter { it.parentId == null }

    /** Categories offered during onboarding's "what do you usually spend on?" step. */
    val ONBOARDING_SUGGESTIONS: List<String> = listOf(
        GROCERIES, FOOD_DELIVERY, RENT, FUEL, CAB, ELECTRICITY, MOBILE_RECHARGE,
        SHOPPING, ENTERTAINMENT, SUBSCRIPTIONS, EMI, SIP, HEALTH, CHILDREN,
    )

    /**
     * Categories a starter budget is proposed for, in priority order.
     *
     * Top-level only, deliberately: proposing budgets for both "Lifestyle" and its child
     * "Shopping" would have the same spend counted against two budgets, which makes both
     * meaningless. A user can still budget a subcategory themselves.
     */
    val BUDGET_SUGGESTIONS: List<String> = listOf(FOOD, TRANSPORT, BILLS, LIFESTYLE, FAMILY)

    private fun MutableList<Category>.parent(
        id: String,
        name: String,
        group: CategoryGroup,
        icon: String,
        order: Int,
    ) = add(
        Category(
            id = id,
            name = name,
            group = group,
            kind = CategoryKind.EXPENSE,
            iconKey = icon,
            colorSeed = group.defaultColorSeed,
            isSystem = true,
            sortOrder = order,
        ),
    )

    private fun MutableList<Category>.child(
        id: String,
        name: String,
        parentId: String,
        group: CategoryGroup,
        icon: String,
        order: Int,
    ) = add(
        Category(
            id = id,
            name = name,
            group = group,
            parentId = parentId,
            kind = CategoryKind.EXPENSE,
            iconKey = icon,
            colorSeed = group.defaultColorSeed,
            isSystem = true,
            sortOrder = order,
        ),
    )

    private fun MutableList<Category>.income(
        id: String,
        name: String,
        icon: String,
        order: Int,
    ) = add(
        Category(
            id = id,
            name = name,
            group = CategoryGroup.INCOME,
            kind = CategoryKind.INCOME,
            iconKey = icon,
            colorSeed = CategoryGroup.INCOME.defaultColorSeed,
            isSystem = true,
            sortOrder = order,
        ),
    )
}
