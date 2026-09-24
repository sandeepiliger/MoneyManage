package ai.labs32.khaata.core.locale

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import ai.labs32.khaata.R
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Choosing the app's language, on both sides of Android 13.
 *
 * From 13 the platform keeps the choice; before it, this app does, and must also move the
 * application's own resources -- notifications and view models read strings through them, and
 * would otherwise stay in the phone's language while the screens changed.
 */
@RunWith(RobolectricTestRunner::class)
class AppLocalesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `a language tag reads back as its language`() {
        assertThat(AppLanguage.fromTag("kn")).isEqualTo(AppLanguage.KANNADA)
        assertThat(AppLanguage.fromTag("te-IN")).isEqualTo(AppLanguage.TELUGU)
        assertThat(AppLanguage.fromTag("ta")).isEqualTo(AppLanguage.TAMIL)
        assertThat(AppLanguage.fromTag("hi")).isEqualTo(AppLanguage.HINDI)
        assertThat(AppLanguage.fromTag("en-GB")).isEqualTo(AppLanguage.ENGLISH)
        // A language the app does not have follows the phone, not a guess.
        assertThat(AppLanguage.fromTag("fr")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromTag(null)).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromTag("")).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test
    fun `nothing chosen means the phone's language`() {
        assertThat(AppLocales.current(context)).isEqualTo(AppLanguage.SYSTEM)
    }

    /** Android 12: the app keeps the choice and moves its own resources. */
    @Test
    @Config(sdk = [32])
    fun `before Android 13 the application context switches language too`() {
        AppLocales.set(context, AppLanguage.KANNADA)

        assertThat(AppLocales.current(context)).isEqualTo(AppLanguage.KANNADA)
        assertThat(context.getString(R.string.nav_home)).isEqualTo("ಹೋಮ್")
        assertThat(Locale.getDefault().language).isEqualTo("kn")

        AppLocales.set(context, AppLanguage.TELUGU)
        assertThat(context.getString(R.string.nav_home)).isEqualTo("హోమ్")

        AppLocales.set(context, AppLanguage.SYSTEM)
        assertThat(AppLocales.current(context)).isEqualTo(AppLanguage.SYSTEM)
        assertThat(context.getString(R.string.nav_home)).isEqualTo("Home")
    }

    /** A restart, or a system change that resets resources, reapplies what was chosen. */
    @Test
    @Config(sdk = [32])
    fun `before Android 13 the choice survives being reapplied at start`() {
        AppLocales.set(context, AppLanguage.TAMIL)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        AppLocales.applyStored(context)

        assertThat(AppLocales.current(context)).isEqualTo(AppLanguage.TAMIL)
        assertThat(context.getString(R.string.nav_home)).isEqualTo("முகப்பு")
        assertThat(AppCompatDelegate.getApplicationLocales().toLanguageTags()).isEqualTo("ta")
    }

    /** Android 14: the platform's per-app language holds the choice. */
    @Test
    @Config(sdk = [34])
    fun `from Android 13 the platform keeps the choice`() {
        AppLocales.set(context, AppLanguage.TAMIL)
        assertThat(AppLocales.current(context)).isEqualTo(AppLanguage.TAMIL)

        AppLocales.set(context, AppLanguage.SYSTEM)
        assertThat(AppLocales.current(context)).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test
    @Config(qualifiers = "kn")
    fun `formatted Kannada strings put the figures where they belong`() {
        assertThat(context.getString(R.string.budgets_spent_of, "₹2,000", "₹5,000")).isEqualTo("₹5,000 ರಲ್ಲಿ ₹2,000")
        assertThat(context.resources.getQuantityString(R.plurals.budgets_days_left, 3, 3)).isEqualTo("3 ದಿನಗಳು ಉಳಿದಿವೆ")
    }

    @Test
    @Config(qualifiers = "te")
    fun `formatted Telugu strings put the figures where they belong`() {
        assertThat(context.getString(R.string.home_budget_used, "Food", 80)).isEqualTo("Food బడ్జెట్ 80% వాడబడింది")
    }

    @Test
    @Config(qualifiers = "ta")
    fun `formatted Tamil strings put the figures where they belong`() {
        assertThat(context.getString(R.string.loans_instalments, 4, 36)).isEqualTo("36-இல் 4 தவணைகள்")
    }
}
