package eu.hyperhdr.android.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Computes a 32-bin histogram and min/max/mean over the 10-bit Y values stored in a P010
 * plane buffer. Pure logic, no Android deps, suitable for JVM unit tests.
 *
 * The P010 wire format encodes each sample as a 16-bit little-endian word: high 10 bits =
 * the 10-bit value, low 6 bits = zero pad. We extract the 10-bit value as
 * `(word >> 6) & 0x3FF`.
 *
 * The histogram has 32 bins each spanning 32 of the 0..1023 range
 * (bin width = 1024 / 32 = 32). A value `v` lands in bin `v / 32`.
 */
object HistogramComputer {

    data class Stats(
        val min: Int,
        val max: Int,
        val mean: Int,
        val histogram32: IntArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Stats &&
            min == other.min && max == other.max && mean == other.mean &&
            histogram32.contentEquals(other.histogram32)
        override fun hashCode(): Int =
            (min * 31 + max) * 31 * 31 + mean * 31 + histogram32.contentHashCode()
    }

    /**
     * @param plane The Y plane bytes. The buffer's byte-order is overridden to LITTLE_ENDIAN
     * for reading (matching the P010 wire format) but the buffer's position is not modified.
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param rowStride Bytes per row of the plane (may exceed `width * pixelStride` due to padding).
     * @param pixelStride Bytes per pixel sample (P010 Y plane = 2).
     */
    fun compute(plane: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): Stats {
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(pixelStride >= 2) { "P010 sample is 2 bytes minimum" }
        require(rowStride >= width * pixelStride) { "rowStride must accommodate the row" }

        val view = plane.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val histogram = IntArray(32)
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var sum = 0L
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val word = view.getShort(rowStart + x * pixelStride).toInt() and 0xFFFF
                val value10 = (word shr 6) and 0x3FF
                if (value10 < min) min = value10
                if (value10 > max) max = value10
                sum += value10
                histogram[value10 / 32]++
            }
        }
        val mean = (sum / (width.toLong() * height)).toInt()
        return Stats(min = min, max = max, mean = mean, histogram32 = histogram)
    }
}
