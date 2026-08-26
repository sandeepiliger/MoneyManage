package ai.labs32.khaata.feature.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.BudgetStatus
import ai.labs32.khaata.core.model.OccurrenceKind
import ai.labs32.khaata.core.model.ScheduledOccurrence
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.ui.components.CategoryIcons
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.TransactionAmountText
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Rows shared between screens.
 *
 * A transaction looks the same on the dashboard, in the list and inside an account, so it is one
 * component. Duplicating it would guarantee the three drift apart in spacing and, worse, in how
 * they render a transfer.
 */

/**
 * One transaction.
 *
 * Shows merchant, category, account and amount — the four things needed to recognise a row
 * without opening it. The icon is tinted from the category's seed so the same category is always
 * the same colour, and the amount carries a sign so direction never depends on colour.
 */
@Composable
fun TransactionRow(
    transaction: Transaction,
    categoryName: String?,
    accountName: String?,
    categoryColorSeed: Int,
    categoryIconKey: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showDate: Boolean = true,
) {
    val fallbackTitle = stringResource(R.string.categories_uncategorised)
    val subtitle = listOfNotNull(categoryName, accountName).joinToString(" • ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Comfortably above the minimum touch target, which matters for a list people scan
            // and tap while walking.
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorBadge(
            icon = CategoryIcons[categoryIconKey],
            colorSeed = categoryColorSeed,
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = transaction.displayTitle(categoryName ?: fallbackTitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (transaction.isPending) {
                Spacer(Modifier.height(2.dp))
                SourceBadge(transaction.source)
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            TransactionAmountText(amount = transaction.amount, type = transaction.type)
            if (showDate) {
                Text(
                    text = relativeDateLabel(transaction.occurredOn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Marks where an unconfirmed row came from.
 *
 * Imported rows are visually distinct from ones the user typed, so a wrong SMS parse is never
 * mistaken for something they entered themselves.
 */
@Composable
private fun SourceBadge(source: TransactionSource) {
    val labelRes = when (source) {
        TransactionSource.SMS_IMPORT, TransactionSource.NOTIFICATION_IMPORT ->
            R.string.transaction_source_sms
        TransactionSource.CSV_IMPORT -> R.string.transaction_source_import
        TransactionSource.RECURRING -> R.string.transaction_source_recurring
        else -> R.string.transaction_pending_badge
    }

    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(KhaataShapeTokens.chip)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** One upcoming bill, EMI, subscription or card payment. */
@Composable
fun UpcomingRow(
    occurrence: ScheduledOccurrence,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val today = LocalDate.now()
    val daysUntil = ChronoUnit.DAYS.between(today, occurrence.dueOn)
    val isOverdue = daysUntil < 0

    val dueLabel = when {
        isOverdue -> stringResource(R.string.recurring_overdue)
        daysUntil == 0L -> stringResource(R.string.recurring_due_today)
        else -> pluralStringResource(
            R.plurals.recurring_due_in_days,
            daysUntil.toInt(),
            daysUntil.toInt(),
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorBadge(
            icon = iconFor(occurrence.kind),
            colorSeed = occurrence.kind.ordinal,
            size = 36.dp,
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = occurrence.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = dueLabel,
                style = MaterialTheme.typography.bodySmall,
                // Overdue is marked by the words as well as the colour.
                color = if (isOverdue) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        MoneyText(
            money = occurrence.amount,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun iconFor(kind: OccurrenceKind): ImageVector = when (kind) {
    OccurrenceKind.RECURRING -> Icons.Default.EventRepeat
    OccurrenceKind.SUBSCRIPTION -> Icons.Default.Subscriptions
    OccurrenceKind.CREDIT_CARD_BILL -> Icons.Default.CreditCard
    OccurrenceKind.LOAN_EMI -> Icons.Default.Payments
    OccurrenceKind.GOAL_CONTRIBUTION -> Icons.Default.Flag
}

/** "Today", "Yesterday", or a short date. */
@Composable
fun relativeDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.transaction_today)
        today.minusDays(1) -> stringResource(R.string.transaction_yesterday)
        else -> {
            // The year is dropped for the current year, which is most rows, and kept otherwise so
            // an old transaction is never ambiguous.
            val pattern = if (date.year == today.year) "d MMM" else "d MMM yyyy"
            date.format(DateTimeFormatter.ofPattern(pattern))
        }
    }
}

/**
 * A low-emphasis "add another" row for the end of a list that otherwise has no add affordance
 * once it holds at least one item — Goals and Budgets both only show their add action inside the
 * empty state, so it becomes unreachable the moment there is something to look at.
 */
@Composable
fun AddRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = KhaataTheme.spacing.touchTarget)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A budget status as words, so the state never depends on colour. */
@Composable
fun budgetStatusLabel(status: BudgetStatus): String = stringResource(
    when (status) {
        BudgetStatus.ON_TRACK -> R.string.budget_status_on_track
        BudgetStatus.PROJECTED_OVER -> R.string.budget_status_projected_over
        BudgetStatus.NEARING_LIMIT -> R.string.budget_status_nearing_limit
        BudgetStatus.EXHAUSTED -> R.string.budget_status_exhausted
        BudgetStatus.OVERSPENT -> R.string.budget_status_overspent
    },
)

/** The colour for a budget status. Always paired with [budgetStatusLabel], never used alone. */
@Composable
fun budgetStatusColor(status: BudgetStatus): Color {
    val money = KhaataTheme.money
    return when (status) {
        BudgetStatus.ON_TRACK -> MaterialTheme.colorScheme.primary
        BudgetStatus.PROJECTED_OVER -> money.warning
        BudgetStatus.NEARING_LIMIT -> money.warning
        BudgetStatus.EXHAUSTED -> money.expense
        BudgetStatus.OVERSPENT -> MaterialTheme.colorScheme.error
    }
}
