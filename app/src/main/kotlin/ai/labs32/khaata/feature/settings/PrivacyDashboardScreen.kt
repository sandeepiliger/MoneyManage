package ai.labs32.khaata.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.content.Context
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.AppSettings
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.SettingsRow
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.EntitlementRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.core.entitlement.Feature
import ai.labs32.khaata.core.sms.SmsPermission
import ai.labs32.khaata.core.sms.SmsTransactionReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrivacyUiState(
    val settings: AppSettings = AppSettings(),
    /** Ads are the only thing a free user sends anywhere without opting in. */
    val showsAds: Boolean = false,
    val cloudAiEntitled: Boolean = false,
)

@HiltViewModel
class PrivacyDashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    entitlementRepository: EntitlementRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivacyUiState())
    val uiState: StateFlow<PrivacyUiState> = _uiState.asStateFlow()

    init {
        combine(
            settingsRepository.settings,
            entitlementRepository.observeShouldShowAds(),
            entitlementRepository.observeFeature(Feature.CLOUD_AI_ASSISTANT),
        ) { settings, showsAds, cloudAiEntitled ->
            PrivacyUiState(settings, showsAds, cloudAiEntitled)
        }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    /** Marks the dashboard as read, so onboarding does not keep pointing at it. */
    fun markSeen() {
        viewModelScope.launch { settingsRepository.setPrivacyDashboardSeen(true) }
    }

    fun setAnalytics(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAnalyticsEnabled(enabled) }
    }

    fun setCrashReporting(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCrashReportingEnabled(enabled) }
    }

    fun setCloudAi(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCloudAiEnabled(enabled) }
    }

    /**
     * Turns bank-SMS reading on or off.
     *
     * The receiver component itself is enabled and disabled alongside the flag, so a user who has
     * this off has no SMS receiver registered with the system at all. A stored boolean the
     * receiver checks at delivery time would still mean the app was being handed every message the
     * phone receives, which is not the same promise.
     */
    fun setSmsImport(enabled: Boolean) {
        viewModelScope.launch {
            // Turning this on takes a granted runtime permission as well as the flag: without one
            // the receiver is registered but never delivered to, which reads to the user as the
            // feature silently not working. The caller requests it and only then gets here.
            val effective = enabled && SmsPermission.isGranted(context)
            settingsRepository.setSmsImportEnabled(effective)
            SmsTransactionReceiver.setEnabled(context, effective)
        }
    }

    /**
     * Re-checks the permission and stands the flag down if it has been revoked.
     *
     * A user can revoke SMS access from Android settings at any time, and nothing tells the app.
     * Without this the switch would keep showing "on" for a feature the system has already stopped
     * delivering to.
     */
    fun syncSmsPermissionState() {
        viewModelScope.launch {
            if (settingsRepository.current().smsImportEnabled && !SmsPermission.isGranted(context)) {
                settingsRepository.setSmsImportEnabled(false)
                SmsTransactionReceiver.setEnabled(context, false)
            }
        }
    }
}

