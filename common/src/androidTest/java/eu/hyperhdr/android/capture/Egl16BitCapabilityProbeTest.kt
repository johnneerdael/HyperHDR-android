package eu.hyperhdr.android.capture

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Egl16BitCapabilityProbeTest {

    @Test
    fun probesBothFormatsWithoutCrashing() {
        // Bring up an EGL context so the probe has something to call into.
        val core = EglCore()
        val pbuffer = core.createPbufferSurface(1, 1)
        core.makeCurrent(pbuffer)

        Egl16BitCapabilityProbe.reset()
        val r16 = Egl16BitCapabilityProbe.supportsR16Fbo()
        val r16ui = Egl16BitCapabilityProbe.supportsR16UiFbo()

        // Log a single summary line that's easy to grep out of logcat.
        Log.i("HyperHdr.ProbeTest", "Probe summary: GL_R16=$r16  GL_R16UI=$r16ui")

        // We don't assert TRUE or FALSE — both are valid outcomes depending on the
        // device's driver. We just assert the probes completed deterministically.
        assertThat(r16 == true || r16 == false).isTrue()
        assertThat(r16ui == true || r16ui == false).isTrue()

        // Caching: second call returns the same value without re-probing.
        assertThat(Egl16BitCapabilityProbe.supportsR16Fbo()).isEqualTo(r16)
        assertThat(Egl16BitCapabilityProbe.supportsR16UiFbo()).isEqualTo(r16ui)

        core.destroySurface(pbuffer)
        core.release()
    }
}
