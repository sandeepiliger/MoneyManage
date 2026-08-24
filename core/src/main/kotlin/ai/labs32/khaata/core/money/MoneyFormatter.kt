package ai.labs32.khaata.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/** How much of the amount to show. */
enum class MoneyStyle {
    /** Full precision with decimals: `₹14,285.50`. */
    FULL,

    /** Decimals dropped when they are zero: `₹14,285` but `₹14,285.50`. */
    SMART,

    /** Decimals always dropped: `₹14,285`. */
    WHOLE,

    /** Abbreviated for tight spaces: `₹14.3K`, `₹1.4L`, `₹2.3Cr`. */
    COMPACT,
}

/** How the sign is rendered. */
enum class SignStyle {
    /** Only negatives get a sign: `-₹850`. */
    NEGATIVE_ONLY,

    /** Both directions are marked: `+₹35,000` / `-₹850`. Used in transaction lists. */
    ALWAYS,

    /** Sign dropped entirely — for magnitudes where direction is shown some other way. */
    NEVER,
}

/**
 * Renders [Money] for display.
 *
 * This is deliberately not delegated to `java.text.NumberFormat`: Indian lakh/crore grouping
 * differs between JVM locale data and Android's ICU, and abbreviations like `1.4L` have no
 * platform equivalent at all. Doing it here keeps rendering identical on every device and makes
 * it unit-testable without an emulator.
 *
 * Strings produced here are for humans. Anything machine-readable uses
 * [Money.toStorageString] or [Money.toPlainString].
 */
object MoneyFormatter {

    private val THOUSAND = BigDecimal("1000")
    private val LAKH = BigDecimal("100000")
    private val CRORE = BigDecimal("10000000")

    /**
     * Formats [money] for display.
     *
     * @param style how much precision to show.
     * @param signStyle how to mark direction.
     * @param withSymbol whether to prefix the currency symbol.
     */
    fun format(
        money: Money,
        style: MoneyStyle = MoneyStyle.SMART,
        signStyle: SignStyle = SignStyle.NEGATIVE_ONLY,
        withSymbol: Boolean = true,
    ): String {
        val magnitude = money.amount.abs()
        val body = when (style) {
            MoneyStyle.FULL -> group(magnitude.setScale(money.currency.minorUnits, RoundingMode.HALF_EVEN), money.currency)
            MoneyStyle.SMART -> if (hasFraction(magnitude)) {
                group(magnitude.setScale(money.currency.minorUnits, RoundingMode.HALF_EVEN), money.currency)
            } else {
                group(magnitude.setScale(0, RoundingMode.HALF_EVEN), money.currency)
            }
            MoneyStyle.WHOLE -> group(magnitude.setScale(0, RoundingMode.HALF_EVEN), money.currency)
            MoneyStyle.COMPACT -> compact(magnitude, money.currency)
        }

        val prefix = buildString {
            when (signStyle) {
                SignStyle.NEGATIVE_ONLY -> if (money.isNegative) append('-')
                SignStyle.ALWAYS -> append(if (money.isNegative) '-' else '+')
                SignStyle.NEVER -> Unit
            }
            if (withSymbol) append(money.currency.symbol)
        }
        return prefix + body
    }

    /** Shorthand for the most common case: a signed, symbol-prefixed, smart-precision amount. */
    fun signed(money: Money): String = format(money, MoneyStyle.SMART, SignStyle.ALWAYS)

    /** Shorthand for a magnitude with no direction, e.g. a budget cap. */
    fun plain(money: Money): String = format(money, MoneyStyle.SMART, SignStyle.NEGATIVE_ONLY)

    /** Shorthand for tight layouts such as chart axes and dense cards. */
    fun compact(money: Money): String = format(money, MoneyStyle.COMPACT, SignStyle.NEGATIVE_ONLY)

