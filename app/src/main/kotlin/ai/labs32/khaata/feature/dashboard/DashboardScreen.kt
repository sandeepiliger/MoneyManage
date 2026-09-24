package ai.labs32.khaata.feature.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.ui.components.PaceBar
import ai.labs32.khaata.core.ui.theme.KhaataPalette
import ai.labs32.khaata.data.repository.DueOccurrence
import ai.labs32.khaata.feature.shared.relativeDateLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
// Filled rather than outlined only because the filled variant is already proven present in this
// project (GoalsScreen); at 14dp the two are indistinguishable.
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.TrendingUp
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.BudgetStatus
import ai.labs32.khaata.core.model.DashboardCard
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.MoneyStyle
import ai.labs32.khaata.core.money.SignStyle
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.CategoryIcons
import ai.labs32.khaata.core.ui.components.ChartLegend
import ai.labs32.khaata.core.ui.components.ChartSlice
import ai.labs32.khaata.core.ui.components.ChartPoint
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.DonutChart
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.ErrorState
import ai.labs32.khaata.core.ui.components.HeroAmount
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.KhaataCardTier
import ai.labs32.khaata.core.ui.components.KhaataHeroCard
import ai.labs32.khaata.core.ui.components.KhaataStatTile
import ai.labs32.khaata.core.ui.components.LabelledProgress
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.Sparkline
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.components.TrendLineChart
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.shared.TransactionRow
import ai.labs32.khaata.feature.shared.UpcomingRow
import ai.labs32.khaata.feature.shared.budgetStatusLabel
import ai.labs32.khaata.feature.shared.budgetStatusColor
import ai.labs32.khaata.feature.shared.chartMoneyFormatter
import ai.labs32.khaata.navigation.Routes

/**
 * The home screen: what needs me today?
 *
 * One headline figure, then only what needs doing, then today's spending. The headline is what can
 * safely be spent today -- the sum of every budget's own daily pace -- rather than a balance,
 * because it is the number that answers "can I afford this?", which is the question people open a
 * finance app to ask. With no budgets yet it falls back to available to spend.
 *
 * The six shortcut tiles that used to sit here are gone: their destinations have tabs of their own
 * now, and a grid of links is the least useful thing to put on the first screen of an app.
 */
