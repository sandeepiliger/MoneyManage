package ai.labs32.khaata.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/**
 * Charts, drawn directly on Compose's Canvas.
 *
 * No charting library. The app needs four simple chart types, and a general-purpose library would
 * add a large dependency, its own theming model that has to be reconciled with this one, and a
 * set of interaction defaults that ignore the accessibility rules the rest of the app follows.
 * Drawing them here keeps them themed correctly, keeps the APK small, and — importantly — lets
 * every chart carry a text alternative rather than being an unlabelled picture to a screen
 * reader.
 *
 * Every chart here is labelled directly rather than through a colour key, so none of them depends
 * on distinguishing colours to be readable.
 */

/** One slice or bar. */
data class ChartSlice(
    val label: String,
    val value: Float,
    val color: Color,
)

/** One point in a series. */
data class ChartPoint(
    val label: String,
    val value: Float,
)

/**
 * A donut chart with a legend.
 *
 * A donut rather than a pie: the hole holds the total, which is the number people actually want,
 * and it avoids the impossible-to-compare central angles of a full pie.
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    centerLabel: String? = null,
    centerValue: String? = null,
    strokeWidth: androidx.compose.ui.unit.Dp = 28.dp,
    contentDescription: String? = null,
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    if (total <= 0f || slices.isEmpty()) {
        ChartEmptyPlaceholder(modifier)
        return
    }

    val animatedSweep by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 600),
        label = "donut-sweep",
    )

    val description = contentDescription ?: buildString {
        append("Breakdown chart. ")
        slices.take(LEGEND_LIMIT).forEach { slice ->
            val percent = (slice.value / total * 100).toInt()
            append("${slice.label} $percent percent. ")
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val diameter = minOf(size.width, size.height) - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            var startAngle = -90f
            slices.forEach { slice ->
                val sweep = (slice.value / total) * 360f * animatedSweep
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }

        if (centerValue != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centerValue,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (centerLabel != null) {
                    Text(
                        text = centerLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The legend for a donut chart.
 *
 * Separate from the chart so a caller can place it beside or below depending on width. Each entry
 * names its slice and its share, so the legend is readable without matching colours by eye.
 */
