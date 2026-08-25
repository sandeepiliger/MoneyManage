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

    // A real page grey rather than near-white. Light mode only works if the page is visibly
    // darker than the cards on it: at Neutral98 the page and a white card measured 1.04:1, so
    // nothing read as a card at all. Neutral95 gives white cards something to lift off.
    background = KhaataPalette.Neutral95,
    onBackground = KhaataPalette.Neutral10,
    surface = KhaataPalette.Neutral100,
    onSurface = KhaataPalette.Neutral10,
    // Deeper than the page now that the page itself is Neutral95. surfaceVariant is what a
    // progress track is drawn in, and a track the same tone as the background would vanish on the
    // white cards those bars actually sit on.
    surfaceVariant = KhaataPalette.Neutral90,
    onSurfaceVariant = KhaataPalette.Neutral40,
    surfaceTint = KhaataPalette.Indigo40,
    inverseSurface = KhaataPalette.Neutral15,
    inverseOnSurface = KhaataPalette.Neutral95,

    // Container roles for the card elevation tiers (KhaataCardTier).
    //
    // These deliberately do NOT follow Material's convention of getting progressively darker as
    // emphasis rises. That convention inverts in light mode once these roles are used for cards:
    // a more important card would be rendered *darker* than the page behind it, which the eye
    // reads as recessed or disabled rather than raised. Emphasized at Neutral90 looked like a
    // greyed-out panel. So in light mode every card tier stays white and the tiers separate by
    // shadow and border instead, which is how a raised card actually behaves in daylight.
    surfaceContainerLowest = KhaataPalette.Neutral100,
    surfaceContainerLow = KhaataPalette.Neutral100,
    surfaceContainer = KhaataPalette.Neutral100,
    surfaceContainerHigh = KhaataPalette.Neutral100,
    surfaceContainerHighest = KhaataPalette.Neutral98,

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
    // Raised from Neutral10: background-to-surface measured only 1.06:1 contrast, so cards were
    // effectively invisible against the screen behind them. Neutral15 plus the hairline border in
    // KhaataCard is what actually separates a card from the page now.
    surface = KhaataPalette.Neutral15,
    onSurface = KhaataPalette.Neutral90,
    // Raised alongside surface, for the same reason -- surfaceVariant is used by things like the
    // progress track, and it needs to stay visibly above the new surface tone.
    surfaceVariant = KhaataPalette.Neutral22,
    onSurfaceVariant = KhaataPalette.Neutral70,
    surfaceTint = KhaataPalette.Indigo80,
    inverseSurface = KhaataPalette.Neutral90,
    inverseOnSurface = KhaataPalette.Neutral15,

    // Container roles for the card elevation tiers (KhaataCardTier) -- Material has no default
    // for these that matches the palette, so they're set explicitly rather than left to fall back.
    //
    // Measured against the page: Flat 1.06:1, Raised 1.16:1, Emphasized 1.34:1. Those are modest
    // numbers because tone can only do so much between near-blacks before cards start reading as
    // washed-out grey rather than dark. They carry it as far as is sensible; the hairline border
    // in KhaataCard is what does the rest of the separating.
    surfaceContainerLowest = KhaataPalette.Neutral6,
    surfaceContainerLow = KhaataPalette.Neutral10,
    surfaceContainer = KhaataPalette.Neutral15,
    surfaceContainerHigh = KhaataPalette.Neutral20,
    surfaceContainerHighest = KhaataPalette.Neutral22,

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
val LocalKhaataElevation = staticCompositionLocalOf { KhaataElevation.Light }

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
        LocalKhaataElevation provides (if (darkTheme) KhaataElevation.Dark else KhaataElevation.Light),
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

    val elevation: KhaataElevation
        @Composable @ReadOnlyComposable get() = LocalKhaataElevation.current
}
