package ai.labs32.khaata.core.nlp

/**
 * Rewrites amounts the way people say them into the form [NaturalLanguageParser] reads.
 *
 * Typed input rarely needs this; dictated input nearly always does. A speech recogniser writes
 * what it heard, and what it heard was "five hundred", "fifteen hundred", "one lakh twenty
 * thousand" or "450 rupees" -- none of which the parser recognised as an amount, so the draft
 * came back empty or, worse, with the wrong figure. Everything here is a pure text rewrite that
 * leaves words it does not understand untouched, so a typed sentence passes through unchanged.
 */
object SpokenText {

    /** Rewrites spoken number words and currency words in [input]; see the object comment. */
    fun normalise(input: String): String {
        if (input.isBlank()) return input
        val withNumbers = numberWordsToDigits(input)
        return currencyWordsToSymbol(withNumbers)
    }

    // ---- Currency words ----------------------------------------------------------------------

    /** "450 rupees", "450 rs", "450 bucks", "₹ 450" and "rupees 450" all become "₹450". */
    private fun currencyWordsToSymbol(text: String): String =
        text
            .replace(AMOUNT_THEN_CURRENCY) { "₹" + it.groupValues[1] }
            .replace(CURRENCY_THEN_AMOUNT) { "₹" + it.groupValues[1] }

    private const val NUMBER = """(\d[\d,]*(?:\.\d{1,2})?(?:\s*(?:lakhs?|lacs?|crores?|thousand|k)\b)?)"""

    private val AMOUNT_THEN_CURRENCY = Regex(
        """$NUMBER\s*(?:rupees|rupee|rupaye|rupiya|rs\.?|bucks|/-)(?![a-z])""",
        RegexOption.IGNORE_CASE,
    )

    private val CURRENCY_THEN_AMOUNT = Regex(
        """(?:\brupees|\brupee|₹)\s+$NUMBER""",
        RegexOption.IGNORE_CASE,
    )

    // ---- Number words ------------------------------------------------------------------------

