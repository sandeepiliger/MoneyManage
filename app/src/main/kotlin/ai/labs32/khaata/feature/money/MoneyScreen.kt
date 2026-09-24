package ai.labs32.khaata.feature.money

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.CreditCardStatus
import ai.labs32.khaata.core.calc.LoanStatus
import ai.labs32.khaata.core.calc.NetWorthSummary
import ai.labs32.khaata.core.model.AccountBalance
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.CategoryIcons
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.KhaataCardTier
import ai.labs32.khaata.core.ui.components.KhaataStatTile
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.PaceBar
import ai.labs32.khaata.core.ui.components.Sparkline
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.shared.AddRow
import ai.labs32.khaata.feature.shared.relativeDateLabel
import ai.labs32.khaata.navigation.Routes

/**
 * The Money tab: what you have, what you owe, and what it adds up to.
 *
 * Every row opens the same screen it did before -- an account's history, a card's cycle, a loan's
 * schedule -- so nothing is lost by the move; it is only found in one place now instead of four.
 */
@Composable
fun MoneyScreen(
    onNavigate: (String) -> Unit,
    viewModel: MoneyViewModel = hiltViewModel(),
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
            Text(
                text = stringResource(R.string.nav_money),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .padding(top = spacing.default)
                    .semantics { heading() },
            )
        }

        state.netWorth?.let { netWorth ->
            item("net-worth") { NetWorthCard(netWorth, state) }
        }

        item("bank-cash") {
            Section(
                title = stringResource(R.string.money_bank_cash),
                // Only balances in the profile currency are added up; a foreign-currency account
                // keeps its own figure on its row but cannot be summed into rupees.
                total = state.spendable
                    .filter { it.currentBalance.currency == state.currency }
                    .takeIf { it.isNotEmpty() }
                    ?.fold(Money.zero(state.currency)) { sum, balance -> sum + balance.currentBalance },
                onManage = { onNavigate(Routes.ACCOUNTS) },
            ) {
                state.spendable.forEachIndexed { index, balance ->
                    if (index > 0) RowDivider()
                    AccountRow(balance, onClick = { onNavigate(Routes.accountDetail(balance.account.id)) })
                }
                AddRow(label = stringResource(R.string.accounts_add), onClick = { onNavigate(Routes.ADD_ACCOUNT) })
            }
        }

        item("cards") {
            Section(
                title = stringResource(R.string.cards_title),
                onManage = if (state.hasCards) ({ onNavigate(Routes.CREDIT_CARDS) }) else null,
            ) {
                state.cards.forEachIndexed { index, status ->
                    if (index > 0) RowDivider()
                    CardRow(status, onClick = { onNavigate(Routes.creditCardDetail(status.card.id)) })
                }
                state.unlinkedCardAccounts.forEach { balance ->
                    if (state.cards.isNotEmpty()) RowDivider()
                    AccountRow(balance, onClick = { onNavigate(Routes.accountDetail(balance.account.id)) })
                }
                AddRow(label = stringResource(R.string.cards_add), onClick = { onNavigate(Routes.ADD_CREDIT_CARD) })
            }
        }

        item("loans") {
            Section(
                title = stringResource(R.string.loans_title),
                onManage = if (state.hasLoans) ({ onNavigate(Routes.LOANS) }) else null,
            ) {
                state.loans.forEachIndexed { index, status ->
                    if (index > 0) RowDivider()
                    LoanRow(status, onClick = { onNavigate(Routes.loanDetail(status.loan.id)) })
                }
                state.unlinkedLoanAccounts.forEach { balance ->
                    if (state.loans.isNotEmpty()) RowDivider()
                    AccountRow(balance, onClick = { onNavigate(Routes.accountDetail(balance.account.id)) })
                }
                AddRow(label = stringResource(R.string.loans_add), onClick = { onNavigate(Routes.ADD_LOAN) })
            }
        }

        item("investments") {
            Section(
                title = stringResource(R.string.investments_title),
                onManage = if (state.hasInvestments) ({ onNavigate(Routes.INVESTMENTS) }) else null,
            ) {
                state.portfolio?.takeIf { it.holdingsCount > 0 }?.let { portfolio ->
                    ProductRow(
                        icon = Icons.AutoMirrored.Outlined.ShowChart,
                        colorSeed = INVESTMENT_SEED,
                        title = stringResource(R.string.money_holdings, portfolio.holdingsCount),
                        subtitle = stringResource(R.string.money_invested, MoneyFormatter.plain(portfolio.invested)),
                        amount = portfolio.currentValue,
                        owed = false,
                        onClick = { onNavigate(Routes.INVESTMENTS) },
                    )
                }
                state.investmentAccounts.forEach { balance ->
                    if ((state.portfolio?.holdingsCount ?: 0) > 0) RowDivider()
                    AccountRow(balance, onClick = { onNavigate(Routes.accountDetail(balance.account.id)) })
                }
                AddRow(
                    label = stringResource(R.string.investments_add),
                    onClick = { onNavigate(Routes.ADD_INVESTMENT) },
                )
            }
        }

        if (state.otherAccounts.isNotEmpty()) {
            item("other") {
                Section(title = stringResource(R.string.money_other_accounts), onManage = null) {
                    state.otherAccounts.forEachIndexed { index, balance ->
                        if (index > 0) RowDivider()
                        AccountRow(balance, onClick = { onNavigate(Routes.accountDetail(balance.account.id)) })
                    }
                }
            }
        }
    }
}

