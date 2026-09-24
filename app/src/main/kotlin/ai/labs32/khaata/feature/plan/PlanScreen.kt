package ai.labs32.khaata.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.BudgetProgress
import ai.labs32.khaata.core.calc.GoalProgress
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.PaceBar
import ai.labs32.khaata.core.ui.components.ProgressRing
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.DueOccurrence
import ai.labs32.khaata.feature.shared.AddRow
import ai.labs32.khaata.feature.shared.UpcomingRow
import ai.labs32.khaata.feature.shared.budgetStatusColor
import ai.labs32.khaata.feature.shared.budgetStatusLabel
import ai.labs32.khaata.navigation.Routes
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * The Plan tab: budgets, bills and goals -- what is coming, and whether the month will hold.
 *
 * These were three separate screens, two of them under More. They answer one question together,
 * and a bill due on the 28th is exactly what decides whether the food budget can take another
 * dinner out, so they sit on one page in the order the question is asked.
 */
@Composable
fun PlanScreen(
    onNavigate: (String) -> Unit,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = KhaataTheme.spacing

    if (state.isLoading) {
        LoadingState()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            bottom = spacing.bottomBarClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        item("header") {
            PlanHeader(
                month = state.month,
                canGoForward = !state.isCurrentMonth,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )
        }

        if (state.budgets.isEmpty()) {
            item("budgets-empty") {
                KhaataCard {
                    EmptyState(
                        icon = Icons.Outlined.PieChart,
                        title = stringResource(R.string.budgets_empty_title),
                        description = stringResource(R.string.budgets_empty_body),
                        actionLabel = stringResource(R.string.budgets_add),
                        onAction = { onNavigate(Routes.ADD_BUDGET) },
                    )
                }
            }
        } else {
            item("summary") { BudgetSummaryCard(state) }
            item("budgets") {
                BudgetsCard(
                    budgets = state.budgets,
                    onOpen = { onNavigate(Routes.budgetDetail(it)) },
                    onAdd = { onNavigate(Routes.ADD_BUDGET) },
                )
            }
        }

        // Bills and goals are about now; looking back at an old month's budgets they would only
        // be noise between the figures being compared.
        if (state.isCurrentMonth) {
            item("bills") {
                BillsCard(
                    state = state,
                    onMarkPaid = viewModel::markPaid,
                    onSkip = viewModel::skip,
                    onSeeAll = { onNavigate(Routes.RECURRING) },
                    onOpenSubscriptions = { onNavigate(Routes.SUBSCRIPTIONS) },
                )
            }
            item("goals") {
                GoalsCard(
                    goals = state.goals,
                    onOpen = { onNavigate(Routes.goalDetail(it)) },
                    onSeeAll = { onNavigate(Routes.GOALS) },
                    onAdd = { onNavigate(Routes.ADD_GOAL) },
                )
            }
        }
    }
}

@Composable
private fun PlanHeader(
    month: YearMonth,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KhaataTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.nav_plan),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.plan_previous_month),
            )
        }
        Text(
            text = month.format(formatter),
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.plan_next_month),
            )
        }
    }
}

/**
 * The month at a glance.
 *
 * With an overall budget, that is the ring: spent of the whole. Without one, the ring counts
 * budgets on track instead -- category budgets can overlap, so adding their limits together would
 * produce a total nobody set and spending counted twice.
 */
@Composable
private fun BudgetSummaryCard(state: PlanUiState) {
    val overall = state.overall
    val total = state.budgets.size
    val fraction = if (overall != null) {
        overall.percentUsedClamped / 100f
    } else {
        if (total == 0) 0f else state.onTrackCount.toFloat() / total
    }
    val ringColor = if (overall != null) budgetStatusColor(overall.status) else MaterialTheme.colorScheme.primary
    val ringLabel = if (overall != null) {
        "${overall.percentUsedClamped}%"
    } else {
        "${state.onTrackCount}/$total"
    }
    val onTrackText = stringResource(R.string.plan_budgets_on_track, state.onTrackCount, total)

    KhaataCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                fraction = fraction,
                description = if (overall != null) {
                    "${budgetStatusLabel(overall.status)}, $ringLabel"
                } else {
                    onTrackText
                },
                color = ringColor,
            ) {
                Text(text = ringLabel, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(KhaataTheme.spacing.default))
            Column(Modifier.weight(1f)) {
                if (overall != null) {
                    Text(
                        text = stringResource(R.string.plan_spent_of_budget),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.budgets_spent_of,
                            MoneyFormatter.plain(overall.spent),
                            MoneyFormatter.plain(overall.limit),
                        ),
                        style = KhaataTextStyles.amountMedium,
                    )
                } else {
                    Text(text = onTrackText, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(4.dp))
                val footnote = when {
                    !state.isCurrentMonth -> stringResource(
                        R.string.plan_final_figures,
                        state.month.format(DateTimeFormatter.ofPattern("MMMM")),
                    )
                    state.dailySafeSpend != null -> stringResource(
                        R.string.budgets_safe_daily,
                        MoneyFormatter.plain(state.dailySafeSpend),
                    )
                    else -> null
                }
                footnote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isCurrentMonth) KhaataTheme.money.income else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetsCard(
    budgets: List<BudgetProgress>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    KhaataCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        CardHeader(title = stringResource(R.string.nav_budgets))
        budgets.forEachIndexed { index, progress ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BudgetRow(progress = progress, onClick = { onOpen(progress.budget.id) })
        }
        AddRow(label = stringResource(R.string.budgets_add), onClick = onAdd)
    }
}

