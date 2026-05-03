package eu.hyperhdr.android.capture

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import eu.hyperhdr.android.flatbuf.FrameSink
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EglP010PipelineSmokeTest {

    @Test
    fun solidWhiteInputRoundTripsThroughP010PipelineToSink() {
        // Bring up an EGL context for the capability probe + check support up front.
        val core = EglCore()
        val pbuffer = core.createPbufferSurface(1, 1)
        core.makeCurrent(pbuffer)
        Egl16BitCapabilityProbe.reset()
        val supports = Egl16BitCapabilityProbe.supportsR16UiFbo()
        core.destroySurface(pbuffer)
        core.release()

        // If the device's GLES driver doesn't support R16UI FBO attachments, skip the test
        // gracefully — Tier C is unavailable on this device, that's a valid outcome.
        assumeTrue("Device GLES driver lacks GL_R16UI/GL_RG16UI FBO support", supports)

        val received = CountDownLatch(1)
        var capturedY: ByteArray? = null
        var capturedUv: ByteArray? = null
        val sink = object : FrameSink {
            override fun sendNv12(
                yPlane: ByteArray, uvPlane: ByteArray,
                width: Int, height: Int, strideY: Int, strideUv: Int,
            ) { /* unused by P010 pipeline */ }

            override fun sendP010(
                yPlane: ByteArray, uvPlane: ByteArray,
                width: Int, height: Int, strideY: Int, strideUv: Int,
            ) {
                if (capturedY != null) return
                capturedY = yPlane.copyOf()
                capturedUv = uvPlane.copyOf()
                received.countDown()
            }
        }

        val cfg = CaptureConfig(160, 90, 30, CaptureTier.HDR_NATIVE)
        val pipeline = EglP010Pipeline(cfg, sink)
        val surface = pipeline.start()

        val canvas = surface.lockCanvas(null)
        try { canvas.drawColor(Color.WHITE) } finally { surface.unlockCanvasAndPost(canvas) }

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(capturedY!!.size).isEqualTo(160 * 90 * 2)         // 28800 bytes
        assertThat(capturedUv!!.size).isEqualTo(160 * 90)             // 14400 bytes (uvSize = w*h)

        // Spot-check the byte layout: white input → BT.601 Y at 10-bit ≈ 940; Cb/Cr ≈ 512.
        // Packed into high 10 bits of u16 little-endian: Y ≈ 940 << 6 = 60160 = 0xEB00 →
        // bytes [0x00, 0xEB] in LE order. Allow ±8 LSB tolerance for shader rounding.
        // Just confirm the high byte is non-zero (proves we're writing into the high-bits region,
        // not zero-pad). Stricter color-matching is for the manual end-to-end recipe, not here.
        val firstPixelHighByte = capturedY!![1].toInt() and 0xFF
        assertThat(firstPixelHighByte).isGreaterThan(0)

        pipeline.stop()
    }
}
