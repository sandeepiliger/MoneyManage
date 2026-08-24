package ai.labs32.khaata.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ai.labs32.khaata.core.database.dao.AccountDao
import ai.labs32.khaata.core.database.dao.AppStateDao
import ai.labs32.khaata.core.database.dao.BudgetDao
import ai.labs32.khaata.core.database.dao.CategoryDao
import ai.labs32.khaata.core.database.dao.CreditCardDao
import ai.labs32.khaata.core.database.dao.GoalDao
import ai.labs32.khaata.core.database.dao.InsightStateDao
import ai.labs32.khaata.core.database.dao.InvestmentDao
import ai.labs32.khaata.core.database.dao.LoanDao
import ai.labs32.khaata.core.database.dao.MerchantRuleDao
import ai.labs32.khaata.core.database.dao.NotificationLogDao
import ai.labs32.khaata.core.database.dao.ReceiptDao
import ai.labs32.khaata.core.database.dao.RecurringRuleDao
import ai.labs32.khaata.core.database.dao.SubscriptionDao
import ai.labs32.khaata.core.database.dao.TagDao
import ai.labs32.khaata.core.database.dao.TransactionDao
import ai.labs32.khaata.core.database.dao.UserProfileDao
import ai.labs32.khaata.core.database.entity.AccountEntity
import ai.labs32.khaata.core.database.entity.AppStateEntity
import ai.labs32.khaata.core.database.entity.BudgetEntity
import ai.labs32.khaata.core.database.entity.CategoryEntity
import ai.labs32.khaata.core.database.entity.CreditCardEntity
import ai.labs32.khaata.core.database.entity.GoalEntity
import ai.labs32.khaata.core.database.entity.InsightStateEntity
import ai.labs32.khaata.core.database.entity.InvestmentEntity
import ai.labs32.khaata.core.database.entity.LoanEntity
import ai.labs32.khaata.core.database.entity.MerchantRuleEntity
import ai.labs32.khaata.core.database.entity.NotificationLogEntity
import ai.labs32.khaata.core.database.entity.ReceiptEntity
import ai.labs32.khaata.core.database.entity.RecurringRuleEntity
import ai.labs32.khaata.core.database.entity.SubscriptionEntity
import ai.labs32.khaata.core.database.entity.TagEntity
import ai.labs32.khaata.core.database.entity.TransactionEntity
import ai.labs32.khaata.core.database.entity.UserProfileEntity

/**
 * The app's local database. Everything the user records lives here and nowhere else.
 *
 * Notes on the configuration:
 *
 *  - **No destructive fallback.** `fallbackToDestructiveMigration` is never enabled. On a schema
 *    mismatch the app must fail loudly rather than silently wipe someone's financial history;
 *    every version bump ships a real [Migration] alongside it.
 *  - **Schemas are exported** (see `room.schemaLocation` in the module's build file) so migrations
 *    can be written against an actual diff and verified with `MigrationTestHelper`.
 *  - **Foreign keys are enforced**, which is what makes the RESTRICT on accounts meaningful.
 */
@Database(
    entities = [
        UserProfileEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        SubscriptionEntity::class,
        CreditCardEntity::class,
        LoanEntity::class,
        InvestmentEntity::class,
        GoalEntity::class,
        TagEntity::class,
        ReceiptEntity::class,
        MerchantRuleEntity::class,
        InsightStateEntity::class,
        NotificationLogEntity::class,
        AppStateEntity::class,
    ],
    version = KhaataDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KhaataDatabase : RoomDatabase() {

    abstract fun userProfileDao(): UserProfileDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun creditCardDao(): CreditCardDao
    abstract fun loanDao(): LoanDao
    abstract fun investmentDao(): InvestmentDao
    abstract fun goalDao(): GoalDao
    abstract fun tagDao(): TagDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun insightStateDao(): InsightStateDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun appStateDao(): AppStateDao

    companion object {
        const val VERSION = 1
        const val NAME = "khaata.db"

        /**
         * Every migration, in order.
         *
         * Version 1 is the initial schema, so this is empty. It exists now rather than later so
         * the pattern is established before the first schema change, and so
         * `MigrationTest` has something to iterate over from day one.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()
    }
}

/**
 * A worked example of the migration style this project uses, kept for reference.
 *
 * Not registered — version 1 needs no migrations. It is here because the temptation at the first
 * schema change is to reach for `fallbackToDestructiveMigration`, and having the correct pattern
 * already written makes doing it properly the path of least resistance.
 *
 * The rules: never drop a column that holds user data; add columns with a default so existing
 * rows stay valid; and when a table must be restructured, create the new one, copy every row,
 * drop the old one and rename — inside the single transaction Room already wraps this in.
 */
@Suppress("unused")
internal object MigrationExample {

    val EXAMPLE_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Additive changes need a NOT NULL default so existing rows remain valid.
            db.execSQL(
                "ALTER TABLE transactions ADD COLUMN exampleFlag INTEGER NOT NULL DEFAULT 0",
            )
            // New indexes are created explicitly; Room will verify they match the entity.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_transactions_exampleFlag " +
                    "ON transactions(exampleFlag)",
            )
        }
    }
}
