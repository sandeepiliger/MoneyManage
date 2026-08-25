package ai.labs32.khaata.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.BuildConfig
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.SettingsRow
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import kotlinx.coroutines.launch

/**
 * About.
 *
 * Also where the open-source notices and the legal links live. The version string includes the
 * build type, because "it works on my phone" conversations go nowhere without knowing whether the
 * user is on a debug build.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowserMessage = stringResource(R.string.about_no_browser)
    var showLicences by remember { mutableStateOf(false) }

    /**
     * Opens an external link.
     *
     * A device with no browser or no mail client is not an error worth a crash — the user is told
     * plainly and the app carries on.
     */
    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(noBrowserMessage) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
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
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
        ) {
            Spacer(Modifier.height(KhaataTheme.spacing.large))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.about_version_detail,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                    BuildConfig.BUILD_TYPE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(KhaataTheme.spacing.medium))

            KhaataCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                SettingsRow(
                    title = stringResource(R.string.settings_privacy_policy),
                    icon = Icons.Default.Shield,
                    onClick = {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_terms),
                    icon = Icons.Default.Gavel,
                    onClick = {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.TERMS_URL)))
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.about_licences),
                    icon = Icons.Default.Description,
                    subtitle = if (showLicences) null else stringResource(R.string.about_licences_show),
                    onClick = { showLicences = !showLicences },
                )
                if (showLicences) {
                    Column(
                        Modifier.padding(
                            horizontal = 16.dp,
                            vertical = KhaataTheme.spacing.small,
                        ),
                    ) {
                        ThirdPartyNotices.ENTRIES.forEach { notice ->
                            Text(
                                text = notice.library,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "${notice.owner} — ${notice.licence}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                }
                SettingsRow(
                    title = stringResource(R.string.about_contact),
                    icon = Icons.Default.MailOutline,
                    subtitle = BuildConfig.SUPPORT_EMAIL,
                    onClick = {
                        open(
                            Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:${BuildConfig.SUPPORT_EMAIL}")
                                // Version details are pre-filled so a support mail arrives with
                                // the two facts that are always asked for first.
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    context.getString(
                                        R.string.about_contact_subject,
                                        BuildConfig.VERSION_NAME,
                                    ),
                                )
                            },
                        )
                    },
                )
            }

            Text(
                text = stringResource(R.string.about_no_advice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = KhaataTheme.spacing.medium),
            )

            Spacer(Modifier.height(KhaataTheme.spacing.xlarge))
        }
    }
}
