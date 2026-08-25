package ai.labs32.khaata.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.GoalPace
import ai.labs32.khaata.core.calc.GoalProgress
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LabelledProgress
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.GoalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

data class GoalsUiState(
    val isLoading: Boolean = true,
    val goals: List<GoalProgress> = emptyList(),
)

@HiltViewModel
class GoalsViewModel @Inject constructor(
    goalRepository: GoalRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GoalsUiState())
    val uiState: StateFlow<GoalsUiState> = _uiState.asStateFlow()

    init {
        goalRepository.observeProgress()
            .onEach { progress ->
                // Achieved goals sink to the bottom; they are a record, not a task.
                _uiState.value = GoalsUiState(
                    isLoading = false,
                    goals = progress.sortedWith(
                        compareBy<GoalProgress> { it.isAchieved }
                            .thenBy { it.monthsRemaining ?: Long.MAX_VALUE },
                    ),
                )
            }
            .launchIn(viewModelScope)
    }
}

/**
 * Savings goals.
 *
 * Each card leads with the monthly figure needed to get there, which is the number that changes
 * what someone does this month. "34% complete" is a status; "₹8,400 a month" is a decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.goals_title)) },
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
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            state.goals.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Flag,
                title = stringResource(R.string.goals_empty_title),
                description = stringResource(R.string.goals_empty_body),
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
            ) {
                items(state.goals, key = { it.goal.id }) { progress ->
                    GoalCard(progress)
                }
            }
        }
    }
}

@Composable
private fun GoalCard(progress: GoalProgress) {
    val swatch = KhaataTheme.money.swatch(progress.goal.colorSeed)

    KhaataCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorBadge(icon = Icons.Outlined.Flag, colorSeed = progress.goal.colorSeed)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = progress.goal.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = paceIcon(progress.pace),
                        contentDescription = null,
                        tint = paceColor(progress.pace),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = paceLabel(progress.pace),
                        style = MaterialTheme.typography.labelMedium,
                        color = paceColor(progress.pace),
                    )
                }
            }
            Text(
                text = "${progress.percentCompleteClamped}%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        LabelledProgress(
            progressPercent = progress.percentCompleteClamped,
            statusLabel = progress.goal.name,
            progressColor = swatch,
            height = 10.dp,
        )

        Spacer(Modifier.height(KhaataTheme.spacing.small))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MoneyText(
                money = progress.goal.currentAmount,
                style = KhaataTextStyles.amountMedium,
            )
            Text(
                text = " / ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoneyText(
                money = progress.goal.targetAmount,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The monthly figure is the actionable one, so it gets prominence over the percentage.
        progress.requiredMonthlyContribution
            ?.takeIf { !progress.isAchieved && it.isPositive }
            ?.let { monthly ->
                Spacer(Modifier.height(KhaataTheme.spacing.small))
                Text(
                    text = stringResource(
                        R.string.goals_required_monthly,
                        MoneyFormatter.plain(monthly),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
    }
}

@Composable
private fun paceColor(pace: GoalPace): Color = when (pace) {
    GoalPace.ACHIEVED -> KhaataTheme.money.income
    GoalPace.ON_TRACK -> KhaataTheme.money.income
    GoalPace.BEHIND -> KhaataTheme.money.warning
    GoalPace.MISSED_DEADLINE -> MaterialTheme.colorScheme.error
    GoalPace.NO_DEADLINE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun paceIcon(pace: GoalPace): ImageVector = when (pace) {
    GoalPace.ACHIEVED -> Icons.Default.CheckCircle
    GoalPace.ON_TRACK -> Icons.Default.CheckCircle
    GoalPace.BEHIND -> Icons.Default.TrendingDown
    GoalPace.MISSED_DEADLINE -> Icons.Default.Schedule
    GoalPace.NO_DEADLINE -> Icons.Default.Schedule
}

@Composable
private fun paceLabel(pace: GoalPace): String = stringResource(
    when (pace) {
        GoalPace.ACHIEVED -> R.string.goals_achieved
        GoalPace.ON_TRACK -> R.string.goals_on_track
        GoalPace.BEHIND -> R.string.goals_behind
        GoalPace.MISSED_DEADLINE -> R.string.goals_missed_deadline
        GoalPace.NO_DEADLINE -> R.string.goals_no_deadline
    },
)
