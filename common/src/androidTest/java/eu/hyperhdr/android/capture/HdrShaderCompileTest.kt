package eu.hyperhdr.android.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import eu.hyperhdr.android.flatbuf.FrameSink
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HdrShaderCompileTest {

    @Test
    fun hdrAwarePipelineStartsCleanlyAndProducesAFrameForAnSrgbInput() {
        val received = CountDownLatch(1)
        val sink = object : FrameSink {
            override fun sendNv12(
                yPlane: ByteArray, uvPlane: ByteArray,
                width: Int, height: Int, strideY: Int, strideUv: Int,
            ) { received.countDown() }
        }
        val cfg = CaptureConfig(160, 90, 30, CaptureTier.HDR_AWARE)
        val pipeline = EglNv12Pipeline(cfg, sink)
        val surface = pipeline.start()

        val canvas = surface.lockCanvas(null)
        try { canvas.drawColor(android.graphics.Color.WHITE) } finally { surface.unlockCanvasAndPost(canvas) }

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue()
        pipeline.stop()
    }
}