@Composable
fun DashboardScreen(
    onNavigate: (String) -> Unit,
    onAddTransaction: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isLoading -> LoadingState()

        state.error != null -> ErrorState(
            message = state.error!!,
            onRetry = viewModel::retry,
        )

        // Nothing recorded yet, but the account and balance from setup are real: the headline shows
        // them straight away, so the first screen after onboarding reflects what was just entered.
        state.isEmpty -> Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KhaataTheme.spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
        ) {
            HomeTopBar(state = state, onToggleVisibility = viewModel::toggleAmountVisibility, onNavigate = onNavigate)
            HomeHero(state = state, onNavigate = onNavigate)
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                title = stringResource(R.string.dashboard_empty_title),
                description = stringResource(R.string.dashboard_empty_body),
                actionLabel = stringResource(R.string.dashboard_empty_action),
                onAction = onAddTransaction,
            )
        }

        else -> DashboardContent(
            state = state,
            onToggleVisibility = viewModel::toggleAmountVisibility,
            onNavigate = onNavigate,
            onOpenTransaction = onOpenTransaction,
            onSnoozeInsight = viewModel::snoozeInsight,
            onBillPaid = viewModel::markBillPaid,
        )
    }
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onToggleVisibility: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onSnoozeInsight: (String) -> Unit,
    onBillPaid: (DueOccurrence) -> Unit,
) {
    val spacing = KhaataTheme.spacing

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            bottom = spacing.bottomBarClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        item("top-bar") {
            HomeTopBar(state = state, onToggleVisibility = onToggleVisibility, onNavigate = onNavigate)
        }

        item("hero") {
            HomeHero(state = state, onNavigate = onNavigate)
        }

        if (state.isDemoMode) {
            item("demo-banner") {
                DemoBanner(onManage = { onNavigate(Routes.SETTINGS) })
            }
        }

        item("needs-you") {
            NeedsYouCard(state = state, onNavigate = onNavigate, onBillPaid = onBillPaid)
        }

        items(state.visibleCards, key = { it.name }) { card ->
            Box(Modifier.animateItem()) {
                when (card) {
                    DashboardCard.SPENDING_OVERVIEW -> SpendingOverviewCard(state)
                    DashboardCard.AI_INSIGHT -> InsightCard(
                        state = state,
                        onSeeAll = { onNavigate(Routes.INSIGHTS) },
                        onAdjustBudget = { budgetId -> onNavigate(Routes.editBudget(budgetId)) },
                        onSnooze = onSnoozeInsight,
                    )
                    DashboardCard.BUDGET_PROGRESS -> BudgetProgressCard(
                        state = state,
                        onSeeAll = { onNavigate(Routes.PLAN) },
                    )
                    DashboardCard.UPCOMING_PAYMENTS -> UpcomingPaymentsCard(
                        state = state,
                        onSeeAll = { onNavigate(Routes.PLAN) },
                    )
                    DashboardCard.CATEGORY_BREAKDOWN -> CategoryBreakdownCard(
                        state = state,
                        onSeeAll = { onNavigate(Routes.TRANSACTIONS) },
                    )
                    DashboardCard.RECENT_TRANSACTIONS -> RecentTransactionsCard(
                        state = state,
                        onOpenTransaction = onOpenTransaction,
                        onSeeAll = { onNavigate(Routes.TRANSACTIONS) },
                    )
                    DashboardCard.GOALS -> GoalsCard(
                        state = state,
                        onSeeAll = { onNavigate(Routes.GOALS) },
                    )
                    DashboardCard.ACCOUNTS -> AccountsCard(
                        state = state,
                        onSeeAll = { onNavigate(Routes.MONEY) },
                    )
                    DashboardCard.SUBSCRIPTIONS -> SubscriptionsCard(
                        state = state,
                        onSeeAll = { onNavigate(Routes.SUBSCRIPTIONS) },
                    )
                    DashboardCard.NET_WORTH_TREND -> NetWorthTrendCard(state)
                }
            }
        }
    }
}

// ---- Top bar ---------------------------------------------------------------------------------

/**
 * Greeting and date, then the three things reachable from Home: the assistant, the privacy toggle
 * and settings. Settings used to be the last entry on a More tab; the avatar is where people look
 * for it in every other app.
 */
