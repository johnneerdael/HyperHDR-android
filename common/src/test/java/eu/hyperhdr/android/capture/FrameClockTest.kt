// common/src/test/java/eu/hyperhdr/android/capture/FrameClockTest.kt
package eu.hyperhdr.android.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameClockTest {

    @Test
    fun `30 fps target on 60 Hz display fires every other vsync`() {
        val clock = FrameClock(targetFps = 30, displayHz = 60.0)
        val results = (0 until 6).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, false, true, false, true, false).inOrder()
    }

    @Test
    fun `60 fps target on 60 Hz display fires every vsync`() {
        val clock = FrameClock(targetFps = 60, displayHz = 60.0)
        val results = (0 until 5).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, true, true, true, true).inOrder()
    }

    @Test
    fun `60 fps target on 120 Hz display fires every other vsync`() {
        val clock = FrameClock(targetFps = 60, displayHz = 120.0)
        val results = (0 until 4).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, false, true, false).inOrder()
    }

    @Test
    fun `target above display rate fires every vsync (degraded gracefully)`() {
        val clock = FrameClock(targetFps = 90, displayHz = 60.0)
        val results = (0 until 3).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, true, true).inOrder()
    }
}
