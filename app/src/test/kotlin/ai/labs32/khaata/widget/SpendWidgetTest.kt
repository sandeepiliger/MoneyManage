package ai.labs32.khaata.widget

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home-screen widget: that it shows the right figures, in the right form, and nothing at all
 * when the user has asked for amounts to stay behind the app lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpendWidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(figures: SpendWidgetFigures) =
        SpendWidget.views(context, figures).apply(context, FrameLayout(context))

    private fun android.view.View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `the widget shows today's and this month's spending`() {
        val view = render(SpendWidgetFigures(Money.of("450"), Money.of("23120")))

        assertThat(view.text(R.id.widget_today_amount)).isEqualTo("₹450")
        assertThat(view.text(R.id.widget_month_amount)).isEqualTo("₹23,120")
        assertThat(view.text(R.id.widget_add)).isEqualTo(context.getString(R.string.widget_add))
        assertThat(view.text(R.id.widget_speak)).isEqualTo(context.getString(R.string.widget_speak))
    }

    @Test
    fun `hidden figures show as dots`() {
        val view = render(SpendWidgetFigures(spentToday = null, spentThisMonth = null))

        assertThat(view.text(R.id.widget_today_amount)).isEqualTo("••••")
        assertThat(view.text(R.id.widget_month_amount)).isEqualTo("••••")
    }

    @Test
    @Config(qualifiers = "kn")
    fun `the widget speaks the app's language`() {
        val view = render(SpendWidgetFigures(Money.of("450"), Money.of("23120")))

        assertThat(view.text(R.id.widget_speak)).isEqualTo("ಮಾತನಾಡಿ")
    }

    /** A widget is on the home screen for anyone holding the phone; the lock has to cover it. */
    @Test
    fun `with the app locked and amounts hidden, the widget reads nothing`() = runTest {
        val settings = EntryPointAccessors
            .fromApplication(context, SpendWidgetEntryPoint::class.java)
            .settings()

        assertThat(SpendWidget.readFigures(context).spentToday).isNotNull()

        settings.setLockMode(AppLockMode.PIN)
        settings.setHideAmountsWhenLocked(true)
        val locked = SpendWidget.readFigures(context)
        assertThat(locked.spentToday).isNull()
        assertThat(locked.spentThisMonth).isNull()

        // The user's own choice to show amounts even with a lock is respected.
        settings.setHideAmountsWhenLocked(false)
        assertThat(SpendWidget.readFigures(context).spentToday).isNotNull()
    }
}
