package ai.labs32.khaata.data.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.receipts.ReceiptImagePlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where receipt images live on disk, and what is done to them on the way in.
 *
 * Files sit in `filesDir/receipts`, which is app-private storage: another app cannot read it, it
 * is excluded from Android's own cloud backup by `backup_rules.xml`, and it disappears when the
 * app is uninstalled. Nothing here ever writes to external storage, and nothing hands out a
 * `content://` URI to the directory — see [ReceiptRepository] for how sharing is done without one.
 *
 * Every image is normalised before it is stored:
 *
 *  - **Sub-sampled at decode.** A 12MP photo decoded whole is ~48MB in memory, which is an
 *    OutOfMemoryError on the low-end phones this app targets rather than a slow frame.
 *  - **Downscaled to 2048px on the long edge.** Enough that printed small print stays legible when
 *    zoomed; small enough that a receipt is a few hundred KB rather than four megabytes.
 *  - **Rotated to match its EXIF orientation.** Most phone cameras record the sensor image plus an
 *    orientation tag rather than rotating the pixels. Ignoring the tag is why a receipt
 *    photographed in portrait shows up on its side, and it is the single most common way an
 *    attachment feature looks broken.
 *  - **Re-encoded as JPEG.** The stored type is always `image/jpeg` regardless of what came in, so
 *    the viewer never has to handle a format the encoder produced by accident.
 */
@Singleton
class ReceiptImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The private directory holding every stored receipt. Created on first use. */
    fun directory(): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** Resolves a stored receipt's relative path to a real file. */
    fun fileFor(relativePath: String): File = File(context.filesDir, relativePath)

    /**
     * Imports the image at [source] into private storage.
     *
     * @return the stored file's path relative to `filesDir`, and its size, or null when the image
     *   could not be read or decoded — a corrupt pick is an expected outcome here, not a crash.
     */
    suspend fun import(source: Uri): Stored? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = readBounds(source) ?: return@runCatching null
            val bitmap = decodeScaled(source, bounds) ?: return@runCatching null
            val oriented = try {
                applyExifRotation(source, bitmap)
            } catch (error: Exception) {
                // A missing or unreadable EXIF block is not a reason to lose the photo; it just
                // means the image is stored as it decoded.
                KhaataLog.w(TAG, "Could not read image orientation")
                bitmap
            }

            val target = File(directory(), "${UUID.randomUUID()}.jpg")
            FileOutputStream(target).use { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, ReceiptImagePlan.JPEG_QUALITY, out)
            }
            // Recycled explicitly rather than left to the collector: these are multi-megabyte
            // allocations and attaching several in a row is exactly when that matters.
            if (oriented !== bitmap) oriented.recycle()
            bitmap.recycle()

            Stored(relativePath = "$DIRECTORY/${target.name}", sizeBytes = target.length())
        }.onFailure { KhaataLog.e(TAG, "Could not import a receipt image", it) }
            .getOrNull()
    }

    /** Deletes a stored receipt's file. Missing is success: the goal is that it is gone. */
    suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(relativePath)
        !file.exists() || file.delete()
    }

    /** Removes every stored receipt image. Part of "delete all my data". */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        directory().listFiles().orEmpty().forEach { runCatching { it.delete() } }
        Unit
    }

    /**
     * Deletes image files with no database row pointing at them.
     *
     * The two can drift apart: a transaction deleted through Room's CASCADE takes its receipt rows
     * with it and knows nothing about files, and a process killed between writing an image and
     * inserting its row leaves the file behind. Neither is recoverable from, so the files are
     * simply reclaimed.
     *
     * @param keep the relative paths that are still referenced.
     * @return how many files were removed.
     */
    suspend fun purgeOrphans(keep: Set<String>): Int = withContext(Dispatchers.IO) {
        val kept = keep.map { it.substringAfterLast('/') }.toSet()
        directory().listFiles().orEmpty()
            .filter { it.isFile && it.name !in kept }
            .count { runCatching { it.delete() }.getOrDefault(false) }
    }

    private fun readBounds(source: Uri): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    private fun decodeScaled(source: Uri, bounds: BitmapFactory.Options): Bitmap? {
        val sample = ReceiptImagePlan.sampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // RGB_565 halves the memory of a photo that has no alpha to preserve. A receipt is
            // text on paper; the banding this could cause has nowhere to show.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val target = ReceiptImagePlan.targetSize(decoded.width, decoded.height)
        if (target.width == decoded.width && target.height == decoded.height) return decoded

        val scaled = Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun applyExifRotation(source: Uri, bitmap: Bitmap): Bitmap {
        val orientation = context.contentResolver.openInputStream(source)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** A stored image: where it went, and how much room it takes. */
    data class Stored(val relativePath: String, val sizeBytes: Long)

    private companion object {
        const val TAG = "ReceiptImageStore"

        /** Relative to `filesDir`. Kept as a constant because stored paths embed it. */
        const val DIRECTORY = "receipts"
    }
}
