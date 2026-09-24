package ai.labs32.khaata.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Sms
import ai.labs32.khaata.core.sms.SmsPermission
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.categorize.DefaultCategories
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.lock.PinSetupDialog

/**
 * First-run setup.
 *
 * Every step past the first two can be skipped, and the progress bar makes the length honest
 * rather than open-ended. The two that cannot be skipped exist because the app is unusable
 * without an account to record against.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isFinished) { if (state.isFinished) onFinished() }

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OnboardingTopBar(state = state, onBack = viewModel::back, onSkip = viewModel::skip)

            AnimatedContent(
                targetState = state.step,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "onboarding-step",
                modifier = Modifier.weight(1f),
            ) { step ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = KhaataTheme.spacing.large),
                ) {
                    when (step) {
                        OnboardingStep.WELCOME -> WelcomeStep(onTryDemo = viewModel::loadDemoData)
                        OnboardingStep.ACCOUNT -> AccountStep(state, viewModel)
                        OnboardingStep.SMS -> SmsStep(
                            state = state,
                            onChange = viewModel::onSmsImportChange,
                            onImportRecentChange = viewModel::onImportRecentSmsChange,
                        )
                        OnboardingStep.FINISH -> FinishStep(viewModel)
                    }
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = KhaataTheme.spacing.large),
                )
            }

            Button(
                onClick = viewModel::next,
                enabled = state.canAdvance && !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(KhaataTheme.spacing.large)
                    .heightIn(min = 52.dp),
            ) {
                Text(
                    stringResource(
                        if (state.step == OnboardingStep.FINISH) {
                            R.string.action_done
                        } else {
                            R.string.action_continue
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.stepIndex > 0) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.weight(1f))
            if (state.step.canSkip) {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.action_skip)) }
            }
        }
        if (state.stepNumber > 0) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KhaataTheme.spacing.large),
            )
            Text(
                text = stringResource(R.string.onboarding_step_of, state.stepNumber, state.stepCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KhaataTheme.spacing.large, vertical = 8.dp),
            )
        }
    }
}

// ---- Steps -----------------------------------------------------------------------------------

@Composable
private fun StepHeading(title: String, body: String? = null) {
    Spacer(Modifier.height(KhaataTheme.spacing.xlarge))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    if (body != null) {
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(KhaataTheme.spacing.large))
}

@Composable
private fun WelcomeStep(onTryDemo: () -> Unit) {
    StepHeading(
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
    )
    // The demo path is offered here rather than hidden in settings: seeing a populated app is
    // far more persuasive than reading about one.
    OutlinedButton(
        onClick = onTryDemo,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.onboarding_try_demo)) }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccountStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(
        title = stringResource(R.string.onboarding_account_title),
        body = stringResource(R.string.onboarding_account_body),
    )

    Text(
        text = stringResource(R.string.onboarding_account_type_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccountType.ONBOARDING_ORDER.take(5).forEach { type ->
            FilterChip(
                selected = state.accountType == type,
                onClick = { viewModel.onAccountTypeChange(type) },
                label = { Text(accountTypeLabel(type)) },
            )
        }
    }

    Spacer(Modifier.height(KhaataTheme.spacing.default))

    OutlinedTextField(
        value = state.accountName,
        onValueChange = viewModel::onAccountNameChange,
        label = { Text(stringResource(R.string.accounts_name)) },
        placeholder = { Text(accountTypeLabel(state.accountType)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = state.accountError != null,
        supportingText = state.accountError?.let { { Text(it) } },
    )

    Spacer(Modifier.height(KhaataTheme.spacing.medium))

    OutlinedTextField(
        value = state.openingBalanceText,
        onValueChange = viewModel::onOpeningBalanceChange,
        label = {
            Text(
                stringResource(
                    if (state.accountType.isLiability) R.string.onboarding_balance_owed_title else R.string.onboarding_balance_title,
                ),
            )
        },
        supportingText = { Text(stringResource(R.string.onboarding_balance_body)) },
        prefix = { Text(state.currency.symbol) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    Spacer(Modifier.height(KhaataTheme.spacing.default))

    // Stated up front, on the screen where someone might otherwise expect to be asked for bank
    // credentials.
    Text(
        text = stringResource(R.string.accounts_never_credentials),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SmsStep(
    state: OnboardingUiState,
    onChange: (Boolean) -> Unit,
    onImportRecentChange: (Boolean) -> Unit,
) {
    // RECEIVE_SMS and READ_SMS are dangerous permissions. Recording the user's intent without
    // asking for them leaves the feature switched on in settings and silently dead in practice,
    // because Android never delivers the broadcast, so the answer here is what decides the flag.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> onChange(grants.values.all { it }) }

    PermissionStep(
        icon = Icons.Outlined.Sms,
        title = stringResource(R.string.onboarding_sms_title),
        body = stringResource(R.string.onboarding_sms_body),
        footnote = stringResource(R.string.onboarding_sms_optional),
        actionLabel = stringResource(R.string.action_enable),
        onAction = { launcher.launch(SmsPermission.REQUIRED) },
        isEnabled = state.smsImportEnabled,
    )

    // Reading messages from before today is its own question, asked only once reading is on, and
    // off unless the user says yes. New messages are the feature; old ones are a one-off
    // catch-up the user should choose, knowing it lands in review rather than in their balance.
    if (state.smsImportEnabled) {
        Spacer(Modifier.height(KhaataTheme.spacing.large))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.importRecentSms,
                    role = Role.Switch,
                    onValueChange = onImportRecentChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.onboarding_sms_history_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.onboarding_sms_history_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(KhaataTheme.spacing.medium))
            // Null: the whole row is the toggle, so the switch must not take a second click.
            Switch(checked = state.importRecentSms, onCheckedChange = null)
        }
    }
}

@Composable
private fun PermissionStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    footnote: String? = null,
    isEnabled: Boolean = false,
) {
    Spacer(Modifier.height(KhaataTheme.spacing.xlarge))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
    }
    Spacer(Modifier.height(KhaataTheme.spacing.large))
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(KhaataTheme.spacing.small))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (footnote != null) {
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        Text(
            text = footnote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(KhaataTheme.spacing.large))
    OutlinedButton(
        onClick = onAction,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        if (isEnabled) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(actionLabel)
    }
}

/**
 * Done -- and the one permission worth asking for on the way out.
 *
 * Reminders were a step of their own. Asked here instead, once the user has seen what the app
 * will remind them about, it is one optional button rather than another screen to get past; bill
 * reminders and budget alerts are on by default and cannot reach anyone on Android 13+ without it.
 */
