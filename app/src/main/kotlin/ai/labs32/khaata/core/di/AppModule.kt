package ai.labs32.khaata.core.di

import android.content.Context
import androidx.room.Room
import ai.labs32.khaata.BuildConfig
import ai.labs32.khaata.core.ads.AdConfigProvider
import ai.labs32.khaata.core.ads.AdProvider
import ai.labs32.khaata.core.ads.AdMobAdProvider
import ai.labs32.khaata.core.ads.NoOpAdProvider
import ai.labs32.khaata.core.ads.StaticAdConfigProvider
import ai.labs32.khaata.core.ai.CloudAiConfig
import ai.labs32.khaata.core.ai.FinancialAiService
import ai.labs32.khaata.core.ai.LocalFinancialAiService
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.analytics.ConsentGatedAnalyticsProvider
import ai.labs32.khaata.core.analytics.NoOpAnalyticsProvider
import ai.labs32.khaata.core.billing.BillingProvider
import ai.labs32.khaata.core.billing.NoOpBillingProvider
import ai.labs32.khaata.core.billing.PlayBillingProvider
import ai.labs32.khaata.core.categorize.MerchantCategorizer
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.common.SystemKhaataClock
import ai.labs32.khaata.core.database.KhaataDatabase
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
import ai.labs32.khaata.core.entitlement.EntitlementManager
import ai.labs32.khaata.core.insights.InsightEngine
import ai.labs32.khaata.core.nlp.NaturalLanguageParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Bindings for the whole app.
 *
 * Everything pluggable is bound here and nowhere else, so what the app actually uses in a given
 * build is answerable by reading one file. The debug/release split for ads and billing is
 * deliberate: development never shows an ad and never touches a real purchase flow.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ---- Database ----------------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KhaataDatabase =
        Room.databaseBuilder(context, KhaataDatabase::class.java, KhaataDatabase.NAME)
            .addMigrations(*KhaataDatabase.MIGRATIONS)
            // Deliberately no fallbackToDestructiveMigration: silently wiping someone's financial
            // history on a schema mismatch is never the right failure mode.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun provideUserProfileDao(db: KhaataDatabase): UserProfileDao = db.userProfileDao()
    @Provides fun provideAccountDao(db: KhaataDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: KhaataDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTransactionDao(db: KhaataDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideBudgetDao(db: KhaataDatabase): BudgetDao = db.budgetDao()
    @Provides fun provideRecurringDao(db: KhaataDatabase): RecurringRuleDao = db.recurringRuleDao()
    @Provides fun provideSubscriptionDao(db: KhaataDatabase): SubscriptionDao = db.subscriptionDao()
    @Provides fun provideCreditCardDao(db: KhaataDatabase): CreditCardDao = db.creditCardDao()
    @Provides fun provideLoanDao(db: KhaataDatabase): LoanDao = db.loanDao()
    @Provides fun provideInvestmentDao(db: KhaataDatabase): InvestmentDao = db.investmentDao()
    @Provides fun provideGoalDao(db: KhaataDatabase): GoalDao = db.goalDao()
    @Provides fun provideTagDao(db: KhaataDatabase): TagDao = db.tagDao()
    @Provides fun provideReceiptDao(db: KhaataDatabase): ReceiptDao = db.receiptDao()
    @Provides fun provideMerchantRuleDao(db: KhaataDatabase): MerchantRuleDao = db.merchantRuleDao()
    @Provides fun provideInsightStateDao(db: KhaataDatabase): InsightStateDao = db.insightStateDao()
    @Provides fun provideNotificationLogDao(db: KhaataDatabase): NotificationLogDao =
        db.notificationLogDao()
    @Provides fun provideAppStateDao(db: KhaataDatabase): AppStateDao = db.appStateDao()

    // ---- Domain services ---------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideClock(): KhaataClock = SystemKhaataClock()

    @Provides
    @Singleton
    fun provideMerchantCategorizer(): MerchantCategorizer = MerchantCategorizer()

    @Provides
    @Singleton
    fun provideNaturalLanguageParser(): NaturalLanguageParser = NaturalLanguageParser()

    @Provides
    @Singleton
    fun provideInsightEngine(): InsightEngine = InsightEngine()

    @Provides
    @Singleton
    fun provideEntitlementManager(): EntitlementManager = EntitlementManager()

    // ---- AI ----------------------------------------------------------------------------------

    /**
     * The on-device assistant, always available.
     *
     * Qualified because a cloud provider is bound alongside it rather than instead of it: the
     * assistant falls back to this whenever cloud AI is unavailable, unconfigured, or the user
     * has not consented.
     */
    @Provides
    @Singleton
    @LocalAi
    fun provideLocalAi(): FinancialAiService = LocalFinancialAiService()

    /**
     * Cloud AI configuration, or null when the build has none.
     *
     * No endpoint or key ships with the app, so this is null in a default build and the cloud
     * option stays disabled in settings.
     */
    @Provides
    @Singleton
    fun provideCloudAiConfig(): CloudAiConfig? {
        if (BuildConfig.CLOUD_AI_ENDPOINT.isBlank() || BuildConfig.CLOUD_AI_API_KEY.isBlank()) {
            return null
        }
        return runCatching {
            CloudAiConfig(
                endpoint = BuildConfig.CLOUD_AI_ENDPOINT,
                apiKey = BuildConfig.CLOUD_AI_API_KEY,
                model = BuildConfig.CLOUD_AI_MODEL,
            )
        }.getOrNull()
    }

    // ---- Pluggable services ------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideAnalyticsProvider(noOp: NoOpAnalyticsProvider): AnalyticsProvider =
        // No analytics backend ships in this build. The consent gate wraps whatever is bound, so
        // adding one later cannot accidentally bypass it.
        ConsentGatedAnalyticsProvider(noOp)

    @Provides
    @Singleton
    fun provideBillingProvider(playBilling: dagger.Lazy<PlayBillingProvider>): BillingProvider =
        // Debug builds never touch a real purchase flow.
        if (BuildConfig.DEBUG) NoOpBillingProvider() else playBilling.get()

    @Provides
    @Singleton
    fun provideAdConfigProvider(): AdConfigProvider = StaticAdConfigProvider()

    @Provides
    @Singleton
    fun provideAdProvider(
        noOp: NoOpAdProvider,
        adMob: dagger.Lazy<AdMobAdProvider>,
    ): AdProvider = if (BuildConfig.DEBUG) noOp else adMob.get()
}

/** Marks the on-device AI implementation, which is bound alongside the optional cloud one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalAi
