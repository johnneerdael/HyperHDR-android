package eu.hyperhdr.android.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Egl16BitCapabilityProbeTest {

    @Test
    fun probeReturnsBooleanWithoutCrashing() {
        // Bring up an EGL context so the probe has something to call into.
        val core = EglCore()
        val pbuffer = core.createPbufferSurface(1, 1)
        core.makeCurrent(pbuffer)

        Egl16BitCapabilityProbe.reset()
        val result = Egl16BitCapabilityProbe.supportsR16Fbo()

        // We don't assert TRUE or FALSE — both are valid outcomes depending on the
        // device's driver. We just assert the probe completes without throwing and
        // returns a deterministic boolean.
        assertThat(result == true || result == false).isTrue()

        // Caching: second call returns same value without re-probing.
        val cached = Egl16BitCapabilityProbe.supportsR16Fbo()
        assertThat(cached).isEqualTo(result)

        core.destroySurface(pbuffer)
        core.release()
    }
}
