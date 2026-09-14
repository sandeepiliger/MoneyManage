package ai.labs32.khaata.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.BudgetCalculator
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.AppSettings
import ai.labs32.khaata.core.model.BudgetStatus
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.entitlement.Feature
import ai.labs32.khaata.core.notifications.KhaataNotifier
import ai.labs32.khaata.data.backup.BackupManager
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CreditCardRepository
import ai.labs32.khaata.data.repository.EntitlementRepository
import ai.labs32.khaata.data.repository.LoanRepository
import ai.labs32.khaata.data.repository.ReceiptRepository
import ai.labs32.khaata.data.repository.RecurringRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.SubscriptionRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background work.
 *
 * Everything here runs without a network constraint, because none of it needs one — reminders,
 * recurring postings and trash cleanup are all local. That is deliberate: a reminder that only
 * fires when the phone is online would be unreliable exactly when someone is travelling.
 *
 * Every worker is idempotent. WorkManager can and does run work more than once, and a duplicated
 * rent transaction or a repeated notification is the kind of bug that makes an app untrustworthy.
 */

/**
 * Posts reminders for bills, EMIs, subscriptions and budgets.
 *
 * Runs daily. Deduplication is by a key that includes the due date, so a second run on the same
 * day posts nothing.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val recurringRepository: RecurringRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val creditCardRepository: CreditCardRepository,
    private val loanRepository: LoanRepository,
    private val budgetRepository: BudgetRepository,
    private val notifier: KhaataNotifier,
    private val clock: KhaataClock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val settings = settingsRepository.current()
        val today = clock.today()

        if (settings.billRemindersEnabled) {
            postBillReminders(today)
        }
        if (settings.budgetAlertsEnabled) {
            postBudgetAlerts()
        }
        Result.success()
    } catch (error: Exception) {
        KhaataLog.e(TAG, "Reminder worker failed", error)
        // Retried rather than failed: a transient database issue should not silently cost the
        // user a day of reminders.
        Result.retry()
    }

    private suspend fun postBillReminders(today: LocalDate) {
        val context = applicationContext

        recurringRepository.observeUpcoming(days = MAX_LEAD_DAYS).first()
            .filter { occurrence ->
                val rule = recurringRepository.findById(occurrence.ruleId) ?: return@filter false
                val daysUntil = ChronoUnit.DAYS.between(today, occurrence.dueOn)
                daysUntil in 0..rule.reminderDaysBefore.toLong()
            }
            .forEach { occurrence ->
                notifier.postBillReminder(
                    dedupeKey = "bill:${occurrence.ruleId}:${occurrence.dueOn}",
                    title = occurrence.name,
                    amount = occurrence.amount,
                    dueOn = occurrence.dueOn,
                    dueLabel = dueLabel(context, today, occurrence.dueOn),
                )
            }

        subscriptionRepository.observeUpcoming(days = MAX_LEAD_DAYS).first()
            .filter { occurrence ->
                val subscription = subscriptionRepository.findById(occurrence.ruleId)
                    ?: return@filter false
                val daysUntil = ChronoUnit.DAYS.between(today, occurrence.dueOn)
                daysUntil in 0..subscription.reminderDaysBefore.toLong()
            }
            .forEach { occurrence ->
                notifier.postBillReminder(
                    dedupeKey = "subscription:${occurrence.ruleId}:${occurrence.dueOn}",
                    title = occurrence.name,
                    amount = occurrence.amount,
                    dueOn = occurrence.dueOn,
                    dueLabel = dueLabel(context, today, occurrence.dueOn),
                )
            }

        loanRepository.observeUpcomingEmis(days = MAX_LEAD_DAYS).first()
            .filter { ChronoUnit.DAYS.between(today, it.dueOn) in 0..DEFAULT_EMI_LEAD_DAYS }
            .forEach { occurrence ->
                notifier.postBillReminder(
                    dedupeKey = "emi:${occurrence.ruleId}:${occurrence.dueOn}",
                    title = occurrence.name,
                    amount = occurrence.amount,
                    dueOn = occurrence.dueOn,
                    dueLabel = dueLabel(context, today, occurrence.dueOn),
                )
            }

        creditCardRepository.observeStatuses().first()
            .filter { status ->
                val daysUntil = status.daysUntilDue(today)
                status.statementBalance.isPositive && daysUntil in 0..CARD_LEAD_DAYS
            }
            .forEach { status ->
                notifier.postBillReminder(
                    dedupeKey = "card:${status.card.id}:${status.paymentDueOn}",
                    title = status.card.cardName,
                    amount = status.statementBalance,
                    dueOn = status.paymentDueOn,
                    dueLabel = dueLabel(context, today, status.paymentDueOn),
                )
            }
    }

    private suspend fun postBudgetAlerts() {
        val context = applicationContext
        budgetRepository.observeProgress().first()
            .filter { it.status == BudgetStatus.NEARING_LIMIT || it.status == BudgetStatus.OVERSPENT }
            .forEach { progress ->
                val body = if (progress.isOverspent) {
                    context.getString(
                        R.string.notification_budget_over,
                        MoneyFormatter.plain(progress.overspentBy),
                    )
                } else {
                    context.getString(
                        R.string.notification_budget_nearing,
                        progress.percentUsedClamped,
                    )
                }
                notifier.postBudgetAlert(
                    // The period is part of the key, so next month's alert is a new notification
                    // rather than a suppressed duplicate.
                    dedupeKey = "budget:${progress.budget.id}:${progress.period.start}:${progress.status}",
                    budgetName = progress.budget.name,
                    body = body,
                )
            }
    }

    private fun dueLabel(context: Context, today: LocalDate, dueOn: LocalDate): String =
        when (val days = ChronoUnit.DAYS.between(today, dueOn)) {
            0L -> context.getString(R.string.recurring_due_today)
            else -> context.resources.getQuantityString(
                R.plurals.recurring_due_in_days,
                days.toInt(),
                days.toInt(),
            )
        }

    companion object {
        const val NAME = "khaata_reminders"
        private const val TAG = "ReminderWorker"
        private const val MAX_LEAD_DAYS = 30
        private const val DEFAULT_EMI_LEAD_DAYS = 3L
        private const val CARD_LEAD_DAYS = 5L
    }
}

/**
 * Writes recurring transactions that are due, and rolls subscription dates forward.
 *
 * Only rules the user marked as auto-posting are written; everything else surfaces as a reminder
 * for them to confirm.
 */
