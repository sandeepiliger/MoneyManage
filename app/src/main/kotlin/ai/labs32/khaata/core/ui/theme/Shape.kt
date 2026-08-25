package ai.labs32.khaata.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * Generously rounded — softer than Material's defaults. Sharp corners read as dense and
 * spreadsheet-like, which is the impression this app is trying not to give; rounded surfaces make
 * a screen full of numbers feel approachable rather than clinical.
 */
internal val KhaataShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Shapes for specific surfaces, so the same element is never rounded two different ways. */
object KhaataShapeTokens {
    val card = RoundedCornerShape(20.dp)
    val cardCompact = RoundedCornerShape(16.dp)
    val chip = RoundedCornerShape(percent = 50)
    val bottomSheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val keypadKey = RoundedCornerShape(16.dp)
    val progressBar = RoundedCornerShape(percent = 50)
    val avatar = RoundedCornerShape(percent = 30)

    // Rounder than a regular card: the hero surface is the one element per screen meant to read
    // as a distinct, deliberate object rather than a list item, and the extra curvature signals that.
    val hero = RoundedCornerShape(28.dp)

    // Slightly rounder than cardCompact so a row of stat tiles doesn't look like it borrowed the
    // list-row shape.
    val statTile = RoundedCornerShape(18.dp)
}