@Composable
private fun HomeTopBar(
    state: DashboardUiState,
    onToggleVisibility: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val greeting = stringResource(
        when (state.greetingKey) {
            GreetingKey.MORNING -> R.string.dashboard_greeting_morning
            GreetingKey.AFTERNOON -> R.string.dashboard_greeting_afternoon
            GreetingKey.EVENING -> R.string.dashboard_greeting_evening
        },
    )
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KhaataTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = LocalDate.now().format(dateFormatter),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.displayName?.let { "$greeting, $it" } ?: greeting,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        IconButton(onClick = { onNavigate(Routes.AI_ASSISTANT) }) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = stringResource(R.string.ai_title),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onToggleVisibility) {
            Icon(
                imageVector = if (state.amountsHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = stringResource(
                    if (state.amountsHidden) R.string.a11y_show_amounts else R.string.a11y_hide_amounts,
                ),
            )
        }
        IconButton(onClick = { onNavigate(Routes.SETTINGS) }) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---- Hero ------------------------------------------------------------------------------------

/**
 * The headline card.
 *
 * Safe to spend today when there are budgets to derive it from, available to spend otherwise.
 * Under it the month's budget as a bar, with a marker at today's share of the month so "ahead of
 * the calendar" is visible without a percentage to read, and the month's three figures below.
 */
@Composable
private fun HomeHero(state: DashboardUiState, onNavigate: (String) -> Unit) {
    val onHero = Color.White
    val onHeroMuted = Color.White.copy(alpha = 0.74f)
    val daily = state.dailySafeSpend
    val overall = state.overallBudget
    val status = overall?.status ?: state.budgetProgress.maxByOrNull { it.status.ordinal }?.status

    KhaataHeroCard(modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    if (daily != null) R.string.home_safe_today else R.string.dashboard_available_to_spend,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = onHeroMuted,
                modifier = Modifier.weight(1f),
            )
            status?.let { HeroStatusPill(it) }
        }
        Spacer(Modifier.height(4.dp))

        AnimatedHeroAmount(
            money = daily ?: state.availableToSpend,
            hidden = state.amountsHidden,
        )

        when {
            overall != null && !state.amountsHidden -> {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.home_left_of,
                        MoneyFormatter.plain(overall.remaining.floorAtZero()),
                        MoneyFormatter.plain(overall.limit),
                    ) + " · " + pluralStringResource(
                        R.plurals.budgets_days_left,
                        overall.daysRemaining,
                        overall.daysRemaining,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = onHeroMuted,
                )
                Spacer(Modifier.height(12.dp))
                val periodDays = overall.daysElapsed + overall.daysRemaining
                PaceBar(
                    fraction = overall.percentUsedClamped / 100f,
                    paceFraction = if (periodDays > 0) overall.daysElapsed.toFloat() / periodDays else null,
                    description = "${budgetStatusLabel(overall.status)}, ${overall.percentUsedClamped}%",
                    color = KhaataPalette.Brass70,
                    trackColor = Color.White.copy(alpha = 0.18f),
                    markerColor = Color.White,
                )
            }
            daily != null && !state.amountsHidden -> {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.dashboard_available_to_spend) + " " +
                        MoneyFormatter.plain(state.availableToSpend),
                    style = MaterialTheme.typography.bodySmall,
                    color = onHeroMuted,
                )
            }
            state.budgetProgress.isEmpty() -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_set_budget),
                    style = MaterialTheme.typography.bodySmall,
                    color = onHero,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onNavigate(Routes.ADD_BUDGET) }
                        .padding(vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.monthSummary?.let { summary ->
                HeroStat(
                    label = stringResource(R.string.home_spent),
                    value = summary.expense,
                    hidden = state.amountsHidden,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(Routes.TRANSACTIONS) },
                )
                HeroStat(
                    label = stringResource(R.string.home_received),
                    value = summary.income,
                    hidden = state.amountsHidden,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(Routes.TRANSACTIONS) },
                )
            }
            state.netWorth?.let { netWorth ->
                HeroStat(
                    label = stringResource(R.string.dashboard_net_worth),
                    value = netWorth.netWorth,
                    hidden = state.amountsHidden,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(Routes.MONEY) },
                )
            }
        }
    }
}

/**
 * The hero figure, counting to its new value rather than jumping, so a change -- a spend just
 * saved, a budget just set -- is something the eye catches instead of has to find.
 */
@Composable
private fun AnimatedHeroAmount(money: Money, hidden: Boolean) {
    val animated by animateFloatAsState(
        targetValue = money.amount.toFloat(),
        animationSpec = tween(durationMillis = 500),
        label = "hero-amount",
    )
    // The animation only drives what is drawn; the settled value is formatted from the exact
    // amount, so a rounding artefact of the float can never be the figure left on screen.
    val shown = if (animated == money.amount.toFloat()) {
        money
    } else {
        Money.of(java.math.BigDecimal.valueOf(animated.toDouble()).setScale(0, java.math.RoundingMode.HALF_EVEN), money.currency)
    }
    HeroAmount(money = shown, hidden = hidden, color = Color.White)
}

@Composable
private fun HeroStatusPill(status: BudgetStatus) {
    val label = when (status) {
        BudgetStatus.ON_TRACK -> R.string.home_on_track
        BudgetStatus.PROJECTED_OVER, BudgetStatus.NEARING_LIMIT -> R.string.home_ahead_of_pace
        BudgetStatus.EXHAUSTED, BudgetStatus.OVERSPENT -> R.string.home_over_budget
    }
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (status == BudgetStatus.ON_TRACK) {
                    Color.White.copy(alpha = 0.14f)
                } else {
                    KhaataPalette.Brass70.copy(alpha = 0.45f)
                },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun HeroStat(
    label: String,
    value: Money,
    hidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.74f),
            maxLines = 1,
        )
        if (hidden) {
            val hiddenDescription = stringResource(R.string.a11y_amount_hidden)
            Text(
                text = "••••",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.clearAndSetSemantics { contentDescription = hiddenDescription },
            )
        } else {
            MoneyText(
                money = value,
                moneyStyle = MoneyStyle.COMPACT,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
        }
    }
}

