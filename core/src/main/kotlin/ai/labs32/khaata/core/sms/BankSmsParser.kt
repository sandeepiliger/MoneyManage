package ai.labs32.khaata.core.sms

import ai.labs32.khaata.core.categorize.MerchantNormaliser
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Extracts transactions from Indian bank and payment SMS.
 *
 * Runs entirely on-device. Message text is parsed in memory and never logged, never persisted
 * verbatim, and never sent anywhere — only the extracted fields (amount, merchant, date,
 * reference) are kept, and only after the user confirms them.
 *
 * The design assumption is that Indian bank SMS has no standard format but does have strong
 * shared conventions: an amount prefixed by `Rs`/`INR`, a direction word (`debited`, `credited`,
 * `spent`, `received`), a masked account suffix, and often a rail marker (`UPI`, `NEFT`, `IMPS`,
 * `POS`, `ATM`). Rather than a per-bank template list that rots the moment a bank changes its
 * wording, this matches those conventions.
 *
 * A parse is a *suggestion*. [ParsedSms.confidence] tells the UI how strongly to present it, and
 * imported rows always land pending for the user to confirm — the app never posts a transaction
 * from an SMS on its own.
 */
object BankSmsParser {

    // ---- Amount ------------------------------------------------------------------------------

    /**
     * `Rs.1,234.50` / `INR 1234` / `₹1,234.50`.
     *
     * The currency marker is required: a bare number in an SMS is far more often a reference,
     * an OTP or a balance fragment than the transaction amount.
     */
    private val AMOUNT = Regex(
        """(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    /** `1,234.50 Rs` — the reversed order some issuers use. */
    private val AMOUNT_SUFFIXED = Regex(
        """([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:rs\.?|inr)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ---- Direction ---------------------------------------------------------------------------

    private val DEBIT_WORDS = listOf(
        "debited", "debit", "spent", "paid", "withdrawn", "purchase", "deducted", "sent",
        "transferred to", "charged",
    )
    private val CREDIT_WORDS = listOf(
        "credited", "credit", "received", "deposited", "refunded", "refund", "cashback",
    )

    // ---- Structure ---------------------------------------------------------------------------

    /** Masked account or card suffix: `A/c XX1234`, `card ending 4321`. */
    private val ACCOUNT_SUFFIX = Regex(
        """(?:a/?c|acct|account|card)\s*(?:no\.?)?\s*(?:x+|\*+|ending|ending\s+with)?\s*(\d{3,4})\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Reference / UTR / RRN number. */
    private val REFERENCE = Regex(
        """(?:ref(?:erence)?(?:\s*no\.?)?|utr|rrn|txn(?:\s*id)?|transaction\s*id)\s*[:.\-#]?\s*([a-z0-9]{6,25})""",
        RegexOption.IGNORE_CASE,
    )

    /** Merchant after the common connective words. Stops at sentence or clause boundaries. */
    private val MERCHANT_AFTER = Regex(
        """(?:\bat\b|\bto\b|\bvpa\b|\btowards\b|\bfor\b|\bfrom\b)\s+([A-Za-z0-9@._\-*&' ]{2,60}?)(?=\s+(?:on|ref|upi|txn|utr|rrn|avl|available|bal|balance|info|not you|if not)\b|[.,;!]|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** Available balance, which we read but never treat as the transaction amount. */
    private val BALANCE = Regex(
        """(?:avl\.?\s*bal|available\s*balance|avlbl\s*bal|bal(?:ance)?)\s*[:.\-]?\s*(?:rs\.?|inr|₹)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    private val RAIL_MARKERS: Map<Regex, PaymentRail> = mapOf(
        Regex("""\bupi\b""", RegexOption.IGNORE_CASE) to PaymentRail.UPI,
        Regex("""\bneft\b""", RegexOption.IGNORE_CASE) to PaymentRail.NEFT,
        Regex("""\bimps\b""", RegexOption.IGNORE_CASE) to PaymentRail.IMPS,
        Regex("""\brtgs\b""", RegexOption.IGNORE_CASE) to PaymentRail.RTGS,
        Regex("""\batm\b""", RegexOption.IGNORE_CASE) to PaymentRail.ATM,
        Regex("""\bpos\b""", RegexOption.IGNORE_CASE) to PaymentRail.POS,
        Regex("""\bemi\b""", RegexOption.IGNORE_CASE) to PaymentRail.EMI,
        Regex("""\bnach\b|\becs\b|\bmandate\b""", RegexOption.IGNORE_CASE) to PaymentRail.MANDATE,
        Regex("""\bcheque\b|\bchq\b""", RegexOption.IGNORE_CASE) to PaymentRail.CHEQUE,
    )

    /**
     * Messages that mention money but are not transactions.
     *
     * Getting this wrong is worse than missing a transaction: an OTP or a promotional message
     * turned into a ₹5,000 expense is an obviously broken app, so these are rejected outright.
     */
    private val NON_TRANSACTION_MARKERS = listOf(
        "otp", "one time password", "one-time password", "do not share", "never share",
        "will be debited", "will be credited", "is due", "due on", "reminder",
        "has been requested", "requesting", "requests rs", "collect request",
        "offer", "cashback offer", "win ", "congratulations", "apply now", "click here",
        "loan offer", "pre-approved", "preapproved", "eligible for", "upgrade your",
        "failed", "declined", "unsuccessful", "reversed", "could not be processed",
        "e-mandate", "auto pay will", "statement is ready", "bill generated",
    )

    private val DATE_PATTERNS: List<Pair<Regex, DateTimeFormatter>> = listOf(
        Regex("""\b(\d{2}[-/]\d{2}[-/]\d{4})\b""") to
            DateTimeFormatter.ofPattern("dd[-][/]MM[-][/]yyyy", Locale.ENGLISH),
        Regex("""\b(\d{2}[-/]\d{2}[-/]\d{2})\b""") to
            DateTimeFormatter.ofPattern("dd[-][/]MM[-][/]yy", Locale.ENGLISH),
        Regex("""\b(\d{2}-[A-Za-z]{3}-\d{4})\b""") to
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
        Regex("""\b(\d{2}-[A-Za-z]{3}-\d{2})\b""") to
            DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
        Regex("""\b(\d{2}[A-Za-z]{3}\d{2})\b""") to
            DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH),
    )

    /**
     * Parses [body] into a transaction suggestion.
     *
     * @param body the message text. Never retained or logged by this function.
     * @param receivedOn the date the message arrived, used when the text carries no date.
     * @param sender the SMS sender id (e.g. `AD-HDFCBK`), used only as a weak hint.
     *
     * @return null when the message is not a completed transaction.
     */
    fun parse(
        body: String,
        receivedOn: LocalDate,
        sender: String? = null,
        currency: CurrencyCode = CurrencyCode.INR,
    ): ParsedSms? {
        if (body.isBlank()) return null
        val lower = body.lowercase()

        // Reject non-transactions before doing any extraction work.
        if (NON_TRANSACTION_MARKERS.any { lower.contains(it) }) return null

        val direction = detectDirection(lower) ?: return null
        val balance = BALANCE.find(body)?.groupValues?.get(1)
        val amount = extractAmount(body, balance) ?: return null

        val rail = RAIL_MARKERS.entries.firstOrNull { it.key.containsMatchIn(body) }?.value
        val merchantRaw = extractMerchant(body, direction)
        val merchantKey = MerchantNormaliser.normalise(merchantRaw)

        return ParsedSms(
            type = direction,
            amount = Money.of(amount, currency),
            merchantRaw = merchantRaw,
            merchantKey = merchantKey,
            merchantDisplayName = MerchantNormaliser.displayName(merchantRaw),
            occurredOn = extractDate(body) ?: receivedOn,
            accountSuffix = ACCOUNT_SUFFIX.find(body)?.groupValues?.get(1),
            referenceNumber = REFERENCE.find(body)?.groupValues?.get(1),
            rail = rail,
            availableBalance = balance?.let {
                runCatching { Money.of(it.replace(",", ""), currency) }.getOrNull()
            },
            sender = sender,
            confidence = scoreConfidence(merchantKey, rail, body),
        )
    }

    private fun detectDirection(lower: String): TransactionType? {
        val debitAt = DEBIT_WORDS.mapNotNull { indexOrNull(lower, it) }.minOrNull()
        val creditAt = CREDIT_WORDS.mapNotNull { indexOrNull(lower, it) }.minOrNull()
        return when {
            debitAt == null && creditAt == null -> null
            creditAt == null -> TransactionType.EXPENSE
            debitAt == null -> TransactionType.INCOME
            // Both appear ("debited ... credited to beneficiary"): the earlier word describes
            // what happened to the user's own account.
            debitAt <= creditAt -> TransactionType.EXPENSE
            else -> TransactionType.INCOME
        }
    }

    private fun indexOrNull(haystack: String, needle: String): Int? =
        haystack.indexOf(needle).takeIf { it >= 0 }

    /**
     * Finds the transaction amount, skipping the available-balance figure.
     *
     * Nearly every Indian bank SMS ends with "Avl Bal Rs.X". Picking the first currency-marked
     * number is right; picking the largest — a tempting shortcut — reports the balance as the
     * spend, which is spectacularly wrong.
     */
    private fun extractAmount(body: String, balanceText: String?): String? {
        val matches = (AMOUNT.findAll(body) + AMOUNT_SUFFIXED.findAll(body))
            .map { it.groupValues[1] to it.range.first }
            .sortedBy { it.second }
            .toList()
        if (matches.isEmpty()) return null

        val candidate = matches.firstOrNull { (value, _) -> value != balanceText } ?: return null
        val cleaned = candidate.first.replace(",", "")
        // A zero-amount "transaction" is a status message, not a spend.
        return cleaned.takeIf { it.toBigDecimalOrNull()?.signum() == 1 }
    }

    private fun extractMerchant(body: String, direction: TransactionType): String? {
        // Prefer the connective that matches the direction: money goes "to" a payee and comes
        // "from" a payer. Fall back to any connective when the preferred one is absent.
        val preferred = if (direction == TransactionType.EXPENSE) {
            listOf("at", "to", "vpa", "towards")
        } else {
            listOf("from", "by", "vpa")
        }

        val matches = MERCHANT_AFTER.findAll(body).toList()
        if (matches.isEmpty()) return null

        val preferredMatch = matches.firstOrNull { match ->
            val connective = match.value.trimStart().substringBefore(' ').lowercase()
            connective in preferred
        }
        val chosen = (preferredMatch ?: matches.first()).groupValues[1].trim()
        return chosen.takeIf { it.isNotBlank() && it.any { char -> char.isLetter() } }
    }

    private fun extractDate(body: String): LocalDate? {
        for ((pattern, formatter) in DATE_PATTERNS) {
            val raw = pattern.find(body)?.groupValues?.get(1) ?: continue
            val normalised = raw.replace('/', '-')
            try {
                return LocalDate.parse(normalised, formatter)
            } catch (_: DateTimeParseException) {
                // Try the next pattern; a malformed date is not a reason to drop the whole parse.
            }
        }
        return null
    }

    /**
     * How much to trust this parse.
     *
     * Drives presentation only: anything below [ParsedSms.REVIEW_THRESHOLD] is shown with the
     * fields highlighted for checking. Nothing is ever posted without the user's confirmation
     * regardless of score.
     */
    private fun scoreConfidence(merchantKey: String?, rail: PaymentRail?, body: String): Int {
        var score = 40
        if (merchantKey != null) score += 25
        if (rail != null) score += 15
        if (ACCOUNT_SUFFIX.containsMatchIn(body)) score += 10
        if (REFERENCE.containsMatchIn(body)) score += 10
        return score.coerceIn(0, 100)
    }
}

/** A transaction extracted from a message, pending the user's confirmation. */
data class ParsedSms(
    val type: TransactionType,
    val amount: Money,
    /** Merchant as it appeared, for display while confirming. */
    val merchantRaw: String?,
    /** Normalised key for category lookup. */
    val merchantKey: String?,
    val merchantDisplayName: String?,
    val occurredOn: LocalDate,
    /** Last 3-4 digits of the account or card, used to match an existing account. */
    val accountSuffix: String?,
    val referenceNumber: String?,
    val rail: PaymentRail?,
    /** Balance quoted by the bank. Shown for reconciliation, never used as a balance of record. */
    val availableBalance: Money?,
    val sender: String?,
    val confidence: Int,
) {
    val needsCloserReview: Boolean get() = confidence < REVIEW_THRESHOLD

    companion object {
        const val REVIEW_THRESHOLD = 65
    }
}

/** The rail a payment travelled over, where the message says. */
enum class PaymentRail {
    UPI, NEFT, IMPS, RTGS, ATM, POS, EMI, MANDATE, CHEQUE,
}