/** One budget: name, spent of limit, and a bar with where spending should be by now. */
@Composable
private fun BudgetRow(progress: BudgetProgress, onClick: () -> Unit) {
    val statusColor = budgetStatusColor(progress.status)
    val statusLabel = budgetStatusLabel(progress.status)
    val periodDays = progress.daysElapsed + progress.daysRemaining
    val pace = if (periodDays > 0) progress.daysElapsed.toFloat() / periodDays else null

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = progress.budget.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.budgets_spent_of,
                    MoneyFormatter.compact(progress.spent),
                    MoneyFormatter.compact(progress.limit),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = if (progress.status.needsAttention) statusColor else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        PaceBar(
            fraction = progress.percentUsedClamped / 100f,
            paceFraction = pace,
            description = "$statusLabel, ${progress.percentUsedClamped}%",
            color = statusColor,
            height = 6.dp,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                modifier = Modifier.weight(1f),
            )
            if (progress.daysRemaining > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.budgets_days_left,
                        progress.daysRemaining,
                        progress.daysRemaining,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BillsCard(
    state: PlanUiState,
    onMarkPaid: (DueOccurrence) -> Unit,
    onSkip: (DueOccurrence) -> Unit,
    onSeeAll: () -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    KhaataCard {
        CardHeader(
            title = stringResource(R.string.plan_bills),
            actionLabel = stringResource(R.string.plan_all_bills),
            onAction = onSeeAll,
        )

        state.awaiting.forEach { occurrence ->
            Spacer(Modifier.height(KhaataTheme.spacing.small))
            AwaitingBill(
                occurrence = occurrence,
                onMarkPaid = { onMarkPaid(occurrence) },
                onSkip = { onSkip(occurrence) },
            )
        }

        if (state.upcoming.isEmpty() && state.awaiting.isEmpty()) {
            Spacer(Modifier.height(KhaataTheme.spacing.small))
            Text(
                text = stringResource(R.string.plan_bills_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(KhaataTheme.spacing.tiny))
            state.upcoming.forEach { occurrence -> UpcomingRow(occurrence = occurrence) }
        }

        Spacer(Modifier.height(KhaataTheme.spacing.tiny))
        // A link, not an AddRow: it opens the subscriptions list rather than adding one.
        TextButton(onClick = onOpenSubscriptions) {
            Text(stringResource(R.string.subscriptions_title))
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** A manual bill whose date has passed, asking whether it actually went out. */
@Composable
private fun AwaitingBill(
    occurrence: DueOccurrence,
    onMarkPaid: () -> Unit,
    onSkip: () -> Unit,
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM") }
    KhaataCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = occurrence.rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.recurring_was_due, occurrence.dueOn.format(dateFormatter)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = MoneyFormatter.plain(occurrence.rule.amount),
                style = KhaataTextStyles.amountMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.plan_did_it_go_out),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.recurring_skip), maxLines = 1)
            }
            Button(onClick = onMarkPaid, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.recurring_confirm_post), maxLines = 1)
            }
        }
    }
}

@Composable
private fun GoalsCard(
    goals: List<GoalProgress>,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
    onAdd: () -> Unit,
) {
    KhaataCard {
        CardHeader(
            title = stringResource(R.string.goals_title),
            actionLabel = if (goals.isNotEmpty()) stringResource(R.string.action_see_all) else null,
            onAction = if (goals.isNotEmpty()) onSeeAll else null,
        )
        goals.take(GOALS_SHOWN).forEach { progress ->
            GoalRow(progress = progress, onClick = { onOpen(progress.goal.id) })
        }
        AddRow(label = stringResource(R.string.goals_add), onClick = onAdd)
    }
}

@Composable
private fun GoalRow(progress: GoalProgress, onClick: () -> Unit) {
    // A finished goal takes the income colour whatever its own seed, so it can never draw a rose
    // bar and read as a warning -- the same rule the Goals screen follows.
    val swatch = if (progress.isAchieved) {
        KhaataTheme.money.income
    } else {
        KhaataTheme.money.swatch(progress.goal.colorSeed)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorBadge(
            icon = Icons.Outlined.Flag,
            colorSeed = progress.goal.colorSeed,
            size = 36.dp,
            tint = if (progress.isAchieved) swatch else null,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = progress.goal.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${progress.percentCompleteClamped}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            PaceBar(
                fraction = progress.percentCompleteClamped / 100f,
                description = "${progress.goal.name}, ${progress.percentCompleteClamped}%",
                color = swatch,
                height = 6.dp,
            )
            progress.requiredMonthlyContribution
                ?.takeIf { !progress.isAchieved && it.isPositive }
                ?.let { monthly ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.goals_required_monthly, MoneyFormatter.plain(monthly)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

private const val GOALS_SHOWN = 3