/**
 * The privacy dashboard.
 *
 * Written as four plain answers — what is stored, what is processed here, what can leave, what
 * never leaves — because that is the order people actually ask them in, and because a privacy
 * policy written as a legal document answers none of them.
 *
 * "What can leave" is generated from the live settings rather than written as fixed prose. If a
 * user has cloud AI off, the screen does not tell them their questions might be sent somewhere;
 * if they turn it on, the line appears. A static list would be wrong for most users most of the
 * time, and a privacy screen that is wrong is worse than no privacy screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: PrivacyDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val permissionDeniedMessage = stringResource(R.string.privacy_sms_permission_denied)
    val openSettingsLabel = stringResource(R.string.action_open_settings)

    // Once a user has denied this twice, Android stops showing the system dialog at all and
    // the launcher's callback fires immediately with everything false -- so a denial (of either
    // kind) needs its own explanation. Leaving the switch to just snap back to off with nothing
    // said is the same silent-failure shape as the bug this permission handling was added to fix.
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        viewModel.setSmsImport(granted)
        if (!granted) {
            coroutineScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = permissionDeniedMessage,
                    actionLabel = openSettingsLabel,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.markSeen()
        // Catches a permission revoked from Android settings while the app was in the background.
        viewModel.syncSmsPermissionState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.privacy_title)) },
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
            Text(
                text = stringResource(R.string.privacy_summary),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = KhaataTheme.spacing.small),
            )

            Text(
                text = stringResource(R.string.privacy_no_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FactCard(
                icon = Icons.Default.Storage,
                title = stringResource(R.string.privacy_stored_title),
                body = stringResource(R.string.privacy_stored_body),
            )

            FactCard(
                icon = Icons.Default.PhoneAndroid,
                title = stringResource(R.string.privacy_local_title),
                body = stringResource(R.string.privacy_local_body),
            )

            // Assembled from what is actually switched on right now.
            KhaataCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(KhaataTheme.spacing.medium))
                    Text(
                        text = stringResource(R.string.privacy_leaves_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(KhaataTheme.spacing.small))

                val outbound = buildList {
                    if (state.showsAds) add(stringResource(R.string.privacy_leaves_ads))
                    add(stringResource(R.string.privacy_leaves_billing))
                    if (settings.cloudAiEnabled) add(stringResource(R.string.privacy_leaves_ai))
                    if (settings.analyticsEnabled) {
                        add(stringResource(R.string.privacy_leaves_analytics))
                    }
                    if (settings.crashReportingEnabled) {
                        add(stringResource(R.string.privacy_leaves_crashes))
                    }
                }

                if (outbound.isEmpty()) {
                    Text(
                        text = stringResource(R.string.privacy_leaves_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    outbound.forEach { line ->
                        Text(
                            text = "• $line",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }

            FactCard(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.privacy_never_title),
                body = stringResource(R.string.privacy_never_body),
            )

            KhaataCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
                CardHeader(
                    title = stringResource(R.string.privacy_controls_title),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(KhaataTheme.spacing.small))

                SettingsRow(
                    title = stringResource(R.string.privacy_analytics),
                    subtitle = stringResource(R.string.privacy_analytics_help),
                    trailing = {
                        Switch(
                            checked = settings.analyticsEnabled,
                            onCheckedChange = viewModel::setAnalytics,
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.privacy_crash_reporting),
                    subtitle = stringResource(R.string.privacy_crash_help),
                    trailing = {
                        Switch(
                            checked = settings.crashReportingEnabled,
                            onCheckedChange = viewModel::setCrashReporting,
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.privacy_cloud_ai),
                    subtitle = if (state.cloudAiEntitled) {
                        stringResource(R.string.privacy_cloud_ai_help)
                    } else {
                        // Stated rather than hidden: the switch is inert on the free plan, and a
                        // toggle that silently does nothing is worse than one that says why.
                        stringResource(R.string.privacy_cloud_ai_requires_plan)
                    },
                    trailing = {
                        Switch(
                            checked = settings.cloudAiEnabled,
                            onCheckedChange = viewModel::setCloudAi,
                            enabled = state.cloudAiEntitled,
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.privacy_sms_access),
                    subtitle = stringResource(R.string.privacy_sms_help),
                    trailing = {
                        Switch(
                            checked = settings.smsImportEnabled,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    // Ask every time it is switched on. Android returns the current
                                    // grants without showing a dialog when they are already held,
                                    // so this costs a user who has granted them nothing.
                                    smsPermissionLauncher.launch(SmsPermission.REQUIRED)
                                } else {
                                    viewModel.setSmsImport(false)
                                }
                            },
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.privacy_export_data),
                    icon = Icons.Default.Download,
                    onClick = onOpenBackup,
                )
            }

            Spacer(Modifier.height(KhaataTheme.spacing.xlarge))
        }
    }
}

@Composable
private fun FactCard(icon: ImageVector, title: String, body: String) {
    KhaataCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(KhaataTheme.spacing.medium))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
