package ai.labs32.khaata.core.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The languages the app is translated into, each named in its own script so the list can be read by
 * someone who does not read the current one.
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    /** Whatever the phone is set to, falling back to English for a language the app lacks. */
    SYSTEM("", ""),
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी"),
    KANNADA("kn", "ಕನ್ನಡ"),
    TELUGU("te", "తెలుగు"),
    TAMIL("ta", "தமிழ்"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            val language = Locale.forLanguageTag(tag).language
            return entries.firstOrNull { it != SYSTEM && it.tag == language } ?: SYSTEM
        }
    }
}

/**
 * Chooses the language the app is shown in, independently of the phone's.
 *
 * On Android 13 and later this is the platform's per-app language: the system stores it, applies it
 * to every screen and to the application context, and lists it under Settings > Apps > Language, so
 * a change made there is honoured here and the other way round.
 *
 * Earlier versions have no such thing. There AndroidX applies the choice to each activity, and this
 * object keeps it in its own preferences and applies it to the application's resources as well --
 * notifications, background work and view models read strings through the application context, and
 * without this they would stay in the phone's language while the screens changed.
 */
object AppLocales {

    private const val PREFS = "khaata_locale"
    private const val KEY_TAG = "language_tag"

    /** The language currently chosen, [AppLanguage.SYSTEM] when none has been. */
    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            AppLanguage.fromTag(locales?.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag())
        } else {
            AppLanguage.fromTag(storedTag(context))
        }

    /**
     * Switches the app to [language]. Open screens are recreated in it straight away; nothing needs
     * restarting.
     */
    fun set(context: Context, language: AppLanguage) {
        val appContext = context.applicationContext
        prefs(appContext).edit(commit = true) { putString(KEY_TAG, language.tag) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Before the activities are recreated, so the first frame they draw already reads the
            // new language from the application context too.
            applyToProcess(appContext.resources, language)
        }
        AppCompatDelegate.setApplicationLocales(localeList(language))
    }

    /**
     * Applies the stored choice at process start, and again after a system configuration change
     * resets the application's resources. Does nothing on Android 13 and later, where the platform
     * does all of this itself.
     */
    fun applyStored(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val language = AppLanguage.fromTag(storedTag(context))
        applyToProcess(context.applicationContext.resources, language)
        if (AppCompatDelegate.getApplicationLocales() != localeList(language)) {
            AppCompatDelegate.setApplicationLocales(localeList(language))
        }
    }

    private fun localeList(language: AppLanguage): LocaleListCompat =
        if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }

    /** The application's resources and the default locale, which date and number formats read. */
    @Suppress("DEPRECATION") // updateConfiguration is the only way to do this below API 33.
    private fun applyToProcess(resources: Resources, language: AppLanguage) {
        val systemLocales = Resources.getSystem().configuration.locales
        val locales = if (language == AppLanguage.SYSTEM) {
            systemLocales
        } else {
            LocaleList(Locale.forLanguageTag(language.tag))
        }
        Locale.setDefault(locales[0])
        val config = Configuration(resources.configuration)
        config.setLocales(locales)
        config.setLayoutDirection(locales[0])
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun storedTag(context: Context): String? = prefs(context).getString(KEY_TAG, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
