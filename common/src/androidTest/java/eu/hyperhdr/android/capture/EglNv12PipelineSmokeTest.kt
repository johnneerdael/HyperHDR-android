package eu.hyperhdr.android.capture

import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import eu.hyperhdr.android.flatbuf.FrameSink
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EglNv12PipelineSmokeTest {

    @Test
    fun solidRedInputProducesExpectedYUV() {
        val received = CountDownLatch(1)
        var capturedY: ByteArray? = null
        var capturedUv: ByteArray? = null

        val sink = object : FrameSink {
            override fun sendNv12(
                yPlane: ByteArray, uvPlane: ByteArray,
                width: Int, height: Int, strideY: Int, strideUv: Int,
            ) {
                if (capturedY != null) return
                capturedY = yPlane.copyOf()
                capturedUv = uvPlane.copyOf()
                received.countDown()
            }
        }

        val pipeline = EglNv12Pipeline(CaptureConfig.STANDARD, sink)
        val surface = pipeline.start()

        // Draw solid red into the surface
        val canvas: Canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(Color.RED)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue()

        // BT.601 limited-range red: Y ≈ 81, U ≈ 90, V ≈ 240. Tolerance ±8 for
        // emulator/driver variance and 8-bit quantisation.
        val y = capturedY!![0].toInt() and 0xFF
        val u = capturedUv!![0].toInt() and 0xFF
        val v = capturedUv!![1].toInt() and 0xFF
        assertThat(y).isIn(73..89)
        assertThat(u).isIn(82..98)
        assertThat(v).isIn(232..248)

        pipeline.stop()
    }
}
