package ai.labs32.khaata.feature.loans

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.AmortisationEntry
import ai.labs32.khaata.core.calc.LoanStatus
import ai.labs32.khaata.core.common.KhaataClock
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
import ai.labs32.khaata.data.repository.LoanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class LoansUiState(
    val isLoading: Boolean = true,
    val statuses: List<LoanStatus> = emptyList(),
)

@HiltViewModel
class LoansViewModel @Inject constructor(
    loanRepository: LoanRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoansUiState())
    val uiState: StateFlow<LoansUiState> = _uiState.asStateFlow()

    init {
        loanRepository.observeStatuses()
            .onEach { _uiState.value = LoansUiState(isLoading = false, statuses = it) }
            .launchIn(viewModelScope)
    }
}

/**
 * Loans.
 *
 * Shows how much of the loan is actually interest, which is the number lenders do not put on the
 * front of a statement and the one that changes how people feel about a prepayment.
 *
 * This is a calculator, not advice — the screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoansScreen(
    onBack: () -> Unit,
    onOpenLoan: (String) -> Unit,
    viewModel: LoansViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.loans_title)) },
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
                icon = Icons.Outlined.AccountBalance,
                title = stringResource(R.string.loans_empty_title),
                description = stringResource(R.string.loans_empty_body),
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
            ) {
                items(state.statuses, key = { it.loan.id }) { status ->
                    LoanCard(status = status, onClick = { onOpenLoan(status.loan.id) })
                }
                item {
                    Text(
                        text = stringResource(R.string.loans_no_advice),
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
private fun LoanCard(status: LoanStatus, onClick: () -> Unit) {
    KhaataCard(onClick = onClick) {
        CardHeader(title = status.loan.name, subtitle = status.loan.lender)

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        MoneyText(money = status.outstandingPrincipal, style = KhaataTextStyles.amountLarge)
        Text(
            text = stringResource(R.string.loans_outstanding),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        LabelledProgress(
            progressPercent = status.percentRepaidClamped,
            statusLabel = stringResource(R.string.loans_repaid),
            progressColor = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.loans_instalments,
                status.instalmentsPaid,
                status.instalmentsPaid + status.instalmentsRemaining,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(KhaataTheme.spacing.default))

        StatPair(
            leadingLabel = stringResource(R.string.loans_emi),
            leadingValue = { MoneyText(money = status.emi, style = KhaataTextStyles.amountMedium) },
            trailingLabel = stringResource(R.string.loans_interest_remaining),
            trailingValue = {
                MoneyText(
                    money = status.interestRemaining,
                    style = KhaataTextStyles.amountMedium,
                    color = KhaataTheme.money.expense,
                )
            },
        )
    }
}

// ---- Detail ----------------------------------------------------------------------------------

data class LoanDetailUiState(
    val isLoading: Boolean = true,
    val status: LoanStatus? = null,
    val schedule: List<AmortisationEntry> = emptyList(),
)

@HiltViewModel
class LoanDetailViewModel @Inject constructor(
    private val loanRepository: LoanRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoanDetailUiState())
    val uiState: StateFlow<LoanDetailUiState> = _uiState.asStateFlow()

    fun load(loanId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    status = loanRepository.statusFor(loanId),
                    schedule = loanRepository.scheduleFor(loanId),
                )
            }
        }
    }
}

/**
 * A loan's amortisation schedule.
 *
 * Every instalment split into principal and interest. The split is the whole point: the first
 * years of a home loan are almost entirely interest, and seeing that laid out is more informative
 * than any summary figure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanDetailScreen(
    loanId: String,
    onBack: () -> Unit,
    viewModel: LoanDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dateFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

    LaunchedEffect(loanId) { viewModel.load(loanId) }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(state.status?.loan?.name.orEmpty()) },
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
        val status = state.status
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            status == null -> ai.labs32.khaata.core.ui.components.ErrorState(
                message = stringResource(R.string.state_error_generic),
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load(loanId) },
            )

            else -> LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
            ) {
                item {
                    KhaataCard {
                        StatPair(
                            leadingLabel = stringResource(R.string.loans_principal),
                            leadingValue = {
                                MoneyText(
                                    money = status.loan.principal,
                                    style = KhaataTextStyles.amountMedium,
                                )
                            },
                            trailingLabel = stringResource(R.string.loans_total_interest),
                            trailingValue = {
                                MoneyText(
                                    money = status.totalInterest,
                                    style = KhaataTextStyles.amountMedium,
                                    color = KhaataTheme.money.expense,
                                )
                            },
                        )
                        Spacer(Modifier.height(KhaataTheme.spacing.default))
                        StatPair(
                            leadingLabel = stringResource(R.string.loans_total_payable),
                            leadingValue = {
                                MoneyText(
                                    money = status.totalPayable,
                                    style = KhaataTextStyles.amountMedium,
                                )
                            },
                            trailingLabel = stringResource(R.string.loans_interest_paid),
                            trailingValue = {
                                MoneyText(
                                    money = status.interestPaid,
                                    style = KhaataTextStyles.amountMedium,
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(KhaataTheme.spacing.default))
                    Text(
                        text = stringResource(R.string.loans_schedule),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.loans_schedule_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(KhaataTheme.spacing.small))
                    ScheduleHeaderRow()
                }

                items(state.schedule, key = { it.instalmentNumber }) { entry ->
                    ScheduleRow(entry = entry, dateLabel = entry.dueOn.format(dateFormatter))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ScheduleHeaderRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.transaction_date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.loans_principal),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.loans_interest_paid),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScheduleRow(entry: AmortisationEntry, dateLabel: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = MoneyFormatter.compact(entry.principalComponent),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = MoneyFormatter.compact(entry.interestComponent),
            style = MaterialTheme.typography.bodyMedium,
            color = KhaataTheme.money.expense,
            modifier = Modifier.weight(1f),
        )
    }
}
