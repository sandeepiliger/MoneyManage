package ai.labs32.khaata.core.money

import kotlinx.serialization.Serializable

/**
 * A supported currency.
 *
 * The app is India-first but not India-only: every monetary value carries its currency, and
 * arithmetic between mismatched currencies is rejected rather than silently coerced. Adding a
 * currency is a matter of adding an entry here — no call site needs to change.
 *
 * [minorUnits] is the number of decimal places the currency is quoted in, which is also the
 * scale every [Money] of that currency is normalised to.
 */
@Serializable
enum class CurrencyCode(
    val code: String,
    val symbol: String,
    val displayName: String,
    val minorUnits: Int,
    val grouping: DigitGrouping,
) {
    INR("INR", "₹", "Indian Rupee", 2, DigitGrouping.INDIAN),
    USD("USD", "$", "US Dollar", 2, DigitGrouping.WESTERN),
    EUR("EUR", "€", "Euro", 2, DigitGrouping.WESTERN),
    GBP("GBP", "£", "British Pound", 2, DigitGrouping.WESTERN),
    AED("AED", "د.إ", "UAE Dirham", 2, DigitGrouping.WESTERN),
    SGD("SGD", "S$", "Singapore Dollar", 2, DigitGrouping.WESTERN),
    AUD("AUD", "A$", "Australian Dollar", 2, DigitGrouping.WESTERN),
    CAD("CAD", "C$", "Canadian Dollar", 2, DigitGrouping.WESTERN),
    JPY("JPY", "¥", "Japanese Yen", 0, DigitGrouping.WESTERN),
    ;

    companion object {
        val DEFAULT: CurrencyCode = INR

        /** Resolves an ISO-4217 code, case-insensitively. Returns null for unknown codes. */
        fun fromCode(raw: String?): CurrencyCode? {
            if (raw.isNullOrBlank()) return null
            val normalised = raw.trim().uppercase()
            return entries.firstOrNull { it.code == normalised }
        }

        /** Resolves an ISO-4217 code, falling back to [DEFAULT] for unknown or missing input. */
        fun fromCodeOrDefault(raw: String?): CurrencyCode = fromCode(raw) ?: DEFAULT
    }
}

/**
 * How the integer part of an amount is grouped when formatted.
 *
 * [INDIAN] is the lakh/crore system: the last three digits form one group and every group above
 * that is two digits, so 14,28,500 rather than 1,428,500. Getting this wrong is immediately
 * jarring to an Indian user, so it is modelled explicitly rather than left to platform locale
 * data (which varies between the JVM and Android's ICU).
 */
enum class DigitGrouping { INDIAN, WESTERN }
