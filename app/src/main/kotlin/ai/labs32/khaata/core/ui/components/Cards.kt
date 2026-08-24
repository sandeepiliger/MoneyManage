package ai.labs32.khaata.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/**
 * The app's card surface.
 *
 * One component so elevation, corner radius and padding are identical everywhere. Elevation is
 * kept low deliberately: a dashboard of ten heavily shadowed cards looks cluttered, and the
 * hierarchy that matters here comes from typography and spacing rather than depth.
 */
@Composable
fun KhaataCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardModifier = modifier.fillMaxWidth()
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = KhaataShapeTokens.card,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            modifier = cardModifier,
            shape = KhaataShapeTokens.card,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * A card header with an optional action.
 *
 * The action is a text button rather than a bare chevron so its purpose is readable and it meets
 * the minimum touch target without extra padding gymnastics.
 */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * A circular icon badge, tinted from a seed colour.
 *
 * Used for categories and accounts. The seed maps to a stable swatch, so the same category is
 * always the same colour without anyone having to store one.
 */
@Composable
fun ColorBadge(
    icon: ImageVector,
    colorSeed: Int,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    contentDescription: String? = null,
) {
    val swatch = KhaataTheme.money.swatch(colorSeed)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 30))
            .background(swatch.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = swatch,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * A labelled progress bar for budgets and goals.
 *
 * The bar is capped at 100% so an overspent budget cannot draw past its track, and the overspend
 * is communicated by [statusLabel] and colour together rather than by an overflowing bar. Screen
 * readers get the percentage and the status as one sentence instead of an unlabelled bar.
 */
@Composable
fun LabelledProgress(
    progressPercent: Int,
    statusLabel: String,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    height: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val clamped = progressPercent.coerceIn(0, 100)
    val description = "$statusLabel, $clamped%"

    Column(modifier = modifier.clearAndSetSemantics { contentDescription = description }) {
        LinearProgressIndicator(
            progress = { clamped / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(KhaataShapeTokens.progressBar),
            color = progressColor,
            trackColor = trackColor,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/**
 * A row of two figures side by side, e.g. income and expenses.
 *
 * Common enough across the dashboard and reports to be worth sharing rather than reimplementing
 * with slightly different spacing each time.
 */
@Composable
fun StatPair(
    leadingLabel: String,
    leadingValue: @Composable () -> Unit,
    trailingLabel: String,
    trailingValue: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                text = leadingLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            leadingValue()
        }
        Spacer(Modifier.width(KhaataTheme.spacing.default))
        Column(Modifier.weight(1f)) {
            Text(
                text = trailingLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            trailingValue()
        }
    }
}

/** A tappable row inside a card or settings list, sized to the minimum touch target. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(KhaataTheme.spacing.default))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(KhaataTheme.spacing.small))
            trailing()
        }
    }
}
