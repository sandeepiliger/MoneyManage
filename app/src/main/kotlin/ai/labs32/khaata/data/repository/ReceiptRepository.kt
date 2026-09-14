package ai.labs32.khaata.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.ReceiptDao
import ai.labs32.khaata.core.database.toDomain
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Receipt
import ai.labs32.khaata.data.receipts.ReceiptImageStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why a receipt could not be attached, in terms the UI can put in front of someone. */
sealed interface ReceiptAttachResult {
    data class Attached(val receipt: Receipt) : ReceiptAttachResult

    /** The file could not be read or decoded — a corrupt pick, or not an image at all. */
    data object Unreadable : ReceiptAttachResult

    /** This transaction already holds [MAX_PER_TRANSACTION] receipts. */
    data object TooManyForTransaction : ReceiptAttachResult

    /** Storing this would take the app past its total receipt allowance. */
    data object OutOfSpace : ReceiptAttachResult

    companion object {
        const val MAX_PER_TRANSACTION = 5
    }
}

/**
 * Receipt images and the rows that point at them.
 *
 * Two things are kept in step here that can otherwise drift: the `receipts` table and the files in
 * private storage. The table has a CASCADE foreign key onto transactions, so deleting a
 * transaction silently takes its receipt rows — and knows nothing whatever about the images they
 * referenced. That is why [purgeOrphans] exists and why the maintenance worker runs it: without
 * it, every deleted transaction would leak its photos for the life of the install.
 *
 * Images never leave the device on their own. They live in app-private storage, are excluded from
 * Android's cloud backup and device transfer, and are deliberately absent from `file_paths.xml`,
 * so no `content://` URI to the receipts directory exists at all. [shareableUri] is the one way
 * one reaches another app, and it works by copying a single file into the already-shared exports
 * cache rather than by opening up the directory.
 */
@Singleton
class ReceiptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val receiptDao: ReceiptDao,
    private val imageStore: ReceiptImageStore,
    private val clock: KhaataClock,
) {

    fun observeForTransaction(transactionId: String): Flow<List<Receipt>> =
        receiptDao.observeForTransaction(transactionId).map { rows -> rows.map { it.toDomain() } }

    suspend fun forTransaction(transactionId: String): List<Receipt> =
        receiptDao.findForTransaction(transactionId).map { it.toDomain() }

    suspend fun findById(id: String): Receipt? = receiptDao.findById(id)?.toDomain()

    /** The file behind a stored receipt, for the viewer to decode. */
    fun fileFor(receipt: Receipt): File = imageStore.fileFor(receipt.relativePath)

    /** Total bytes every stored receipt occupies, for the storage line in settings. */
    suspend fun totalBytes(): Long = receiptDao.totalBytes()

    /**
     * Stores the image at [source] against [transactionId].
     *
     * The per-transaction and total caps are checked before anything is written, so a refusal
     * never leaves a file behind that no row points at.
     */
    suspend fun attach(transactionId: String, source: Uri): ReceiptAttachResult {
        if (receiptDao.countForTransaction(transactionId) >= ReceiptAttachResult.MAX_PER_TRANSACTION) {
            return ReceiptAttachResult.TooManyForTransaction
        }
        if (receiptDao.totalBytes() >= MAX_TOTAL_BYTES) {
            return ReceiptAttachResult.OutOfSpace
        }

        val stored = imageStore.import(source) ?: return ReceiptAttachResult.Unreadable

        val receipt = Receipt(
            id = UUID.randomUUID().toString(),
            transactionId = transactionId,
            relativePath = stored.relativePath,
            // Always JPEG: the store re-encodes whatever came in, so the viewer never meets a
            // format that arrived by accident.
            mimeType = MIME_JPEG,
            sizeBytes = stored.sizeBytes,
            capturedOn = clock.today(),
        )

        return runCatching {
            receiptDao.upsert(receipt.toEntity())
            ReceiptAttachResult.Attached(receipt)
        }.getOrElse { error ->
            // The row failed, so the file it points at is already garbage. Removed now rather
            // than left for the orphan sweep, which might not run for a week.
            KhaataLog.e(TAG, "Could not record an attached receipt", error)
            imageStore.delete(stored.relativePath)
            ReceiptAttachResult.Unreadable
        }
    }

    /** Removes a receipt and its image. */
    suspend fun delete(receipt: Receipt) {
        receiptDao.delete(receipt.toEntity())
        imageStore.delete(receipt.relativePath)
    }

    /**
     * Copies [receipt] into the shared exports cache and returns a URI to the copy.
     *
     * The copy is the point. Granting a URI into `filesDir/receipts` would mean the receipts
     * directory is reachable through the FileProvider from then on; copying one file into the
     * directory that is already shared keeps that guarantee intact, and the cache is cleared by
     * the system under pressure so the copy does not linger.
     */
    suspend fun shareableUri(receipt: Receipt): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val shared = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
            val copy = File(shared, "receipt-${receipt.capturedOn}-${receipt.id.take(8)}.jpg")
            imageStore.fileFor(receipt.relativePath).copyTo(copy, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        }.onFailure { KhaataLog.e(TAG, "Could not prepare a receipt to share", it) }
            .getOrNull()
    }

    /**
     * A file in the shared cache for a camera app to write into.
     *
     * `TakePicture` hands the camera a URI rather than returning a bitmap, and that URI has to be
     * one another app can write to. It points at the exports cache, not the receipts directory:
     * the photo is imported out of it moments later and the staging file deleted, so the private
     * directory is never exposed even for an instant.
     */
    fun newCaptureTarget(): CaptureTarget {
        val shared = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        val file = File(shared, "capture-${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return CaptureTarget(file = file, uri = uri)
    }

    /** Discards a staging file once its photo has been imported, or when capture was cancelled. */
    suspend fun discardCapture(target: CaptureTarget) = withContext(Dispatchers.IO) {
        runCatching { target.file.delete() }
        Unit
    }

    /** Removes every receipt row and image. Part of "delete all my data". */
    suspend fun deleteAll() {
        receiptDao.deleteAll()
        imageStore.deleteAll()
    }

    /**
     * Deletes images no row points at any more.
     *
     * Run from the maintenance worker. The common cause is a deleted transaction: Room's CASCADE
     * removes the receipt rows and leaves their files untouched, so without this a user who
     * cleared a year of transactions would keep every photo forever.
     *
     * @return how many files were reclaimed.
     */
    suspend fun purgeOrphans(): Int {
        val referenced = receiptDao.getAll().map { it.relativePath }.toSet()
        return imageStore.purgeOrphans(referenced)
    }

    private companion object {
        const val TAG = "ReceiptRepository"
        const val MIME_JPEG = "image/jpeg"

        /** Already declared in `file_paths.xml`; receipts borrow it rather than opening a new one. */
        const val SHARE_DIRECTORY = "exports"

        /**
         * Total receipt storage allowed, across every transaction.
         *
         * 256MB is several hundred receipts at the size these are stored. The cap exists because
         * an expense tracker quietly filling a phone is a one-star review, and because a user has
         * no way to see what is using the space until it is a problem.
         */
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }

    /** A staging file for the camera, and the URI handed to it. */
    data class CaptureTarget(val file: File, val uri: Uri)
}
