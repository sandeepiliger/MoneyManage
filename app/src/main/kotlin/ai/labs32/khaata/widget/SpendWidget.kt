package ai.labs32.khaata.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ai.labs32.khaata.MainActivity
import ai.labs32.khaata.R
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.MoneyStyle
import ai.labs32.khaata.core.notifications.KhaataNotifier
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The home-screen widget: what has gone out today and this month, and a button each to add a
 * spend or say one.
 *
 * Logging has to be cheaper than forgetting to. From the home screen this is one tap to the add
 * screen, or one tap straight into listening, without finding the app first.
 *
 * It honours the app lock: with a lock set and "hide amounts" on, the figures show as dots, since
 * a widget is on screen for anyone who picks up the phone.
 */
class SpendWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Two database reads: quick, but not something to do on the main thread a broadcast runs
        // on. goAsync keeps the process alive until they finish.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val figures = SpendWidget.readFigures(context)
                manager.updateAppWidget(appWidgetIds, SpendWidget.views(context, figures))
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Widget update failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SpendWidget"
    }
}

/** What the widget shows. Null amounts mean hidden. */
data class SpendWidgetFigures(val spentToday: Money?, val spentThisMonth: Money?)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpendWidgetEntryPoint {
    fun transactions(): TransactionRepository
    fun settings(): SettingsRepository
    fun profile(): ProfileRepository
    fun clock(): KhaataClock
}

object SpendWidget {

    /** Asks every placed widget to redraw. Does nothing, cheaply, when none is placed. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, SpendWidgetProvider::class.java))
        if (ids.isEmpty()) return
        context.sendBroadcast(
            Intent(context, SpendWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
        )
    }

    /** Today's and this month's spend, the same figures Home shows, or hidden behind the lock. */
    suspend fun readFigures(context: Context): SpendWidgetFigures {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, SpendWidgetEntryPoint::class.java)
        val settings = deps.settings().current()
        if (settings.lockMode != AppLockMode.OFF && settings.hideAmountsWhenLocked) {
            return SpendWidgetFigures(spentToday = null, spentThisMonth = null)
        }
        val currency = deps.profile().observe().first()?.currency ?: CurrencyCode.DEFAULT
        val today = deps.clock().today()
        val transactions = deps.transactions()
        return SpendWidgetFigures(
            spentToday = transactions.observeTotalSpend(DateRange(today, today), currency).first(),
            spentThisMonth = transactions.observeTotalSpend(DateRange.ofMonth(today), currency).first(),
        )
    }

    fun views(context: Context, figures: SpendWidgetFigures): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_spend).apply {
            setTextViewText(R.id.widget_today_amount, amountText(figures.spentToday))
            setTextViewText(R.id.widget_month_amount, amountText(figures.spentThisMonth))
            setOnClickPendingIntent(R.id.widget_figures, openApp(context, action = null, requestCode = 0))
            setOnClickPendingIntent(R.id.widget_add, openApp(context, KhaataNotifier.ACTION_QUICK_ADD, requestCode = 1))
            setOnClickPendingIntent(R.id.widget_speak, openApp(context, MainActivity.ACTION_VOICE_ADD, requestCode = 2))
        }

    private fun amountText(money: Money?): String =
        money?.let { MoneyFormatter.format(it, MoneyStyle.WHOLE) } ?: HIDDEN

    private fun openApp(context: Context, action: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(action ?: Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val HIDDEN = "••••"
}
