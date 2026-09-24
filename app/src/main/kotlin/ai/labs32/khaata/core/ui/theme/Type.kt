package ai.labs32.khaata.core.ui.theme

import androidx.compose.material3.Typography
import ai.labs32.khaata.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The brand typeface, Plus Jakarta Sans, bundled as five static weights (SIL Open Font Licence;
 * the licence text is in docs/licenses). Static files rather than the variable font because
 * variation axes need API 26 and this app runs from 24.
 *
 * It has a rupee sign and tabular figures, which is what an amount needs, but no Devanagari --
 * see [khaataTypography] for how Hindi is kept whole.
 */
internal val BrandFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)

/**
 * Typography, set in [family].
 *
 * The theme passes the brand face for English and the system font for Hindi. A Latin-only face
 * under Devanagari text falls back glyph by glyph to the system font mid-word, which looks broken;
 * the device's own font renders Hindi whole. Amounts ([KhaataTextStyles]) stay in the brand face in
 * both, because a figure is only ever digits, a rupee sign and a lakh or crore suffix.
 *
 * Sizes are generous. A finance app is read at arm's length while standing at a counter, and the
 * most common accessibility complaint about this category of app is that the numbers are too
 * small.
 */
internal fun khaataTypography(family: FontFamily) = Typography(
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = family,
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
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1).sp,
        textAlign = TextAlign.Start,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** Card-level figures — a budget limit, an account balance. */
    val amountLarge = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** Transaction rows. */
    val amountMedium = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** Secondary figures and chart axes. */
    val amountSmall = TextStyle(
        fontFamily = BrandFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** The number on the amount keypad. */
    val keypadAmount = TextStyle(
        fontFamily = BrandFontFamily,
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
     * Bottom padding under the last item of a scrolling list.
     *
     * Only breathing room now. It used to be 168dp to clear a floating add button stacked above
     * the navigation bar; add lives inside the bar itself, which the outer Scaffold already
     * reserves space for, so nothing floats over a list's last row any more.
     */
    val bottomBarClearance: androidx.compose.ui.unit.Dp = 24.dp,
)
