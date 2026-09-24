package ai.labs32.khaata.core.locale

import android.content.Context
import ai.labs32.khaata.R
import ai.labs32.khaata.core.categorize.DefaultCategories

/**
 * The built-in categories, named in the app's language.
 *
 * Rows are seeded with English names and keep them in the database, so a backup, a CSV export and
 * a change of language all see the same stored value. The name is translated on the way out of the
 * database and turned back into English on the way in (see the category mappers), so the user sees
 * "ದಿನಸಿ" or "किराना" where the row says "Groceries".
 *
 * A category the user has renamed is theirs: its stored name no longer matches the seeded one, and
 * it is shown exactly as typed in every language.
 */
object BuiltInCategoryNames {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastLanguage: String? = null

    /** Called once at startup. Until then, and in tests that never call it, names pass through. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** The name to show for a stored category row. */
    fun display(id: String, storedName: String, isSystem: Boolean): String {
        if (!isSystem || storedName != DefaultCategories.defaultName(id)) return storedName
        return translated(id) ?: storedName
    }

    /**
     * The name to store for a category about to be written. A built-in category still showing its
     * translated name goes back as its English original, so switching language later still
     * translates it; anything else is stored as it is.
     */
    fun stored(id: String, shownName: String, isSystem: Boolean): String {
        if (!isSystem) return shownName
        val seeded = DefaultCategories.defaultName(id) ?: return shownName
        return if (shownName == translated(id)) seeded else shownName
    }

    /**
     * True the first time this is asked after the app's language has changed. Screens hold on to
     * category lists they already read; the caller uses this to make them read again.
     */
    fun languageChangedSinceLastCheck(): Boolean {
        val current = appContext?.resources?.configuration?.locales?.get(0)?.toLanguageTag() ?: return false
        val previous = lastLanguage
        lastLanguage = current
        return previous != null && previous != current
    }

    private fun translated(id: String): String? {
        val context = appContext ?: return null
        val resource = STRINGS[id] ?: return null
        return context.getString(resource)
    }

    private val STRINGS: Map<String, Int> = mapOf(
        DefaultCategories.FOOD to R.string.category_name_food,
        DefaultCategories.GROCERIES to R.string.category_name_groceries,
        DefaultCategories.RESTAURANTS to R.string.category_name_restaurants,
        DefaultCategories.FOOD_DELIVERY to R.string.category_name_food_delivery,
        DefaultCategories.TEA_COFFEE to R.string.category_name_tea_coffee,
        DefaultCategories.SNACKS to R.string.category_name_snacks,
        DefaultCategories.TRANSPORT to R.string.category_name_transport,
        DefaultCategories.FUEL to R.string.category_name_fuel,
        DefaultCategories.CAB to R.string.category_name_cab,
        DefaultCategories.AUTO_RICKSHAW to R.string.category_name_auto,
        DefaultCategories.PUBLIC_TRANSPORT to R.string.category_name_public_transport,
        DefaultCategories.PARKING to R.string.category_name_parking,
        DefaultCategories.TOLL_FASTAG to R.string.category_name_toll,
        DefaultCategories.VEHICLE_MAINTENANCE to R.string.category_name_vehicle_maintenance,
        DefaultCategories.BILLS to R.string.category_name_bills,
        DefaultCategories.ELECTRICITY to R.string.category_name_electricity,
        DefaultCategories.WATER to R.string.category_name_water,
        DefaultCategories.INTERNET to R.string.category_name_internet,
        DefaultCategories.MOBILE_RECHARGE to R.string.category_name_mobile,
        DefaultCategories.GAS_CYLINDER to R.string.category_name_gas,
        DefaultCategories.DTH_CABLE to R.string.category_name_dth,
        DefaultCategories.MAINTENANCE to R.string.category_name_society_maintenance,
        DefaultCategories.HOME to R.string.category_name_home,
        DefaultCategories.RENT to R.string.category_name_rent,
        DefaultCategories.HOUSEHOLD_HELP to R.string.category_name_household_help,
        DefaultCategories.REPAIRS to R.string.category_name_repairs,
        DefaultCategories.LIFESTYLE to R.string.category_name_lifestyle,
        DefaultCategories.SHOPPING to R.string.category_name_shopping,
        DefaultCategories.ENTERTAINMENT to R.string.category_name_entertainment,
        DefaultCategories.SUBSCRIPTIONS to R.string.category_name_subscriptions,
        DefaultCategories.TRAVEL to R.string.category_name_travel,
        DefaultCategories.FITNESS to R.string.category_name_fitness,
        DefaultCategories.PERSONAL_CARE to R.string.category_name_personal_care,
        DefaultCategories.GIFTS_FESTIVALS to R.string.category_name_gifts_festivals,
        DefaultCategories.HEALTH to R.string.category_name_health,
        DefaultCategories.MEDICINES to R.string.category_name_medicines,
        DefaultCategories.DOCTOR to R.string.category_name_doctor,
        DefaultCategories.HEALTH_INSURANCE to R.string.category_name_health_insurance,
        DefaultCategories.FINANCIAL to R.string.category_name_financial,
        DefaultCategories.EMI to R.string.category_name_emi,
        DefaultCategories.LOAN_REPAYMENT to R.string.category_name_loan_repayment,
        DefaultCategories.INSURANCE to R.string.category_name_insurance,
        DefaultCategories.INVESTMENT to R.string.category_name_investment,
        DefaultCategories.SIP to R.string.category_name_sip,
        DefaultCategories.BANK_CHARGES to R.string.category_name_bank_charges,
        DefaultCategories.TAX to R.string.category_name_tax,
        DefaultCategories.FAMILY to R.string.category_name_family,
        DefaultCategories.CHILDREN to R.string.category_name_children,
        DefaultCategories.EDUCATION to R.string.category_name_education,
        DefaultCategories.PARENTS to R.string.category_name_parents,
        DefaultCategories.INCOME_SALARY to R.string.category_name_salary,
        DefaultCategories.INCOME_BUSINESS to R.string.category_name_business_income,
        DefaultCategories.INCOME_FREELANCE to R.string.category_name_freelance,
        DefaultCategories.INCOME_INTEREST to R.string.category_name_interest_income,
        DefaultCategories.INCOME_RENT to R.string.category_name_rental_income,
        DefaultCategories.INCOME_REFUND to R.string.category_name_refund,
        DefaultCategories.INCOME_OTHER to R.string.category_name_other_income,
        DefaultCategories.UNCATEGORISED to R.string.category_name_uncategorised,
    )
}
