package ai.labs32.khaata.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ai.labs32.khaata.MainActivity
import ai.labs32.khaata.R
import ai.labs32.khaata.core.notifications.KhaataNotifier

/**
 * Long-press the app icon: "Add an expense" and "Say what you spent".
 *
 * Published from code rather than a shortcuts.xml because a static file has to name the package,
 * and debug builds install under a different one. Republished whenever the app's language changes
 * so the labels follow it.
 */
object AppShortcuts {

    fun publish(context: Context) {
        val shortcuts = listOf(
            shortcut(
                context,
                id = "add",
                action = KhaataNotifier.ACTION_QUICK_ADD,
                shortLabel = R.string.shortcut_add_short,
                longLabel = R.string.shortcut_add_long,
                icon = R.drawable.ic_shortcut_add,
            ),
            shortcut(
                context,
                id = "speak",
                action = MainActivity.ACTION_VOICE_ADD,
                shortLabel = R.string.shortcut_speak_short,
                longLabel = R.string.shortcut_speak_long,
                icon = R.drawable.ic_shortcut_speak,
            ),
        )
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    private fun shortcut(
        context: Context,
        id: String,
        action: String,
        shortLabel: Int,
        longLabel: Int,
        icon: Int,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(shortLabel))
            .setLongLabel(context.getString(longLabel))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, MainActivity::class.java).setAction(action))
            .build()
}
