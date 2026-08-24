package ai.labs32.khaata.core.backup

import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * CSV export and import for transactions.
 *
 * CSV is the format people already know how to open, and exporting to it is a promise that the
 * user's data is not trapped in this app. Import accepts the same columns, plus the loose
 * variations other apps and bank statements produce.
 */
object CsvExporter {

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    val HEADERS: List<String> = listOf(
        "Date", "Type", "Amount", "Currency", "Account", "To Account",
        "Category", "Merchant", "Note", "Tags", "Reference", "Id",
    )

    /**
     * Writes [transactions] as CSV.
     *
     * Amounts are written as plain decimals with no grouping or currency symbol, so a spreadsheet
     * reads them as numbers rather than text.
     */
    fun export(
        transactions: List<Transaction>,
        accounts: List<Account>,
        categories: List<Category>,
    ): String {
        val accountNames = accounts.associate { it.id to it.name }
        val categoryNames = categories.associate { it.id to it.name }

        return buildString {
            appendLine(HEADERS.joinToString(",") { escape(it) })
            for (transaction in transactions.filter { !it.isDeleted }) {
                appendLine(
                    listOf(
                        transaction.occurredOn.format(DATE_FORMAT),
                        transaction.type.name,
                        transaction.amount.toPlainString(),
                        transaction.amount.currency.code,
                        accountNames[transaction.accountId] ?: transaction.accountId,
                        transaction.transferAccountId?.let { accountNames[it] ?: it }.orEmpty(),
                        transaction.categoryId?.let { categoryNames[it] ?: it }.orEmpty(),
                        transaction.merchant.orEmpty(),
                        transaction.note.orEmpty(),
                        transaction.tags.joinToString(";"),
                        transaction.referenceNumber.orEmpty(),
                        transaction.id,
                    ).joinToString(",") { escape(it) },
                )
            }
        }
    }

    /**
     * Quotes a CSV field.
     *
     * Also guards against formula injection: a field starting with `=`, `+`, `-` or `@` is
     * executed as a formula when the file is opened in Excel or Sheets, which turns a merchant
     * name a user typed into code running on someone else's machine. Prefixing with a single
     * quote neutralises it while still displaying the original text.
     */
    private fun escape(value: String): String {
        val guarded = if (value.isNotEmpty() && value.first() in FORMULA_TRIGGERS) {
            "'$value"
        } else {
            value
        }
        val needsQuoting = guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"${guarded.replace("\"", "\"\"")}\"" else guarded
    }

    private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
}

/**
 * Parses transaction CSV.
 *
 * Deliberately forgiving about shape and strict about values: column order and letter case vary
 * between sources, but an unparseable amount is always rejected with a reason rather than
 * guessed at.
 */