// ---- Needs you -------------------------------------------------------------------------------

/**
 * Everything on Home that wants an action, each with its action on the row.
 *
 * Bank messages waiting for confirmation, bills whose date has passed, card payments coming due,
 * budgets running ahead. These used to be a banner, a card further down, and two screens away;
 * gathered here, "is there anything I need to do?" has one place to look, and an empty list says
 * so plainly rather than making the user scroll to be sure.
 */
@Composable
private fun NeedsYouCard(
    state: DashboardUiState,
    onNavigate: (String) -> Unit,
    onBillPaid: (DueOccurrence) -> Unit,
) {
    val money = KhaataTheme.money

    KhaataCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.home_needs_you),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )

        if (state.needsAttentionCount == 0) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = money.income,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_all_clear),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            NeedsYouRows(state = state, onNavigate = onNavigate, onBillPaid = onBillPaid)
        }
    }
}

@Composable
private fun NeedsYouRows(
    state: DashboardUiState,
    onNavigate: (String) -> Unit,
    onBillPaid: (DueOccurrence) -> Unit,
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM") }
    val money = KhaataTheme.money
    // Rows so far, so each one after the first is set off by a divider.
    var shown = 0
    val divider = MaterialTheme.colorScheme.outlineVariant

    if (state.pendingImportCount > 0) {
        if (shown++ > 0) HorizontalDivider(color = divider)
        NeedsYouRow(
            icon = Icons.Outlined.Sms,
            tint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.dashboard_pending_imports, state.pendingImportCount),
            subtitle = null,
            actionLabel = stringResource(R.string.home_review),
            onAction = { onNavigate(Routes.PENDING_IMPORTS) },
        )
    }

    // One row per bill, not per missed date: a rent nobody confirmed for three months is one
    // thing to deal with, and listing each month pushed everything else off the card. The row
    // offers the oldest date first, since confirming in order keeps the ledger in order; the rest
    // are counted beside it and all of them are on Plan.
    val billsByRule = remember(state.awaitingBills) {
        state.awaitingBills.groupBy { it.rule.id }.values.map { it.first() to it.size - 1 }
    }
    billsByRule.take(MAX_BILLS_IN_NEEDS_YOU).forEach { (occurrence, more) ->
        if (shown++ > 0) HorizontalDivider(color = divider)
        val due = stringResource(R.string.recurring_was_due, occurrence.dueOn.format(dateFormatter))
        NeedsYouRow(
            icon = Icons.Outlined.EventRepeat,
            tint = money.warning,
            title = "${occurrence.rule.name} · ${MoneyFormatter.plain(occurrence.rule.amount)}",
            subtitle = if (more > 0) {
                due + " · " + pluralStringResource(R.plurals.home_more_waiting, more, more)
            } else {
                due
            },
            actionLabel = stringResource(R.string.recurring_confirm_post),
            onAction = { onBillPaid(occurrence) },
        )
    }
    if (billsByRule.size > MAX_BILLS_IN_NEEDS_YOU) {
        if (shown++ > 0) HorizontalDivider(color = divider)
        val extra = billsByRule.size - MAX_BILLS_IN_NEEDS_YOU
        NeedsYouRow(
            icon = Icons.Outlined.EventRepeat,
            tint = money.warning,
            title = pluralStringResource(R.plurals.home_more_bills, extra, extra),
            subtitle = null,
            actionLabel = stringResource(R.string.home_open),
            onAction = { onNavigate(Routes.PLAN) },
        )
    }

    state.cardsDueSoon.forEach { status ->
        if (shown++ > 0) HorizontalDivider(color = divider)
        val overdue = status.isOverdue(LocalDate.now())
        NeedsYouRow(
            icon = Icons.Outlined.CreditCard,
            tint = if (overdue) money.expense else money.warning,
            title = if (overdue) {
                "${status.card.cardName} · ${stringResource(R.string.cards_overdue)}"
            } else {
                stringResource(
                    R.string.home_card_bill_due,
                    status.card.cardName,
                    relativeDateLabel(status.paymentDueOn),
                )
            },
            subtitle = MoneyFormatter.plain(status.statementBalance.takeIf { it.isPositive } ?: status.outstanding),
            actionLabel = stringResource(R.string.home_open),
            onAction = { onNavigate(Routes.creditCardDetail(status.card.id)) },
        )
    }

    state.budgetsNeedingAttention.take(MAX_BUDGETS_IN_NEEDS_YOU).forEach { progress ->
        if (shown++ > 0) HorizontalDivider(color = divider)
        NeedsYouRow(
            icon = Icons.Outlined.PieChart,
            tint = budgetStatusColor(progress.status),
            title = stringResource(R.string.home_budget_used, progress.budget.name, progress.percentUsedClamped),
            subtitle = budgetStatusLabel(progress.status),
            actionLabel = stringResource(R.string.home_open),
            onAction = { onNavigate(Routes.budgetDetail(progress.budget.id)) },
        )
    }
}