/**
 * The headline: net worth, split into what you have and what you owe.
 *
 * The split is the point. A net worth of ₹4.8 lakh reads very differently when it is ₹6.1 lakh
 * held against ₹1.3 lakh owed, and neither half was visible anywhere before.
 */
@Composable
private fun NetWorthCard(netWorth: NetWorthSummary, state: MoneyUiState) {
    val money = KhaataTheme.money
    KhaataCard(tier = KhaataCardTier.Emphasized) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dashboard_net_worth),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyText(money = netWorth.netWorth, style = KhaataTextStyles.amountLarge)
                state.netWorthChangePercent?.let { percent ->
                    val rising = percent.signum() >= 0
                    val formatted = MoneyFormatter.percentage(percent, decimals = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (rising) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = if (rising) money.income else money.expense,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.dashboard_vs_last_month,
                                if (percent.signum() > 0) "+$formatted" else formatted,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (rising) money.income else money.expense,
                        )
                    }
                }
            }
            if (state.netWorthTrend.size >= 2) {
                Sparkline(
                    values = state.netWorthTrend,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .width(96.dp)
                        .height(40.dp),
                )
            }
        }
        Spacer(Modifier.height(KhaataTheme.spacing.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            KhaataStatTile(
                label = stringResource(R.string.money_you_have),
                tint = money.income,
                modifier = Modifier.weight(1f),
            ) {
                MoneyText(money = netWorth.assets, style = KhaataTextStyles.amountMedium, color = money.income)
            }
            KhaataStatTile(
                label = stringResource(R.string.money_you_owe),
                tint = money.expense,
                modifier = Modifier.weight(1f),
            ) {
                MoneyText(money = netWorth.liabilities, style = KhaataTextStyles.amountMedium, color = money.expense)
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    onManage: (() -> Unit)?,
    total: Money? = null,
    content: @Composable () -> Unit,
) {
    KhaataCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        CardHeader(
            title = title,
            subtitle = total?.let { MoneyFormatter.plain(it) },
            actionLabel = onManage?.let { stringResource(R.string.money_manage) },
            onAction = onManage,
        )
        content()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun AccountRow(balance: AccountBalance, onClick: () -> Unit) {
    val account = balance.account
    ProductRow(
        icon = accountIcon(balance),
        colorSeed = account.colorSeed,
        title = account.name,
        subtitle = listOfNotNull(account.institution, account.maskedIdentifier?.let { "••$it" })
            .joinToString(" · ")
            .ifBlank { null },
        amount = balance.displayBalance,
        owed = balance.isOwed,
        onClick = onClick,
    )
}

@Composable
private fun CardRow(status: CreditCardStatus, onClick: () -> Unit) {
    val card = status.card
    ProductRow(
        icon = Icons.Outlined.CreditCard,
        colorSeed = card.colorSeed,
        title = card.cardName,
        // The same wording as the Cards screen: a due date already passed with a statement still
        // unpaid is "Overdue", not a date in the past presented as if it were still coming.
        subtitle = (
            if (status.isOverdue(java.time.LocalDate.now())) {
                stringResource(R.string.cards_overdue)
            } else {
                stringResource(R.string.money_card_due, relativeDateLabel(status.paymentDueOn))
            }
            ) + " · " + stringResource(R.string.money_card_used, status.utilisationPercentClamped),
        amount = status.outstanding,
        owed = status.outstanding.isPositive,
        onClick = onClick,
    ) {
        Spacer(Modifier.height(6.dp))
        PaceBar(
            fraction = status.utilisationPercentClamped / 100f,
            description = stringResource(R.string.money_card_used, status.utilisationPercentClamped),
            color = if (status.utilisationPercentClamped >= HIGH_UTILISATION) {
                KhaataTheme.money.expense
            } else {
                MaterialTheme.colorScheme.primary
            },
            height = 4.dp,
        )
    }
}

@Composable
private fun LoanRow(status: LoanStatus, onClick: () -> Unit) {
    ProductRow(
        icon = Icons.Outlined.RequestQuote,
        colorSeed = status.loan.colorSeed,
        title = status.loan.name,
        subtitle = stringResource(R.string.money_loan_emi, MoneyFormatter.plain(status.emi)) +
            " · " + stringResource(R.string.money_loan_repaid, status.percentRepaidClamped),
        amount = status.outstandingPrincipal,
        owed = true,
        onClick = onClick,
    )
}

/** One line in a section: badge, name, detail, and the amount -- in the expense colour when owed. */
@Composable
private fun ProductRow(
    icon: ImageVector,
    colorSeed: Int,
    title: String,
    subtitle: String?,
    amount: Money,
    owed: Boolean,
    onClick: () -> Unit,
    below: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorBadge(icon = icon, colorSeed = colorSeed, size = 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines: "Payment overdue · 31% of limit used" is the point of the row,
                    // and cut to one it lost exactly the figure that mattered.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            below()
        }
        Spacer(Modifier.width(12.dp))
        MoneyText(
            money = amount,
            style = KhaataTextStyles.amountMedium,
            color = if (owed) KhaataTheme.money.expense else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Above this, a card's utilisation starts to count against a credit score. */
private const val HIGH_UTILISATION = 30

/** A fixed seed for the portfolio row, which has no colour of its own. */
private const val INVESTMENT_SEED = 3

/**
 * An account's badge, from its type. Account icon keys are not category keys -- "bank" happens to
 * be both, "savings" and "cash" are not -- so looking them up as categories drew the generic
 * shapes glyph for most accounts.
 */
private fun accountIcon(balance: AccountBalance): ImageVector = when (balance.account.type) {
    AccountType.BANK, AccountType.SAVINGS, AccountType.CURRENT -> Icons.Outlined.AccountBalance
    AccountType.CASH -> Icons.Outlined.Payments
    AccountType.WALLET -> Icons.Outlined.AccountBalanceWallet
    AccountType.CREDIT_CARD -> Icons.Outlined.CreditCard
    AccountType.LOAN -> Icons.Outlined.RequestQuote
    AccountType.INVESTMENT -> Icons.AutoMirrored.Outlined.ShowChart
    AccountType.OTHER -> CategoryIcons[balance.account.iconKey]
}
