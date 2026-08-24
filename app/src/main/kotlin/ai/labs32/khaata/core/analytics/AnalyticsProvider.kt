package ai.labs32.khaata.core.analytics

import ai.labs32.khaata.core.logging.KhaataLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Product analytics.
 *
 * The interface is narrow on purpose. [AnalyticsEvent] is a closed set of events with typed,
 * non-identifying parameters — there is no `track(name, Map<String, Any>)` escape hatch, because
 * that is how an amount or a merchant name ends up in an analytics payload six months later
 * without anyone deciding it should.
 *
 * Nothing is recorded unless the user has turned analytics on, and it is off by default.
 */
interface AnalyticsProvider {

    /** Records [event]. A no-op when the user has not consented. */
    fun track(event: AnalyticsEvent)

    /** Reflects the user's choice. Turning it off must also discard anything already queued. */
    fun setEnabled(enabled: Boolean)

    /** Wipes any locally queued events — part of "delete all my data". */
    fun reset()
}

/**
 * The complete set of events the app may record.
 *
 * Every event answers "is this feature being used?" and nothing else. None carries an amount, a
 * balance, a merchant, a category name, an account, a date of a transaction, or any free text
 * the user typed. Counts and enum values only.
 */
sealed class AnalyticsEvent(val name: String) {

    data object AppOpened : AnalyticsEvent("app_open")

    data object OnboardingStarted : AnalyticsEvent("onboarding_started")

    data class OnboardingCompleted(
        val accountsCreated: Int,
        val budgetsCreated: Int,
    ) : AnalyticsEvent("onboarding_completed")

    /** [entryMethod] tells us whether the fast paths are actually being used. */
    data class TransactionCreated(
        val entryMethod: EntryMethod,
        val type: String,
        val hadCategorySuggestion: Boolean,
    ) : AnalyticsEvent("transaction_created")

    data object TransactionEdited : AnalyticsEvent("transaction_edited")

    data object TransactionDeleted : AnalyticsEvent("transaction_deleted")

    data class BudgetCreated(val isCategoryScoped: Boolean) : AnalyticsEvent("budget_created")

    data object GoalCreated : AnalyticsEvent("goal_created")

    data object SubscriptionViewed : AnalyticsEvent("subscription_viewed")

    data object ReportViewed : AnalyticsEvent("report_viewed")

    data class PaywallViewed(val trigger: String) : AnalyticsEvent("paywall_viewed")

    data class PurchaseStarted(val productId: String) : AnalyticsEvent("purchase_started")

    data class PurchaseCompleted(val productId: String) : AnalyticsEvent("purchase_completed")

    data class AiAssistantUsed(val provider: String, val wasAnswered: Boolean) :
        AnalyticsEvent("ai_used")

    data class BackupCreated(val recordCount: Int) : AnalyticsEvent("backup_created")

    data class ExportCreated(val format: String) : AnalyticsEvent("export_created")

    data class ImportCompleted(val importedCount: Int, val rejectedCount: Int) :
        AnalyticsEvent("import_completed")

    data class SmsImportReviewed(val accepted: Boolean) : AnalyticsEvent("sms_import_reviewed")

    data object DemoModeEnabled : AnalyticsEvent("demo_mode_enabled")

    /**
     * A handled error, for spotting broken flows.
     *
     * [context] is a fixed code such as "backup_read" — never a message, which could contain
     * a filename or user text.
     */
    data class ErrorEncountered(val context: String) : AnalyticsEvent("error_encountered")

    /** Non-identifying parameters for this event. */
    fun parameters(): Map<String, Any> = when (this) {
        is OnboardingCompleted -> mapOf(
            "accounts_created" to accountsCreated,
            "budgets_created" to budgetsCreated,
        )
        is TransactionCreated -> mapOf(
            "entry_method" to entryMethod.name,
            "type" to type,
            "had_suggestion" to hadCategorySuggestion,
        )
        is BudgetCreated -> mapOf("category_scoped" to isCategoryScoped)
        is PaywallViewed -> mapOf("trigger" to trigger)
        is PurchaseStarted -> mapOf("product_id" to productId)
        is PurchaseCompleted -> mapOf("product_id" to productId)
        is AiAssistantUsed -> mapOf("provider" to provider, "answered" to wasAnswered)
        is BackupCreated -> mapOf("record_count" to recordCount)
        is ExportCreated -> mapOf("format" to format)
        is ImportCompleted -> mapOf("imported" to importedCount, "rejected" to rejectedCount)
        is SmsImportReviewed -> mapOf("accepted" to accepted)
        is ErrorEncountered -> mapOf("context" to context)
        else -> emptyMap()
    }
}

/** How a transaction was entered, which is the main thing worth measuring about entry speed. */
enum class EntryMethod { QUICK_ADD, FULL_FORM, NATURAL_LANGUAGE, SMS_IMPORT, CSV_IMPORT, RECURRING }

/**
 * An analytics provider that records nothing.
 *
 * The default binding. No analytics backend ships in this build, so the app collects nothing at
 * all; wiring one up means providing a different binding and nothing else.
 */
@Singleton
class NoOpAnalyticsProvider @Inject constructor() : AnalyticsProvider {

    override fun track(event: AnalyticsEvent) {
        // Visible in debug so event wiring can be checked; compiled out of release.
        KhaataLog.d(TAG, "event: ${event.name}")
    }

    override fun setEnabled(enabled: Boolean) = Unit

    override fun reset() = Unit

    private companion object {
        const val TAG = "Analytics"
    }
}

/**
 * Wraps a provider so nothing is recorded without consent.
 *
 * The gate lives here rather than in each backend, so a future provider cannot forget it.
 */
class ConsentGatedAnalyticsProvider(
    private val delegate: AnalyticsProvider,
) : AnalyticsProvider {

    @Volatile
    private var enabled: Boolean = false

    override fun track(event: AnalyticsEvent) {
        if (enabled) delegate.track(event)
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        delegate.setEnabled(enabled)
        // Turning analytics off discards anything already collected, rather than merely stopping
        // new collection.
        if (!enabled) delegate.reset()
    }

    override fun reset() = delegate.reset()
}