@HiltWorker
class RecurringPostingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recurringRepository: RecurringRepository,
    private val subscriptionRepository: SubscriptionRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val posted = recurringRepository.postDueTransactions()
        subscriptionRepository.advancePastDue()
        KhaataLog.d(TAG, "Posted $posted recurring transaction(s)")
        Result.success()
    } catch (error: Exception) {
        KhaataLog.e(TAG, "Recurring posting failed", error)
        Result.retry()
    }

    companion object {
        const val NAME = "khaata_recurring_posting"
        private const val TAG = "RecurringPostingWorker"
    }
}

/** The daily nudge to record spending. Contains no financial data. */
@HiltWorker
class DailyReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val transactionRepository: TransactionRepository,
    private val notifier: KhaataNotifier,
    private val clock: KhaataClock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val settings = settingsRepository.current()
        if (settings.dailyReminderEnabled) {
            // Nothing is gained by nudging someone who has already logged today.
            val today = clock.today()
            val loggedToday = transactionRepository
                .getInRange(ai.labs32.khaata.core.common.DateRange(today, today))
                .any { it.source != ai.labs32.khaata.core.model.TransactionSource.RECURRING }

            if (!loggedToday) {
                notifier.postDailyReminder(dedupeKey = "daily:$today")
            }
        }
        Result.success()
    } catch (error: Exception) {
        KhaataLog.e(TAG, "Daily reminder failed", error)
        Result.success() // A missed nudge is not worth retrying.
    }

    companion object {
        const val NAME = "khaata_daily_reminder"
        private const val TAG = "DailyReminderWorker"
    }
}

/**
 * Writes a backup into the folder the user chose.
 *
 * Only runs when the feature is switched on, a folder is still granted, and the user is entitled
 * to it — each is re-checked here rather than trusted from scheduling time, because a
 * subscription can lapse and a folder grant can be revoked between runs.
 *
 * A failure is retried rather than swallowed: the whole point of this worker is that the user is
 * not watching it, so a run that quietly did nothing is worse than one that tries again.
 */
@HiltWorker
class ScheduledBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val entitlementRepository: EntitlementRepository,
    private val backupManager: BackupManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val settings = settingsRepository.current()
        val folder = settings.backupFolderUri

        when {
            !settings.scheduledBackupEnabled -> Result.success()
            folder == null -> Result.success()
            !entitlementRepository.isUnlocked(Feature.SCHEDULED_BACKUP) -> Result.success()
            else -> backupManager.writeBackupToFolder(android.net.Uri.parse(folder)).fold(
                onSuccess = {
                    KhaataLog.d(TAG, "Scheduled backup written")
                    Result.success()
                },
                onFailure = { error ->
                    // A revoked folder grant is permanent until the user picks again, so the
                    // setting is stood down rather than retried forever against a dead URI --
                    // the same reconciliation the SMS toggle does when its permission goes.
                    KhaataLog.e(TAG, "Scheduled backup failed", error)
                    if (error is SecurityException) {
                        settingsRepository.setScheduledBackupEnabled(false)
                        settingsRepository.setBackupFolderUri(null)
                        Result.success()
                    } else {
                        Result.retry()
                    }
                },
            )
        }
    } catch (error: Exception) {
        KhaataLog.e(TAG, "Scheduled backup worker failed", error)
        Result.retry()
    }

    companion object {
        const val NAME = "khaata_scheduled_backup"
        private const val TAG = "ScheduledBackupWorker"
    }
}