    private val UNITS = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
        "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L,
        "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L,
        "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L,
        // Hinglish one to ten. Like every unit these are rewritten only before a scale -- "do
        // sau" is 200 -- so "do" and "teen" in an ordinary sentence stay words.
        "ek" to 1L, "do" to 2L, "teen" to 3L, "char" to 4L, "chaar" to 4L, "paanch" to 5L,
        "panch" to 5L, "chhe" to 6L, "chhah" to 6L, "saat" to 7L, "aath" to 8L, "nau" to 9L,
        "das" to 10L,
    )
    private val TENS = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fourty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
    )

    /**
     * Scales in Indian usage. Lakh and crore are how amounts above a thousand are said here. The
     * Hindi scale words only count after a number, so a "sau" on its own is left alone.
     */
    private val SCALES = mapOf(
        "hundred" to 100L, "thousand" to 1_000L, "lakh" to 100_000L, "lakhs" to 100_000L,
        "lac" to 100_000L, "lacs" to 100_000L, "million" to 1_000_000L, "crore" to 10_000_000L,
        "crores" to 10_000_000L,
        // Hinglish, as an English recogniser writes it down: "5 sau", "2 hazaar".
        "sau" to 100L, "hazar" to 1_000L, "hazaar" to 1_000L, "hajar" to 1_000L, "hajaar" to 1_000L,
    )

    private val WORD = Regex("""[A-Za-z]+|\d+(?:\.\d+)?|[^A-Za-z\d]+""")

    /**
     * Replaces each run of number words with its value: "two hundred and fifty" → "250",
     * "fifteen hundred" → "1500", "four fifty" → "450", "one lakh twenty thousand" → "120000",
     * "2 thousand five hundred" → "2500".
     *
     * A run must contain a scale word or a tens word, or be a unit next to a digit-based scale,
     * before it is rewritten. A lone "one" or "two" is left as a word: "two coffees" is a
     * quantity, not ₹2, and "one plus" is a phone.
     */
    private fun numberWordsToDigits(text: String): String {
        val tokens = WORD.findAll(text).map { it.value }.toList()
        val out = StringBuilder()
        var index = 0
        while (index < tokens.size) {
            val run = readNumber(tokens, index)
            if (run == null) {
                out.append(tokens[index])
                index++
            } else {
                out.append(run.value)
                index = run.endExclusive
            }
        }
        return out.toString()
    }

    private data class NumberRun(val value: Long, val endExclusive: Int)

    /**
     * Reads the longest number starting at [start], or null when the tokens there are not a
     * number worth rewriting. Separators between words are single spaces, hyphens ("twenty-five")
     * or the word "and" after a scale ("one hundred and fifty").
     */
    private fun readNumber(tokens: List<String>, start: Int): NumberRun? {
        var total = 0L // completed groups: lakhs, thousands
        var current = 0L // the group being built: up to 999, or a larger scale's multiplier
        var sawScaleOrTens = false
        var sawWord = false
        var lastWasScale = false
        var lastNumberToken = -1
        var index = start

        fun isGap(token: String) = token.isNotEmpty() && token.all { it == ' ' || it == '-' }
        fun nextIsScale(at: Int) = nextWordIndex(tokens, at)?.let { tokens[it].lowercase() in SCALES } == true

        while (index < tokens.size) {
            val token = tokens[index]
            val word = token.lowercase()
            val low = current % 100
            when {
                word in UNITS -> {
                    // After nothing, a scale or a round tens word only: "twenty five" is 25,
                    // "five five" is not a number.
                    if (!(low == 0L || (low >= 20 && low % 10 == 0L))) break
                    if (low == 0L && current % 1000 != 0L && !lastWasScale) break
                    current += UNITS.getValue(word)
                    sawWord = true
                    lastWasScale = false
                }
                // "four fifty" is 450 and "twelve fifty" is 1,250: how prices are said here. A
                // number under a hundred followed straight by a tens word is hundreds and tens.
                word in TENS && low != 0L && current < 100 && total == 0L && !lastWasScale -> {
                    current = current * 100 + TENS.getValue(word)
                    sawWord = true
                    sawScaleOrTens = true
                    lastWasScale = false
                }
                word in TENS -> {
                    if (low != 0L) break
                    if (current % 1000 != 0L && !lastWasScale) break
                    current += TENS.getValue(word)
                    sawWord = true
                    sawScaleOrTens = true
                    lastWasScale = false
                }
                word in SCALES -> {
                    if (lastNumberToken < 0) break
                    val scale = SCALES.getValue(word)
                    if (current == 0L) current = 1L
                    if (scale == 100L) {
                        current *= 100
                    } else {
                        total += current * scale
                        current = 0
                    }
                    sawWord = true
                    sawScaleOrTens = true
                    lastWasScale = true
                }
                // "a hundred", "a thousand".
                (word == "a" || word == "an") && lastNumberToken < 0 && nextIsScale(index) -> {
                    current = 1L
                }
                // A digit run can start a spoken amount -- "2 thousand five hundred" -- but only
                // at the start and only when a scale follows; otherwise it is left for the parser.
                token.first().isDigit() && index == start && !token.contains('.') -> {
                    if (!nextIsScale(index)) return null
                    current = token.toLongOrNull() ?: return null
                }
                // Joins "one hundred and fifty"; any other "and" ends the number, so "chai fifty
                // and samosa" stays two things.
                word == "and" && lastWasScale -> {
                    val next = nextWordIndex(tokens, index)?.let { tokens[it].lowercase() }
                    if (next == null || (next !in UNITS && next !in TENS)) break
                    index++
                    continue
                }
                isGap(token) && lastNumberToken >= 0 -> {
                    index++
                    continue
                }
                else -> break
            }
            lastNumberToken = index
            index++
        }

        if (lastNumberToken < 0 || !sawWord || !sawScaleOrTens) return null
        val value = total + current
        if (value <= 0) return null
        return NumberRun(value, lastNumberToken + 1)
    }

    private fun nextWordIndex(tokens: List<String>, from: Int): Int? {
        var index = from + 1
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.any { it.isLetterOrDigit() }) return index
            if (!token.all { it == ' ' || it == '-' }) return null
            index++
        }
        return null
    }
}
