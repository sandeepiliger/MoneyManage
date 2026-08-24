package ai.labs32.khaata.core.money

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * An exact monetary amount in a single currency.
 *
 * Money is never represented as a floating point number anywhere in this app. The value is a
 * [BigDecimal] normalised to the currency's minor-unit scale (2 decimal places for INR), so
 * every amount that reaches the database, the UI or an export is a value the user could
 * actually pay. Intermediate maths that genuinely needs more precision — loan amortisation,
 * annualised returns — works in raw [BigDecimal] at a higher scale and converts back once, at
 * the end (see [ofExact] and [MoneyMath]).
 *
 * Arithmetic between different currencies throws rather than coercing, because a silently wrong
 * total is far worse than a crash in a finance app.
 */
@Serializable(with = MoneySerializer::class)
class Money private constructor(
    val amount: BigDecimal,
    val currency: CurrencyCode,
) : Comparable<Money> {

    init {
        require(amount.scale() == currency.minorUnits) {
            "Money must be normalised to ${currency.minorUnits} dp, got scale ${amount.scale()}"
        }
    }

    // ---- Predicates ----------------------------------------------------------------------

    val isZero: Boolean get() = amount.signum() == 0
    val isPositive: Boolean get() = amount.signum() > 0
    val isNegative: Boolean get() = amount.signum() < 0
    val signum: Int get() = amount.signum()

    /** The amount expressed in whole minor units (paise for INR). Useful for compact storage. */
    val minorUnits: Long get() = amount.movePointRight(currency.minorUnits).longValueExact()

    // ---- Arithmetic ----------------------------------------------------------------------

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.add(other.amount), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.subtract(other.amount), currency)
    }

    operator fun times(multiplier: Int): Money =
        Money(amount.multiply(BigDecimal(multiplier)).setScale(currency.minorUnits, ROUNDING), currency)

    operator fun times(multiplier: Long): Money =
        Money(amount.multiply(BigDecimal(multiplier)).setScale(currency.minorUnits, ROUNDING), currency)

    operator fun times(multiplier: BigDecimal): Money =
        Money(amount.multiply(multiplier).setScale(currency.minorUnits, ROUNDING), currency)

    operator fun div(divisor: Int): Money {
        require(divisor != 0) { "Cannot divide money by zero" }
        return Money(amount.divide(BigDecimal(divisor), currency.minorUnits, ROUNDING), currency)
    }

    operator fun div(divisor: BigDecimal): Money {
        require(divisor.signum() != 0) { "Cannot divide money by zero" }
        return Money(amount.divide(divisor, currency.minorUnits, ROUNDING), currency)
    }

    operator fun unaryMinus(): Money = Money(amount.negate(), currency)

    fun abs(): Money = if (isNegative) -this else this

    /** Returns this amount with the sign forced negative — used when normalising expense rows. */
    fun asNegative(): Money = if (isPositive) -this else this

    /** Returns this amount with the sign forced positive — used when normalising income rows. */
    fun asPositive(): Money = abs()

    /** [percent] percent of this amount, e.g. `budget.percent(85)` for a warning threshold. */
    fun percent(percent: BigDecimal): Money =
        times(percent.divide(HUNDRED, MoneyMath.PRECISION))

    /**
     * This amount as a percentage of [total], or null when [total] is zero.
     *
     * Returned at [MoneyMath.RATIO_SCALE] so callers can render "18.4%" without re-deriving it.
     */
    fun percentageOf(total: Money): BigDecimal? {
        requireSameCurrency(total)
        if (total.isZero) return null
        return amount.multiply(HUNDRED)
            .divide(total.amount, MoneyMath.RATIO_SCALE, ROUNDING)
    }

    /**
     * Splits this amount into [parts] shares that sum back to exactly this amount.
     *
     * The remainder left by rounding is distributed one minor unit at a time across the leading
     * shares, so splitting ₹100 three ways yields 33.34 / 33.33 / 33.33 rather than three
     * amounts that quietly lose a paisa.
     */
    fun split(parts: Int): List<Money> {
        require(parts > 0) { "Cannot split money into $parts parts" }
        return allocate(List(parts) { BigDecimal.ONE })
    }

    /**
     * Distributes this amount across [weights], preserving the total exactly.
     *
     * Used for splitting a shared bill and for spreading an annual subscription cost across
     * months. Weights must be non-negative and not all zero.
     */
    fun allocate(weights: List<BigDecimal>): List<Money> {
        require(weights.isNotEmpty()) { "Cannot allocate money across no weights" }
        require(weights.all { it.signum() >= 0 }) { "Allocation weights must be non-negative" }
        val totalWeight = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        require(totalWeight.signum() > 0) { "Allocation weights must not all be zero" }

        val unit = BigDecimal.ONE.movePointLeft(currency.minorUnits)
        // Floor each share so the sum can only ever be short, never over.
        val shares = weights.map { weight ->
            amount.multiply(weight)
                .divide(totalWeight, currency.minorUnits, RoundingMode.FLOOR)
        }.toMutableList()

        var remainder = amount.subtract(shares.fold(BigDecimal.ZERO, BigDecimal::add))
        var index = 0
        // `remainder` is an exact multiple of one minor unit, so this terminates.
        while (remainder.signum() != 0 && index < shares.size) {
            if (remainder.signum() > 0) {
                shares[index] = shares[index].add(unit)
                remainder = remainder.subtract(unit)
            } else {
                shares[index] = shares[index].subtract(unit)
                remainder = remainder.add(unit)
            }
            index++
        }
        return shares.map { Money(it.setScale(currency.minorUnits, ROUNDING), currency) }
    }

    // ---- Comparison ----------------------------------------------------------------------

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    /** True when this amount is at least [other]. Reads better than `compareTo` at call sites. */
    fun atLeast(other: Money): Boolean = this >= other

    fun coerceAtLeast(minimum: Money): Money = if (this < minimum) minimum else this

    fun coerceAtMost(maximum: Money): Money = if (this > maximum) maximum else this

    /** Clamps to zero — for "remaining budget" style values that must never render negative. */
    fun floorAtZero(): Money = coerceAtLeast(zero(currency))

    // ---- Equality / conversion ------------------------------------------------------------

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        // Scale is normalised at construction, so compareTo and equals agree here.
        return currency == other.currency && amount.compareTo(other.amount) == 0
    }

    override fun hashCode(): Int = 31 * amount.stripTrailingZeros().hashCode() + currency.hashCode()

    /** Machine-readable form: `INR:1428500` (minor units). This is what persistence uses. */
    fun toStorageString(): String = "${currency.code}:$minorUnits"

    /** Plain, ungrouped decimal, e.g. `14285.00`. For CSV and debugging, never for the UI. */
    fun toPlainString(): String = amount.toPlainString()

    override fun toString(): String = "${currency.code} ${amount.toPlainString()}"

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Currency mismatch: ${currency.code} vs ${other.currency.code}. " +
                "Convert explicitly before combining amounts."
        }
    }

    companion object {
        internal val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN
        private val HUNDRED: BigDecimal = BigDecimal("100")

        fun zero(currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
            Money(BigDecimal.ZERO.setScale(currency.minorUnits), currency)

        /** Builds an amount, rounding to the currency's scale with banker's rounding. */
        fun of(amount: BigDecimal, currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
            Money(amount.setScale(currency.minorUnits, ROUNDING), currency)

        fun of(amount: Long, currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
            of(BigDecimal(amount), currency)

        fun of(amount: Int, currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
            of(BigDecimal(amount), currency)

        /**
         * Builds an amount from a decimal string such as `"1234.50"`.
         *
         * @throws NumberFormatException if [amount] is not a valid decimal.
         */
        fun of(amount: String, currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
            of(BigDecimal(amount.trim()), currency)

        /** Builds an amount from whole minor units — the inverse of [minorUnits]. */
        fun ofMinor(minorUnits: Long, currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
            Money(
                BigDecimal.valueOf(minorUnits).movePointLeft(currency.minorUnits)
                    .setScale(currency.minorUnits),
                currency,
            )

        /**
         * Rounds a high-precision intermediate result down to a real payable amount.
         *
         * This is the single sanctioned exit from raw [BigDecimal] maths back into [Money];
         * keeping it named makes those crossings easy to audit.
         */
        fun ofExact(amount: BigDecimal, currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
            of(amount, currency)

        /** Parses [toStorageString]. Returns null on malformed input rather than throwing. */
        fun fromStorageString(raw: String?): Money? {
            if (raw.isNullOrBlank()) return null
            val separator = raw.indexOf(':')
            if (separator <= 0) return null
            val currency = CurrencyCode.fromCode(raw.substring(0, separator)) ?: return null
            val minor = raw.substring(separator + 1).trim().toLongOrNull() ?: return null
            return ofMinor(minor, currency)
        }

        /**
         * Parses user-typed input such as `"1,234.50"`, `"₹1,234"`, `"1.2k"` or `"2 lakh"`.
         *
         * Returns null rather than throwing — this runs against keystrokes, so invalid input is
         * an expected state, not an error.
         */
        fun parseUserInput(raw: String?, currency: CurrencyCode = CurrencyCode.DEFAULT): Money? =
            MoneyParser.parse(raw, currency)

        /** Sums [amounts], returning zero in [currency] for an empty list. */
        fun sum(amounts: Iterable<Money>, currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
            amounts.fold(zero(currency)) { total, next -> total + next }
    }
}

/** Shared precision settings for maths that runs above the currency's own scale. */
object MoneyMath {
    /** Working precision for interest, returns and projections. */
    val PRECISION: MathContext = MathContext(24, RoundingMode.HALF_EVEN)

    /** Scale used for percentages and ratios surfaced to the UI. */
    const val RATIO_SCALE: Int = 4
}

/** Sums an iterable of amounts. Empty sums resolve to zero in [currency]. */
fun Iterable<Money>.sumOrZero(currency: CurrencyCode = CurrencyCode.DEFAULT): Money =
    Money.sum(this, currency)

/** Sums a selected amount across items. Empty sums resolve to zero in [currency]. */
inline fun <T> Iterable<T>.sumOfMoney(
    currency: CurrencyCode = CurrencyCode.DEFAULT,
    selector: (T) -> Money,
): Money = fold(Money.zero(currency)) { total, item -> total + selector(item) }

/**
 * Serialises [Money] as its compact storage string so backups stay human-readable and are
 * immune to floating point drift.
 */
object MoneySerializer : KSerializer<Money> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ai.labs32.khaata.Money", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Money) {
        encoder.encodeString(value.toStorageString())
    }

    override fun deserialize(decoder: Decoder): Money {
        val raw = decoder.decodeString()
        return Money.fromStorageString(raw)
            ?: throw IllegalArgumentException("Malformed money value: '$raw'")
    }
}
