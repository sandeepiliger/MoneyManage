package ai.labs32.khaata.feature.creditcards

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.CreditCard
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.CreditCardStatus
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.model.UtilisationBand
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.LabelledProgress
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.components.MoneyText
import ai.labs32.khaata.core.ui.components.StatPair
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.CreditCardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class CreditCardsUiState(
    val isLoading: Boolean = true,
    val statuses: List<CreditCardStatus> = emptyList(),
)

@HiltViewModel
class CreditCardsViewModel @Inject constructor(
    creditCardRepository: CreditCardRepository,
    val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreditCardsUiState())
    val uiState: StateFlow<CreditCardsUiState> = _uiState.asStateFlow()

    init {
        creditCardRepository.observeStatuses()
            .onEach { statuses ->
                // Cards closest to their due date first, so the one that needs paying is on top.
                _uiState.value = CreditCardsUiState(
                    isLoading = false,
                    statuses = statuses.sortedBy { it.paymentDueOn },
                )
            }
            .launchIn(viewModelScope)
    }
}

/**
 * Credit cards.
 *
 * Leads with what is actually owed on the last statement and when it is due, not with today's
 * running balance. Those are different numbers, and the one a user is about to be charged for is
 * the statement balance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditCardsScreen(
    onBack: () -> Unit,
    viewModel: CreditCardsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = viewModel.clock.today()
    val dateFormatter = DateTimeFormatter.ofPattern("d MMM")

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.cards_title)) },
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

            state.statuses.isEmpty() -> EmptyState(
                icon = Icons.Outlined.CreditCard,
                title = stringResource(R.string.cards_empty_title),
                description = stringResource(R.string.cards_empty_body),
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
            ) {
                items(state.statuses, key = { it.card.id }) { status ->
                    CreditCardCard(
                        status = status,
                        dueLabel = status.paymentDueOn.format(dateFormatter),
                        isOverdue = status.isOverdue(today),
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.cards_minimum_estimate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = KhaataTheme.spacing.default),
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditCardCard(
    status: CreditCardStatus,
    dueLabel: String,
    isOverdue: Boolean,
) {
    val bandColor = utilisationColor(status.utilisationBand)

    KhaataCard {
        CardHeader(
            title = status.card.cardName,
            subtitle = listOfNotNull(
                status.card.issuer,
                status.card.lastFourDigits?.let { "••$it" },
            ).joinToString(" • "),
        )

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        MoneyText(money = status.statementBalance, style = KhaataTextStyles.amountHero)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.cards_statement_balance),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isOverdue) {
                    stringResource(R.string.cards_overdue)
                } else {
                    stringResource(R.string.cards_due_on, dueLabel)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isOverdue) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.cards_minimum_due),
            leadingValue = {
                MoneyText(money = status.minimumDue, style = KhaataTextStyles.amountMedium)
            },
            trailingLabel = stringResource(R.string.cards_available_credit),
            trailingValue = {
                MoneyText(money = status.availableCredit, style = KhaataTextStyles.amountMedium)
            },
        )

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.cards_utilisation),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Band is stated in words alongside the percentage, so "high" is never inferred from
            // the bar's colour alone.
            Text(
                text = "${utilisationLabel(status.utilisationBand)} · ${status.utilisationPercentClamped}%",
                style = MaterialTheme.typography.labelMedium,
                color = bandColor,
            )
        }
        Spacer(Modifier.height(6.dp))
        LabelledProgress(
            progressPercent = status.utilisationPercentClamped,
            statusLabel = utilisationLabel(status.utilisationBand),
            progressColor = bandColor,
        )

        if (status.interestWarning) {
            Spacer(Modifier.height(KhaataTheme.spacing.medium))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                // A statement of fact about what revolving costs, not advice about what to do.
                Text(
                    text = stringResource(R.string.cards_interest_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun utilisationColor(band: UtilisationBand): Color = when (band) {
    UtilisationBand.HEALTHY -> KhaataTheme.money.income
    UtilisationBand.ELEVATED -> KhaataTheme.money.warning
    UtilisationBand.HIGH -> KhaataTheme.money.expense
    UtilisationBand.OVER_LIMIT -> MaterialTheme.colorScheme.error
}

@Composable
private fun utilisationLabel(band: UtilisationBand): String = stringResource(
    when (band) {
        UtilisationBand.HEALTHY -> R.string.utilisation_healthy
        UtilisationBand.ELEVATED -> R.string.utilisation_elevated
        UtilisationBand.HIGH -> R.string.utilisation_high
        UtilisationBand.OVER_LIMIT -> R.string.utilisation_over_limit
    },
)
