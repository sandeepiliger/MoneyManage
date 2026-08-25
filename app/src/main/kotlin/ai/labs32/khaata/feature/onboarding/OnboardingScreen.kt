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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
                        OnboardingStep.WHY -> WhyStep()
                        OnboardingStep.CURRENCY -> CurrencyStep(state, viewModel::onCurrencyChange)
                        OnboardingStep.LANGUAGE -> LanguageStep(state, viewModel::onLanguageChange)
                        OnboardingStep.ACCOUNT -> AccountStep(state, viewModel)
                        OnboardingStep.INCOME -> IncomeStep(state, viewModel::onIncomeChange)
                        OnboardingStep.CATEGORIES -> CategoriesStep(state, viewModel::onCategoryToggle)
                        OnboardingStep.BUDGET -> BudgetStep(state, viewModel)
                        OnboardingStep.NOTIFICATIONS -> NotificationsStep(viewModel)
                        OnboardingStep.LOCK -> LockStep(state, viewModel::onLockModeChange)
                        OnboardingStep.SMS -> SmsStep(state, viewModel::onSmsImportChange)
                        OnboardingStep.FINISH -> FinishStep()
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
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KhaataTheme.spacing.large),
        )
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

@Composable
private fun WhyStep() {
    StepHeading(title = stringResource(R.string.onboarding_why_title))
    listOf(
        R.string.onboarding_why_spending,
        R.string.onboarding_why_budget,
        R.string.onboarding_why_bills,
        R.string.onboarding_why_private,
    ).forEach { res ->
        Row(
            Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(res), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CurrencyStep(state: OnboardingUiState, onSelect: (CurrencyCode) -> Unit) {
    StepHeading(
        title = stringResource(R.string.onboarding_currency_title),
        body = stringResource(R.string.onboarding_currency_body),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CurrencyCode.entries.forEach { currency ->
            FilterChip(
                selected = state.currency == currency,
                onClick = { onSelect(currency) },
                label = { Text("${currency.symbol} ${currency.code}") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LanguageStep(state: OnboardingUiState, onSelect: (String) -> Unit) {
    StepHeading(title = stringResource(R.string.onboarding_language_title))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("en" to R.string.language_english, "hi" to R.string.language_hindi)
            .forEach { (tag, labelRes) ->
                FilterChip(
                    selected = state.languageTag == tag,
                    onClick = { onSelect(tag) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
    }
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
        label = { Text(stringResource(R.string.onboarding_balance_title)) },
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
private fun IncomeStep(state: OnboardingUiState, onChange: (String) -> Unit) {
    StepHeading(
        title = stringResource(R.string.onboarding_income_title),
        body = stringResource(R.string.onboarding_income_body),
    )
    OutlinedTextField(
        value = state.monthlyIncomeText,
        onValueChange = onChange,
        prefix = { Text(state.currency.symbol) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoriesStep(state: OnboardingUiState, onToggle: (String) -> Unit) {
    StepHeading(
        title = stringResource(R.string.onboarding_categories_title),
        body = stringResource(R.string.onboarding_categories_body),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DefaultCategories.ONBOARDING_SUGGESTIONS.forEach { categoryId ->
            val category = DefaultCategories.ALL.firstOrNull { it.id == categoryId }
                ?: return@forEach
            FilterChip(
                selected = categoryId in state.selectedCategoryIds,
                onClick = { onToggle(categoryId) },
                label = { Text(category.name) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BudgetStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(
        title = stringResource(R.string.onboarding_budget_title),
        body = stringResource(R.string.onboarding_budget_body),
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DefaultCategories.BUDGET_SUGGESTIONS.forEach { categoryId ->
            val category = DefaultCategories.ALL.firstOrNull { it.id == categoryId }
                ?: return@forEach
            FilterChip(
                selected = state.budgetCategoryId == categoryId,
                onClick = { viewModel.onBudgetCategoryChange(categoryId) },
                label = { Text(category.name) },
            )
        }
    }

    Spacer(Modifier.height(KhaataTheme.spacing.default))

    OutlinedTextField(
        value = state.budgetLimitText,
        onValueChange = viewModel::onBudgetLimitChange,
        label = { Text(stringResource(R.string.budgets_limit)) },
        prefix = { Text(state.currency.symbol) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    // A suggested figure derived from stated income, offered rather than imposed.
    viewModel.suggestedBudget()?.let { suggestion ->
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { viewModel.onBudgetLimitChange(suggestion.toPlainString()) }) {
            Text("${stringResource(R.string.action_add)} ${MoneyFormatter.plain(suggestion)}")
        }
    }
}

@Composable
private fun NotificationsStep(viewModel: OnboardingViewModel) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.onNotificationsRequested() }

    PermissionStep(
        icon = Icons.Outlined.Notifications,
        title = stringResource(R.string.onboarding_notifications_title),
        body = stringResource(R.string.onboarding_notifications_body),
        actionLabel = stringResource(R.string.action_enable),
        onAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onNotificationsRequested()
            }
        },
    )
}

@Composable
private fun LockStep(state: OnboardingUiState, onSelect: (AppLockMode) -> Unit) {
    StepHeading(
        title = stringResource(R.string.onboarding_lock_title),
        body = stringResource(R.string.onboarding_lock_body),
    )
    listOf(
        AppLockMode.BIOMETRIC to R.string.settings_lock_biometric,
        AppLockMode.PIN to R.string.settings_lock_pin,
        AppLockMode.OFF to R.string.settings_lock_off,
    ).forEach { (mode, labelRes) ->
        OutlinedButton(
            onClick = { onSelect(mode) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .heightIn(min = 48.dp),
        ) {
            if (state.lockMode == mode) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(labelRes))
        }
    }
}

@Composable
private fun SmsStep(state: OnboardingUiState, onChange: (Boolean) -> Unit) {
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

@Composable
private fun FinishStep() {
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
