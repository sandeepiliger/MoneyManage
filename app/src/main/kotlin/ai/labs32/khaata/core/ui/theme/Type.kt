package ai.labs32.khaata.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Typography.
 *
 * The system font family is used deliberately rather than a bundled typeface. On Android it
 * resolves to the device's own font, which means Devanagari renders correctly for Hindi without
 * shipping a second font file — a bundled Latin-only face would fall back mid-string and look
 * broken. It also keeps the APK smaller, which matters on the devices this app targets.
 *
 * Sizes are generous. A finance app is read at arm's length while standing at a counter, and the
 * most common accessibility complaint about this category of app is that the numbers are too
 * small.
 */
internal val KhaataTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Styles for monetary figures.
 *
 * Amounts are set in medium or heavier weight and with tighter tracking than body text: a rupee
 * figure is the thing the eye should land on first, and grouped digits read better when they are
 * not spaced apart.
 */
object KhaataTextStyles {

    /**
     * `tnum` gives every digit the same advance width, so a column of amounts lines up on its
     * decimal point instead of drifting with how many wide digits ("8") versus narrow ones ("1")
     * a figure happens to contain. Every style below carries it for exactly that reason.
     */
    private const val TABULAR_FIGURES = "tnum"

    /** The single headline figure on the dashboard. */
    val amountHero = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1).sp,
        textAlign = TextAlign.Start,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** Card-level figures — a budget limit, an account balance. */
    val amountLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** Transaction rows. */
    val amountMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** Secondary figures and chart axes. */
    val amountSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** The number on the amount keypad. */
    val keypadAmount = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.5).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )
}

/**
 * The spacing scale.
 *
 * A 4dp base, exposed as named steps so layouts do not sprinkle raw dp values. [touchTarget] is
 * the accessibility floor every interactive element is sized against.
 */
data class KhaataSpacing(
    val none: androidx.compose.ui.unit.Dp = 0.dp,
    val tiny: androidx.compose.ui.unit.Dp = 4.dp,
    val small: androidx.compose.ui.unit.Dp = 8.dp,
    val medium: androidx.compose.ui.unit.Dp = 12.dp,
    val default: androidx.compose.ui.unit.Dp = 16.dp,
    val large: androidx.compose.ui.unit.Dp = 24.dp,
    val xlarge: androidx.compose.ui.unit.Dp = 32.dp,
    val xxlarge: androidx.compose.ui.unit.Dp = 48.dp,

    /** Horizontal padding for full-width screen content. */
    val screenHorizontal: androidx.compose.ui.unit.Dp = 16.dp,

    /** Minimum size of anything tappable. Never reduced, on any screen. */
    val touchTarget: androidx.compose.ui.unit.Dp = 48.dp,

    /**
     * Bottom padding that clears the navigation bar and the floating action button.
     *
     * The FAB stack is 56dp add button + 12dp gap + 40dp mic button = 108dp tall, sitting above
     * a roughly 48dp nav bar with its own margin -- 96dp was sized for the single add button this
     * screen used to have and left the FAB stack overlapping the last card in a list.
     */
    val bottomBarClearance: androidx.compose.ui.unit.Dp = 168.dp,
)
