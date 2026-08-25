package ai.labs32.khaata.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.backup.ImportMode
import ai.labs32.khaata.core.ui.components.CardHeader
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.SettingsRow
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.backup.ExportedFile
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Backup and export.
 *
 * A backup here is a file, not a cloud sync. The user creates it, the share sheet hands it to
 * whatever they already trust — Drive, WhatsApp to themselves, a cable — and Khaata never sees
 * where it went. That is slower than a sync toggle and it is the only design consistent with
 * never uploading someone's ledger.
 *
 * Restore always shows what a file contains and asks how to merge it before writing anything,
 * because "restore" is the one action in this app that can destroy months of records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { backupMessageText(it) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    // The system picker is used rather than a permission grab: the app never asks for storage
    // access, it only ever receives the one file the user chose.
    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::previewBackup) }

    val pickCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::previewCsv) }

    fun share(file: ExportedFile) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType
            putExtra(Intent.EXTRA_STREAM, file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.backup_share_title)),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.backup_title)) },
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
                text = stringResource(R.string.backup_local_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = KhaataTheme.spacing.small),
            )

            KhaataCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                CardHeader(
                    title = stringResource(R.string.backup_section_export),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SettingsRow(
                    title = stringResource(R.string.backup_create),
                    subtitle = stringResource(R.string.backup_create_help),
                    icon = Icons.Default.Download,
                    onClick = viewModel::exportBackup,
                )
                SettingsRow(
                    title = stringResource(R.string.backup_export_csv),
                    subtitle = stringResource(R.string.backup_export_csv_help),
                    icon = Icons.Default.TableChart,
                    onClick = viewModel::exportCsv,
                )
            }

            if (state.exports.isNotEmpty()) {
                KhaataCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    CardHeader(
                        title = stringResource(R.string.backup_section_files),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        actionLabel = stringResource(R.string.action_clear),
                        onAction = viewModel::clearExports,
                    )
                    state.exports.forEach { file ->
                        SettingsRow(
                            title = file.fileName,
                            subtitle = formatSize(file.sizeBytes),
                            icon = Icons.Default.Share,
                            onClick = { share(file) },
                        )
                    }
                }
            }

            KhaataCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                CardHeader(
                    title = stringResource(R.string.backup_section_restore),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SettingsRow(
                    title = stringResource(R.string.backup_restore),
                    icon = Icons.Default.Upload,
                    onClick = { pickBackup.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )
                SettingsRow(
                    title = stringResource(R.string.backup_import_csv),
                    subtitle = stringResource(R.string.backup_import_csv_help),
                    icon = Icons.Default.TableChart,
                    onClick = { pickCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                )
            }

            if (state.isBusy) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            Spacer(Modifier.height(KhaataTheme.spacing.xlarge))
        }
    }

    state.pendingRestore?.let { pending ->
        RestoreDialog(
            summaryText = stringResource(
                R.string.backup_restore_summary,
                pending.summary.accountCount,
                pending.summary.transactionCount,
                pending.summary.budgetCount,
            ),
            exportedOn = pending.summary.exportedAt
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM yyyy")),
            selectedMode = pending.mode,
            onSelectMode = viewModel::selectImportMode,
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::dismissRestore,
        )
    }

    state.pendingCsv?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCsv,
            title = { Text(stringResource(R.string.backup_import_csv)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
                    Text(
                        stringResource(
                            R.string.backup_csv_ready,
                            pending.result.rows.size,
                        ),
                    )
                    if (pending.result.rejected.isNotEmpty()) {
                        // Rejections are named before the import, not discovered afterwards.
                        Text(
                            text = stringResource(
                                R.string.backup_csv_rejected,
                                pending.result.rejected.size,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = stringResource(R.string.backup_csv_account_matching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmCsvImport,
                    enabled = pending.result.hasRows,
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCsv) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RestoreDialog(
    summaryText: String,
    exportedOn: String,
    selectedMode: ImportMode,
    onSelectMode: (ImportMode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
                Text(stringResource(R.string.backup_exported_on, exportedOn))
                Text(summaryText, style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(KhaataTheme.spacing.small))
                Text(
                    text = stringResource(R.string.backup_restore_mode),
                    style = MaterialTheme.typography.labelLarge,
                )

                Column(Modifier.selectableGroup()) {
                    ImportMode.entries.forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // The whole row is the target, not just the 20dp circle, and it
                                // reads to a screen reader as one radio option rather than as a
                                // button next to some unrelated text.
                                .selectable(
                                    selected = mode == selectedMode,
                                    onClick = { onSelectMode(mode) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == selectedMode, onClick = null)
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(
                                    text = importModeLabel(mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (mode == ImportMode.REPLACE_ALL) {
                                    Text(
                                        text = stringResource(R.string.backup_mode_replace_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_restore),
                    color = if (selectedMode == ImportMode.REPLACE_ALL) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun importModeLabel(mode: ImportMode): String = stringResource(
    when (mode) {
        ImportMode.MERGE_SKIP_EXISTING -> R.string.backup_mode_merge_skip
        ImportMode.MERGE_OVERWRITE_EXISTING -> R.string.backup_mode_merge_overwrite
        ImportMode.REPLACE_ALL -> R.string.backup_mode_replace
    },
)

@Composable
private fun backupMessageText(message: BackupMessage): String = when (message) {
    is BackupMessage.Exported -> stringResource(R.string.backup_created)
    is BackupMessage.CsvExported -> stringResource(R.string.backup_export_created)
    is BackupMessage.Restored -> stringResource(R.string.backup_restore_done, message.count)
    is BackupMessage.RestoredWithRejections -> stringResource(
        R.string.backup_restore_rejected,
        message.rejectedCount,
    )

    BackupMessage.InvalidFile -> stringResource(R.string.backup_invalid)
    BackupMessage.TooNew -> stringResource(R.string.backup_too_new)
    BackupMessage.Failed -> stringResource(R.string.backup_failed)
    BackupMessage.ExportsCleared -> stringResource(R.string.backup_exports_cleared)
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
