package ai.labs32.khaata.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.labs32.khaata.MainActivity
import ai.labs32.khaata.R
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.NotificationLogDao
import ai.labs32.khaata.core.database.entity.NotificationLogEntity
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.sms.ParsedSms
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels.
 *
 * Three narrow channels rather than one general one, so a user who wants bill reminders but finds
 * the daily nudge annoying can silence exactly that in system settings instead of turning
 * everything off. Importance is set per channel to match how interruptive each genuinely is.
 */
object NotificationChannels {

    const val BILLS = "khaata_bills"
    const val BUDGETS = "khaata_budgets"
    const val REMINDERS = "khaata_reminders"

    /**
     * Bank messages staged for confirmation.
     *
     * Separate from [REMINDERS] rather than reusing it. That channel is IMPORTANCE_LOW with the
     * badge off, which is right for a habit nudge and wrong for this: a transaction waiting to be
     * confirmed would sit silently in the shade with nothing on the launcher icon, so a user who
     * did not go looking saw nothing and reasonably concluded SMS reading was not working.
     *
     * A separate channel also lets someone mute daily nudges without muting money, and a new id
     * is the only way to get the new importance -- Android will not let an existing channel's
     * importance be raised.
     */
    const val IMPORTS = "khaata_imports"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                BILLS,
                context.getString(R.string.notification_channel_bills),
                // A missed EMI has a real cost, so this one is allowed to make a sound.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_bills_description)
                setShowBadge(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                BUDGETS,
                context.getString(R.string.notification_channel_budgets),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_budgets_description)
                setShowBadge(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                IMPORTS,
                context.getString(R.string.notification_channel_imports),
                // DEFAULT so it carries a badge and is actually noticed. The trade-off is a second
                // sound just after the bank's own SMS tone; if that reads as too much, lower this
                // to IMPORTANCE_LOW but keep setShowBadge(true) -- the badge is what stops a
                // staged transaction going unseen.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_imports_description)
                setShowBadge(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                REMINDERS,
                context.getString(R.string.notification_channel_reminders),
                // A habit nudge is not worth a sound; it should be there when the user looks.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_reminders_description)
                setShowBadge(false)
            },
        )
    }
}

/**
 * Posts the app's notifications.
 *
 * Two rules hold for every notification here:
 *
 *  1. **No amounts on the lock screen.** Notification content is visible to anyone holding the
 *    phone, so the public version says "your Food budget needs attention" and the amount only
 *    appears once the device is unlocked. `VISIBILITY_PRIVATE` plus a redacted public version is
 *    what makes that work.
 *  2. **Never repeated.** Each notification carries a dedupe key recorded in the database, so a
 *    worker that runs twice cannot tell the user about the same bill twice.
 */
