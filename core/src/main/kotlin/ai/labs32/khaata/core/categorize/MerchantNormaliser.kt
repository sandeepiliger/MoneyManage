package ai.labs32.khaata.core.categorize

/**
 * Reduces a merchant string to a stable key.
 *
 * Payment rails mangle merchant names badly. The same coffee shop arrives as
 * `POS 4321 STARBUCKS INDIA BLR`, `starbucks*india`, and `UPI-STARBUCKS@HDFCBANK`. Without
 * normalisation the app learns three separate rules and the user has to categorise the same
 * place over and over, which is exactly the friction this feature exists to remove.
 */
object MerchantNormaliser {

    /** Tokens that carry no merchant identity and only add noise. */
    private val NOISE_TOKENS = setOf(
        "pos", "upi", "neft", "imps", "rtgs", "atm", "vpa", "ref", "refno", "txn", "trf",
        "payment", "purchase", "paid", "recd", "received", "debit", "credit", "card", "acct",
        "ac", "a", "to", "from", "via", "at", "on", "the", "pvt", "ltd", "limited", "india",
        "in", "inc", "llp", "co", "company", "services", "service", "technologies", "tech",
        "solutions", "online", "store", "bill", "bills", "billdesk", "razorpay",
        "payu", "billpay", "autopay", "mandate", "nach", "ecs", "emi", "www", "com",
    )

    /**
     * City and locality tokens that payment terminals append.
     *
     * Without these, `SWIGGY BLR` and `SWIGGY BANGALORE` become two different merchants, so a
     * user who orders from the same place at home and at the office ends up categorising it
     * twice. Stripping location keeps one merchant, one rule.
     */
    private val LOCATION_TOKENS = setOf(
        "blr", "bangalore", "bengaluru", "mum", "mumbai", "bom", "del", "delhi", "ncr",
        "newdelhi", "gurgaon", "gurugram", "noida", "ghaziabad", "faridabad",
        "hyd", "hyderabad", "secunderabad", "chn", "chennai", "madras",
        "pune", "pnq", "kolkata", "ccu", "calcutta", "ahmedabad", "amd", "surat",
        "jaipur", "lucknow", "kanpur", "nagpur", "indore", "bhopal", "patna",
        "kochi", "cochin", "ernakulam", "trivandrum", "coimbatore", "mysore", "mysuru",
        "chandigarh", "mohali", "vizag", "visakhapatnam", "vadodara", "nashik", "thane",
        "branch", "outlet", "terminal", "airport", "mall",
    )

    /** Bank handles seen after `@` in a VPA — never part of the merchant's identity. */
    private val VPA_HANDLES = Regex("@[a-z0-9.\\-]+")

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val LONG_DIGITS = Regex("\\d{3,}")

    /**
     * Normalises [raw] to a lower-case, underscore-free key such as `swiggy` or `indian_oil`.
     *
     * Returns null when nothing identifying survives — a purely numeric reference, say — so
     * callers never learn a rule keyed on noise.
     */
    fun normalise(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val withoutHandles = raw.lowercase().replace(VPA_HANDLES, " ")
        // Long digit runs are references and card fragments, not names.
        val withoutReferences = withoutHandles.replace(LONG_DIGITS, " ")

        val tokens = withoutReferences
            .split(NON_ALPHANUMERIC)
            .filter { it.isNotBlank() }
            .filter { it !in NOISE_TOKENS }
            .filter { it !in LOCATION_TOKENS }
            // Single characters left over from splitting are never meaningful.
            .filter { it.length > 1 || it.all { char -> char.isDigit() } }

        if (tokens.isEmpty()) return null

        // Two tokens is enough to identify a merchant and keeps keys stable across the extra
        // location and terminal suffixes different rails append.
        return tokens.take(2).joinToString("_").takeIf { it.isNotBlank() }
    }

    /**
     * A human-friendly merchant name derived from [raw].
     *
     * Used to prefill the merchant field so the user sees "Swiggy" rather than
     * `UPI-SWIGGY-BLR-4432`.
     */
    fun displayName(raw: String?): String? {
        val key = normalise(raw) ?: return null
        return key.split('_').joinToString(" ") { token ->
            // Only known acronyms are shouted. A blanket "short tokens are acronyms" rule turns
            // "Indian Oil" into "Indian OIL".
            if (token in ACRONYMS) token.uppercase() else token.replaceFirstChar { it.uppercase() }
        }
    }

    /** Brands normally written in capitals, so the prefilled merchant name looks right. */
    private val ACRONYMS = setOf(
        "kfc", "pvr", "ccd", "hdfc", "icici", "sbi", "idfc", "rbl", "iob", "pnb", "bob",
        "iocl", "bpcl", "hpcl", "ongc", "bsnl", "mtnl", "dth", "atm", "lic", "nps", "ppf",
        "epf", "sip", "emi",
    )

    /** True when two merchant strings refer to the same place. */
    fun sameMerchant(first: String?, second: String?): Boolean {
        val a = normalise(first) ?: return false
        val b = normalise(second) ?: return false
        return a == b
    }
}