    /**
     * Renders a ratio as a percentage string, e.g. `18.4%`.
     *
     * @param decimals how many decimal places to keep; 0 gives `18%`.
     */
    fun percentage(value: BigDecimal?, decimals: Int = 1): String {
        if (value == null) return "—"
        val rounded = value.setScale(decimals, RoundingMode.HALF_EVEN)
        return "${rounded.toPlainString()}%"
    }

    /**
     * Content description for screen readers.
     *
     * Symbols and abbreviations read poorly aloud, so this spells the amount out: TalkBack
     * announces "850 rupees spent" rather than "minus rupee-sign eight five zero".
     */
    fun accessibleDescription(money: Money, spentLabel: String, receivedLabel: String): String {
        val words = "${money.amount.abs().setScale(0, RoundingMode.HALF_EVEN).toPlainString()} " +
            money.currency.displayName.lowercase() + "s"
        return if (money.isNegative) "$words $spentLabel" else "$words $receivedLabel"
    }

    private fun hasFraction(magnitude: BigDecimal): Boolean =
        magnitude.stripTrailingZeros().scale() > 0

    private fun compact(magnitude: BigDecimal, currency: CurrencyCode): String {
        if (currency.grouping == DigitGrouping.INDIAN) {
            return when {
                magnitude >= CRORE -> abbreviate(magnitude, CRORE, "Cr")
                magnitude >= LAKH -> abbreviate(magnitude, LAKH, "L")
                magnitude >= THOUSAND -> abbreviate(magnitude, THOUSAND, "K")
                else -> group(magnitude.setScale(0, RoundingMode.HALF_EVEN), currency)
            }
        }
        val billion = BigDecimal("1000000000")
        val million = BigDecimal("1000000")
        return when {
            magnitude >= billion -> abbreviate(magnitude, billion, "B")
            magnitude >= million -> abbreviate(magnitude, million, "M")
            magnitude >= THOUSAND -> abbreviate(magnitude, THOUSAND, "K")
            else -> group(magnitude.setScale(0, RoundingMode.HALF_EVEN), currency)
        }
    }

    private fun abbreviate(magnitude: BigDecimal, unit: BigDecimal, suffix: String): String {
        val scaled = magnitude.divide(unit, 1, RoundingMode.HALF_EVEN)
        // Drop a trailing ".0" so we show "2Cr", not "2.0Cr".
        val text = if (scaled.remainder(BigDecimal.ONE).signum() == 0) {
            scaled.setScale(0, RoundingMode.HALF_EVEN).toPlainString()
        } else {
            scaled.toPlainString()
        }
        return text + suffix
    }

    /**
     * Applies digit grouping to a non-negative decimal.
     *
     * Indian grouping puts the first separator after three digits and every subsequent one after
     * two: 1,42,850 rather than 142,850.
     */
    internal fun group(value: BigDecimal, currency: CurrencyCode): String {
        val plain = value.toPlainString()
        val dot = plain.indexOf('.')
        val integerPart = if (dot < 0) plain else plain.substring(0, dot)
        val fractionPart = if (dot < 0) "" else plain.substring(dot)

        val grouped = when (currency.grouping) {
            DigitGrouping.INDIAN -> groupIndian(integerPart)
            DigitGrouping.WESTERN -> groupWestern(integerPart)
        }
        return grouped + fractionPart
    }

    private fun groupWestern(digits: String): String {
        if (digits.length <= 3) return digits
        val out = StringBuilder()
        val offset = digits.length % 3
        if (offset > 0) out.append(digits, 0, offset)
        var index = offset
        while (index < digits.length) {
            if (out.isNotEmpty()) out.append(',')
            out.append(digits, index, index + 3)
            index += 3
        }
        return out.toString()
    }

    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val lastThree = digits.substring(digits.length - 3)
        val rest = digits.substring(0, digits.length - 3)

        val out = StringBuilder()
        val offset = rest.length % 2
        if (offset > 0) out.append(rest, 0, offset)
        var index = offset
        while (index < rest.length) {
            if (out.isNotEmpty()) out.append(',')
            out.append(rest, index, index + 2)
            index += 2
        }
        out.append(',').append(lastThree)
        return out.toString()
    }
}
