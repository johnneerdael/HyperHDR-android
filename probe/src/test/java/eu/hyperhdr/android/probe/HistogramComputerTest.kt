package eu.hyperhdr.android.probe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HistogramComputerTest {

    /** Build a P010 Y plane buffer where every sample is the same 10-bit value. */
    private fun uniformY(value10: Int, width: Int, height: Int, rowStride: Int): ByteBuffer {
        require(rowStride >= width * 2) { "rowStride must accommodate width*2 bytes" }
        val buf = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val word16 = ((value10 and 0x3FF) shl 6).toShort()
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                buf.putShort(rowStart + x * 2, word16)
            }
        }
        return buf
    }

    @Test
    fun `uniform mid-range Y produces single-bin histogram with matching mean`() {
        val buf = uniformY(value10 = 512, width = 16, height = 8, rowStride = 32)
        val stats = HistogramComputer.compute(buf, width = 16, height = 8, rowStride = 32, pixelStride = 2)
        assertThat(stats.min).isEqualTo(512)
        assertThat(stats.max).isEqualTo(512)
        assertThat(stats.mean).isEqualTo(512)
        // Bin width is 1024/32 = 32; value 512 falls in bin index 512/32 = 16.
        assertThat(stats.histogram32.sum()).isEqualTo(16 * 8)
        assertThat(stats.histogram32[16]).isEqualTo(16 * 8)
    }

    @Test
    fun `min and max scan the full range`() {
        val buf = uniformY(value10 = 64, width = 4, height = 2, rowStride = 8)
        // Inject a single bright sample at (0,0) = 940
        buf.order(ByteOrder.LITTLE_ENDIAN).putShort(0, ((940 and 0x3FF) shl 6).toShort())
        val stats = HistogramComputer.compute(buf, width = 4, height = 2, rowStride = 8, pixelStride = 2)
        assertThat(stats.min).isEqualTo(64)
        assertThat(stats.max).isEqualTo(940)
    }

    @Test
    fun `histogram total equals width times height`() {
        val buf = uniformY(value10 = 256, width = 32, height = 16, rowStride = 64)
        val stats = HistogramComputer.compute(buf, width = 32, height = 16, rowStride = 64, pixelStride = 2)
        assertThat(stats.histogram32.sum()).isEqualTo(32 * 16)
    }

    @Test
    fun `respects rowStride larger than width times pixelStride (padded rows)`() {
        // 4 pixels wide, but each row has 4 bytes of padding after the pixel data.
        val width = 4; val height = 2; val rowStride = 12  // (4 * 2) + 4 = 12
        val buf = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val v = ((300 and 0x3FF) shl 6).toShort()
        // Row 0: 4 valid samples + 4 bytes of padding; row 1: same
        for (y in 0 until height) {
            for (x in 0 until width) buf.putShort(y * rowStride + x * 2, v)
            // padding bytes intentionally left as 0; if HistogramComputer reads them it'll fail
        }
        val stats = HistogramComputer.compute(buf, width, height, rowStride, pixelStride = 2)
        // Should ONLY count the 4 * 2 = 8 valid samples, all at value 300.
        assertThat(stats.histogram32.sum()).isEqualTo(8)
        assertThat(stats.min).isEqualTo(300)
        assertThat(stats.max).isEqualTo(300)
    }
}
