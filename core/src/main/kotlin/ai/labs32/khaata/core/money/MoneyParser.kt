package ai.labs32.khaata.core.money

import java.math.BigDecimal

/**
 * Parses amounts the way an Indian user actually types them.
 *
 * Handles currency symbols, both Western and Indian digit grouping, and the shorthand people
 * reach for in conversation — `1.2k`, `50k`, `2 lakh`, `1.5cr`. Everything returns null on bad
 * input rather than throwing, because this runs live against a text field.
 */
object MoneyParser {

    private val THOUSAND = BigDecimal("1000")
    private val LAKH = BigDecimal("100000")
    private val CRORE = BigDecimal("10000000")

    /** Suffix multipliers, longest-first so `lakhs` matches before `l`. */
    private val MULTIPLIERS: List<Pair<String, BigDecimal>> = listOf(
        "crores" to CRORE,
        "crore" to CRORE,
        "crs" to CRORE,
        "cr" to CRORE,
        "lakhs" to LAKH,
        "lakh" to LAKH,
        "lacs" to LAKH,
        "lac" to LAKH,
        "l" to LAKH,
        "thousand" to THOUSAND,
        "k" to THOUSAND,
    )

    private val CURRENCY_NOISE = setOf('₹', '$', '€', '£', '¥', ',', ' ', ' ', ' ')

    /**
     * Parses [raw] into an amount in [currency].
     *
     * Returns null for blank input, unparseable input, or values that do not fit a rupee amount.
     * The result is always non-negative: sign is a property of the transaction type, not of the
     * number the user typed.
     */
    fun parse(raw: String?, currency: CurrencyCode = CurrencyCode.DEFAULT): Money? {
        val decimal = parseDecimal(raw) ?: return null
        if (decimal.signum() < 0) return null
        return runCatching { Money.of(decimal, currency) }.getOrNull()
    }

    /**
     * Parses [raw] into a plain decimal, applying any `k`/`lakh`/`cr` multiplier.
     *
     * Exposed separately because the natural-language transaction parser needs the number before
     * it knows which account (and therefore which currency) the transaction belongs to.
     */
    fun parseDecimal(raw: String?): BigDecimal? {
        if (raw.isNullOrBlank()) return null

        val cleaned = buildString(raw.length) {
            for (char in raw) if (char !in CURRENCY_NOISE) append(char)
        }.lowercase().trim()
        if (cleaned.isEmpty()) return null

        // "rs"/"inr"/"rupees" prefixes are common in SMS and in typed input.
        val withoutPrefix = cleaned
            .removePrefix("inr")
            .removePrefix("rupees")
            .removePrefix("rupee")
            .removePrefix("rs.")
            .removePrefix("rs")
            .trim()
        if (withoutPrefix.isEmpty()) return null

        val (numberPart, multiplier) = splitMultiplier(withoutPrefix)
        if (numberPart.isEmpty()) return null

        // Reject anything that is not a bare decimal by this point; a stray letter means the
        // caller handed us something that was never an amount.
        if (!numberPart.matches(NUMERIC)) return null

        val base = runCatching { BigDecimal(numberPart) }.getOrNull() ?: return null
        val scaled = if (multiplier == null) base else base.multiply(multiplier)

        // Guard against absurd values that would overflow downstream minor-unit maths.
        if (scaled.abs() > MAX_SUPPORTED) return null
        return scaled
    }

    private fun splitMultiplier(input: String): Pair<String, BigDecimal?> {
        for ((suffix, multiplier) in MULTIPLIERS) {
            if (input.endsWith(suffix)) {
                val head = input.dropLast(suffix.length).trim()
                // "l" and "k" must follow a digit, otherwise a word like "lunch" would match.
                if (head.isNotEmpty() && head.last().isDigit()) return head to multiplier
            }
        }
        return input to null
    }

    private val NUMERIC = Regex("""\d+(\.\d+)?""")

    /** One lakh crore rupees — far above any realistic personal balance, below overflow. */
    private val MAX_SUPPORTED = BigDecimal("1000000000000000")
}
