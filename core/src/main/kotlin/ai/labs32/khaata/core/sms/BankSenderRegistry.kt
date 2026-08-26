package ai.labs32.khaata.core.sms

/**
 * Infers a bank's display name from an SMS sender id, for naming an account auto-created from a
 * message nothing else identifies it against.
 *
 * Indian carriers require every transactional sender id to carry a DLT-registered header: a
 * two-letter agency prefix, a dash, then a 6-character (occasionally shorter) code the bank
 * itself chose — `AD-HDFCBK`, `VM-SBIINB`, `JD-ICICIB`. [nameFor] strips the prefix and looks the
 * remainder up.
 *
 * Deliberately a lookup, not a guess. The table only covers banks matched with real confidence;
 * everything else returns null, and the caller falls back to a masked-digits-only label rather
 * than risk telling someone their money sits at the wrong bank. Extend the table as gaps show up
 * — it is bounded and reviewable on purpose, not an attempt at completeness on day one.
 */
object BankSenderRegistry {

    private val PREFIX = Regex("""^[A-Z]{2}-""")

    private val BANKS: Map<String, String> = mapOf(
        "HDFCBK" to "HDFC Bank",
        "HDFCBN" to "HDFC Bank",
        "ICICIB" to "ICICI Bank",
        "ICICIT" to "ICICI Bank",
        "SBIINB" to "State Bank of India",
        "SBIPSG" to "State Bank of India",
        "SBIUPI" to "State Bank of India",
        "ATMSBI" to "State Bank of India",
        "AXISBK" to "Axis Bank",
        "AXISBN" to "Axis Bank",
        "KOTAKB" to "Kotak Mahindra Bank",
        "KOTAK" to "Kotak Mahindra Bank",
        "PNBSMS" to "Punjab National Bank",
        "PUNBNK" to "Punjab National Bank",
        "BOIIND" to "Bank of India",
        "CANBNK" to "Canara Bank",
        "CANBK" to "Canara Bank",
        "UNIONB" to "Union Bank of India",
        "UBIIND" to "Union Bank of India",
        "IDFCFB" to "IDFC FIRST Bank",
        "IDFCBK" to "IDFC FIRST Bank",
        "INDUSB" to "IndusInd Bank",
        "INDUS" to "IndusInd Bank",
        "YESBNK" to "Yes Bank",
        "RBLBNK" to "RBL Bank",
        "FEDBNK" to "Federal Bank",
        "IDBIBK" to "IDBI Bank",
        "BOBIBK" to "Bank of Baroda",
        "BOBTXN" to "Bank of Baroda",
        "AUSFBL" to "AU Small Finance Bank",
        "AUBANK" to "AU Small Finance Bank",
        "PAYTMB" to "Paytm Payments Bank",
        "AIRTLB" to "Airtel Payments Bank",
        "APBUPI" to "Airtel Payments Bank",
        "JIOPAY" to "Jio Payments Bank",
        "HSBCIN" to "HSBC",
        "SCBLTD" to "Standard Chartered Bank",
        "DBSBNK" to "DBS Bank",
        "CITIBK" to "Citibank",
    )

    /** The bank's display name, or null when [sender] is missing or unrecognised. */
    fun nameFor(sender: String?): String? {
        if (sender.isNullOrBlank()) return null
        val header = sender.trim().uppercase().replace(PREFIX, "")
        return BANKS[header]
    }
}
