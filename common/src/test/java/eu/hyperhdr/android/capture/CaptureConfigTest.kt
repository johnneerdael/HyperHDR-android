package eu.hyperhdr.android.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class CaptureConfigTest {

    @Test
    fun `standard preset is 160x90 at 30 fps in SDR`() {
        val c = CaptureConfig.STANDARD
        assertThat(c.width).isEqualTo(160)
        assertThat(c.height).isEqualTo(90)
        assertThat(c.frameRate).isEqualTo(30)
        assertThat(c.tier).isEqualTo(CaptureTier.SDR)
    }

    @Test
    fun `high preset is 192x108 at 60 fps in SDR`() {
        val c = CaptureConfig.HIGH
        assertThat(c.width).isEqualTo(192)
        assertThat(c.height).isEqualTo(108)
        assertThat(c.frameRate).isEqualTo(60)
        assertThat(c.tier).isEqualTo(CaptureTier.SDR)
    }

    @Test
    fun `odd width is rejected`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(width = 161, height = 90, frameRate = 30, tier = CaptureTier.SDR)
        }
        assertThat(ex).hasMessageThat().contains("even")
    }

    @Test
    fun `odd height is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(width = 160, height = 91, frameRate = 30, tier = CaptureTier.SDR)
        }
    }

    @Test
    fun `non-positive frame rate is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(width = 160, height = 90, frameRate = 0, tier = CaptureTier.SDR)
        }
    }

    @Test
    fun `HDR_NATIVE tier is a recognized CaptureTier value`() {
        // Lightweight presence check — the actual capability probe + pipeline selection
        // happens in HyperHdrCaptureService and EglP010Pipeline.
        val cfg = CaptureConfig(width = 160, height = 90, frameRate = 30, tier = CaptureTier.HDR_NATIVE)
        assertThat(cfg.tier).isEqualTo(CaptureTier.HDR_NATIVE)
    }
}
