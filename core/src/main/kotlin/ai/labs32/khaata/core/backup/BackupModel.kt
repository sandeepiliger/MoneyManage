package ai.labs32.khaata.core.backup

import ai.labs32.khaata.core.common.InstantSerializer
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AppSettings
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CreditCard
import ai.labs32.khaata.core.model.Goal
import ai.labs32.khaata.core.model.Investment
import ai.labs32.khaata.core.model.Loan
import ai.labs32.khaata.core.model.MerchantRule
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.model.Tag
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.UserProfile
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A complete backup of the user's financial data.
 *
 * This is the user's data in a form they own: readable JSON, no proprietary container, no
 * account required to restore it. Amounts are stored as `INR:142850` minor-unit strings and
 * dates as ISO-8601, so the file is inspectable and immune to floating-point drift.
 *
 * Receipt images are referenced but not embedded — they are the bulk of the data and are exported
 * alongside the JSON when the user asks for them.
 */
@Serializable
data class BackupFile(
    /** Bumped only when the shape changes incompatibly. See [BackupSerializer] for handling. */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String,
    @Serializable(with = InstantSerializer::class) val exportedAt: Instant,
    val profile: UserProfile? = null,
    val settings: AppSettings? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val recurringRules: List<RecurringRule> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val creditCards: List<CreditCard> = emptyList(),
    val loans: List<Loan> = emptyList(),
    val investments: List<Investment> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val merchantRules: List<MerchantRule> = emptyList(),
) {
    /** Row counts for the "here is what will be restored" confirmation screen. */
    fun summary(): BackupSummary = BackupSummary(
        schemaVersion = schemaVersion,
        exportedAt = exportedAt,
        appVersion = appVersion,
        accountCount = accounts.size,
        transactionCount = transactions.size,
        categoryCount = categories.size,
        budgetCount = budgets.size,
        goalCount = goals.size,
        recurringCount = recurringRules.size,
        subscriptionCount = subscriptions.size,
        loanCount = loans.size,
        investmentCount = investments.size,
        creditCardCount = creditCards.size,
        tagCount = tags.size,
        merchantRuleCount = merchantRules.size,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** What a backup contains, shown before anything is written. */
data class BackupSummary(
    val schemaVersion: Int,
    val exportedAt: Instant,
    val appVersion: String,
    val accountCount: Int,
    val transactionCount: Int,
    val categoryCount: Int,
    val budgetCount: Int,
    val goalCount: Int,
    val recurringCount: Int,
    val subscriptionCount: Int,
    val loanCount: Int,
    val investmentCount: Int,
    val creditCardCount: Int,
    val tagCount: Int,
    val merchantRuleCount: Int,
) {
    val totalRecords: Int
        get() = accountCount + transactionCount + categoryCount + budgetCount + goalCount +
            recurringCount + subscriptionCount + loanCount + investmentCount + creditCardCount +
            tagCount + merchantRuleCount
}

/**
 * How an import should treat data already on the device.
 *
 * There is no silent option. Restoring a backup can destroy months of records, so the user picks
 * explicitly and sees the row counts first.
 */
enum class ImportMode {
    /** Adds rows whose ids are not already present; leaves existing rows untouched. */
    MERGE_SKIP_EXISTING,

    /** Adds new rows and overwrites existing ones with the same id. */
    MERGE_OVERWRITE_EXISTING,

    /** Deletes everything currently stored, then imports. Requires explicit confirmation. */
    REPLACE_ALL,
}

/** The outcome of an import, reported back to the user in full. */
data class ImportResult(
    val mode: ImportMode,
    val imported: Map<String, Int>,
    val skipped: Map<String, Int>,
    /**
     * Rows rejected by validation.
     *
     * Import is deliberately lenient about individual bad rows and strict about what it accepts:
     * one malformed transaction in a 4,000-row file skips that row and reports it, rather than
     * failing the whole restore. Silently accepting it would be worse.
     */
    val rejected: List<RejectedRecord>,
) {
    val totalImported: Int get() = imported.values.sum()
    val totalSkipped: Int get() = skipped.values.sum()
    val hasRejections: Boolean get() = rejected.isNotEmpty()
}

data class RejectedRecord(
    val recordType: String,
    val recordId: String?,
    val reason: String,
)