/**
 * Housekeeping: purges long-deleted transactions and stale notification records.
 *
 * Runs weekly. Deleted transactions are kept for 30 days so an accidental swipe is recoverable,
 * then removed for good.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val transactionRepository: TransactionRepository,
    private val notificationLogDao: ai.labs32.khaata.core.database.dao.NotificationLogDao,
    private val insightStateDao: ai.labs32.khaata.core.database.dao.InsightStateDao,
    private val receiptRepository: ReceiptRepository,
    private val clock: KhaataClock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val purged = transactionRepository.purgeOldDeleted()

        // Receipt rows go with their transaction through Room's CASCADE, which knows nothing
        // about the image files they pointed at. Without this sweep every deleted transaction
        // would leak its photos for the life of the install.
        val reclaimed = receiptRepository.purgeOrphans()
        if (reclaimed > 0) KhaataLog.d(TAG, "Reclaimed $reclaimed orphaned receipt image(s)")
        notificationLogDao.purgeBefore(clock.now().minusSeconds(NOTIFICATION_RETENTION_DAYS * 86_400))
        insightStateDao.purgeOtherPeriods(currentPeriodKey = clock.today().let { "${it.year}-${it.monthValue}" })
        KhaataLog.d(TAG, "Maintenance purged $purged transaction(s)")
        Result.success()
    } catch (error: Exception) {
        KhaataLog.e(TAG, "Maintenance failed", error)
        Result.success() // Housekeeping can wait for the next run.
    }

    companion object {
        const val NAME = "khaata_maintenance"
        private const val TAG = "MaintenanceWorker"
        private const val NOTIFICATION_RETENTION_DAYS = 60L
    }
}

/**
 * Schedules the app's background work.
 *
 * All of it is periodic and unconstrained. `KEEP` rather than `REPLACE` for the recurring jobs, so
 * re-scheduling on every launch does not reset a job's interval and postpone it indefinitely; the
 * daily reminder uses `UPDATE` because its delay changes when the user picks a different time.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: KhaataClock,
) {

    fun scheduleAll(settings: AppSettings) {
        val workManager = WorkManager.getInstance(context)

        workManager.enqueueUniquePeriodicWork(
            ReminderWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(Duration.ofHours(12))
                .setConstraints(localOnlyConstraints())
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            RecurringPostingWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecurringPostingWorker>(Duration.ofHours(12))
                .setConstraints(localOnlyConstraints())
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            MaintenanceWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(Duration.ofDays(7))
                .setConstraints(
                    Constraints.Builder()
                        // Housekeeping waits for a charger; it is never urgent.
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build(),
        )

        // Weekly, on a charger and unmetered where possible: writing the whole ledger to a cloud
        // folder is exactly the kind of thing that should not land on someone's mobile data.
        if (settings.scheduledBackupEnabled && settings.backupFolderUri != null) {
            workManager.enqueueUniquePeriodicWork(
                ScheduledBackupWorker.NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScheduledBackupWorker>(Duration.ofDays(7))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build(),
            )
        } else {
            workManager.cancelUniqueWork(ScheduledBackupWorker.NAME)
        }

        if (settings.dailyReminderEnabled) {
            workManager.enqueueUniquePeriodicWork(
                DailyReminderWorker.NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DailyReminderWorker>(Duration.ofDays(1))
                    .setInitialDelay(delayUntilReminderTime(settings.dailyReminderMinuteOfDay))
                    .setConstraints(localOnlyConstraints())
                    .build(),
            )
        } else {
            workManager.cancelUniqueWork(DailyReminderWorker.NAME)
        }
    }

    /** Cancels everything — used when the user deletes all their data. */
    fun cancelAll() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ReminderWorker.NAME)
        workManager.cancelUniqueWork(RecurringPostingWorker.NAME)
        workManager.cancelUniqueWork(DailyReminderWorker.NAME)
        workManager.cancelUniqueWork(MaintenanceWorker.NAME)
        workManager.cancelUniqueWork(ScheduledBackupWorker.NAME)
    }

    /**
     * No network required.
     *
     * Every one of these jobs is local, so requiring connectivity would only make them less
     * reliable.
     */
    private fun localOnlyConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()

    /** How long until the next occurrence of the user's chosen reminder time. */
    private fun delayUntilReminderTime(minuteOfDay: Int): Duration {
        val now = ZonedDateTime.ofInstant(clock.now(), clock.zone())
        val target = now.with(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        val next = if (target.isAfter(now)) target else target.plusDays(1)
        return Duration.between(now, next)
    }
}
