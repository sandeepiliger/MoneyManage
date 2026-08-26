package ai.labs32.khaata.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/**
 * How much visual weight a card carries.
 *
 * A dashboard of eleven identically-styled cards has no focal point -- the eye has nowhere to
 * land, and nothing tells the reader which number matters most. These tiers exist so a screen can
 * say that explicitly: [Flat] recedes into a list, [Raised] is the ordinary default, and
 * [Emphasized] is reserved for the one or two surfaces per screen that should draw the eye first.
 */
enum class KhaataCardTier { Flat, Raised, Emphasized }

/**
 * The app's card surface.
 *
 * One component so corner radius and padding stay identical everywhere, while [tier] controls
 * container tone, elevation and border together -- the three cues that make a card read as a
 * distinct object rather than a flat rectangle. The hairline border matters more than the
 * elevation on this palette: measured contrast between adjacent surface tones is as low as
 * 1.06:1 in dark mode, well below what tonal elevation alone can make visible, so every tier
 * carries a border and elevation is the secondary cue rather than the only one.
 */
@Composable
fun KhaataCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tier: KhaataCardTier = KhaataCardTier.Raised,
    containerColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedContainerColor = containerColor ?: when (tier) {
        KhaataCardTier.Flat -> MaterialTheme.colorScheme.surfaceContainerLow
        KhaataCardTier.Raised -> MaterialTheme.colorScheme.surfaceContainer
        KhaataCardTier.Emphasized -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val elevation = when (tier) {
        KhaataCardTier.Flat -> 0.dp
        KhaataCardTier.Raised -> 2.dp
        KhaataCardTier.Emphasized -> 6.dp
    }
    val borderColor = when (tier) {
        KhaataCardTier.Emphasized -> KhaataTheme.elevation.hairlineStrong
        KhaataCardTier.Flat, KhaataCardTier.Raised -> KhaataTheme.elevation.hairline
    }
    val cardModifier = modifier
        .fillMaxWidth()
        .border(width = 1.dp, color = borderColor, shape = KhaataShapeTokens.card)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = KhaataShapeTokens.card,
            colors = CardDefaults.cardColors(containerColor = resolvedContainerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            modifier = cardModifier,
            shape = KhaataShapeTokens.card,
            colors = CardDefaults.cardColors(containerColor = resolvedContainerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * The hero surface -- the one card per screen that carries the headline figure (the running
 * balance, the month's net position).
 *
 * It is an indigo gradient in *both* light and dark theme, deliberately: everywhere else on the
 * screen adapts to the theme, but the hero is the brand moment, and a card that changed colour
 * under the reader's finger when they toggled dark mode would undercut the "this is Khaata"
 * recognition it exists to create. Because the surface is always dark indigo, content placed on
 * it should always be light -- white or near-white text and icons, not `onSurface`.
 */
@Composable
fun KhaataHeroCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KhaataShapeTokens.hero)
            .background(Brush.linearGradient(KhaataTheme.elevation.heroGradient))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
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
    // Overrides the seed-derived swatch, for the one caller (a completed goal) that needs a
    // specific semantic colour rather than its category's stable seed colour.
    tint: Color? = null,
) {
    val swatch = tint ?: KhaataTheme.money.swatch(colorSeed)
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

/**
 * A single figure on its own tinted ground.
 *
 * [StatPair] sets two figures as bare text side by side, which makes income and expense read as
 * one continuous block. Giving each its own tinted tile separates them at a glance and lets the
 * tint carry the semantic (income/expense) without colour being the only signal -- the label and
 * the amount's own sign still say which is which.
 */
@Composable
fun KhaataStatTile(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    value: @Composable () -> Unit,
) {
    Column(
        modifier
            .clip(KhaataShapeTokens.statTile)
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
        Spacer(Modifier.height(2.dp))
        value()
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
