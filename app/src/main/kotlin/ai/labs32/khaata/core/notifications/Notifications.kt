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
