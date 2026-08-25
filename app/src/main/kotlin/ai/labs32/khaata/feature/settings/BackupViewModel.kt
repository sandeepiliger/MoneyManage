package ai.labs32.khaata.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.backup.BackupFile
import ai.labs32.khaata.core.backup.BackupReadResult
import ai.labs32.khaata.core.backup.BackupSummary
import ai.labs32.khaata.core.backup.CsvImportResult
import ai.labs32.khaata.core.backup.ImportMode
import ai.labs32.khaata.data.backup.BackupManager
import ai.labs32.khaata.data.backup.ExportedFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A backup the user picked, held while they choose how to merge it. Nothing is written yet. */
data class PendingRestore(
    val backup: BackupFile,
    val summary: BackupSummary,
    /** Defaults to the least destructive option — never to replace-everything. */
    val mode: ImportMode = ImportMode.MERGE_SKIP_EXISTING,
)

/** A parsed CSV, held for confirmation. */
data class PendingCsv(val result: CsvImportResult)

sealed interface BackupMessage {
    data class Exported(val fileName: String) : BackupMessage
    data class CsvExported(val fileName: String) : BackupMessage
    data class Restored(val count: Int) : BackupMessage
    data class RestoredWithRejections(val rejectedCount: Int) : BackupMessage
    data object InvalidFile : BackupMessage
    data object TooNew : BackupMessage
    data object Failed : BackupMessage
    data object ExportsCleared : BackupMessage
}

data class BackupUiState(
    val isBusy: Boolean = false,
    val exports: List<ExportedFile> = emptyList(),
    val pendingRestore: PendingRestore? = null,
    val pendingCsv: PendingCsv? = null,
    val message: BackupMessage? = null,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
    private val analytics: AnalyticsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        refreshExports()
    }

    private fun refreshExports() {
        viewModelScope.launch {
            _uiState.update { it.copy(exports = backupManager.existingExports()) }
        }
    }

    // ---- Export ------------------------------------------------------------------------------

    fun exportBackup() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true) }

        viewModelScope.launch {
            val result = backupManager.exportBackup()
            val summary = result.getOrNull()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = summary
                        ?.let { file -> BackupMessage.Exported(file.fileName) }
                        ?: BackupMessage.Failed,
                )
            }
            if (summary != null) {
                // The record count, never the contents. See AnalyticsEvent for what may be sent.
                analytics.track(AnalyticsEvent.BackupCreated(recordCount = summary.recordCount ?: 0))
            }
            refreshExports()
        }
    }

    fun exportCsv() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true) }

        viewModelScope.launch {
            val result = backupManager.exportCsv()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.getOrNull()
                        ?.let { file -> BackupMessage.CsvExported(file.fileName) }
                        ?: BackupMessage.Failed,
                )
            }
            if (result.isSuccess) analytics.track(AnalyticsEvent.ExportCreated(format = "csv"))
            refreshExports()
        }
    }

    fun clearExports() {
        viewModelScope.launch {
            backupManager.clearExports()
            _uiState.update { it.copy(message = BackupMessage.ExportsCleared) }
            refreshExports()
        }
    }

    // ---- Restore -----------------------------------------------------------------------------

    /**
     * Reads a picked file and shows what it contains.
     *
     * Nothing is written at this point. Every outcome — a good backup, a corrupted file, one from
     * a future version — resolves to something on screen rather than to a crash or to silence.
     */
    fun previewBackup(uri: Uri) {
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            when (val result = backupManager.readBackup(uri)) {
                is BackupReadResult.Success -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        pendingRestore = PendingRestore(
                            backup = result.backup,
                            summary = result.backup.summary(),
                        ),
                    )
                }

                is BackupReadResult.TooNew -> _uiState.update {
                    it.copy(isBusy = false, message = BackupMessage.TooNew)
                }

                is BackupReadResult.Invalid -> _uiState.update {
                    it.copy(isBusy = false, message = BackupMessage.InvalidFile)
                }
            }
        }
    }

    fun selectImportMode(mode: ImportMode) = _uiState.update {
        it.copy(pendingRestore = it.pendingRestore?.copy(mode = mode))
    }

    fun dismissRestore() = _uiState.update { it.copy(pendingRestore = null) }

    fun confirmRestore() {
        val pending = _uiState.value.pendingRestore ?: return
        _uiState.update { it.copy(isBusy = true, pendingRestore = null) }

        viewModelScope.launch {
            val result = backupManager.restore(pending.backup, pending.mode)
            val outcome = result.getOrNull()

            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = when {
                        outcome == null -> BackupMessage.Failed
                        // A partial success is reported as one: the count that got in and the
                        // count that did not, rather than a bare "Restored".
                        outcome.hasRejections ->
                            BackupMessage.RestoredWithRejections(outcome.rejected.size)

                        else -> BackupMessage.Restored(outcome.totalImported)
                    },
                )
            }

            outcome?.let {
                analytics.track(
                    AnalyticsEvent.ImportCompleted(
                        importedCount = it.totalImported,
                        rejectedCount = it.rejected.size,
                    ),
                )
            }
        }
    }

    // ---- CSV ---------------------------------------------------------------------------------

    fun previewCsv(uri: Uri) {
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = backupManager.readCsv(uri)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { parsed ->
                        state.copy(isBusy = false, pendingCsv = PendingCsv(parsed))
                    },
                    onFailure = { state.copy(isBusy = false, message = BackupMessage.InvalidFile) },
                )
            }
        }
    }

    fun dismissCsv() = _uiState.update { it.copy(pendingCsv = null) }

    fun confirmCsvImport() {
        val pending = _uiState.value.pendingCsv ?: return
        _uiState.update { it.copy(isBusy = true, pendingCsv = null) }

        viewModelScope.launch {
            val result = backupManager.importCsvRows(pending.result.rows)
            val outcome = result.getOrNull()

            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = when {
                        outcome == null -> BackupMessage.Failed
                        outcome.hasRejections ->
                            BackupMessage.RestoredWithRejections(outcome.rejected.size)

                        else -> BackupMessage.Restored(outcome.totalImported)
                    },
                )
            }

            outcome?.let {
                analytics.track(
                    AnalyticsEvent.ImportCompleted(
                        importedCount = it.totalImported,
                        rejectedCount = it.rejected.size,
                    ),
                )
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
