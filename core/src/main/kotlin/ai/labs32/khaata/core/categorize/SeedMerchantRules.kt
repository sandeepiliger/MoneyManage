package ai.labs32.khaata.core.categorize

import ai.labs32.khaata.core.categorize.DefaultCategories as C

/**
 * The merchant → category knowledge a fresh install ships with.
 *
 * This is what makes the very first Swiggy entry a one-tap operation instead of a four-tap one.
 * Every entry is a well-known Indian brand keyed by its [MerchantNormaliser] form.
 *
 * These are starting points, not fixed truths: a user who files Amazon under Groceries corrects
 * it once and [MerchantCategorizer] prefers their choice from then on. The seed set is
 * deliberately conservative — a wrong guess that the user has to undo is worse than no guess.
 */
object SeedMerchantRules {

    /**
     * Merchant key → category id.
     *
     * Keys are what [MerchantNormaliser.normalise] produces, so a lookup is a plain map hit with
     * no fuzzy matching at read time.
     */
    val RULES: Map<String, String> = buildMap {
        // ---- Food delivery and groceries ----
        put("swiggy", C.FOOD_DELIVERY)
        put("zomato", C.FOOD_DELIVERY)
        put("swiggy_instamart", C.GROCERIES)
        put("zepto", C.GROCERIES)
        put("blinkit", C.GROCERIES)
        put("bigbasket", C.GROCERIES)
        put("dmart", C.GROCERIES)
        put("avenue_supermarts", C.GROCERIES)
        put("reliance_fresh", C.GROCERIES)
        put("reliance_smart", C.GROCERIES)
        put("more_retail", C.GROCERIES)
        put("spencers", C.GROCERIES)
        put("natures_basket", C.GROCERIES)
        put("licious", C.GROCERIES)
        put("country_delight", C.GROCERIES)
        put("milkbasket", C.GROCERIES)
        put("dunzo", C.GROCERIES)

        // ---- Restaurants, cafes ----
        put("starbucks", C.TEA_COFFEE)
        put("cafe_coffee", C.TEA_COFFEE)
        put("ccd", C.TEA_COFFEE)
        put("chaayos", C.TEA_COFFEE)
        put("chai_point", C.TEA_COFFEE)
        put("third_wave", C.TEA_COFFEE)
        put("blue_tokai", C.TEA_COFFEE)
        put("dominos", C.RESTAURANTS)
        put("pizza_hut", C.RESTAURANTS)
        put("mcdonalds", C.RESTAURANTS)
        put("burger_king", C.RESTAURANTS)
        put("kfc", C.RESTAURANTS)
        put("subway", C.RESTAURANTS)
        put("haldirams", C.RESTAURANTS)
        put("barbeque_nation", C.RESTAURANTS)
        put("wow_momo", C.RESTAURANTS)
        put("faasos", C.FOOD_DELIVERY)
        put("eatfit", C.FOOD_DELIVERY)
        put("box8", C.FOOD_DELIVERY)

        // ---- Transport ----
        put("uber", C.CAB)
        put("ola", C.CAB)
        put("ola_cabs", C.CAB)
        put("rapido", C.CAB)
        put("blusmart", C.CAB)
        put("meru", C.CAB)
        put("namma_yatri", C.CAB)
        put("indian_oil", C.FUEL)
        put("iocl", C.FUEL)
        put("bharat_petroleum", C.FUEL)
        put("bpcl", C.FUEL)
        put("hindustan_petroleum", C.FUEL)
        put("hpcl", C.FUEL)
        put("shell", C.FUEL)
        put("nayara", C.FUEL)
        put("jio_bp", C.FUEL)
        put("fastag", C.TOLL_FASTAG)
        put("nhai", C.TOLL_FASTAG)
        put("paytm_fastag", C.TOLL_FASTAG)
        put("irctc", C.TRAVEL)
        put("indigo", C.TRAVEL)
        // "India" is stripped as noise (it is filler in "Starbucks India"), so "AIR INDIA"
        // reduces to "air". Keyed to match what the normaliser actually produces.
        put("air", C.TRAVEL)
        put("spicejet", C.TRAVEL)
        put("akasa_air", C.TRAVEL)
        put("vistara", C.TRAVEL)
        put("redbus", C.TRAVEL)
        put("abhibus", C.TRAVEL)
        put("makemytrip", C.TRAVEL)
        put("goibibo", C.TRAVEL)
        put("cleartrip", C.TRAVEL)
        put("yatra", C.TRAVEL)
        put("oyo", C.TRAVEL)
        put("bmtc", C.PUBLIC_TRANSPORT)
        put("bmrcl", C.PUBLIC_TRANSPORT)
        put("dmrc", C.PUBLIC_TRANSPORT)
        put("onecard_metro", C.PUBLIC_TRANSPORT)

        // ---- Shopping ----
        put("amazon", C.SHOPPING)
        put("flipkart", C.SHOPPING)
        put("myntra", C.SHOPPING)
        put("ajio", C.SHOPPING)
        put("meesho", C.SHOPPING)
        put("nykaa", C.PERSONAL_CARE)
        put("tata_cliq", C.SHOPPING)
        put("snapdeal", C.SHOPPING)
        put("decathlon", C.SHOPPING)
        put("ikea", C.SHOPPING)
        put("croma", C.SHOPPING)
        put("reliance_digital", C.SHOPPING)
        put("vijay_sales", C.SHOPPING)
        put("lenskart", C.SHOPPING)
        put("firstcry", C.CHILDREN)
        // "Company" is stripped as noise, so "URBAN COMPANY" reduces to "urban".
        put("urban", C.PERSONAL_CARE)
        put("urbanclap", C.PERSONAL_CARE)

        // ---- Subscriptions and entertainment ----
        put("netflix", C.SUBSCRIPTIONS)
        put("spotify", C.SUBSCRIPTIONS)
        put("youtube", C.SUBSCRIPTIONS)
        put("google_one", C.SUBSCRIPTIONS)
        put("google_play", C.SUBSCRIPTIONS)
        put("apple", C.SUBSCRIPTIONS)
        put("prime_video", C.SUBSCRIPTIONS)
        put("hotstar", C.SUBSCRIPTIONS)
        put("disney", C.SUBSCRIPTIONS)
        put("sonyliv", C.SUBSCRIPTIONS)
        put("zee5", C.SUBSCRIPTIONS)
        put("jiocinema", C.SUBSCRIPTIONS)
        put("jiohotstar", C.SUBSCRIPTIONS)
        put("audible", C.SUBSCRIPTIONS)
        put("gaana", C.SUBSCRIPTIONS)
        put("wynk", C.SUBSCRIPTIONS)
        put("bookmyshow", C.ENTERTAINMENT)
        put("pvr", C.ENTERTAINMENT)
        put("inox", C.ENTERTAINMENT)
        put("cult_fit", C.FITNESS)
        put("cultfit", C.FITNESS)
        put("gold_gym", C.FITNESS)

        // ---- Bills and utilities ----
        put("bescom", C.ELECTRICITY)
        put("mseb", C.ELECTRICITY)
        put("tata_power", C.ELECTRICITY)
        put("adani_electricity", C.ELECTRICITY)
        put("torrent_power", C.ELECTRICITY)
        put("bses", C.ELECTRICITY)
        put("tneb", C.ELECTRICITY)
        put("airtel", C.MOBILE_RECHARGE)
        put("jio", C.MOBILE_RECHARGE)
        put("vodafone", C.MOBILE_RECHARGE)
        put("vi", C.MOBILE_RECHARGE)
        put("bsnl", C.MOBILE_RECHARGE)
        put("act_fibernet", C.INTERNET)
        put("hathway", C.INTERNET)
        put("excitel", C.INTERNET)
        put("jiofiber", C.INTERNET)
        put("tata_play", C.DTH_CABLE)
        put("dish_tv", C.DTH_CABLE)
        put("indane", C.GAS_CYLINDER)
        put("hp_gas", C.GAS_CYLINDER)
        put("bharat_gas", C.GAS_CYLINDER)

        // ---- Health ----
        put("apollo_pharmacy", C.MEDICINES)
        put("pharmeasy", C.MEDICINES)
        put("netmeds", C.MEDICINES)
        put("tata_1mg", C.MEDICINES)
        put("1mg", C.MEDICINES)
        put("wellness_forever", C.MEDICINES)
        put("practo", C.DOCTOR)
        put("dr_lal", C.DOCTOR)
        put("thyrocare", C.DOCTOR)
        put("metropolis", C.DOCTOR)
        put("fortis", C.DOCTOR)
        put("manipal", C.DOCTOR)

        // ---- Financial ----
        put("zerodha", C.INVESTMENT)
        put("groww", C.INVESTMENT)
        put("upstox", C.INVESTMENT)
        put("angel_one", C.INVESTMENT)
        put("kuvera", C.INVESTMENT)
        put("coin_zerodha", C.INVESTMENT)
        put("indmoney", C.INVESTMENT)
        put("lic", C.INSURANCE)
        put("hdfc_life", C.INSURANCE)
        put("icici_prudential", C.INSURANCE)
        put("sbi_life", C.INSURANCE)
        put("star_health", C.HEALTH_INSURANCE)
        put("niva_bupa", C.HEALTH_INSURANCE)
        put("policybazaar", C.INSURANCE)
        put("acko", C.INSURANCE)
        put("digit_insurance", C.INSURANCE)
    }

    /** Merchant keys that identify a recurring service, used to prefill subscription tracking. */
    val SUBSCRIPTION_MERCHANTS: Set<String> = setOf(
        "netflix", "spotify", "youtube", "google_one", "prime_video", "hotstar", "disney",
        "sonyliv", "zee5", "jiocinema", "jiohotstar", "audible", "gaana", "wynk", "cult_fit",
        "cultfit", "apple",
    )
}
