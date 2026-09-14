package ai.labs32.khaata.core.receipts

/**
 * How a captured receipt image should be resized before it is stored.
 *
 * A phone camera hands back 8–12 megapixels, around 4MB a shot. Storing that is wrong twice over:
 * a few dozen receipts would outweigh the entire ledger, and none of that resolution survives
 * being read on a phone screen anyway. Every serious expense app downscales on capture.
 *
 * The maths lives in `core` rather than beside `BitmapFactory` so the awkward parts — an already
 * small image, an extreme panorama, the sample-size power of two — are covered by tests that run
 * on any JDK instead of needing a device.
 */
object ReceiptImagePlan {

    /**
     * Longest edge, in pixels, that a stored receipt keeps.
     *
     * 2048 is the point where a printed bill stays legible when zoomed — which is the whole reason
     * someone attaches one — while a typical photo lands comfortably under 500KB. Going to 1024
     * starts losing small print on a long supermarket receipt; staying at 4000 buys nothing a
     * reader can see.
     */
    const val MAX_EDGE_PX = 2048

    /** JPEG quality. 80 is the usual knee: artefacts are invisible, size is roughly a quarter. */
    const val JPEG_QUALITY = 80

    /**
     * The size [sourceWidth] × [sourceHeight] should be stored at.
     *
     * Aspect ratio is preserved and an image already within [maxEdge] is never enlarged — scaling
     * a small image up would cost bytes and add nothing.
     */
    fun targetSize(sourceWidth: Int, sourceHeight: Int, maxEdge: Int = MAX_EDGE_PX): Size {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "Image dimensions must be positive, got ${sourceWidth}x$sourceHeight"
        }
        require(maxEdge > 0) { "maxEdge must be positive, got $maxEdge" }

        val longest = maxOf(sourceWidth, sourceHeight)
        if (longest <= maxEdge) return Size(sourceWidth, sourceHeight)

        val scale = maxEdge.toDouble() / longest
        // Rounded, then floored at 1: a 4000x3 panorama would otherwise scale its short edge to
        // zero and produce an image no decoder will accept.
        return Size(
            width = (sourceWidth * scale).roundToIntAtLeastOne(),
            height = (sourceHeight * scale).roundToIntAtLeastOne(),
        )
    }

    /**
     * The `BitmapFactory.Options.inSampleSize` to decode with.
     *
     * Decoding a 12MP image at full size only to shrink it allocates ~48MB, which is how photo
     * attachment becomes an OutOfMemoryError on a low-end phone. Sub-sampling at decode time is
     * the fix, and the decoder only honours powers of two — so this returns the largest power of
     * two that still leaves the image at or above the target, with the final exact resize done
     * afterwards.
     */
    fun sampleSize(sourceWidth: Int, sourceHeight: Int, maxEdge: Int = MAX_EDGE_PX): Int {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "Image dimensions must be positive, got ${sourceWidth}x$sourceHeight"
        }
        require(maxEdge > 0) { "maxEdge must be positive, got $maxEdge" }

        var sample = 1
        var longest = maxOf(sourceWidth, sourceHeight)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun Double.roundToIntAtLeastOne(): Int = Math.round(this).toInt().coerceAtLeast(1)

    /** A pixel size. Deliberately not `android.util.Size`, which cannot be used from `core`. */
    data class Size(val width: Int, val height: Int)
}
