package ai.labs32.khaata.feature.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.DashboardCard
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.MoneyStyle
import ai.labs32.khaata.core.money.SignStyle
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.ChartLegend
import ai.labs32.khaata.core.ui.components.ChartSlice
import ai.labs32.khaata.core.ui.components.ChartPoint
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.DonutChart
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.ErrorState
import ai.labs32.khaata.core.ui.components.HeroAmount
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LabelledProgress
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.components.TrendLineChart
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.shared.TransactionRow
import ai.labs32.khaata.feature.shared.UpcomingRow
import ai.labs32.khaata.feature.shared.budgetStatusLabel
import ai.labs32.khaata.feature.shared.budgetStatusColor
import ai.labs32.khaata.navigation.Routes

/**
 * The home screen.
 *
 * The layout answers the questions in the order people actually ask them: how much do I have,
 * what happened this month, what is coming, where did it go. The lead figure is
 * "available to spend" rather than net worth — net worth is a number you check occasionally,
 * while what you can safely spend is the one you open the app for.
 *
 * Cards below the header are user-reorderable, because the right order genuinely differs: someone
 * servicing three EMIs wants upcoming payments first, someone building a habit wants recent
 * transactions.
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

        state.isEmpty -> Column {
            DashboardHeader(state = state, onToggleVisibility = viewModel::toggleAmountVisibility)
            EmptyState(
                icon = Icons.Outlined.ReceiptLong,
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
        )
    }
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onToggleVisibility: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
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
        item("header") {
            DashboardHeader(state = state, onToggleVisibility = onToggleVisibility)
        }

        if (state.isDemoMode) {
            item("demo-banner") {
                DemoBanner(onManage = { onNavigate(Routes.SETTINGS) })
            }
        }

        if (state.pendingImportCount > 0) {
            item("pending-imports") {
                PendingImportsBanner(
                    count = state.pendingImportCount,
                    onOpen = { onNavigate(Routes.PENDING_IMPORTS) },
                )
            }
        }

        items(state.visibleCards, key = { it.name }) { card ->
            when (card) {
                DashboardCard.SPENDING_OVERVIEW -> SpendingOverviewCard(state)
                DashboardCard.AI_INSIGHT -> InsightCard(
                    state = state,
                    onSeeAll = { onNavigate(Routes.INSIGHTS) },
                )
                DashboardCard.BUDGET_PROGRESS -> BudgetProgressCard(
                    state = state,
                    onSeeAll = { onNavigate(Routes.BUDGETS) },
                )
                DashboardCard.UPCOMING_PAYMENTS -> UpcomingPaymentsCard(
                    state = state,
                    onSeeAll = { onNavigate(Routes.RECURRING) },
                )
                DashboardCard.CATEGORY_BREAKDOWN -> CategoryBreakdownCard(
                    state = state,
                    onSeeAll = { onNavigate(Routes.REPORTS) },
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
                    onSeeAll = { onNavigate(Routes.ACCOUNTS) },
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

// ---- Header ----------------------------------------------------------------------------------

@Composable
private fun DashboardHeader(
    state: DashboardUiState,
    onToggleVisibility: () -> Unit,
) {
    val spacing = KhaataTheme.spacing
    val greeting = stringResource(
        when (state.greetingKey) {
            GreetingKey.MORNING -> R.string.dashboard_greeting_morning
            GreetingKey.AFTERNOON -> R.string.dashboard_greeting_afternoon
            GreetingKey.EVENING -> R.string.dashboard_greeting_evening
        },
    )

    Column(Modifier.padding(top = spacing.default, bottom = spacing.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.displayName?.let { "$greeting, $it" } ?: greeting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.dashboard_available_to_spend),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }

        Spacer(Modifier.height(4.dp))

        HeroAmount(money = state.availableToSpend, hidden = state.amountsHidden)

        state.netWorth?.let { netWorth ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.dashboard_net_worth),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                if (state.amountsHidden) {
                    Text("••••", style = MaterialTheme.typography.labelLarge)
                } else {
                    MoneyText(
                        money = netWorth.netWorth,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoBanner(onManage: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KhaataShapeTokens.cardCompact)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onManage)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.dashboard_demo_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.dashboard_demo_exit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun PendingImportsBanner(count: Int, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KhaataShapeTokens.cardCompact)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.dashboard_pending_imports, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.action_confirm),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

// ---- Cards -----------------------------------------------------------------------------------

@Composable
private fun SpendingOverviewCard(state: DashboardUiState) {
    val summary = state.monthSummary ?: return
    val money = KhaataTheme.money

    KhaataCard {
        CardHeader(title = stringResource(R.string.dashboard_this_month))
        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        StatPair(
            leadingLabel = stringResource(R.string.dashboard_income),
            leadingValue = {
                MoneyText(
                    money = summary.income,
                    style = KhaataTextStyles.amountLarge,
                    color = money.income,
                )
            },
            trailingLabel = stringResource(R.string.dashboard_expenses),
            trailingValue = {
                MoneyText(
                    money = summary.expense,
                    style = KhaataTextStyles.amountLarge,
                    color = money.expense,
                )
            },
        )

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
private fun InsightCard(state: DashboardUiState, onSeeAll: () -> Unit) {
    val insight = state.topInsight ?: return

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
    }
}

@Composable
private fun BudgetProgressCard(state: DashboardUiState, onSeeAll: () -> Unit) {
    if (state.budgetProgress.isEmpty()) return

    KhaataCard {
        CardHeader(
            title = stringResource(R.string.dashboard_budget_progress),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = onSeeAll,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        state.budgetProgress.take(3).forEach { progress ->
            Column(Modifier.padding(vertical = 6.dp)) {
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LabelledProgress(
                    progressPercent = progress.percentUsedClamped,
                    statusLabel = budgetStatusLabel(progress.status),
                    progressColor = budgetStatusColor(progress.status),
                )
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

        Box(Modifier.fillMaxWidth().height(180.dp)) {
            DonutChart(
                slices = slices,
                centerLabel = stringResource(R.string.dashboard_expenses),
                centerValue = total?.let { MoneyFormatter.compact(it) },
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        ChartLegend(
            slices = slices,
            valueFormatter = { value ->
                MoneyFormatter.compact(
                    ai.labs32.khaata.core.money.Money.of(
                        java.math.BigDecimal(value.toDouble()),
                        state.currency,
                    ),
                )
            },
        )
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
        state.recentTransactions.forEach { transaction ->
            TransactionRow(
                transaction = transaction,
                categoryName = state.categories.firstOrNull { it.id == transaction.categoryId }?.name,
                accountName = state.accounts.firstOrNull { it.account.id == transaction.accountId }
                    ?.account?.name,
                categoryColorSeed = state.categories
                    .firstOrNull { it.id == transaction.categoryId }?.colorSeed ?: 0,
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
            Row(
                Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorBadge(
                    icon = Icons.Outlined.Flag,
                    colorSeed = progress.goal.colorSeed,
                    size = 36.dp,
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
                        progressColor = KhaataTheme.money.swatch(progress.goal.colorSeed),
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
                    Text("••••", style = MaterialTheme.typography.bodyMedium)
                } else {
                    MoneyText(
                        money = balance.displayBalance,
                        style = KhaataTextStyles.amountMedium,
                        color = if (balance.account.isLiability) {
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
            valueFormatter = { value ->
                MoneyFormatter.compact(
                    ai.labs32.khaata.core.money.Money.of(
                        java.math.BigDecimal(value.toDouble()),
                        state.currency,
                    ),
                )
            },
        )
    }
}
