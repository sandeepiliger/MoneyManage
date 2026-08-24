package ai.labs32.khaata.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
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
import ai.labs32.khaata.core.database.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Dao
interface AccountDao {

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Upsert
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Update
    suspend fun update(account: AccountEntity)

    /**
     * Removes an account outright.
     *
     * Callers must check [ai.labs32.khaata.core.database.dao.TransactionDao.countForAccount]
     * first: the foreign key is RESTRICT, so deleting an account that still has transactions
     * fails rather than silently orphaning them. Archiving is the normal path.
     */
    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("UPDATE accounts SET isArchived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Instant)

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY sortOrder, name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY isArchived, sortOrder, name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder, name")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun findById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observeById(id: String): Flow<AccountEntity?>

    /** Matches an imported transaction to an account by the masked suffix in a bank SMS. */
    @Query("SELECT * FROM accounts WHERE maskedIdentifier = :suffix AND isArchived = 0 LIMIT 1")
    suspend fun findByMaskedIdentifier(suffix: String): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts WHERE isArchived = 0")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM accounts WHERE isArchived = 0")
    suspend fun activeCount(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM accounts")
    suspend fun nextSortOrder(): Int

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    @Query("DELETE FROM accounts WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoData()
}

@Dao
interface CategoryDao {

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(categories: List<CategoryEntity>)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("UPDATE categories SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY group_name, sortOrder, name")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY group_name, sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY group_name, sortOrder, name")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE parentId IS NULL AND isArchived = 0 ORDER BY sortOrder")
    fun observeTopLevel(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId = :parentId AND isArchived = 0 ORDER BY sortOrder")
    fun observeChildren(parentId: String): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("DELETE FROM categories WHERE isSystem = 0")
    suspend fun deleteUserCategories()

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {

    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Upsert
    suspend fun upsertAll(budgets: List<BudgetEntity>)

    @Delete
    suspend fun delete(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE isActive = 1 ORDER BY sortOrder, name")
    fun observeActive(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets ORDER BY isActive DESC, sortOrder, name")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets ORDER BY sortOrder, name")
    suspend fun getAll(): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun findById(id: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE id = :id")
    fun observeById(id: String): Flow<BudgetEntity?>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM budgets")
    suspend fun nextSortOrder(): Int

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()

    @Query("DELETE FROM budgets WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoData()
}

@Dao
interface RecurringRuleDao {

    @Upsert
    suspend fun upsert(rule: RecurringRuleEntity)

    @Upsert
    suspend fun upsertAll(rules: List<RecurringRuleEntity>)

    @Delete
    suspend fun delete(rule: RecurringRuleEntity)

    @Query("SELECT * FROM recurring_rules WHERE isActive = 1 ORDER BY name")
    fun observeActive(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY isActive DESC, name")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE isActive = 1")
    suspend fun getActive(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules")
    suspend fun getAll(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun findById(id: String): RecurringRuleEntity?

    /** Advances the posting watermark, which is what makes generation idempotent. */
    @Query("UPDATE recurring_rules SET lastPostedOn = :postedOn WHERE id = :id")
    suspend fun markPosted(id: String, postedOn: LocalDate)

    @Query("DELETE FROM recurring_rules")
    suspend fun deleteAll()

    @Query("DELETE FROM recurring_rules WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoData()
}

@Dao
interface SubscriptionDao {

    @Upsert
    suspend fun upsert(subscription: SubscriptionEntity)

    @Upsert
    suspend fun upsertAll(subscriptions: List<SubscriptionEntity>)

    @Delete
    suspend fun delete(subscription: SubscriptionEntity)

    @Query("SELECT * FROM subscriptions WHERE isActive = 1 AND cancelledOn IS NULL ORDER BY nextPaymentDate")
    fun observeActive(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY isActive DESC, nextPaymentDate")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE isActive = 1 AND cancelledOn IS NULL")
    suspend fun getActive(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun findById(id: String): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE merchantKey = :merchantKey LIMIT 1")
    suspend fun findByMerchantKey(merchantKey: String): SubscriptionEntity?

    @Query("UPDATE subscriptions SET nextPaymentDate = :nextDate WHERE id = :id")
    suspend fun updateNextPaymentDate(id: String, nextDate: LocalDate)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()

    @Query("DELETE FROM subscriptions WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoData()
}

@Dao
interface CreditCardDao {

    @Upsert
    suspend fun upsert(card: CreditCardEntity)

    @Upsert
    suspend fun upsertAll(cards: List<CreditCardEntity>)

    @Delete
    suspend fun delete(card: CreditCardEntity)

    @Query("SELECT * FROM credit_cards WHERE isActive = 1")
    fun observeActive(): Flow<List<CreditCardEntity>>

    @Query("SELECT * FROM credit_cards")
    suspend fun getAll(): List<CreditCardEntity>

    @Query("SELECT * FROM credit_cards WHERE id = :id")
    suspend fun findById(id: String): CreditCardEntity?

    @Query("SELECT * FROM credit_cards WHERE accountId = :accountId LIMIT 1")
    suspend fun findByAccountId(accountId: String): CreditCardEntity?

    @Query("DELETE FROM credit_cards")
    suspend fun deleteAll()

    @Query("DELETE FROM credit_cards WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoData()
}

@Dao
interface LoanDao {

    @Upsert
    suspend fun upsert(loan: LoanEntity)

    @Upsert
    suspend fun upsertAll(loans: List<LoanEntity>)

    @Delete
    suspend fun delete(loan: LoanEntity)

    @Query("SELECT * FROM loans WHERE isClosed = 0 ORDER BY name")
    fun observeOpen(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans ORDER BY isClosed, name")
    fun observeAll(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans")
    suspend fun getAll(): List<LoanEntity>

    @Query("SELECT * FROM loans WHERE id = :id")
    suspend fun findById(id: String): LoanEntity?

    @Query("DELETE FROM loans")
    suspend fun deleteAll()

    @Query("DELETE FROM loans WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoData()
}

@Dao
interface InvestmentDao {

    @Upsert
    suspend fun upsert(investment: InvestmentEntity)

    @Upsert
    suspend fun upsertAll(investments: List<InvestmentEntity>)

    @Delete
    suspend fun delete(investment: InvestmentEntity)

    @Query("SELECT * FROM investments WHERE isClosed = 0 ORDER BY name")
    fun observeOpen(): Flow<List<InvestmentEntity>>

    @Query("SELECT * FROM investments ORDER BY isClosed, name")
    fun observeAll(): Flow<List<InvestmentEntity>>

    @Query("SELECT * FROM investments")
    suspend fun getAll(): List<InvestmentEntity>

    @Query("SELECT * FROM investments WHERE id = :id")
    suspend fun findById(id: String): InvestmentEntity?

    @Query("UPDATE investments SET current_minor_units = :valueMinor, valuedOn = :valuedOn WHERE id = :id")
    suspend fun updateValuation(id: String, valueMinor: Long, valuedOn: LocalDate)

    @Query("DELETE FROM investments")
    suspend fun deleteAll()

    @Query("DELETE FROM investments WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoData()
}

@Dao
interface GoalDao {

    @Upsert
    suspend fun upsert(goal: GoalEntity)

    @Upsert
    suspend fun upsertAll(goals: List<GoalEntity>)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE isArchived = 0 ORDER BY achievedOn IS NOT NULL, targetDate")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals ORDER BY isArchived, targetDate")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals")
    suspend fun getAll(): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun findById(id: String): GoalEntity?

    @Query(
        """
        UPDATE goals
        SET currentamt_minor_units = currentamt_minor_units + :deltaMinor
        WHERE id = :id
        """,
    )
    suspend fun addProgress(id: String, deltaMinor: Long)

    @Query("UPDATE goals SET achievedOn = :achievedOn WHERE id = :id")
    suspend fun markAchieved(id: String, achievedOn: LocalDate)

    @Query("DELETE FROM goals")
    suspend fun deleteAll()

    @Query("DELETE FROM goals WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoData()
}

@Dao
interface TagDao {

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("SELECT * FROM tags ORDER BY usageCount DESC, name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY usageCount DESC, name")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Query("UPDATE tags SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: String)

    @Query("DELETE FROM tags")
    suspend fun deleteAll()
}

@Dao
interface ReceiptDao {

    @Upsert
    suspend fun upsert(receipt: ReceiptEntity)

    @Delete
    suspend fun delete(receipt: ReceiptEntity)

    @Query("SELECT * FROM receipts WHERE transactionId = :transactionId")
    suspend fun findForTransaction(transactionId: String): List<ReceiptEntity>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun findById(id: String): ReceiptEntity?

    @Query("SELECT * FROM receipts")
    suspend fun getAll(): List<ReceiptEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM receipts")
    suspend fun totalBytes(): Long

    @Query("DELETE FROM receipts")
    suspend fun deleteAll()
}

@Dao
interface MerchantRuleDao {

    @Upsert
    suspend fun upsert(rule: MerchantRuleEntity)

    @Upsert
    suspend fun upsertAll(rules: List<MerchantRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(rules: List<MerchantRuleEntity>)

    @Delete
    suspend fun delete(rule: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rules")
    fun observeAll(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules")
    suspend fun getAll(): List<MerchantRuleEntity>

    @Query("SELECT * FROM merchant_rules WHERE merchantKey = :merchantKey LIMIT 1")
    suspend fun findByMerchantKey(merchantKey: String): MerchantRuleEntity?

    @Query("SELECT COUNT(*) FROM merchant_rules WHERE isUserDefined = 1")
    suspend fun userDefinedCount(): Int

    /** Clears learned rules while leaving the shipped set, for "forget what you learned". */
    @Query("DELETE FROM merchant_rules WHERE isSeeded = 0")
    suspend fun deleteLearned()

    @Query("DELETE FROM merchant_rules")
    suspend fun deleteAll()
}

@Dao
interface UserProfileDao {

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)

    @Query("SELECT * FROM user_profile WHERE id = :id")
    fun observeById(id: String): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = :id")
    suspend fun findById(id: String): UserProfileEntity?

    @Query("UPDATE user_profile SET hasCompletedOnboarding = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markOnboardingComplete(id: String, updatedAt: Instant)

    @Query("UPDATE user_profile SET isDemoMode = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDemoMode(id: String, enabled: Boolean, updatedAt: Instant)

    @Query("DELETE FROM user_profile")
    suspend fun deleteAll()
}

@Dao
interface InsightStateDao {

    @Upsert
    suspend fun upsert(state: InsightStateEntity)

    @Query("SELECT insightId FROM insight_state WHERE periodKey = :periodKey")
    suspend fun dismissedIdsForPeriod(periodKey: String): List<String>

    @Query("SELECT insightId FROM insight_state WHERE periodKey = :periodKey")
    fun observeDismissedIdsForPeriod(periodKey: String): Flow<List<String>>

    /** Drops dismissals from previous periods so a new month starts clean. */
    @Query("DELETE FROM insight_state WHERE periodKey != :currentPeriodKey")
    suspend fun purgeOtherPeriods(currentPeriodKey: String)

    @Query("DELETE FROM insight_state")
    suspend fun deleteAll()
}

@Dao
interface NotificationLogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: NotificationLogEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM notification_log WHERE dedupeKey = :key)")
    suspend fun exists(key: String): Boolean

    @Query("DELETE FROM notification_log WHERE postedAt < :before")
    suspend fun purgeBefore(before: Instant)

    @Query("DELETE FROM notification_log")
    suspend fun deleteAll()
}

@Dao
interface AppStateDao {

    @Upsert
    suspend fun put(entry: AppStateEntity)

    @Query("SELECT value FROM app_state WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("DELETE FROM app_state WHERE key = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM app_state")
    suspend fun deleteAll()
}