@Singleton
class KhaataNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationLogDao: NotificationLogDao,
    private val clock: KhaataClock,
) {

    private val manager = NotificationManagerCompat.from(context)

    /** Whether the app may post notifications at all. */
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return manager.areNotificationsEnabled()
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Posts a bill or EMI reminder.
     *
     * @param dedupeKey identifies this specific reminder, e.g. `bill:rec-rent:2026-04-05`.
     * @return true if it was posted; false if permission is missing or it was already sent.
     */
    suspend fun postBillReminder(
        dedupeKey: String,
        title: String,
        amount: Money,
        dueOn: LocalDate,
        dueLabel: String,
    ): Boolean {
        if (!hasPermission()) return false
        if (notificationLogDao.exists(dedupeKey)) return false

        val notification = baseBuilder(NotificationChannels.BILLS)
            .setContentTitle(context.getString(R.string.notification_bill_title, title, dueLabel))
            .setContentText(MoneyFormatter.plain(amount))
            // The lock screen shows the title only; the amount appears after unlocking.
            .setPublicVersion(
                baseBuilder(NotificationChannels.BILLS)
                    .setContentTitle(context.getString(R.string.notification_bill_title, title, dueLabel))
                    .build(),
            )
            .addAction(
                0,
                context.getString(R.string.notification_action_record),
                quickAddIntent(),
            )
            .build()

        return post(dedupeKey, NotificationChannels.BILLS, notification)
    }

    /** Posts a budget alert. The amount is again withheld from the lock screen. */
    suspend fun postBudgetAlert(
        dedupeKey: String,
        budgetName: String,
        body: String,
    ): Boolean {
        if (!hasPermission()) return false
        if (notificationLogDao.exists(dedupeKey)) return false

        val notification = baseBuilder(NotificationChannels.BUDGETS)
            .setContentTitle(context.getString(R.string.notification_budget_title, budgetName))
            .setContentText(body)
            .setPublicVersion(
                baseBuilder(NotificationChannels.BUDGETS)
                    .setContentTitle(context.getString(R.string.notification_budget_title, budgetName))
                    .build(),
            )
            .build()

        return post(dedupeKey, NotificationChannels.BUDGETS, notification)
    }

    /** Posts the daily "record what you spent" nudge. Contains no financial data at all. */
    suspend fun postDailyReminder(dedupeKey: String): Boolean {
        if (!hasPermission()) return false
        if (notificationLogDao.exists(dedupeKey)) return false

        val notification = baseBuilder(NotificationChannels.REMINDERS)
            .setContentTitle(context.getString(R.string.notification_daily_title))
            .setContentText(context.getString(R.string.notification_daily_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                context.getString(R.string.notification_action_record),
                quickAddIntent(),
            )
            .build()

        return post(dedupeKey, NotificationChannels.REMINDERS, notification)
    }

    /**
     * Tells the user an imported transaction is waiting for them to confirm it.
     *
     * Carries the merchant and the amount in the expanded notification and neither on the lock
     * screen, and never any part of the SMS itself. Low priority: this is not urgent, it is a
     * queue the user clears when convenient, and an interrupting sound for every card swipe would
     * have the feature turned off within a day.
     */
    suspend fun notifyPendingImport(parsed: ParsedSms): Boolean {
        if (!hasPermission()) return false
        // The reference number identifies the payment, so re-parsing the same message — a carrier
        // redelivery, say — replaces the notification rather than stacking a second one.
        val dedupeKey = "import:${parsed.referenceNumber ?: parsed.hashCode()}"
        if (notificationLogDao.exists(dedupeKey)) return false

        val title = context.getString(R.string.notification_import_title)
        val notification = baseBuilder(NotificationChannels.IMPORTS)
            .setContentTitle(title)
            .setContentText(
                context.getString(
                    R.string.notification_import_body,
                    parsed.merchantDisplayName ?: context.getString(R.string.notification_import_unknown_merchant),
                    MoneyFormatter.plain(parsed.amount),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setPublicVersion(
                baseBuilder(NotificationChannels.IMPORTS).setContentTitle(title).build(),
            )
            .setContentIntent(pendingImportsIntent())
            .build()

        return post(dedupeKey, NotificationChannels.IMPORTS, notification)
    }

    /**
     * Says a payment was recognised but could not be filed against an account.
     *
     * Without this the outcome is invisible: the message parses, matches no account, and is
     * dropped with nothing written and nothing shown -- which is indistinguishable from SMS
     * reading being broken, and is the shape of the complaint that led here. It is actionable in a
     * way the other quiet outcomes are not: the fix is to record the account's last four digits,
     * and the notification opens the app so the user can.
     *
     * Deliberately *not* keyed on the message. One nudge a day is enough to explain a gap in the
     * ledger; one per unmatched SMS would be a notification per transaction, which is worse than
     * saying nothing. A promotional SMS never reaches here -- that is NotATransaction, and stays
     * silent as it should.
     */
    suspend fun notifyImportNeedsAccount(): Boolean {
        if (!hasPermission()) return false

        val dedupeKey = "import-no-account:${clock.today()}"
        if (notificationLogDao.exists(dedupeKey)) return false

        val title = context.getString(R.string.notification_import_no_account_title)
        val notification = baseBuilder(NotificationChannels.IMPORTS)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_import_no_account_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notification_import_no_account_body)),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setPublicVersion(
                baseBuilder(NotificationChannels.IMPORTS).setContentTitle(title).build(),
            )
            .build()

        return post(dedupeKey, NotificationChannels.IMPORTS, notification)
    }

    private fun baseBuilder(channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            // Private by default: content is hidden on a locked screen unless a public version
            // is supplied.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

    private suspend fun post(
        dedupeKey: String,
        channelId: String,
        notification: android.app.Notification,
    ): Boolean = try {
        // The id is derived from the dedupe key so re-posting replaces rather than stacks.
        manager.notify(dedupeKey.hashCode(), notification)
        notificationLogDao.insertIfAbsent(
            NotificationLogEntity(
                id = UUID.randomUUID().toString(),
                dedupeKey = dedupeKey,
                channelId = channelId,
                postedAt = clock.now(),
            ),
        )
        true
    } catch (error: SecurityException) {
        // Permission can be revoked between the check and the post.
        KhaataLog.w(TAG, "Notification blocked by permission state")
        false
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PENDING_INTENT_FLAGS)
    }

    private fun pendingImportsIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_REVIEW_IMPORTS)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 2, intent, PENDING_INTENT_FLAGS)
    }

    private fun quickAddIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_QUICK_ADD)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 1, intent, PENDING_INTENT_FLAGS)
    }

    /** Clears the notification history — part of "delete all my data". */
    suspend fun clearHistory() {
        manager.cancelAll()
        notificationLogDao.deleteAll()
    }

    companion object {
        const val ACTION_QUICK_ADD = "ai.labs32.khaata.action.QUICK_ADD"
        const val ACTION_REVIEW_IMPORTS = "ai.labs32.khaata.action.REVIEW_IMPORTS"

        private const val TAG = "KhaataNotifier"

        /** Immutable, because none of these intents carries data that needs filling in later. */
        private const val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}

/**
 * Re-schedules reminders after a reboot or an app update.
 *
 * Alarms and periodic work do not survive either, so without this a user who reboots their phone
 * quietly stops getting bill reminders — a failure they would never think to report.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var workScheduler: ai.labs32.khaata.core.work.WorkScheduler
    @Inject lateinit var settingsRepository: ai.labs32.khaata.data.repository.SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                workScheduler.scheduleAll(settingsRepository.current())
            } catch (error: Exception) {
                KhaataLog.e("BootReceiver", "Rescheduling failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Handles notification action buttons that should not open the app. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS -> {
                val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (id >= 0) NotificationManagerCompat.from(context).cancel(id)
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "ai.labs32.khaata.action.DISMISS_NOTIFICATION"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
