package ai.labs32.khaata.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import ai.labs32.khaata.core.model.ThemePreference

private val LightColorScheme = lightColorScheme(
    primary = KhaataPalette.Indigo40,
    onPrimary = KhaataPalette.Neutral100,
    primaryContainer = KhaataPalette.Indigo90,
    onPrimaryContainer = KhaataPalette.Indigo10,
    inversePrimary = KhaataPalette.Indigo70,

    secondary = KhaataPalette.Brass40,
    onSecondary = KhaataPalette.Neutral100,
    secondaryContainer = KhaataPalette.Brass90,
    onSecondaryContainer = KhaataPalette.Brass10,

    tertiary = KhaataPalette.Teal40,
    onTertiary = KhaataPalette.Neutral100,
    tertiaryContainer = KhaataPalette.Teal90,
    onTertiaryContainer = KhaataPalette.Teal20,

    background = KhaataPalette.Neutral98,
    onBackground = KhaataPalette.Neutral10,
    surface = KhaataPalette.Neutral100,
    onSurface = KhaataPalette.Neutral10,
    surfaceVariant = KhaataPalette.Neutral95,
    onSurfaceVariant = KhaataPalette.Neutral40,
    surfaceTint = KhaataPalette.Indigo40,
    inverseSurface = KhaataPalette.Neutral15,
    inverseOnSurface = KhaataPalette.Neutral95,

    error = KhaataPalette.Red40,
    onError = KhaataPalette.Neutral100,
    errorContainer = KhaataPalette.Red90,
    onErrorContainer = KhaataPalette.Red30,

    outline = KhaataPalette.Neutral60,
    outlineVariant = KhaataPalette.Neutral90,
    scrim = KhaataPalette.Neutral0,
)

private val DarkColorScheme = darkColorScheme(
    primary = KhaataPalette.Indigo80,
    onPrimary = KhaataPalette.Indigo20,
    primaryContainer = KhaataPalette.Indigo30,
    onPrimaryContainer = KhaataPalette.Indigo90,
    inversePrimary = KhaataPalette.Indigo40,

    secondary = KhaataPalette.Brass80,
    onSecondary = KhaataPalette.Brass20,
    secondaryContainer = KhaataPalette.Brass30,
    onSecondaryContainer = KhaataPalette.Brass90,

    tertiary = KhaataPalette.Teal80,
    onTertiary = KhaataPalette.Teal20,
    tertiaryContainer = KhaataPalette.Teal30,
    onTertiaryContainer = KhaataPalette.Teal90,

    // Not pure black: a very dark neutral keeps elevation readable on OLED without the smearing
    // that pure black causes when scrolling.
    background = KhaataPalette.Neutral6,
    onBackground = KhaataPalette.Neutral90,
    surface = KhaataPalette.Neutral10,
    onSurface = KhaataPalette.Neutral90,
    surfaceVariant = KhaataPalette.Neutral20,
    onSurfaceVariant = KhaataPalette.Neutral70,
    surfaceTint = KhaataPalette.Indigo80,
    inverseSurface = KhaataPalette.Neutral90,
    inverseOnSurface = KhaataPalette.Neutral15,

    error = KhaataPalette.Red70,
    onError = KhaataPalette.Red30,
    errorContainer = KhaataPalette.Red30,
    onErrorContainer = KhaataPalette.Red90,

    outline = KhaataPalette.Neutral50,
    outlineVariant = KhaataPalette.Neutral30,
    scrim = KhaataPalette.Neutral0,
)

val LocalMoneyColors = staticCompositionLocalOf { MoneyColors.Light }
val LocalKhaataSpacing = staticCompositionLocalOf { KhaataSpacing() }

/**
 * The app's theme.
 *
 * Dynamic colour is available but off by default. Material You would make the app adopt whatever
 * hue the user's wallpaper happens to be, which for a finance app costs the recognisable identity
 * that makes it feel trustworthy — and can produce a wallpaper-derived red that reads as an
 * error state on every screen. It stays available as an explicit preference.
 */
@Composable
fun KhaataTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val moneyColors = if (darkTheme) MoneyColors.Dark else MoneyColors.Light

    CompositionLocalProvider(
        LocalMoneyColors provides moneyColors,
        LocalKhaataSpacing provides KhaataSpacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KhaataTypography,
            shapes = KhaataShapes,
            content = content,
        )
    }
}

/** Shorthand for the semantic money colours. */
object KhaataTheme {
    val money: MoneyColors
        @Composable @ReadOnlyComposable get() = LocalMoneyColors.current

    val spacing: KhaataSpacing
        @Composable @ReadOnlyComposable get() = LocalKhaataSpacing.current
}