@Composable
private fun NeedsYouRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onAction) {
            Text(actionLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DemoBanner(onManage: () -> Unit) {
    // A plain row rather than a card: it is context about the data on screen, not something to
    // act on the way the "Needs you" items are.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onManage)
            .padding(horizontal = KhaataTheme.spacing.small, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Science,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.dashboard_demo_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.dashboard_demo_exit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private const val MAX_BUDGETS_IN_NEEDS_YOU = 2
private const val MAX_BILLS_IN_NEEDS_YOU = 3

// ---- Cards -----------------------------------------------------------------------------------

@Composable
private fun SpendingOverviewCard(state: DashboardUiState) {
    val summary = state.monthSummary ?: return
    val money = KhaataTheme.money

    KhaataCard(tier = KhaataCardTier.Emphasized) {
        CardHeader(title = stringResource(R.string.dashboard_this_month))
        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        Row(Modifier.fillMaxWidth()) {
            KhaataStatTile(
                label = stringResource(R.string.dashboard_income),
                tint = money.income,
                modifier = Modifier.weight(1f),
            ) {
                MoneyText(
                    money = summary.income,
                    style = KhaataTextStyles.amountLarge,
                    color = money.income,
                )
            }
            Spacer(Modifier.width(KhaataTheme.spacing.small))
            KhaataStatTile(
                label = stringResource(R.string.dashboard_expenses),
                tint = money.expense,
                modifier = Modifier.weight(1f),
            ) {
                MoneyText(
                    money = summary.expense,
                    style = KhaataTextStyles.amountLarge,
                    color = money.expense,
                )
            }
        }

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.dashboard_saved),
            leadingValue = {
                MoneyText(
                    money = summary.net,
                    style = KhaataTextStyles.amountMedium,
                    signStyle = SignStyle.ALWAYS,
                    color = if (summary.isSurplus) money.income else money.expense,
                )
            },
            trailingLabel = stringResource(R.string.dashboard_savings_rate),
            trailingValue = {
                Text(
                    text = MoneyFormatter.percentage(summary.savingsRatePercent, decimals = 0),
                    style = KhaataTextStyles.amountMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
        )
    }
}

@Composable
private fun InsightCard(
    state: DashboardUiState,
    onSeeAll: () -> Unit,
    onAdjustBudget: (String) -> Unit,
    onSnooze: (String) -> Unit,
) {
    val insight = state.topInsight ?: return

    // The card itself still opens the full list -- the buttons below are the common action taken
    // straight from Home, not a replacement for the tap.
    KhaataCard(onClick = onSeeAll) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dashboard_ai_insight),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        Text(
            text = insight.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = insight.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(KhaataTheme.spacing.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            // Adjust budget only appears when this insight is actually about one -- it opens that
            // budget to edit rather than silently changing the limit itself, the same "the user
            // decides the number" rule every other screen in this app already follows.
            insight.budgetId?.let { budgetId ->
                OutlinedButton(onClick = { onAdjustBudget(budgetId) }) {
                    Text(stringResource(R.string.insight_action_adjust_budget))
                }
            }
            OutlinedButton(onClick = { onSnooze(insight.id) }) {
                Text(stringResource(R.string.insight_action_snooze))
            }
        }
    }
}

@Composable
private fun BudgetProgressCard(state: DashboardUiState, onSeeAll: () -> Unit) {
    if (state.budgetProgress.isEmpty()) return
    val categoriesById = remember(state.categories) { state.categories.associateBy { it.id } }

    KhaataCard {
        CardHeader(
            title = stringResource(R.string.dashboard_budget_progress),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = onSeeAll,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        state.budgetProgress.take(3).forEach { progress ->
            // The first covered category stands in for the budget's icon and colour. A budget can
            // cover several categories, or none at all for an overall limit -- CategoryIcons
            // already falls back to a neutral glyph for a null key, and a budget with no category
            // to borrow a seed from gets a stable one derived from its own id instead.
            val linkedCategory = progress.budget.categoryIds.firstOrNull()?.let { categoriesById[it] }
            val isOffTrack = progress.status != BudgetStatus.ON_TRACK
            val statusColor = budgetStatusColor(progress.status)

            Row(
                Modifier.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorBadge(
                    icon = CategoryIcons[linkedCategory?.iconKey],
                    colorSeed = linkedCategory?.colorSeed ?: progress.budget.id.hashCode(),
                    size = 32.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = progress.budget.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.budgets_spent_of,
                                MoneyFormatter.compact(progress.spent),
                                MoneyFormatter.compact(progress.limit),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            // Off track colours the figure itself rather than only the status
                            // label below it, so the number that actually needs attention is
                            // where the colour lands.
                            color = if (isOffTrack) statusColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LabelledProgress(
                        progressPercent = progress.percentUsedClamped,
                        statusLabel = budgetStatusLabel(progress.status),
                        progressColor = statusColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingPaymentsCard(state: DashboardUiState, onSeeAll: () -> Unit) {
    if (state.upcoming.isEmpty()) return

    KhaataCard {
        CardHeader(
            title = stringResource(R.string.dashboard_upcoming_payments),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = onSeeAll,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        state.upcoming.forEach { occurrence ->
            UpcomingRow(occurrence = occurrence)
        }
    }
}

@Composable
private fun CategoryBreakdownCard(state: DashboardUiState, onSeeAll: () -> Unit) {
    if (state.categoryBreakdown.isEmpty()) return
    val money = KhaataTheme.money

    val slices = state.categoryBreakdown.map { spend ->
        ChartSlice(
            label = spend.category?.name ?: stringResource(R.string.categories_uncategorised),
            value = spend.amount.amount.toFloat(),
            color = money.swatch(spend.category?.colorSeed ?: 0),
        )
    }
    val total = state.monthSummary?.expense

    KhaataCard {
        CardHeader(
            title = stringResource(R.string.dashboard_category_breakdown),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = onSeeAll,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.default))

        // Donut beside its legend rather than above it -- the two were never competing for the
        // same horizontal space, only stacked because nobody had shrunk the donut enough to sit
        // next to six rows of text. 132dp is small enough to leave the legend room without
        // clipping it on a compact phone width.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp)) {
                DonutChart(
                    slices = slices,
                    centerLabel = stringResource(R.string.dashboard_expenses),
                    centerValue = total?.let { MoneyFormatter.compact(it) },
                )
            }

            Spacer(Modifier.width(KhaataTheme.spacing.default))

            ChartLegend(
                modifier = Modifier.weight(1f),
                slices = slices,
                valueFormatter = chartMoneyFormatter(state.currency),
            )
        }
    }
}

@Composable
private fun RecentTransactionsCard(
    state: DashboardUiState,
    onOpenTransaction: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    if (state.recentTransactions.isEmpty()) return

    KhaataCard(contentPadding = PaddingValues(vertical = 16.dp)) {
        CardHeader(
            title = stringResource(R.string.dashboard_recent_transactions),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = onSeeAll,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))

        val categoriesById = remember(state.categories) { state.categories.associateBy { it.id } }
        val accountsById = remember(state.accounts) {
            state.accounts.associateBy { it.account.id }
        }

        state.recentTransactions.forEach { transaction ->
            val category = categoriesById[transaction.categoryId]

            TransactionRow(
                transaction = transaction,
                categoryName = category?.name,
                accountName = accountsById[transaction.accountId]?.account?.name,
                transferAccountName = transaction.transferAccountId?.let { accountsById[it]?.account?.name },
                categoryColorSeed = category?.colorSeed ?: 0,
                categoryIconKey = category?.iconKey,
                onClick = { onOpenTransaction(transaction.id) },
            )
        }
    }
}

@Composable
private fun GoalsCard(state: DashboardUiState, onSeeAll: () -> Unit) {
    if (state.goals.isEmpty()) return

    KhaataCard {
        CardHeader(
            title = stringResource(R.string.dashboard_goals),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = onSeeAll,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        state.goals.forEach { progress ->
            // A completed goal is shown in the income colour regardless of its own seed -- seed 2
            // is Rose70, the same colour as money.expense, so a finished goal could otherwise draw
            // a rose bar and read as a warning. Matches the same fix on the Goals screen itself.
            val swatch = if (progress.isAchieved) {
                KhaataTheme.money.income
            } else {
                KhaataTheme.money.swatch(progress.goal.colorSeed)
            }
            Row(
                Modifier.padding(vertical = 8.dp),
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
                    Text(
                        text = progress.goal.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    LabelledProgress(
                        progressPercent = progress.percentCompleteClamped,
                        statusLabel = progress.goal.name,
                        progressColor = swatch,
                        height = 6.dp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${progress.percentCompleteClamped}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccountsCard(state: DashboardUiState, onSeeAll: () -> Unit) {
    if (state.accounts.isEmpty()) return

    KhaataCard {
        CardHeader(
            title = stringResource(R.string.dashboard_accounts),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = onSeeAll,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))

        state.accounts.take(4).forEach { balance ->
            Row(
                Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorBadge(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    colorSeed = balance.account.colorSeed,
                    size = 36.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = balance.account.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    balance.account.institution?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (state.amountsHidden) {
                    val hiddenDescription = stringResource(R.string.a11y_amount_hidden)
                    Text(
                        text = "••••",
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = hiddenDescription
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    MoneyText(
                        money = balance.displayBalance,
                        style = KhaataTextStyles.amountMedium,
                        color = if (balance.isOwed) {
                            KhaataTheme.money.expense
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionsCard(state: DashboardUiState, onSeeAll: () -> Unit) {
    val cost = state.subscriptionCost ?: return
    if (cost.count == 0) return

    KhaataCard(onClick = onSeeAll) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Subscriptions,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dashboard_subscriptions),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(KhaataTheme.spacing.medium))
        StatPair(
            leadingLabel = stringResource(R.string.subscriptions_total_monthly),
            leadingValue = {
                MoneyText(money = cost.perMonth, style = KhaataTextStyles.amountLarge)
            },
            trailingLabel = stringResource(R.string.subscriptions_total_yearly),
            trailingValue = {
                MoneyText(money = cost.perYear, style = KhaataTextStyles.amountLarge)
            },
        )
    }
}

@Composable
private fun NetWorthTrendCard(state: DashboardUiState) {
    if (state.netWorthTrend.size < 2) return

    KhaataCard {
        CardHeader(title = stringResource(R.string.dashboard_net_worth_trend))
        Spacer(Modifier.height(KhaataTheme.spacing.default))
        TrendLineChart(
            points = state.netWorthTrend.map { (label, value) -> ChartPoint(label, value) },
            valueFormatter = chartMoneyFormatter(state.currency),
        )
    }
}
