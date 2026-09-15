package ai.labs32.khaata.data.receipts

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * Importing a picked image.
 *
 * This exists because of a bug that every unit test in the project missed and that only a device
 * could show: `readBounds` treated the bounds decode's return value as a success signal, and a
 * bounds decode returns null *on success* — the dimensions come back in the options object, not
 * as a bitmap. Every image a user picked was rejected with "that image could not be read", which
 * is indistinguishable from a genuinely corrupt file, so the feature looked like it was working
 * and inspecting the photo rather than refusing everything sight unseen.
 *
 * The maths around it was covered by `ReceiptImagePlanTest` and was never wrong. What was missing
 * was a test that ran the import itself, which is what this is.
 *
 * Assertions stay on strings and booleans rather than on `File` objects, because Truth has no
 * `File` overload and `assertThat(someFile)` is an overload ambiguity in Kotlin rather than the
 * readable assertion it looks like.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned rather than inherited from targetSdk: this Robolectric cannot run SDK 36.
@Config(sdk = [34])
class ReceiptImageStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ReceiptImageStore(context)

    @Test
    fun `a picked image is imported rather than rejected`() = runTest {
        val stored = store.import(imageUri(width = 1200, height = 1600))

        assertThat(stored).isNotNull()
        assertThat(store.fileFor(stored!!.relativePath).exists()).isTrue()
        assertThat(stored.sizeBytes).isGreaterThan(0L)
    }

    @Test
    fun `a stored image is recorded relative to the private receipts directory`() = runTest {
        val stored = store.import(imageUri(width = 800, height = 600))

        assertThat(stored).isNotNull()
        // Relative, because an absolute path breaks the moment the app's data directory moves.
        assertThat(stored!!.relativePath).startsWith("receipts/")
        assertThat(store.fileFor(stored.relativePath).parentFile?.path)
            .isEqualTo(store.directory().path)
    }

    @Test
    fun `an unreadable source is reported rather than thrown`() = runTest {
        val missing = Uri.fromFile(File(context.cacheDir, "no-such-image.jpg"))

        // A corrupt or vanished pick is an expected outcome, not a crash: the caller turns this
        // into a message and the user picks something else.
        assertThat(store.import(missing)).isNull()
    }

    @Test
    fun `deleting a stored image removes its file`() = runTest {
        val stored = store.import(imageUri(width = 640, height = 480))

        assertThat(stored).isNotNull()
        assertThat(store.delete(stored!!.relativePath)).isTrue()
        assertThat(store.fileFor(stored.relativePath).exists()).isFalse()
    }

    @Test
    fun `an image no row points at is reclaimed and a referenced one is kept`() = runTest {
        val kept = store.import(imageUri(width = 400, height = 300))
        val orphan = store.import(imageUri(width = 400, height = 300))

        assertThat(kept).isNotNull()
        assertThat(orphan).isNotNull()

        val reclaimed = store.purgeOrphans(keep = setOf(kept!!.relativePath))

        assertThat(reclaimed).isEqualTo(1)
        assertThat(store.fileFor(kept.relativePath).exists()).isTrue()
        assertThat(store.fileFor(orphan!!.relativePath).exists()).isFalse()
    }

    /**
     * A PNG on disk, addressed the way the photo picker addresses one.
     *
     * Written through Android's own Bitmap rather than ImageIO: unit tests compile against
     * android.jar, which carries no java.awt or javax.imageio, so reaching for the JDK's image
     * classes here does not compile at all.
     */
    private fun imageUri(width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("receipt-source", ".png", context.cacheDir)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}
