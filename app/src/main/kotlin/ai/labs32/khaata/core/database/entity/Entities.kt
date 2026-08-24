package ai.labs32.khaata.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.BudgetPeriod
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.model.CategoryKind
import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.InvestmentKind
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A monetary amount as two persisted columns.
 *
 * Embedding rather than a single converted column keeps the amount an integer count of minor
 * units, which SQLite can compare, sum and index exactly. A REAL column would be smaller to write
 * and would silently corrupt totals.
 */
data class MoneyColumns(
    @ColumnInfo(name = "minor_units") val minorUnits: Long,
    @ColumnInfo(name = "currency") val currency: String,
) {
    fun toMoney(): Money = ai.labs32.khaata.core.database.moneyOf(minorUnits, currency)

    companion object {
        fun from(money: Money): MoneyColumns =
            MoneyColumns(money.minorUnits, money.currency.code)

        fun fromOrZero(money: Money?): MoneyColumns =
            money?.let { from(it) } ?: MoneyColumns(0L, ai.labs32.khaata.core.money.CurrencyCode.DEFAULT.code)
    }
}

// -------------------------------------------------------------------------------------------
// Profile and settings
// -------------------------------------------------------------------------------------------

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String?,
    val currency: String,
    val languageTag: String,
    @ColumnInfo(name = "monthly_income_minor") val monthlyIncomeMinor: Long?,
    val monthStartDay: Int,
    val hasCompletedOnboarding: Boolean,
    val isDemoMode: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// -------------------------------------------------------------------------------------------
// Accounts
// -------------------------------------------------------------------------------------------

