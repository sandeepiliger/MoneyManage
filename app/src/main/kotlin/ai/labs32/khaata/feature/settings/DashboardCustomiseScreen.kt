package ai.labs32.khaata.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.DashboardCard
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardCustomiseUiState(
    val order: List<DashboardCard> = DashboardCard.DEFAULT_ORDER,
    val hidden: Set<DashboardCard> = emptySet(),
)

/**
 * Reordering and hiding dashboard cards.
 *
 * The order and the hidden set were already stored, already read by the dashboard, and had no way
 * to be changed: `setDashboardCardOrder` and `setDashboardCardHidden` existed with no callers, so
 * every user saw the default arrangement forever.
 */
@HiltViewModel
class DashboardCustomiseViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardCustomiseUiState())
    val uiState: StateFlow<DashboardCustomiseUiState> = _uiState.asStateFlow()

    init {
        settingsRepository.settings
            .onEach { settings ->
                _uiState.update {
                    it.copy(
                        order = settings.dashboardCardOrder,
                        hidden = settings.hiddenDashboardCards,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun moveUp(card: DashboardCard) = move(card, -1)

    fun moveDown(card: DashboardCard) = move(card, 1)

    private fun move(card: DashboardCard, offset: Int) {
        val current = _uiState.value.order
        val from = current.indexOf(card)
        val to = from + offset
        if (from < 0 || to !in current.indices) return

        val reordered = current.toMutableList().apply {
            removeAt(from)
            add(to, card)
        }
        // Written straight through rather than held as draft state with a save button: there is
        // nothing to validate, and the dashboard behind this screen updates as the user goes,
        // which is the feedback that makes the arrangement worth choosing.
        viewModelScope.launch { settingsRepository.setDashboardCardOrder(reordered) }
    }

    fun setVisible(card: DashboardCard, visible: Boolean) {
        viewModelScope.launch { settingsRepository.setDashboardCardHidden(card, !visible) }
    }

    fun reset() {
        viewModelScope.launch {
            settingsRepository.setDashboardCardOrder(DashboardCard.DEFAULT_ORDER)
            DashboardCard.entries.forEach { settingsRepository.setDashboardCardHidden(it, false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardCustomiseScreen(
    onBack: () -> Unit,
    viewModel: DashboardCustomiseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.dashboard_customise)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::reset) {
                        Text(stringResource(R.string.dashboard_customise_reset))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small),
        ) {
            item(key = "help") {
                Text(
                    text = stringResource(R.string.dashboard_customise_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = KhaataTheme.spacing.small),
                )
            }

            itemsIndexed(state.order, key = { _, card -> card.name }) { index, card ->
                DashboardCardRow(
                    card = card,
                    visible = card !in state.hidden,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.order.lastIndex,
                    onMoveUp = { viewModel.moveUp(card) },
                    onMoveDown = { viewModel.moveDown(card) },
                    onVisibleChange = { viewModel.setVisible(card, it) },
                )
            }
        }
    }
}

@Composable
private fun DashboardCardRow(
    card: DashboardCard,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onVisibleChange: (Boolean) -> Unit,
) {
    KhaataCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Arrows rather than drag-and-drop. Dragging is the prettier gesture and the worse
            // control: it is close to unusable with TalkBack, hard to hit accurately on a phone
            // held one-handed, and needs a gesture library this app does not otherwise carry.
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.dashboard_customise_move_up),
                )
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.dashboard_customise_move_down),
                )
            }
            Text(
                text = stringResource(dashboardCardLabel(card)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = KhaataTheme.spacing.small),
            )
            Switch(checked = visible, onCheckedChange = onVisibleChange)
        }
    }
}

private fun dashboardCardLabel(card: DashboardCard): Int = when (card) {
    DashboardCard.SPENDING_OVERVIEW -> R.string.dashboard_card_spending_overview
    DashboardCard.BUDGET_PROGRESS -> R.string.dashboard_card_budget_progress
    DashboardCard.UPCOMING_PAYMENTS -> R.string.dashboard_card_upcoming_payments
    DashboardCard.CATEGORY_BREAKDOWN -> R.string.dashboard_card_category_breakdown
    DashboardCard.RECENT_TRANSACTIONS -> R.string.dashboard_card_recent_transactions
    DashboardCard.AI_INSIGHT -> R.string.dashboard_card_insight
    DashboardCard.GOALS -> R.string.dashboard_card_goals
    DashboardCard.ACCOUNTS -> R.string.dashboard_card_accounts
    DashboardCard.SUBSCRIPTIONS -> R.string.dashboard_card_subscriptions
    DashboardCard.NET_WORTH_TREND -> R.string.dashboard_card_net_worth
}