@Composable
private fun FinishStep(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.onNotificationsRequested() }

    Spacer(Modifier.height(KhaataTheme.spacing.xxlarge))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
    }
    Spacer(Modifier.height(KhaataTheme.spacing.large))
    Text(
        text = stringResource(R.string.onboarding_finish_title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(KhaataTheme.spacing.small))
    Text(
        text = stringResource(R.string.onboarding_finish_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Spacer(Modifier.height(KhaataTheme.spacing.xlarge))
        OutlinedButton(
            onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            enabled = !state.notificationsRequested,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(
                if (state.notificationsRequested) Icons.Default.CheckCircle else Icons.Outlined.Notifications,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_notifications_title))
        }
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        Text(
            text = stringResource(R.string.onboarding_notifications_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun accountTypeLabel(type: AccountType): String = stringResource(
    when (type) {
        AccountType.CASH -> R.string.account_type_cash
        AccountType.BANK -> R.string.account_type_bank
        AccountType.SAVINGS -> R.string.account_type_savings
        AccountType.CURRENT -> R.string.account_type_current
        AccountType.CREDIT_CARD -> R.string.account_type_credit_card
        AccountType.WALLET -> R.string.account_type_wallet
        AccountType.INVESTMENT -> R.string.account_type_investment
        AccountType.LOAN -> R.string.account_type_loan
        AccountType.OTHER -> R.string.account_type_other
    },
)
