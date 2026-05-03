package eu.hyperhdr.android.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameRateGateTest {

    @Test
    fun `first tick always passes regardless of nano value`() {
        val gate = FrameRateGate(targetFps = 30)
        assertThat(gate.shouldTick(0L)).isTrue()
    }

    @Test
    fun `first tick passes even at very large nano values`() {
        val gate = FrameRateGate(targetFps = 30)
        assertThat(gate.shouldTick(Long.MAX_VALUE / 2)).isTrue()
    }

    @Test
    fun `second tick within frame interval is blocked`() {
        val gate = FrameRateGate(targetFps = 30)
        gate.shouldTick(0L)
        // 30 fps interval = 33,333,333 ns; try at 16 ms
        assertThat(gate.shouldTick(16_000_000L)).isFalse()
    }

    @Test
    fun `second tick exactly at frame interval passes`() {
        val gate = FrameRateGate(targetFps = 30)
        gate.shouldTick(0L)
        // 30 fps interval = 33,333,333 ns; try at exactly that
        assertThat(gate.shouldTick(33_333_333L)).isTrue()
    }

    @Test
    fun `second tick well after frame interval passes`() {
        val gate = FrameRateGate(targetFps = 30)
        gate.shouldTick(0L)
        assertThat(gate.shouldTick(100_000_000L)).isTrue()
    }

    @Test
    fun `successive blocked ticks do not advance the last-tick anchor`() {
        val gate = FrameRateGate(targetFps = 30)
        gate.shouldTick(0L)
        // Two blocked attempts within the interval must both return false
        // and the second must not silently shift the next-allowed deadline.
        assertThat(gate.shouldTick(10_000_000L)).isFalse()
        assertThat(gate.shouldTick(20_000_000L)).isFalse()
        // Now at exactly the original deadline, we should be allowed through.
        assertThat(gate.shouldTick(33_333_333L)).isTrue()
    }

    @Test
    fun `60 fps target gates roughly every 16 ms`() {
        val gate = FrameRateGate(targetFps = 60)
        gate.shouldTick(0L)
        // 60 fps interval = 16,666,666 ns
        assertThat(gate.shouldTick(8_000_000L)).isFalse()
        assertThat(gate.shouldTick(16_666_666L)).isTrue()
    }

    @Test
    fun `disabled mode targetFps zero always passes`() {
        val gate = FrameRateGate(targetFps = 0)
        assertThat(gate.shouldTick(0L)).isTrue()
        assertThat(gate.shouldTick(1L)).isTrue()
        assertThat(gate.shouldTick(Long.MAX_VALUE / 2)).isTrue()
    }

    @Test
    fun `disabled mode negative targetFps always passes`() {
        val gate = FrameRateGate(targetFps = -1)
        assertThat(gate.shouldTick(0L)).isTrue()
        assertThat(gate.shouldTick(1_000_000_000L)).isTrue()
    }
}
