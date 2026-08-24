package ai.labs32.khaata.core.nlp

import ai.labs32.khaata.core.categorize.MerchantNormaliser
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Turns a typed sentence into draft transactions.
 *
 * "I spent 850 on Swiggy yesterday" becomes an ₹850 expense at Swiggy, dated yesterday. A
 * sentence with several amounts — "1200 petrol and 850 groceries" — becomes several drafts.
 *
 * This is a deterministic, on-device parser, and it is deliberately the *default* path rather
 * than a cloud model. It costs nothing, works offline and on a flight, is instant on a
 * mid-range phone, and never sends a word of the user's spending to anyone. The cloud AI
 * provider is an optional enhancement layered on top (see the `ai` package), not a dependency.
 *
 * Everything it produces is a draft. [ParsedEntry.needsReview] flags the ones the UI should ask
 * about, and the confirmation screen is mandatory — the app never writes a transaction straight
 * from free text.
 */
class NaturalLanguageParser(
    private val currency: CurrencyCode = CurrencyCode.INR,
) {

    /**
     * Parses [input] into one or more drafts, relative to [today].
     *
     * Returns an empty list when no amount can be found, which the UI presents as
     * "couldn't read an amount" rather than a silent no-op.
     */
    fun parse(input: String, today: LocalDate): List<ParsedEntry> {
        if (input.isBlank()) return emptyList()

        val text = input.trim()
        val lower = text.lowercase()

        val datePhrase = DatePhraseParser.find(lower, today)
        val resolvedDate = datePhrase?.date
        val defaultType = detectType(lower) ?: TransactionType.EXPENSE

        // Digits that belong to a date must never be read as an amount: "spent 100 on 05/03"
        // is one ₹100 expense, not three. Masking with spaces keeps every index aligned with
        // the original text, so merchant extraction still works on the untouched string.
        val amountSearchText = datePhrase?.let { mask(text, it.range) } ?: text

        val segments = splitIntoSegments(text, amountSearchText)
        val entries = segments.mapNotNull { segment ->
            parseSegment(segment, defaultType, resolvedDate ?: today, resolvedDate != null)
        }

        // A sentence with one amount but several clauses ("paid rent 25000 by transfer") must
        // still yield exactly one draft, so fall back to parsing the whole string.
        if (entries.isEmpty()) {
            return listOfNotNull(
                parseSegment(
                    Segment(text, amountSearchText),
                    defaultType,
                    resolvedDate ?: today,
                    resolvedDate != null,
                ),
            )
        }
        return entries
    }

    /** Replaces [range] with spaces, preserving every other character's index. */
    private fun mask(text: String, range: IntRange): String {
        val chars = text.toCharArray()
        for (index in range) {
            if (index in chars.indices) chars[index] = ' '
        }
        return String(chars)
    }

    /**
     * A slice of the input: [text] as the user wrote it, and [searchable] with any date digits
     * masked out so they cannot be mistaken for an amount.
     */
    private data class Segment(val text: String, val searchable: String)

    private fun parseSegment(
        segment: Segment,
        defaultType: TransactionType,
        date: LocalDate,
        dateWasExplicit: Boolean,
    ): ParsedEntry? {
        val amountMatch = AMOUNT_IN_TEXT.find(segment.searchable) ?: return null
        val decimal = MoneyParser.parseDecimal(amountMatch.value) ?: return null
        if (decimal.signum() <= 0) return null

        val amount = runCatching { Money.of(decimal, currency) }.getOrNull() ?: return null

        // A segment can carry its own direction word that overrides the sentence's.
        val type = detectType(segment.text.lowercase()) ?: defaultType
        val merchant = extractMerchant(segment.text, amountMatch.range)

        return ParsedEntry(
            type = type,
            amount = amount,
            merchantRaw = merchant,
            merchantKey = MerchantNormaliser.normalise(merchant),
            merchantDisplayName = MerchantNormaliser.displayName(merchant),
            occurredOn = date,
            dateWasExplicit = dateWasExplicit,
            sourceText = segment.text.trim(),
        )
    }

    /**
     * Splits a sentence with several amounts into one segment per amount.
     *
     * "1200 petrol and 850 groceries" has to become two drafts; treating it as one loses money.
     * A single-amount sentence is returned untouched so ordinary input takes the simple path.
     */
    private fun splitIntoSegments(text: String, searchable: String): List<Segment> {
        val amounts = AMOUNT_IN_TEXT.findAll(searchable).toList()
        if (amounts.size <= 1) return listOf(Segment(text, searchable))

        val segments = ArrayList<Segment>(amounts.size)
        for (index in amounts.indices) {
            // Each segment runs from just after the previous amount to just before the next one,
            // so the words describing an amount stay attached to it.
            val start = if (index == 0) {
                0
            } else {
                boundaryBetween(searchable, amounts[index - 1].range.last, amounts[index].range.first)
            }
            val end = if (index == amounts.lastIndex) {
                text.length
            } else {
                boundaryBetween(searchable, amounts[index].range.last, amounts[index + 1].range.first)
            }
            // Both strings share an index space, so one pair of offsets slices both.
            segments += Segment(text.substring(start, end), searchable.substring(start, end))
        }
        return segments
    }

    /**
     * Finds where to cut between two amounts.
     *
     * Prefers an explicit separator ("and", a comma, "+"); otherwise splits at the midpoint of
     * the gap, which keeps each amount with its nearest descriptive words.
     */
    private fun boundaryBetween(text: String, previousEnd: Int, nextStart: Int): Int {
        val gap = text.substring(previousEnd + 1, nextStart)
        for (separator in SEGMENT_SEPARATORS) {
            val at = gap.indexOf(separator, ignoreCase = true)
            if (at >= 0) return previousEnd + 1 + at + separator.length
        }
        return previousEnd + 1 + gap.length / 2
    }

    /**
     * Works out which way the money moved.
     *
     * Priority matters more than word order here. "I paid 35000 salary today" starts with a
     * spending verb but is plainly income: `salary` names the money itself, while `paid` is a
     * generic verb people use for both directions. So a strong income noun outranks the verbs,
     * and an explicit transfer word outranks everything. Only when none of those appear does
     * position decide, on the reasoning that the first direction word usually describes what
     * happened to the user's own money.
     */
    private fun detectType(lower: String): TransactionType? {
        if (TRANSFER_WORDS.any { indexOrNull(lower, it) != null }) return TransactionType.TRANSFER
        if (STRONG_INCOME_NOUNS.any { indexOrNull(lower, it) != null }) return TransactionType.INCOME

        val incomeAt = INCOME_WORDS.mapNotNull { indexOrNull(lower, it) }.minOrNull()
        val expenseAt = EXPENSE_WORDS.mapNotNull { indexOrNull(lower, it) }.minOrNull()

        return when {
            incomeAt == null && expenseAt == null -> null
            incomeAt == null -> TransactionType.EXPENSE
            expenseAt == null -> TransactionType.INCOME
            expenseAt <= incomeAt -> TransactionType.EXPENSE
            else -> TransactionType.INCOME
        }
    }

    private fun indexOrNull(haystack: String, needle: String): Int? =
        Regex("\\b${Regex.escape(needle)}").find(haystack)?.range?.first

    /**
     * Pulls the merchant or description out of a segment.
     *
     * Everything that is not the amount, a direction word, a date phrase or a filler word is
     * treated as describing what the money was for.
     */
    private fun extractMerchant(segment: String, amountRange: IntRange): String? {
        val withoutAmount = segment.removeRange(amountRange)
        val tokens = withoutAmount
            .split(Regex("[^A-Za-z0-9@&'._-]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it.lowercase() !in STOP_WORDS }
            .filter { !it.all { char -> char.isDigit() } }

        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ")
    }

    private companion object {
        /**
         * An amount inside a sentence.
         *
         * Requires either a currency marker, a magnitude suffix, or a bare number of at least two
         * digits — so "2 coffees" is not read as ₹2 while "850" still is.
         */
        private const val MAGNITUDE = "k|l|cr|lakh|lakhs|lac|lacs|crore|crores|thousand"

        val AMOUNT_IN_TEXT = Regex(
            // Currency-marked: "Rs.850", "₹1,200", "INR 2 lakh".
            """(?:(?:rs\.?|inr|₹)\s*\d[\d,]*(?:\.\d{1,2})?(?:\s*(?:$MAGNITUDE))?)""" +
                // Magnitude-suffixed: "50k", "2.5 lakh".
                """|(?:\d[\d,]*(?:\.\d{1,2})?\s*(?:$MAGNITUDE))""" +
                // Bare, two digits or more: "850". One digit is excluded so "2 coffees" is
                // not read as an amount.
                """|(?:\b\d{2,}[\d,]*(?:\.\d{1,2})?\b)""",
            RegexOption.IGNORE_CASE,
        )

        val SEGMENT_SEPARATORS = listOf(" and ", ",", " plus ", " + ", ";", " & ")

        val EXPENSE_WORDS = listOf(
            "spent", "spend", "paid", "pay", "bought", "buy", "purchased", "gave", "expense",
            "bill", "cost", "charged",
        )
        val INCOME_WORDS = listOf("received", "receive", "got", "earned", "credited", "income")

        /**
         * Nouns that name incoming money rather than describe an action.
         *
         * These outrank direction verbs because they are unambiguous: whatever verb surrounds
         * it, "salary" is money arriving.
         */
        val STRONG_INCOME_NOUNS = listOf(
            "salary", "bonus", "refund", "cashback", "dividend", "stipend", "commission",
            "reimbursement", "payout",
        )

        val TRANSFER_WORDS = listOf("transferred", "transfer", "moved", "moving")

        /** Words that carry no merchant information. */
        val STOP_WORDS = setOf(
            "i", "we", "me", "my", "a", "an", "the", "on", "in", "at", "to", "from", "for",
            "of", "by", "with", "was", "were", "is", "are", "am", "and", "or", "rs", "inr",
            "rupees", "rupee", "today", "yesterday", "tomorrow", "last", "this", "next",
            "morning", "evening", "afternoon", "night", "week", "month", "year", "day",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
            "january", "february", "march", "april", "june", "july", "august", "september",
            "october", "november", "december",
            "spent", "spend", "paid", "pay", "bought", "buy", "purchased", "gave", "expense",
            "cost", "charged", "received", "receive", "got", "earned", "credited", "income",
            "transferred", "transfer", "moved", "moving", "some", "about", "around", "approx",
        )
    }
}

/** A draft transaction produced from free text. Never written without the user confirming. */
data class ParsedEntry(
    val type: TransactionType,
    val amount: Money,
    val merchantRaw: String?,
    val merchantKey: String?,
    val merchantDisplayName: String?,
    val occurredOn: LocalDate,
    /** True when the text named a date; false means we defaulted to today. */
    val dateWasExplicit: Boolean,
    /** The fragment this draft came from, shown on the confirmation screen. */
    val sourceText: String,
) {
    /** True when the UI should draw attention to fields the parse is least sure about. */
    val needsReview: Boolean get() = merchantKey == null || type == TransactionType.TRANSFER
}

/**
 * Resolves date phrases people actually type.
 *
 * Kept separate from the sentence parser so it can be tested exhaustively — relative dates are
 * easy to get subtly wrong, and "last Friday" landing a week off would quietly corrupt a report.
 */
internal object DatePhraseParser {

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val DAYS_AGO = Regex("""(\d{1,3})\s*days?\s*(?:ago|back)""")
    private val EXPLICIT_DATE = Regex("""\b(\d{1,2})[-/](\d{1,2})(?:[-/](\d{2,4}))?\b""")

    /**
     * Finds the date phrase in [lower], if there is one.
     *
     * The matched [DatePhrase.range] is returned even when the date itself does not resolve
     * ("45/99"), so the caller can still keep those digits out of amount detection. A malformed
     * date should cost the user a default date, not a phantom ₹45 transaction.
     */
    fun find(lower: String, today: LocalDate): DatePhrase? {
        phraseAt(lower, "day before yesterday")?.let { return DatePhrase(today.minusDays(2), it) }
        phraseAt(lower, "yesterday")?.let { return DatePhrase(today.minusDays(1), it) }
        phraseAt(lower, "today")?.let { return DatePhrase(today, it) }
        phraseAt(lower, "tomorrow")?.let { return DatePhrase(today.plusDays(1), it) }

        DAYS_AGO.find(lower)?.let { match ->
            val days = match.groupValues[1].toLongOrNull()
            if (days != null && days <= MAX_DAYS_AGO) {
                return DatePhrase(today.minusDays(days), match.range)
            }
        }

        // "last friday" / "on friday" — resolved backwards, since spending is usually logged
        // after the fact rather than in advance.
        for ((name, dayOfWeek) in WEEKDAYS) {
            val range = phraseAt(lower, name) ?: continue
            val includeToday = phraseAt(lower, "last") == null
            return DatePhrase(mostRecent(dayOfWeek, today, includeToday), range)
        }

        phraseAt(lower, "last month")?.let { return DatePhrase(today.minusMonths(1), it) }
        phraseAt(lower, "last week")?.let { return DatePhrase(today.minusWeeks(1), it) }

        EXPLICIT_DATE.find(lower)?.let { match ->
            val day = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toIntOrNull()
            val yearText = match.groupValues[3]
            val year = when {
                yearText.isEmpty() -> today.year
                yearText.length == 2 -> 2000 + yearText.toInt()
                else -> yearText.toIntOrNull()
            }
            val resolved = if (day != null && month != null && year != null) {
                runCatching { LocalDate.of(year, month, day) }.getOrNull()
            } else {
                null
            }
            // Reported either way: the span is date-shaped, so it is not money.
            return DatePhrase(resolved, match.range)
        }
        return null
    }

    private fun phraseAt(lower: String, phrase: String): IntRange? {
        val at = lower.indexOf(phrase)
        return if (at >= 0) at until (at + phrase.length) else null
    }

    private fun mostRecent(target: DayOfWeek, today: LocalDate, includeToday: Boolean): LocalDate {
        var candidate = if (includeToday) today else today.minusDays(1)
        var guard = 0
        while (candidate.dayOfWeek != target && guard < 8) {
            candidate = candidate.minusDays(1)
            guard++
        }
        return candidate
    }

    private const val MAX_DAYS_AGO = 3650L
}

/**
 * A date phrase found in free text.
 *
 * [date] is null when the phrase looked like a date but did not resolve to a real one; [range]
 * is reported regardless so those characters are never read as an amount.
 */
internal data class DatePhrase(val date: LocalDate?, val range: IntRange)