@Entity(
    tableName = "accounts",
    indices = [
        Index("isArchived"),
        Index("sortOrder"),
    ],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: AccountType,
    val currency: String,
    @Embedded(prefix = "opening_") val openingBalance: MoneyColumns,
    val institution: String?,
    /** Last four digits only. A full account or card number is never stored. */
    val maskedIdentifier: String?,
    val includeInNetWorth: Boolean,
    val includeInAvailableBalance: Boolean,
    val colorSeed: Int,
    val iconKey: String,
    val notes: String?,
    val sortOrder: Int,
    val isArchived: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// -------------------------------------------------------------------------------------------
// Categories
// -------------------------------------------------------------------------------------------

@Entity(
    tableName = "categories",
    indices = [
        Index("parentId"),
        Index("isArchived"),
        Index(value = ["group_name", "sortOrder"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            // Deleting a parent promotes its children to top level rather than destroying the
            // transactions filed under them.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "group_name") val group: CategoryGroup,
    val parentId: String?,
    val kind: CategoryKind,
    val iconKey: String,
    val colorSeed: Int,
    val isSystem: Boolean,
    val isArchived: Boolean,
    val sortOrder: Int,
)

// -------------------------------------------------------------------------------------------
// Transactions
// -------------------------------------------------------------------------------------------

/**
 * The ledger.
 *
 * This is the only table that grows without bound, so its indexes are chosen for the queries the
 * app actually runs rather than for completeness:
 *
 *  - `(deletedAt, occurredOn)` — every list and report filters out deleted rows and then orders
 *    or ranges by date. This composite covers both in one index.
 *  - `(accountId, occurredOn)` — balance aggregation per account.
 *  - `(categoryId, occurredOn)` — budget and category reports.
 *  - `merchantKey` — merchant search and rule matching.
 *
 * Foreign keys use RESTRICT on accounts: deleting an account that still has transactions would
 * orphan them and silently change every historical total, so the app archives instead.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["deletedAt", "occurredOn"]),
        Index(value = ["accountId", "occurredOn"]),
        Index(value = ["categoryId", "occurredOn"]),
        Index(value = ["transferAccountId"]),
        Index(value = ["merchantKey"]),
        Index(value = ["recurringRuleId"]),
        Index(value = ["isPending"]),
        Index(value = ["referenceNumber"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["transferAccountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            // A deleted category leaves its transactions uncategorised rather than deleting them.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val type: TransactionType,
    @Embedded(prefix = "amount_") val amount: MoneyColumns,
    val accountId: String,
    val transferAccountId: String?,
    val categoryId: String?,
    val merchant: String?,
    /** Normalised merchant, denormalised onto the row so search and rules avoid recomputing it. */
    val merchantKey: String?,
    val note: String?,
    val occurredOn: LocalDate,
    val tags: Set<String>,
    val receiptId: String?,
    val source: TransactionSource,
    val referenceNumber: String?,
    val recurringRuleId: String?,
    val isPending: Boolean,
    /** Soft delete, so an accidental swipe is recoverable and history stays auditable. */
    val deletedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// -------------------------------------------------------------------------------------------
// Budgets
// -------------------------------------------------------------------------------------------

@Entity(
    tableName = "budgets",
    indices = [Index("isActive"), Index("sortOrder")],
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @Embedded(prefix = "limit_") val limitAmount: MoneyColumns,
    val period: BudgetPeriod,
    /** Empty means an overall spending limit across every category. */
    val categoryIds: List<String>,
    val accountIds: List<String>,
    val anchorDate: LocalDate,
    val endDate: LocalDate?,
    val alertThresholdPercent: Int,
    val rollsOver: Boolean,
    val isActive: Boolean,
    val sortOrder: Int,
)

// -------------------------------------------------------------------------------------------
// Recurring and subscriptions
// -------------------------------------------------------------------------------------------

@Entity(
    tableName = "recurring_rules",
    indices = [Index("isActive"), Index("accountId")],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: TransactionType,
    @Embedded(prefix = "amount_") val amount: MoneyColumns,
    val accountId: String,
    val transferAccountId: String?,
    val categoryId: String?,
    val merchant: String?,
    val note: String?,
    val frequency: Frequency,
    val interval: Int,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val maxOccurrences: Int?,
    /** Last date posted to the ledger, which is what makes posting idempotent. */
    val lastPostedOn: LocalDate?,
    val autoPost: Boolean,
    val reminderDaysBefore: Int,
    val isActive: Boolean,
)

@Entity(
    tableName = "subscriptions",
    indices = [Index("isActive"), Index("nextPaymentDate"), Index("merchantKey")],
)
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    @Embedded(prefix = "amount_") val amount: MoneyColumns,
    val cycle: Frequency,
    val nextPaymentDate: LocalDate,
    val startedOn: LocalDate,
    val cancelledOn: LocalDate?,
    val categoryId: String?,
    val accountId: String?,
    val merchantKey: String?,
    val reminderDaysBefore: Int,
    val iconKey: String,
    val colorSeed: Int,
    val notes: String?,
    val isActive: Boolean,
)

// -------------------------------------------------------------------------------------------
// Credit cards, loans, investments, goals
// -------------------------------------------------------------------------------------------

@Entity(
    tableName = "credit_cards",
    indices = [Index(value = ["accountId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CreditCardEntity(
    @PrimaryKey val id: String,
    /** Unique: the linked account's balance *is* the outstanding amount, so one card per account. */
    val accountId: String,
    val cardName: String,
    val issuer: String,
    @Embedded(prefix = "limit_") val creditLimit: MoneyColumns,
    val statementDayOfMonth: Int,
    val dueDayOfMonth: Int,
    val minimumDuePercent: BigDecimal,
    @Embedded(prefix = "mindue_") val minimumDueFloor: MoneyColumns,
    val lastFourDigits: String?,
    val colorSeed: Int,
    val isActive: Boolean,
)

@Entity(
    tableName = "loans",
    indices = [Index("isClosed"), Index("accountId")],
)
data class LoanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lender: String?,
    @Embedded(prefix = "principal_") val principal: MoneyColumns,
    val annualInterestRatePercent: BigDecimal,
    val tenureMonths: Int,
    val startDate: LocalDate,
    @ColumnInfo(name = "emi_override_minor") val emiOverrideMinor: Long?,
    val emiDayOfMonth: Int,
    val accountId: String?,
    val categoryId: String?,
    val colorSeed: Int,
    val isClosed: Boolean,
)

@Entity(
    tableName = "investments",
    indices = [Index("isClosed"), Index("kind")],
)
data class InvestmentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: InvestmentKind,
    @Embedded(prefix = "invested_") val investedAmount: MoneyColumns,
    @Embedded(prefix = "current_") val currentValue: MoneyColumns,
    val startedOn: LocalDate,
    val valuedOn: LocalDate,
    val accountId: String?,
    val units: BigDecimal?,
    val folioOrSymbol: String?,
    val notes: String?,
    val colorSeed: Int,
    val isClosed: Boolean,
)

@Entity(
    tableName = "goals",
    indices = [Index("isArchived"), Index("targetDate")],
)
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    @Embedded(prefix = "target_") val targetAmount: MoneyColumns,
    @Embedded(prefix = "currentamt_") val currentAmount: MoneyColumns,
    val targetDate: LocalDate?,
    val startedOn: LocalDate,
    val achievedOn: LocalDate?,
    val accountId: String?,
    val iconKey: String,
    val colorSeed: Int,
    val notes: String?,
    val isArchived: Boolean,
)

// -------------------------------------------------------------------------------------------
// Supporting tables
// -------------------------------------------------------------------------------------------

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorSeed: Int,
    val usageCount: Int,
)

@Entity(
    tableName = "receipts",
    indices = [Index("transactionId")],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    /** Relative to the app's private files directory. Never an absolute or external path. */
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val capturedOn: LocalDate,
)

/**
 * A learned or user-set merchant rule.
 *
 * `merchantKey` is unique: one rule per merchant, so a correction replaces rather than competing
 * with what was there before.
 */
@Entity(
    tableName = "merchant_rules",
    indices = [Index(value = ["merchantKey"], unique = true)],
)
data class MerchantRuleEntity(
    @PrimaryKey val id: String,
    val merchantKey: String,
    val categoryId: String,
    val accountId: String?,
    val confidence: Int,
    val isUserDefined: Boolean,
    val isSeeded: Boolean,
)

/**
 * A generated insight the user has interacted with.
 *
 * Insights themselves are recomputed on demand rather than stored; this table only remembers
 * which ones were dismissed, so a dismissal sticks across restarts.
 */
@Entity(tableName = "insight_state", indices = [Index("dismissedAt")])
data class InsightStateEntity(
    @PrimaryKey val insightId: String,
    val dismissedAt: Instant,
    /** Period the dismissal applies to, so next month's version of an insight reappears. */
    val periodKey: String,
)

/** A notification the app has posted, so reminders are not repeated. */
@Entity(
    tableName = "notification_log",
    indices = [Index("postedAt"), Index(value = ["dedupeKey"], unique = true)],
)
data class NotificationLogEntity(
    @PrimaryKey val id: String,
    /** Identifies what the notification was about, e.g. `bill:rec-rent:2026-04-05`. */
    val dedupeKey: String,
    val channelId: String,
    val postedAt: Instant,
)

/**
 * Miscellaneous key/value state that does not warrant its own table.
 *
 * Deliberately not a dumping ground for user preferences — those live in DataStore. This is for
 * database-scoped bookkeeping such as seed versions and migration markers.
 */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Instant,
)
