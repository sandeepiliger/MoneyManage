package ai.labs32.khaata.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A progress bar with a marker for where spending "should" be by now.
 *
 * "92% used" means nothing on its own: on the 29th it is fine, on the 10th it is a problem. The
 * marker at [paceFraction] -- the share of the period already gone -- is what makes the bar
 * readable at a glance: fill past the marker is spending ahead of the calendar.
 *
 * The fill is capped at the track so an overspent budget cannot draw past it; the overspend is
 * carried by [description] and by the caller's colour and label, never by the bar alone.
 */
@Composable
fun PaceBar(
    fraction: Float,
    description: String,
    modifier: Modifier = Modifier,
    paceFraction: Float? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    markerColor: Color = MaterialTheme.colorScheme.onSurface,
    height: Dp = 8.dp,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = FILL_ANIMATION_MS),
        label = "pace-bar-fill",
    )
    // The marker overhangs the bar a little above and below, so it reads as a line across the
    // bar rather than as a notch cut into the fill.
    val overhang = 3.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height + overhang * 2)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val barHeight = height.toPx()
        val top = overhang.toPx()
        val radius = CornerRadius(barHeight / 2, barHeight / 2)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, top),
            size = Size(size.width, barHeight),
            cornerRadius = radius,
        )
        if (animated > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, top),
                size = Size((size.width * animated).coerceAtLeast(barHeight), barHeight),
                cornerRadius = radius,
            )
        }
        paceFraction?.coerceIn(0f, 1f)?.let { pace ->
            val x = size.width * pace
            drawLine(
                color = markerColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A ring filled to [fraction], with [content] in its middle -- for a single "how much of the
 * whole is used" figure, such as the month's budget on Plan.
 */
@Composable
fun ProgressRing(
    fraction: Float,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    strokeWidth: Dp = 10.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = FILL_ANIMATION_MS),
        label = "progress-ring-fill",
    )
    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (animated > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** Long enough to be seen filling, short enough never to be waited on. */
private const val FILL_ANIMATION_MS = 450
