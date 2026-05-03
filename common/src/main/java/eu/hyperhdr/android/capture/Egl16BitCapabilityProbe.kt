package eu.hyperhdr.android.capture

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log

/**
 * Probes the device's GLES driver for `GL_R16` and `GL_RG16` FBO color-attachment support.
 * Required by [EglP010Pipeline] (Tier C HDR). The result is cached after the first probe;
 * call [reset] to force re-detection (e.g. on configuration change).
 */
object Egl16BitCapabilityProbe {

    private const val TAG = "HyperHdr.Probe"

    // GL_EXT_texture_norm16 / OpenGL ES 3.1+ constants not exposed as named fields in GLES30.
    private const val GL_R16  = 0x822A  // == 33322
    private const val GL_RG16 = 0x822C  // == 33324

    @Volatile private var cached: Boolean? = null

    /**
     * Returns true iff the current EGL context supports allocating + binding `GL_R16`/`GL_RG16`
     * textures as FBO color attachments. **Must be called from a thread with an EGL context
     * already current** (typically the GL thread used by [EglNv12Pipeline] or a dedicated
     * probe thread spun up by the service).
     */
    fun supportsR16Fbo(): Boolean {
        cached?.let { return it }

        val texIds = IntArray(2)
        val fboId = IntArray(1)
        try {
            GLES20.glGenTextures(2, texIds, 0)
            GLES20.glGenFramebuffers(1, fboId, 0)

            // Try GL_R16 first
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES30.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GL_R16,
                4, 4, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_SHORT, null,
            )
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                Log.i(TAG, "GL_R16 texture allocation failed — Tier C unavailable")
                cached = false; return false
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texIds[0], 0,
            )
            val r16Status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (r16Status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.i(TAG, "GL_R16 FBO incomplete (status=$r16Status) — Tier C unavailable")
                cached = false; return false
            }

            // GL_RG16
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[1])
            GLES30.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GL_RG16,
                4, 4, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_SHORT, null,
            )
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                Log.i(TAG, "GL_RG16 texture allocation failed — Tier C unavailable")
                cached = false; return false
            }

            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texIds[1], 0,
            )
            val rg16Status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (rg16Status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.i(TAG, "GL_RG16 FBO incomplete (status=$rg16Status) — Tier C unavailable")
                cached = false; return false
            }

            Log.i(TAG, "GL_R16 + GL_RG16 FBO support: YES")
            cached = true; return true
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fboId, 0)
            GLES20.glDeleteTextures(2, texIds, 0)
        }
    }

    /** Forces the next [supportsR16Fbo] call to re-probe instead of returning the cached result. */
    fun reset() { cached = null }
}