@Composable
fun ChartLegend(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    valueFormatter: (Float) -> String = { it.toInt().toString() },
    limit: Int = LEGEND_LIMIT,
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    Column(modifier = modifier.fillMaxWidth()) {
        slices.take(limit).forEach { slice ->
            val percent = if (total > 0f) (slice.value / total * 100).toInt() else 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(slice.color),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = valueFormatter(slice.value),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (slices.size > limit) {
            val remaining = slices.drop(limit)
            val remainingTotal = remaining.sumOf { it.value.toDouble() }.toFloat()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${remaining.size} more",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = valueFormatter(remainingTotal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A grouped bar chart, for income against expense per period.
 *
 * Two bars per group with distinct colours *and* positions, so the pair is readable without
 * relying on hue.
 */
@Composable
fun GroupedBarChart(
    groups: List<BarGroup>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 180.dp,
    valueFormatter: (Float) -> String = { it.toInt().toString() },
    contentDescription: String? = null,
) {
    if (groups.isEmpty()) {
        ChartEmptyPlaceholder(modifier)
        return
    }
    val maxValue = groups.flatMap { listOf(it.primary, it.secondary) }.maxOrNull() ?: 0f
    if (maxValue <= 0f) {
        ChartEmptyPlaceholder(modifier)
        return
    }

    val incomeColor = KhaataTheme.money.income
    val expenseColor = KhaataTheme.money.expense
    val animated by animateFloatAsState(1f, tween(600), label = "bars")

    val description = contentDescription ?: buildString {
        append("Income and expense chart. ")
        groups.takeLast(6).forEach {
            append("${it.label}: income ${valueFormatter(it.primary)}, expense ${valueFormatter(it.secondary)}. ")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clearAndSetSemantics { this.contentDescription = description },
        ) {
            val groupWidth = size.width / groups.size
            val barWidth = (groupWidth * 0.28f).coerceAtMost(24.dp.toPx())
            val gap = barWidth * 0.35f

            groups.forEachIndexed { index, group ->
                val centerX = groupWidth * index + groupWidth / 2f
                drawBar(
                    x = centerX - barWidth - gap / 2f,
                    width = barWidth,
                    value = group.primary,
                    maxValue = maxValue,
                    color = incomeColor,
                    animation = animated,
                )
                drawBar(
                    x = centerX + gap / 2f,
                    width = barWidth,
                    value = group.secondary,
                    maxValue = maxValue,
                    color = expenseColor,
                    animation = animated,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            groups.forEach { group ->
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun DrawScope.drawBar(
    x: Float,
    width: Float,
    value: Float,
    maxValue: Float,
    color: Color,
    animation: Float,
) {
    if (value <= 0f) return
    val barHeight = (value / maxValue) * size.height * animation
    val radius = CornerRadius(width / 3f, width / 3f)
    drawRoundRect(
        color = color,
        topLeft = Offset(x, size.height - barHeight),
        size = Size(width, barHeight),
        cornerRadius = radius,
    )
}

data class BarGroup(val label: String, val primary: Float, val secondary: Float)

/**
 * A line chart with an area fill, for balance and net worth over time.
 *
 * The y-axis is not forced to zero: a net worth moving between ₹4,80,000 and ₹5,20,000 is a
 * flat line if the axis starts at zero, which hides exactly the change the chart exists to show.
 * The axis labels state the range so the scale is never ambiguous.
 */
@Composable
fun TrendLineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 160.dp,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    valueFormatter: (Float) -> String = { it.toInt().toString() },
    contentDescription: String? = null,
) {
    if (points.size < 2) {
        ChartEmptyPlaceholder(modifier)
        return
    }

    val values = points.map { it.value }
    val maxValue = values.max()
    val minValue = values.min()
    // A flat series would divide by zero; give it a nominal range so it draws as a centred line.
    val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f

    val animated by animateFloatAsState(1f, tween(700), label = "trend")
    val fillColor = lineColor.copy(alpha = 0.14f)

    val description = contentDescription ?: buildString {
        append("Trend chart from ${points.first().label} to ${points.last().label}. ")
        append("Ranges from ${valueFormatter(minValue)} to ${valueFormatter(maxValue)}. ")
        append("Currently ${valueFormatter(values.last())}.")
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clearAndSetSemantics { this.contentDescription = description },
        ) {
            val stepX = size.width / (points.size - 1)
            fun yFor(value: Float): Float =
                size.height - ((value - minValue) / range) * size.height * animated

            val linePath = Path()
            val fillPath = Path()

            points.forEachIndexed { index, point ->
                val x = stepX * index
                val y = yFor(point.value)
                if (index == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, size.height)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(size.width, size.height)
            fillPath.close()

            drawPath(fillPath, color = fillColor)
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )

            // A dot on the most recent point, which is the one being asked about.
            val lastX = size.width
            val lastY = yFor(values.last())
            drawCircle(color = lineColor, radius = 5.dp.toPx(), center = Offset(lastX, lastY))
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = points.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = points.last().label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A minimal trend line with no axes, labels or grid — meant to sit inside a card next to a number
 * that already states the value, e.g. a net worth figure with its recent shape beside it.
 *
 * Deliberately bare: a sparkline earns its place by being glanceable at a small size, and axis
 * ticks or gridlines at that size would be noise rather than information. The last point carries
 * a dot because "where are we now" is the one thing a shape alone cannot say.
 *
 * Hidden from TalkBack rather than described, because it duplicates the accompanying number
 * exactly — a spoken description would just repeat what the screen reader already read a moment
 * earlier, adding noise rather than information.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: androidx.compose.ui.unit.Dp = 2.dp,
) {
    if (values.size < 2) return

    val maxValue = values.max()
    val minValue = values.min()
    // A flat series would divide by zero; give it a nominal range so it draws as a centred line.
    val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f

    Canvas(modifier.clearAndSetSemantics { }) {
        val strokePx = strokeWidth.toPx()
        val dotRadius = strokePx * 1.6f

        // Inset by the dot's radius and half the stroke width. Drawn flush to the canvas the end
        // dot is clipped in half by the right edge, and any point at the series minimum or maximum
        // loses half its stroke to the top or bottom — both of which read as a rendering fault
        // rather than as a deliberately tight chart.
        val left = strokePx / 2f
        val right = (size.width - dotRadius).coerceAtLeast(left + 1f)
        val top = dotRadius
        val bottom = (size.height - dotRadius).coerceAtLeast(top + 1f)

        val stepX = (right - left) / (values.size - 1)
        fun yFor(value: Float): Float = bottom - ((value - minValue) / range) * (bottom - top)

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = left + stepX * index
            val y = yFor(value)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        drawCircle(
            color = color,
            radius = dotRadius,
            center = Offset(right, yFor(values.last())),
        )
    }
}

/**
 * A horizontal bar per category, ranked.
 *
 * Preferred over a donut when there are more than a handful of categories: comparing lengths is
 * far easier than comparing angles, and the labels sit next to their bars.
 */
@Composable
fun RankedBarList(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    valueFormatter: (Float) -> String = { it.toInt().toString() },
    limit: Int = 8,
    onSliceClick: ((ChartSlice) -> Unit)? = null,
) {
    if (slices.isEmpty()) {
        ChartEmptyPlaceholder(modifier)
        return
    }
    val maxValue = slices.maxOf { it.value }.takeIf { it > 0f } ?: return

    Column(modifier = modifier.fillMaxWidth()) {
        slices.take(limit).forEach { slice ->
            val fraction = (slice.value / maxValue).coerceIn(0f, 1f)
            val description = "${slice.label}, ${valueFormatter(slice.value)}"
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSliceClick != null) {
                            Modifier.clickable { onSliceClick(slice) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 8.dp)
                    .clearAndSetSemantics { contentDescription = description },
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = slice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = valueFormatter(slice.value),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(slice.color),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartEmptyPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.chart_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val LEGEND_LIMIT = 6
