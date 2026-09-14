package ai.labs32.khaata.core.receipts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The resize maths behind receipt capture.
 *
 * Worth testing on its own because every failure mode here is either invisible or fatal: an image
 * scaled to a zero-width edge is rejected by the encoder, an unsampled 12MP decode is an
 * OutOfMemoryError on the low-end phones this app targets, and an image enlarged rather than
 * shrunk silently costs storage while looking identical.
 */
class ReceiptImagePlanTest {

    // ---- targetSize --------------------------------------------------------------------------

    @Test
    fun `a photo larger than the limit is scaled down to it`() {
        val size = ReceiptImagePlan.targetSize(4000, 3000, maxEdge = 2048)

        assertThat(size.width).isEqualTo(2048)
        assertThat(size.height).isEqualTo(1536)
    }

    @Test
    fun `a portrait photo is scaled on its own longest edge`() {
        val size = ReceiptImagePlan.targetSize(3000, 4000, maxEdge = 2048)

        assertThat(size.height).isEqualTo(2048)
        assertThat(size.width).isEqualTo(1536)
    }

    @Test
    fun `an image already within the limit is left exactly as it is`() {
        // Never enlarged: upscaling costs bytes and adds no detail a reader can see.
        val size = ReceiptImagePlan.targetSize(800, 600, maxEdge = 2048)

        assertThat(size).isEqualTo(ReceiptImagePlan.Size(800, 600))
    }

    @Test
    fun `an image exactly at the limit is untouched`() {
        val size = ReceiptImagePlan.targetSize(2048, 1024, maxEdge = 2048)

        assertThat(size).isEqualTo(ReceiptImagePlan.Size(2048, 1024))
    }

    @Test
    fun `aspect ratio survives the scale`() {
        val size = ReceiptImagePlan.targetSize(3000, 2000, maxEdge = 2048)
        val sourceRatio = 3000.0 / 2000.0
        val targetRatio = size.width.toDouble() / size.height

        assertThat(targetRatio).isWithin(0.01).of(sourceRatio)
    }

    @Test
    fun `an extreme panorama keeps at least one pixel on its short edge`() {
        // 4000x3 scaled to a 2048 long edge puts the short edge at 1.5px. Rounding that to zero
        // produces a size no encoder will accept, so it floors at one.
        val size = ReceiptImagePlan.targetSize(4000, 3, maxEdge = 2048)

        assertThat(size.width).isEqualTo(2048)
        assertThat(size.height).isAtLeast(1)
    }

    @Test
    fun `a square photo stays square`() {
        val size = ReceiptImagePlan.targetSize(3000, 3000, maxEdge = 2048)

        assertThat(size.width).isEqualTo(size.height)
        assertThat(size.width).isEqualTo(2048)
    }

    @Test
    fun `zero or negative dimensions are rejected rather than producing nonsense`() {
        listOf(0 to 100, 100 to 0, -1 to 100).forEach { (w, h) ->
            runCatching { ReceiptImagePlan.targetSize(w, h) }
                .also { assertThat(it.isFailure).isTrue() }
        }
    }

    // ---- sampleSize --------------------------------------------------------------------------

    @Test
    fun `an image within the limit is decoded at full size`() {
        assertThat(ReceiptImagePlan.sampleSize(1600, 1200, maxEdge = 2048)).isEqualTo(1)
    }

    @Test
    fun `sampling is always a power of two`() {
        val samples = listOf(
            ReceiptImagePlan.sampleSize(4000, 3000),
            ReceiptImagePlan.sampleSize(8000, 6000),
            ReceiptImagePlan.sampleSize(12000, 9000),
            ReceiptImagePlan.sampleSize(2500, 2500),
        )
        // The decoder rounds anything else down to one anyway, so a non-power-of-two would
        // silently decode at full size -- the exact allocation this is meant to avoid.
        samples.forEach { assertThat(Integer.bitCount(it)).isEqualTo(1) }
    }

    @Test
    fun `sampling never takes the image below the target size`() {
        // Sub-sampling too aggressively cannot be undone: the detail is gone before the exact
        // resize runs. Every sample size must leave the long edge at or above the target.
        val cases = listOf(4000 to 3000, 6000 to 4000, 12000 to 9000, 2049 to 1000)
        cases.forEach { (w, h) ->
            val sample = ReceiptImagePlan.sampleSize(w, h, maxEdge = 2048)
            val sampledLongEdge = maxOf(w, h) / sample

            assertThat(sampledLongEdge).isAtLeast(2048)
        }
    }

    @Test
    fun `a twelve megapixel photo is sub-sampled rather than decoded whole`() {
        // 4000x3000 at full size is ~48MB in ARGB_8888, which is an OOM on a 2GB device.
        assertThat(ReceiptImagePlan.sampleSize(4000, 3000, maxEdge = 2048)).isAtLeast(1)
        assertThat(ReceiptImagePlan.sampleSize(8000, 6000, maxEdge = 2048)).isAtLeast(2)
    }

    @Test
    fun `the defaults are the documented ones`() {
        assertThat(ReceiptImagePlan.MAX_EDGE_PX).isEqualTo(2048)
        assertThat(ReceiptImagePlan.JPEG_QUALITY).isEqualTo(80)
    }
}
