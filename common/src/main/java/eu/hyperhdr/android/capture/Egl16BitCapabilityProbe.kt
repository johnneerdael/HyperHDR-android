package eu.hyperhdr.android.capture

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log

/**
 * Probes the device's GLES driver for 16-bit FBO color-attachment support.
 *
 * Two probes (we test both because device support varies):
 * - [supportsR16Fbo]: `GL_R16` / `GL_RG16` (normalized 16-bit, GL_EXT_texture_norm16 extension).
 * - [supportsR16UiFbo]: `GL_R16UI` / `GL_RG16UI` (unsigned 16-bit integer, mandatory in core ES 3.0).
 *
 * Tier C ([EglP010Pipeline]) needs ONE of the two; the eventual pipeline architecture is chosen
 * based on which the device supports. Results are cached independently per probe.
 *
 * **Must be called from a thread with an EGL context already current.**
 */
object Egl16BitCapabilityProbe {

    private const val TAG = "HyperHdr.Probe"

    // GL_EXT_texture_norm16 constants (not exposed as named fields in Android's GLES30).
    private const val GL_R16  = 0x822A
    private const val GL_RG16 = 0x822C

    @Volatile private var cachedNormalized: Boolean? = null
    @Volatile private var cachedInteger: Boolean? = null

    /**
     * Probes `GL_R16` / `GL_RG16` (normalized 16-bit). Returns true iff both formats can be
     * allocated as textures and bound as FBO color attachments. Requires the
     * `GL_EXT_texture_norm16` extension, which many Android GLES drivers do NOT support.
     */
    fun supportsR16Fbo(): Boolean = probeFormat(
        cached = { cachedNormalized },
        setCached = { cachedNormalized = it },
        rInternal = GL_R16, rgInternal = GL_RG16,
        rFormat = GLES30.GL_RED, rgFormat = GLES30.GL_RG,
        type = GLES30.GL_UNSIGNED_SHORT,
        labelPrefix = "GL_R16/GL_RG16",
    )

    /**
     * Probes `GL_R16UI` / `GL_RG16UI` (unsigned 16-bit integer). Returns true iff both formats
     * can be allocated as textures and bound as FBO color attachments. These formats are
     * mandatory for color-renderable textures in core ES 3.0, so support is much more
     * widespread than the normalized variants.
     */
    fun supportsR16UiFbo(): Boolean = probeFormat(
        cached = { cachedInteger },
        setCached = { cachedInteger = it },
        rInternal = GLES30.GL_R16UI, rgInternal = GLES30.GL_RG16UI,
        rFormat = GLES30.GL_RED_INTEGER, rgFormat = GLES30.GL_RG_INTEGER,
        type = GLES30.GL_UNSIGNED_SHORT,
        labelPrefix = "GL_R16UI/GL_RG16UI",
    )

    /** Forces the next probe call to re-probe instead of returning the cached result. */
    fun reset() {
        cachedNormalized = null
        cachedInteger = null
    }

    private fun probeFormat(
        cached: () -> Boolean?,
        setCached: (Boolean) -> Unit,
        rInternal: Int, rgInternal: Int,
        rFormat: Int, rgFormat: Int,
        type: Int,
        labelPrefix: String,
    ): Boolean {
        cached()?.let { return it }

        val texIds = IntArray(2)
        val fboId = IntArray(1)
        try {
            GLES20.glGenTextures(2, texIds, 0)
            GLES20.glGenFramebuffers(1, fboId, 0)

            // Try the R-channel format first.
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            // Integer formats need NEAREST filtering — LINEAR is invalid on integer textures.
            val isInteger = rFormat == GLES30.GL_RED_INTEGER
            val filter = if (isInteger) GLES20.GL_NEAREST else GLES20.GL_LINEAR
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, rInternal,
                4, 4, 0, rFormat, type, null,
            )
            val rAllocErr = GLES20.glGetError()
            if (rAllocErr != GLES20.GL_NO_ERROR) {
                Log.i(TAG, "$labelPrefix texture allocation failed (err=0x${rAllocErr.toString(16)}) — unavailable")
                setCached(false); return false
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texIds[0], 0,
            )
            val rStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (rStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.i(TAG, "$labelPrefix R-FBO incomplete (status=0x${rStatus.toString(16)}) — unavailable")
                setCached(false); return false
            }

            // Try the RG-channel format.
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[1])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, rgInternal,
                4, 4, 0, rgFormat, type, null,
            )
            val rgAllocErr = GLES20.glGetError()
            if (rgAllocErr != GLES20.GL_NO_ERROR) {
                Log.i(TAG, "$labelPrefix RG texture allocation failed (err=0x${rgAllocErr.toString(16)}) — unavailable")
                setCached(false); return false
            }

            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texIds[1], 0,
            )
            val rgStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (rgStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.i(TAG, "$labelPrefix RG-FBO incomplete (status=0x${rgStatus.toString(16)}) — unavailable")
                setCached(false); return false
            }

            Log.i(TAG, "$labelPrefix FBO support: YES")
            setCached(true); return true
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fboId, 0)
            GLES20.glDeleteTextures(2, texIds, 0)
        }
    }
}
