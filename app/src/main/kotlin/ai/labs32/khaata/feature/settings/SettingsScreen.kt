package ai.labs32.khaata.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.BuildConfig
import ai.labs32.khaata.R
import ai.labs32.khaata.core.entitlement.Tier
import ai.labs32.khaata.core.model.AppLockMode
import ai.labs32.khaata.core.model.ThemePreference
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.SettingsRow
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.navigation.Routes

/**
 * Settings.
 *
 * Grouped by what the user is trying to do rather than by which subsystem owns the flag. Anything
 * that changes what leaves the device lives under Privacy and is reached from here rather than
 * being scattered through this list, so there is exactly one screen to check when someone wants
 * to know what the app is sending anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { settingsMessageText(it) }

    // One shared launcher for all three notification-backed toggles below: POST_NOTIFICATIONS is
    // a single app-level permission, so whichever toggle asks first is the only prompt the user
    // ever sees. `onGranted` is swapped in right before each `launch()` so the same launcher can
    // report back to whichever switch triggered it.
    var onNotificationPermissionResult by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> onNotificationPermissionResult(granted) }

    fun requestNotificationsThen(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onNotificationPermissionResult = onResult
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // No runtime prompt exists below API 33; notifications are granted by default there.
            onResult(true)
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KhaataTheme.spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
        ) {
            PlanCard(tier = state.tier, onOpenPaywall = { onNavigate(Routes.PAYWALL) })

            SectionCard(title = stringResource(R.string.settings_profile)) {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::setDisplayName,
                    label = { Text(stringResource(R.string.settings_display_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(KhaataTheme.spacing.medium))
                SettingsRow(
                    title = stringResource(R.string.settings_currency),
                    subtitle = state.currency.code,
                )
                MonthStartRow(
                    day = state.monthStartDay,
                    onSelect = viewModel::setMonthStartDay,
                )
            }

            SectionCard(title = stringResource(R.string.settings_appearance)) {
                ChipRow(
                    label = stringResource(R.string.settings_theme),
                    options = ThemePreference.entries,
                    selected = state.theme,
                    optionLabel = { themeLabel(it) },
                    onSelect = viewModel::setTheme,
                )
            }

            SectionCard(title = stringResource(R.string.settings_security)) {
                ChipRow(
                    label = stringResource(R.string.settings_app_lock),
                    options = state.availableLockModes,
                    selected = state.lockMode,
                    optionLabel = { lockModeLabel(it) },
                    onSelect = viewModel::setLockMode,
                )
                if (state.lockMode != AppLockMode.OFF) {
                    ChipRow(
                        label = stringResource(R.string.settings_lock_after),
                        options = LOCK_DELAY_OPTIONS,
                        selected = state.lockAfterSeconds,
                        optionLabel = { lockDelayLabel(it) },
                        onSelect = viewModel::setLockAfterSeconds,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_hide_amounts),
                        icon = Icons.Default.Lock,
                        trailing = {
                            Switch(
                                checked = state.hideAmountsWhenLocked,
                                onCheckedChange = viewModel::setHideAmountsWhenLocked,
                            )
                        },
                    )
                }
                if (state.biometricUnavailableReason != null) {
                    Text(
                        text = state.biometricUnavailableReason!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings_notifications)) {
                SettingsRow(
                    title = stringResource(R.string.settings_budget_alerts),
                    trailing = {
                        Switch(
                            checked = state.budgetAlertsEnabled,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    requestNotificationsThen(viewModel::setBudgetAlerts)
                                } else {
                                    viewModel.setBudgetAlerts(false)
                                }
                            },
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_bill_reminders),
                    trailing = {
                        Switch(
                            checked = state.billRemindersEnabled,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    requestNotificationsThen(viewModel::setBillReminders)
                                } else {
                                    viewModel.setBillReminders(false)
                                }
                            },
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_daily_reminder),
                    trailing = {
                        Switch(
                            checked = state.dailyReminderEnabled,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    requestNotificationsThen(viewModel::setDailyReminder)
                                } else {
                                    viewModel.setDailyReminder(false)
                                }
                            },
                        )
                    },
                )
                if (state.dailyReminderEnabled) {
                    ChipRow(
                        label = stringResource(R.string.settings_reminder_time),
                        options = REMINDER_TIME_OPTIONS,
                        selected = state.dailyReminderMinuteOfDay,
                        optionLabel = { reminderTimeLabel(it) },
                        onSelect = viewModel::setDailyReminderTime,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings_data)) {
                SettingsRow(
                    title = stringResource(R.string.settings_manage_categories),
                    icon = Icons.Default.Category,
                    onClick = { onNavigate(Routes.CATEGORIES) },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_manage_accounts),
                    icon = Icons.Default.AccountBalanceWallet,
                    onClick = { onNavigate(Routes.ACCOUNTS) },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_merchant_rules),
                    icon = Icons.Default.Storefront,
                    onClick = { onNavigate(Routes.MERCHANT_RULES) },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_recently_deleted),
                    icon = Icons.Default.DeleteSweep,
                    onClick = { onNavigate(Routes.RECENTLY_DELETED) },
                )
                SettingsRow(
                    title = stringResource(R.string.backup_title),
                    icon = Icons.Default.CloudUpload,
                    onClick = { onNavigate(Routes.BACKUP) },
                )
            }

            SectionCard(title = stringResource(R.string.settings_demo_mode)) {
                Text(
                    text = stringResource(R.string.settings_demo_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(KhaataTheme.spacing.small))
                SettingsRow(
                    title = stringResource(
                        if (state.demoMode) {
                            R.string.settings_demo_disable
                        } else {
                            R.string.settings_demo_enable
                        },
                    ),
                    subtitle = if (state.hasRealData && !state.demoMode) {
                        // Loading samples alongside real data would corrupt every total the user
                        // has built up, so it is refused rather than merged.
                        stringResource(R.string.settings_demo_blocked)
                    } else {
                        null
                    },
                    onClick = if (state.hasRealData && !state.demoMode) {
                        null
                    } else {
                        viewModel::toggleDemoMode
                    },
                )
            }

            SectionCard(title = stringResource(R.string.settings_privacy)) {
                SettingsRow(
                    title = stringResource(R.string.privacy_title),
                    icon = Icons.Default.Shield,
                    onClick = { onNavigate(Routes.PRIVACY) },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_ai),
                    icon = Icons.Default.Psychology,
                    onClick = { onNavigate(Routes.AI_ASSISTANT) },
                )
            }

            SectionCard(title = stringResource(R.string.settings_about)) {
                SettingsRow(
                    title = stringResource(R.string.settings_about),
                    icon = Icons.Default.Info,
                    subtitle = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    onClick = { onNavigate(Routes.ABOUT) },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_help),
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    onClick = { onNavigate(Routes.ABOUT) },
                )
            }

            SectionCard(title = stringResource(R.string.settings_delete_all)) {
                SettingsRow(
                    title = stringResource(R.string.settings_delete_all),
                    icon = Icons.Default.DeleteForever,
                    onClick = viewModel::requestDeleteAll,
                )
            }

            Spacer(Modifier.height(KhaataTheme.spacing.xlarge))
        }
    }

    if (state.showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteAll,
            title = { Text(stringResource(R.string.settings_delete_all_title)) },
            text = { Text(stringResource(R.string.settings_delete_all_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteAll) {
                    Text(
                        text = stringResource(R.string.settings_delete_all_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteAll) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun PlanCard(tier: Tier, onOpenPaywall: () -> Unit) {
    KhaataCard(onClick = onOpenPaywall) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(
                Icons.Default.WorkspacePremium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(0.dp))
            Column(Modifier.padding(start = KhaataTheme.spacing.medium)) {
                Text(
                    text = stringResource(R.string.paywall_current_plan),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = tierLabel(tier), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    KhaataCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        content()
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            items(options, key = { it.toString() }) { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                )
            }
        }
    }
}

@Composable
private fun MonthStartRow(day: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_month_start),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            // Salary dates in India are commonly the 1st or the 7th, and someone paid on the 7th
            // thinks of their month that way regardless of what a calendar says.
            text = stringResource(R.string.settings_month_start_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            items(MONTH_START_OPTIONS, key = { it }) { option ->
                FilterChip(
                    selected = option == day,
                    onClick = { onSelect(option) },
                    label = { Text(option.toString()) },
                )
            }
        }
    }
}

@Composable
internal fun themeLabel(theme: ThemePreference): String = stringResource(
    when (theme) {
        ThemePreference.SYSTEM -> R.string.settings_theme_system
        ThemePreference.LIGHT -> R.string.settings_theme_light
        ThemePreference.DARK -> R.string.settings_theme_dark
    },
)

@Composable
internal fun lockModeLabel(mode: AppLockMode): String = stringResource(
    when (mode) {
        AppLockMode.OFF -> R.string.settings_lock_off
        AppLockMode.BIOMETRIC -> R.string.settings_lock_biometric
        AppLockMode.PIN -> R.string.settings_lock_pin
    },
)

@Composable
internal fun tierLabel(tier: Tier): String = stringResource(
    when (tier) {
        Tier.FREE -> R.string.paywall_tier_free
        Tier.PRO -> R.string.paywall_tier_pro
        Tier.AI_PRO -> R.string.paywall_tier_ai_pro
        Tier.FAMILY -> R.string.paywall_tier_family
    },
)

@Composable
private fun lockDelayLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.settings_lock_immediately)
    60 -> stringResource(R.string.settings_lock_minute)
    else -> stringResource(R.string.settings_lock_seconds, seconds)
}

@Composable
private fun reminderTimeLabel(minuteOfDay: Int): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    val suffix = if (hour < 12) "am" else "pm"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "%d:%02d %s".format(displayHour, minute, suffix)
}

@Composable
private fun settingsMessageText(message: SettingsMessage): String = stringResource(
    when (message) {
        SettingsMessage.DeletedEverything -> R.string.settings_delete_all_done
        SettingsMessage.DemoLoaded -> R.string.settings_demo_loaded
        SettingsMessage.DemoCleared -> R.string.settings_demo_cleared
    },
)

private val LOCK_DELAY_OPTIONS = listOf(0, 30, 60, 300)
private val REMINDER_TIME_OPTIONS = listOf(9 * 60, 13 * 60, 18 * 60, 21 * 60, 22 * 60)

/** The 1st plus the dates salaries most often land on. A full 1-28 picker is noise. */
private val MONTH_START_OPTIONS = listOf(1, 5, 7, 10, 15, 25)
