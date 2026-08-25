package ai.labs32.khaata.feature.settings

import android.net.Uri
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.backup.BackupFile
import ai.labs32.khaata.core.backup.BackupReadResult
import ai.labs32.khaata.core.backup.ImportMode
import ai.labs32.khaata.core.backup.ImportResult
import ai.labs32.khaata.core.backup.RejectedRecord
import ai.labs32.khaata.data.backup.BackupManager
import ai.labs32.khaata.data.backup.ExportedFile
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The restore state machine.
 *
 * Restore is the one action in this app that can destroy months of records, so the behaviour worth
 * pinning is not "does it call the manager" but the order of consent: nothing is written until the
 * user has seen what a file contains and picked a merge mode, the default mode is never the
 * destructive one, and a partial success is reported as partial rather than as success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val manager = mockk<BackupManager>(relaxed = true)
    private val analytics = mockk<AnalyticsProvider>(relaxed = true)
    private val uri = mockk<Uri>(relaxed = true)

    private val backup = BackupFile(appVersion = "1.0.0", exportedAt = Instant.EPOCH)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { manager.existingExports() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BackupViewModel(manager, analytics)

    @Test
    fun `previewing a backup writes nothing`() = runTest(dispatcher) {
        coEvery { manager.readBackup(uri) } returns BackupReadResult.Success(backup)

        val model = viewModel()
        model.previewBackup(uri)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(model.uiState.value.pendingRestore).isNotNull()
        coVerify(exactly = 0) { manager.restore(any(), any()) }
    }

    /** Replace-everything must never be what a user gets by not choosing. */
    @Test
    fun `the default merge mode is the least destructive one`() = runTest(dispatcher) {
        coEvery { manager.readBackup(uri) } returns BackupReadResult.Success(backup)

        val model = viewModel()
        model.previewBackup(uri)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(model.uiState.value.pendingRestore?.mode)
            .isEqualTo(ImportMode.MERGE_SKIP_EXISTING)
    }

    @Test
    fun `an unreadable file reports itself rather than crashing`() = runTest(dispatcher) {
        coEvery { manager.readBackup(uri) } returns BackupReadResult.Invalid("nope")

        val model = viewModel()
        model.previewBackup(uri)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(model.uiState.value.pendingRestore).isNull()
        assertThat(model.uiState.value.message).isEqualTo(BackupMessage.InvalidFile)
    }

    @Test
    fun `a backup from a newer version is distinguished from a corrupt one`() = runTest(dispatcher) {
        coEvery { manager.readBackup(uri) } returns BackupReadResult.TooNew(fileSchemaVersion = 99)

        val model = viewModel()
        model.previewBackup(uri)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(model.uiState.value.message).isEqualTo(BackupMessage.TooNew)
    }

    @Test
    fun `dismissing the dialog leaves the data alone`() = runTest(dispatcher) {
        coEvery { manager.readBackup(uri) } returns BackupReadResult.Success(backup)

        val model = viewModel()
        model.previewBackup(uri)
        dispatcher.scheduler.advanceUntilIdle()
        model.dismissRestore()

        assertThat(model.uiState.value.pendingRestore).isNull()
        coVerify(exactly = 0) { manager.restore(any(), any()) }
    }

    @Test
    fun `confirming applies the mode the user picked`() = runTest(dispatcher) {
        coEvery { manager.readBackup(uri) } returns BackupReadResult.Success(backup)
        coEvery { manager.restore(any(), any()) } returns Result.success(
            ImportResult(
                mode = ImportMode.REPLACE_ALL,
                imported = mapOf("transactions" to 12),
                skipped = emptyMap(),
                rejected = emptyList(),
            ),
        )

        val model = viewModel()
        model.previewBackup(uri)
        dispatcher.scheduler.advanceUntilIdle()
        model.selectImportMode(ImportMode.REPLACE_ALL)
        model.confirmRestore()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { manager.restore(backup, ImportMode.REPLACE_ALL) }
        assertThat(model.uiState.value.message).isEqualTo(BackupMessage.Restored(12))
    }

    /**
     * A restore that skipped rows is not a success. Reporting it as one leaves the user believing
     * they have data they do not have — the single worst outcome this screen can produce.
     */
    @Test
    fun `a partial restore is reported as partial`() = runTest(dispatcher) {
        coEvery { manager.readBackup(uri) } returns BackupReadResult.Success(backup)
        coEvery { manager.restore(any(), any()) } returns Result.success(
            ImportResult(
                mode = ImportMode.MERGE_SKIP_EXISTING,
                imported = mapOf("transactions" to 40),
                skipped = emptyMap(),
                rejected = listOf(RejectedRecord("transaction", "t-9", "Unknown account")),
            ),
        )

        val model = viewModel()
        model.previewBackup(uri)
        dispatcher.scheduler.advanceUntilIdle()
        model.confirmRestore()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(model.uiState.value.message)
            .isEqualTo(BackupMessage.RestoredWithRejections(1))
    }

    @Test
    fun `a failed restore says so instead of claiming success`() = runTest(dispatcher) {
        coEvery { manager.readBackup(uri) } returns BackupReadResult.Success(backup)
        coEvery { manager.restore(any(), any()) } returns
            Result.failure(IllegalStateException("disk full"))

        val model = viewModel()
        model.previewBackup(uri)
        dispatcher.scheduler.advanceUntilIdle()
        model.confirmRestore()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(model.uiState.value.message).isEqualTo(BackupMessage.Failed)
        assertThat(model.uiState.value.isBusy).isFalse()
    }

    @Test
    fun `a successful export offers the file it wrote`() = runTest(dispatcher) {
        val file = ExportedFile(
            fileName = "khaata-backup-2026-03-15.json",
            uri = uri,
            sizeBytes = 2048,
            mimeType = "application/json",
            recordCount = 120,
        )
        coEvery { manager.exportBackup() } returns Result.success(file)
        coEvery { manager.existingExports() } returns listOf(file)

        val model = viewModel()
        model.exportBackup()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(model.uiState.value.message)
            .isEqualTo(BackupMessage.Exported(file.fileName))
        assertThat(model.uiState.value.exports).containsExactly(file)
    }

    @Test
    fun `a second export is ignored while the first is running`() = runTest(dispatcher) {
        coEvery { manager.exportBackup() } returns Result.success(
            ExportedFile("a.json", uri, 1, "application/json", 1),
        )

        val model = viewModel()
        model.exportBackup()
        model.exportBackup()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { manager.exportBackup() }
    }
}
