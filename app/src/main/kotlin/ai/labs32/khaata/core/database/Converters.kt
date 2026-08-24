package ai.labs32.khaata.core.database

import androidx.room.TypeConverter
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.BudgetPeriod
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.model.CategoryKind
import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.InvestmentKind
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Room type converters.
 *
 * The important decisions here are about how money and dates are stored:
 *
 *  - **Money is two columns, never one REAL.** The amount is persisted as a `Long` count of minor
 *    units and the currency as its ISO code (see `@Embedded` money fields on the entities). SQLite
 *    has no decimal type, so a REAL column would reintroduce exactly the floating-point error the
 *    domain layer is built to avoid.
 *  - **Dates are stored sortably and comparably.** [LocalDate] becomes an epoch-day `Long` so
 *    `WHERE occurredOn BETWEEN ?` uses an index and compares correctly; [Instant] becomes epoch
 *    millis for the same reason.
 */
object Converters {

    // ---- Dates -------------------------------------------------------------------------------

    @TypeConverter
    @JvmStatic
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    @JvmStatic
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    @JvmStatic
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    @JvmStatic
    fun epochMillisToInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    // ---- Decimals ----------------------------------------------------------------------------

    /**
     * Interest rates and unit counts, stored as their exact decimal string.
     *
     * Text rather than REAL for the same reason money is: `8.5` has no exact binary
     * representation, and an interest rate that drifts produces a wrong EMI.
     */
    @TypeConverter
    @JvmStatic
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    @JvmStatic
    fun stringToBigDecimal(value: String?): BigDecimal? =
        value?.let { runCatching { BigDecimal(it) }.getOrNull() }

    // ---- Currency ----------------------------------------------------------------------------

    @TypeConverter
    @JvmStatic
    fun currencyToCode(value: CurrencyCode?): String? = value?.code

    @TypeConverter
    @JvmStatic
    fun codeToCurrency(value: String?): CurrencyCode? = CurrencyCode.fromCode(value)

    // ---- Enums -------------------------------------------------------------------------------
    //
    // Stored by name rather than ordinal. An ordinal column silently remaps every existing row
    // the first time someone inserts a value into the middle of an enum, which in this app would
    // turn expenses into transfers. Names survive reordering.
    //
    // Each `from*` converter falls back to a safe default rather than throwing, so one unreadable
    // row cannot take down a whole query.

    @TypeConverter
    @JvmStatic
    fun accountTypeToString(value: AccountType?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToAccountType(value: String?): AccountType =
        value?.let { name -> AccountType.entries.firstOrNull { it.name == name } } ?: AccountType.OTHER

    @TypeConverter
    @JvmStatic
    fun transactionTypeToString(value: TransactionType?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToTransactionType(value: String?): TransactionType =
        value?.let { name -> TransactionType.entries.firstOrNull { it.name == name } }
            ?: TransactionType.EXPENSE

    @TypeConverter
    @JvmStatic
    fun transactionSourceToString(value: TransactionSource?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToTransactionSource(value: String?): TransactionSource =
        value?.let { name -> TransactionSource.entries.firstOrNull { it.name == name } }
            ?: TransactionSource.MANUAL

    @TypeConverter
    @JvmStatic
    fun categoryGroupToString(value: CategoryGroup?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToCategoryGroup(value: String?): CategoryGroup =
        value?.let { name -> CategoryGroup.entries.firstOrNull { it.name == name } }
            ?: CategoryGroup.OTHER

    @TypeConverter
    @JvmStatic
    fun categoryKindToString(value: CategoryKind?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToCategoryKind(value: String?): CategoryKind =
        value?.let { name -> CategoryKind.entries.firstOrNull { it.name == name } }
            ?: CategoryKind.EXPENSE

    @TypeConverter
    @JvmStatic
    fun frequencyToString(value: Frequency?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToFrequency(value: String?): Frequency =
        value?.let { name -> Frequency.entries.firstOrNull { it.name == name } } ?: Frequency.MONTHLY

    @TypeConverter
    @JvmStatic
    fun budgetPeriodToString(value: BudgetPeriod?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToBudgetPeriod(value: String?): BudgetPeriod =
        value?.let { name -> BudgetPeriod.entries.firstOrNull { it.name == name } }
            ?: BudgetPeriod.MONTHLY

    @TypeConverter
    @JvmStatic
    fun investmentKindToString(value: InvestmentKind?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToInvestmentKind(value: String?): InvestmentKind =
        value?.let { name -> InvestmentKind.entries.firstOrNull { it.name == name } }
            ?: InvestmentKind.OTHER

    // ---- Tags --------------------------------------------------------------------------------

    /**
     * Tags are stored as a delimited string on the transaction row.
     *
     * A join table would be more normalised, but tags are always read with their transaction and
     * never queried across rows in bulk, so the join would cost a query on the hottest path in
     * the app for no benefit. The delimiter is a unit separator, which cannot appear in a tag.
     */
    @TypeConverter
    @JvmStatic
    fun tagsToString(value: Set<String>?): String {
        val tags = value?.filter { it.isNotBlank() }.orEmpty()
        if (tags.isEmpty()) return ""
        // Wrapped in delimiters at both ends so `tags LIKE '%<US>work<US>%'` matches the tag
        // "work" exactly and never the tag "workshop".
        return tags.joinToString(
            separator = TAG_DELIMITER,
            prefix = TAG_DELIMITER,
            postfix = TAG_DELIMITER,
        )
    }

    /** Builds the pattern fragment [tagsToString] can be matched against with LIKE. */
    @JvmStatic
    fun tagMatchPattern(tag: String): String = "$TAG_DELIMITER$tag$TAG_DELIMITER"

    @TypeConverter
    @JvmStatic
    fun stringToTags(value: String?): Set<String> =
        value?.split(TAG_DELIMITER)?.filter { it.isNotBlank() }?.toSet().orEmpty()

    @TypeConverter
    @JvmStatic
    fun stringListToString(value: List<String>?): String =
        value?.filter { it.isNotBlank() }?.joinToString(TAG_DELIMITER).orEmpty()

    @TypeConverter
    @JvmStatic
    fun stringToStringList(value: String?): List<String> =
        value?.split(TAG_DELIMITER)?.filter { it.isNotBlank() }.orEmpty()

    /** ASCII unit separator — not typeable, so it can never collide with a tag's own text. */
    const val TAG_DELIMITER: String = "\u001F"
}

/**
 * Builds a [Money] from the two columns an `@Embedded` money field persists.
 *
 * Defensive by design: a null or unknown currency falls back to the default rather than throwing,
 * because a single corrupt row should degrade one figure, not crash the transaction list.
 */
fun moneyOf(minorUnits: Long?, currencyCode: String?): Money {
    val currency = CurrencyCode.fromCodeOrDefault(currencyCode)
    return Money.ofMinor(minorUnits ?: 0L, currency)
}

/** Nullable counterpart of [moneyOf] — returns null when the amount column is null. */
fun moneyOrNull(minorUnits: Long?, currencyCode: String?): Money? =
    minorUnits?.let { moneyOf(it, currencyCode) }