class CsvImporter(
    private val defaultCurrency: CurrencyCode = CurrencyCode.INR,
) {

    /**
     * Parses [text] into draft rows.
     *
     * Rows are returned for the user to map onto real accounts and categories before anything is
     * written — a CSV names an account "HDFC", which may or may not be the account of that name
     * already on the device.
     */
    fun parse(text: String): CsvImportResult {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) {
            return CsvImportResult(emptyList(), listOf(RejectedRecord("csv", null, "The file is empty.")))
        }

        val header = parseLine(lines.first()).map { it.trim().lowercase() }
        val columns = resolveColumns(header)
        if (columns.date == null || columns.amount == null) {
            return CsvImportResult(
                emptyList(),
                listOf(
                    RejectedRecord(
                        "csv",
                        null,
                        "Could not find a date column and an amount column in the header row.",
                    ),
                ),
            )
        }

        val rows = mutableListOf<CsvTransactionRow>()
        val rejected = mutableListOf<RejectedRecord>()

        for ((index, line) in lines.drop(1).withIndex()) {
            val lineNumber = index + 2 // 1-based, and the header took line 1.
            val fields = parseLine(line)

            val dateText = fields.getOrNull(columns.date)?.trim()
            val date = parseDate(dateText)
            if (date == null) {
                rejected += RejectedRecord("transaction", "line $lineNumber", "Unreadable date '$dateText'.")
                continue
            }

            val amountText = fields.getOrNull(columns.amount)?.trim()
            val amountDecimal = MoneyParser.parseDecimal(amountText?.removePrefix("-"))
            if (amountDecimal == null || amountDecimal.signum() <= 0) {
                rejected += RejectedRecord("transaction", "line $lineNumber", "Unreadable amount '$amountText'.")
                continue
            }

            val currency = columns.currency
                ?.let { CurrencyCode.fromCode(fields.getOrNull(it)?.trim()) }
                ?: defaultCurrency
            val amount = runCatching { Money.of(amountDecimal, currency) }.getOrNull()
            if (amount == null) {
                rejected += RejectedRecord("transaction", "line $lineNumber", "Amount '$amountText' is out of range.")
                continue
            }

            // Direction comes from an explicit type column when present, otherwise from the sign
            // of the amount — the convention bank statement exports use.
            val declaredType = columns.type?.let { parseType(fields.getOrNull(it)) }
            val type = declaredType
                ?: if (amountText?.startsWith("-") == true) TransactionType.EXPENSE else TransactionType.INCOME

            rows += CsvTransactionRow(
                lineNumber = lineNumber,
                occurredOn = date,
                type = type,
                amount = amount,
                accountName = columns.account?.let { fields.getOrNull(it)?.trim() }?.takeIf { it.isNotBlank() },
                transferAccountName = columns.toAccount?.let { fields.getOrNull(it)?.trim() }?.takeIf { it.isNotBlank() },
                categoryName = columns.category?.let { fields.getOrNull(it)?.trim() }?.takeIf { it.isNotBlank() },
                merchant = columns.merchant?.let { fields.getOrNull(it)?.trim() }?.takeIf { it.isNotBlank() },
                note = columns.note?.let { fields.getOrNull(it)?.trim() }?.takeIf { it.isNotBlank() },
                tags = columns.tags
                    ?.let { fields.getOrNull(it) }
                    ?.split(';', '|')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    .orEmpty(),
                referenceNumber = columns.reference?.let { fields.getOrNull(it)?.trim() }?.takeIf { it.isNotBlank() },
            )
        }
        return CsvImportResult(rows, rejected)
    }

    /**
     * Splits one CSV line, honouring quoted fields and doubled quotes.
     *
     * Hand-rolled rather than pulled in as a dependency: the format is small, and a parser we own
     * is a parser we can make behave predictably on the malformed input this will actually meet.
     */
    private fun parseLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                inQuotes && char == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        // Undo the export-side formula guard so a round trip is lossless.
        return fields.map { it.removePrefix("'") }
    }

    private fun resolveColumns(header: List<String>): ColumnMap {
        fun find(vararg names: String): Int? =
            header.indexOfFirst { column -> names.any { column == it } }.takeIf { it >= 0 }

        return ColumnMap(
            date = find("date", "transaction date", "value date", "txn date"),
            type = find("type", "transaction type", "dr/cr"),
            amount = find("amount", "value", "transaction amount", "debit/credit"),
            currency = find("currency", "ccy"),
            account = find("account", "from account", "wallet", "account name"),
            toAccount = find("to account", "destination", "transfer to"),
            category = find("category", "categories"),
            merchant = find("merchant", "payee", "description", "narration", "particulars"),
            note = find("note", "notes", "remarks", "comment"),
            tags = find("tags", "labels"),
            reference = find("reference", "ref", "ref no", "utr", "cheque no"),
        )
    }

    private fun parseType(raw: String?): TransactionType? {
        val value = raw?.trim()?.lowercase() ?: return null
        return when {
            value.isEmpty() -> null
            value.startsWith("exp") || value == "debit" || value == "dr" || value == "withdrawal" ->
                TransactionType.EXPENSE
            value.startsWith("inc") || value == "credit" || value == "cr" || value == "deposit" ->
                TransactionType.INCOME
            value.startsWith("trans") -> TransactionType.TRANSFER
            else -> null
        }
    }

    private fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        for (formatter in DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, formatter)
            } catch (_: DateTimeParseException) {
                // Try the next accepted format.
            }
        }
        return null
    }

    private data class ColumnMap(
        val date: Int?,
        val type: Int?,
        val amount: Int?,
        val currency: Int?,
        val account: Int?,
        val toAccount: Int?,
        val category: Int?,
        val merchant: Int?,
        val note: Int?,
        val tags: Int?,
        val reference: Int?,
    )

    private companion object {
        /** ISO first, then the day-first formats common in Indian bank exports. */
        val DATE_FORMATS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("dd-MM-yy"),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy"),
            DateTimeFormatter.ofPattern("dd MMM yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        )
    }
}

/** A parsed CSV row, before it is mapped onto real accounts and categories. */
data class CsvTransactionRow(
    val lineNumber: Int,
    val occurredOn: LocalDate,
    val type: TransactionType,
    val amount: Money,
    val accountName: String?,
    val transferAccountName: String?,
    val categoryName: String?,
    val merchant: String?,
    val note: String?,
    val tags: Set<String>,
    val referenceNumber: String?,
)

data class CsvImportResult(
    val rows: List<CsvTransactionRow>,
    val rejected: List<RejectedRecord>,
) {
    val hasRows: Boolean get() = rows.isNotEmpty()
}
