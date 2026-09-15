package ai.labs32.khaata.data.receipts

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Importing a picked image.
 *
 * This exists because of a bug that every unit test in the project missed and that only a device
 * could show: `readBounds` treated the bounds decode's return value as a success signal, and a
 * bounds decode returns null *on success* — the dimensions come back in the options object, not
 * as a bitmap. Every image a user picked was rejected with "that image could not be read", which
 * is indistinguishable from a genuinely corrupt file, so the feature looked like it worked and
 * refused everything.
 *
 * The maths around it was covered by `ReceiptImagePlanTest` and was never wrong. What was missing
 * was a test that ran the import end to end, which is what this is.
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
    fun `a stored image lands inside the private receipts directory`() = runTest {
        val stored = store.import(imageUri(width = 800, height = 600))

        assertThat(stored).isNotNull()
        // The path is stored relative to filesDir and resolved on read, so an absolute path here
        // would break the moment the app's data directory moved.
        assertThat(stored!!.relativePath).startsWith("receipts/")
        assertThat(store.fileFor(stored.relativePath).parentFile).isEqualTo(store.directory())
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
        val stored = store.import(imageUri(width = 640, height = 480))!!

        assertThat(store.delete(stored.relativePath)).isTrue()
        assertThat(store.fileFor(stored.relativePath).exists()).isFalse()
    }

    @Test
    fun `an image no row points at is reclaimed and a referenced one is kept`() = runTest {
        val kept = store.import(imageUri(width = 400, height = 300))!!
        val orphan = store.import(imageUri(width = 400, height = 300))!!

        val reclaimed = store.purgeOrphans(keep = setOf(kept.relativePath))

        assertThat(reclaimed).isEqualTo(1)
        assertThat(store.fileFor(kept.relativePath).exists()).isTrue()
        assertThat(store.fileFor(orphan.relativePath).exists()).isFalse()
    }

    /** A real PNG on disk, addressed the way the photo picker addresses one. */
    private fun imageUri(width: Int, height: Int): Uri {
        val file = File.createTempFile("receipt-source", ".png", context.cacheDir)
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file)
        return Uri.fromFile(file)
    }
}
