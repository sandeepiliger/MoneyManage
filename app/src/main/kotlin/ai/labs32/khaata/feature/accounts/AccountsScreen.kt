package ai.labs32.khaata.feature.accounts

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.BalanceCalculator
import ai.labs32.khaata.core.calc.NetWorthSummary
import ai.labs32.khaata.core.model.AccountBalance
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.EntitlementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

data class AccountsUiState(
    val isLoading: Boolean = true,
    val balances: List<AccountBalance> = emptyList(),
    val netWorth: NetWorthSummary? = null,
    val availableToSpend: Money = Money.zero(),
    val remainingSlots: Int? = null,
) {
    val active: List<AccountBalance> get() = balances.filter { !it.account.isArchived }
    val archived: List<AccountBalance> get() = balances.filter { it.account.isArchived }
    val atFreeLimit: Boolean get() = remainingSlots == 0
}

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val entitlementRepository: EntitlementRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    init {
        accountRepository.observeBalances()
            .onEach { balances ->
                val activeCount = balances.count { !it.account.isArchived }
                _uiState.value = AccountsUiState(
                    isLoading = false,
                    balances = balances,
                    netWorth = BalanceCalculator.netWorth(balances),
                    availableToSpend = BalanceCalculator.availableToSpend(balances),
                    // Null means unlimited. Read per emission rather than combined, because the
                    // limit depends on the account count that just changed.
                    remainingSlots = entitlementRepository
                        .observeRemainingAccountSlots(activeCount)
                        .first(),
                )
            }
            .launchIn(viewModelScope)
    }
}

/**
 * The accounts list.
 *
 * Leads with net worth and available-to-spend side by side, because the distinction between them
 * is the thing this screen exists to make concrete: a PPF balance counts towards one and not the
 * other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onUpgrade: () -> Unit,
    onBack: () -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = if (state.atFreeLimit) onUpgrade else onAddAccount) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.accounts_add))
            }
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            state.balances.isEmpty() -> EmptyState(
                icon = Icons.Outlined.AccountBalanceWallet,
                title = stringResource(R.string.accounts_empty_title),
                description = stringResource(R.string.accounts_empty_body),
                modifier = Modifier.padding(padding),
                actionLabel = stringResource(R.string.accounts_add),
                onAction = onAddAccount,
            )

            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
            ) {
                item { NetWorthCard(state) }

                items(state.active, key = { it.account.id }) { balance ->
                    AccountCard(balance = balance, onClick = { onOpenAccount(balance.account.id) })
                }

                if (state.archived.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.accounts_archived),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = KhaataTheme.spacing.default),
                        )
                    }
                    items(state.archived, key = { it.account.id }) { balance ->
                        AccountCard(
                            balance = balance,
                            onClick = { onOpenAccount(balance.account.id) },
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.accounts_never_credentials),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = KhaataTheme.spacing.large),
                    )
                }
            }
        }
    }
}

@Composable
private fun NetWorthCard(state: AccountsUiState) {
    val netWorth = state.netWorth ?: return

    KhaataCard {
        CardHeader(title = stringResource(R.string.dashboard_net_worth))
        Spacer(Modifier.height(KhaataTheme.spacing.small))

        MoneyText(money = netWorth.netWorth, style = KhaataTextStyles.amountHero)

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.dashboard_available_to_spend),
            leadingValue = {
                MoneyText(money = state.availableToSpend, style = KhaataTextStyles.amountMedium)
            },
            trailingLabel = stringResource(R.string.cards_outstanding),
            trailingValue = {
                MoneyText(
                    money = netWorth.liabilities,
                    style = KhaataTextStyles.amountMedium,
                    color = KhaataTheme.money.expense,
                )
            },
        )
    }
}

@Composable
private fun AccountCard(balance: AccountBalance, onClick: () -> Unit) {
    KhaataCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorBadge(
                icon = Icons.Outlined.AccountBalanceWallet,
                colorSeed = balance.account.colorSeed,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = balance.account.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    balance.account.institution,
                    balance.account.maskedIdentifier?.let { "••$it" },
                ).joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(
                    money = balance.displayBalance,
                    style = KhaataTextStyles.amountLarge,
                    color = if (balance.account.isLiability) {
                        KhaataTheme.money.expense
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                // Liabilities are labelled rather than just coloured, so "owed" is never
                // inferred from a hue.
                if (balance.account.isLiability) {
                    Text(
                        text = stringResource(R.string.cards_outstanding),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
