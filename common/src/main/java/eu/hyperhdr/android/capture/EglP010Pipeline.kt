package eu.hyperhdr.android.capture

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import eu.hyperhdr.android.flatbuf.FrameSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 10-bit P010 capture pipeline. Samples the MediaProjection SurfaceTexture, runs a fragment
 * shader that converts RGB → BT.601 limited-range YCbCr at 10-bit precision, packs the result
 * into the high 10 bits of 16-bit unsigned integer FBO attachments (Microsoft P010 byte
 * layout), reads back via `glReadPixels(GL_*_INTEGER, GL_UNSIGNED_SHORT)`, and hands the
 * bytes to a [FrameSink] via [FrameSink.sendP010].
 *
 * Underlying texture format is `GL_R16UI`/`GL_RG16UI` (mandatory core ES 3.0). The bytes the
 * client emits and the bytes HyperHDR's P010 path consumes are identical regardless of whether
 * the GPU produced them as "normalized R16" or "integer R16UI" — only the GLSL output type
 * differs.
 *
 * Requires [Egl16BitCapabilityProbe.supportsR16UiFbo] to have returned true on this device's
 * GLES driver; the service-level tier selector enforces this gate.
 */
class EglP010Pipeline(
    private val config: CaptureConfig,
    private val sink: FrameSink,
) {
    companion object { private const val TAG = "HyperHdr.P010" }

    init {
        require(config.tier == CaptureTier.HDR_NATIVE) {
            "EglP010Pipeline requires CaptureTier.HDR_NATIVE; got ${config.tier}"
        }
    }

    private val thread = HandlerThread("HyperHdrP010Egl").also { it.start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private var pbuffer: android.opengl.EGLSurface? = null
    private lateinit var pack: P010PackShader

    private var oesTextureId = 0
    private var yTextureId = 0
    private var uvTextureId = 0
    private var yFbo = 0
    private var uvFbo = 0

    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface
    private val texTransform = FloatArray(16)

    // P010 byte sizes:
    //   Y plane: width * height samples × 2 bytes/sample
    //   UV plane: (width/2) × (height/2) Cb/Cr pairs × 2 samples × 2 bytes/sample
    //          = (width × height / 4) × 4 = width × height bytes
    private val ySize = config.width * config.height * 2
    private val uvSize = config.width * config.height
    private val yByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(ySize).order(ByteOrder.nativeOrder())
    private val uvByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(uvSize).order(ByteOrder.nativeOrder())
    private val yBytes = ByteArray(ySize)
    private val uvBytes = ByteArray(uvSize)

    @Volatile private var frameAvailableLatch = 0
    private val frameLock = Object()
    private val rateGate = FrameRateGate(targetFps = config.frameRate)

    fun start(): Surface {
        runOnGl {
            try {
                egl = EglCore()
                pbuffer = egl.createPbufferSurface(1, 1)
                egl.makeCurrent(pbuffer!!)

                pack = P010PackShader()

                oesTextureId = createOesTexture()
                yTextureId = createIntegerTexture(config.width, config.height,
                    GLES30.GL_R16UI, GLES30.GL_RED_INTEGER)
                uvTextureId = createIntegerTexture(config.width / 2, config.height / 2,
                    GLES30.GL_RG16UI, GLES30.GL_RG_INTEGER)

                yFbo = createFbo(yTextureId)
                uvFbo = createFbo(uvTextureId)

                surfaceTexture = SurfaceTexture(oesTextureId)
                surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
                surface = Surface(surfaceTexture)
            } catch (t: Throwable) {
                Log.w(TAG, "EglP010Pipeline.start() failed mid-init, cleaning up", t)
                releaseUnchecked()
                throw t
            }
        }
        return surface
    }

    fun stop() {
        runOnGl { releaseUnchecked() }
        thread.quitSafely()
    }

    private fun releaseUnchecked() {
        runCatching { if (::surface.isInitialized) surface.release() }
        runCatching {
            if (::surfaceTexture.isInitialized) {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.detachFromGLContext()
                surfaceTexture.release()
            }
        }
        if (yFbo != 0 || uvFbo != 0) {
            runCatching { GLES20.glDeleteFramebuffers(2, intArrayOf(yFbo, uvFbo), 0) }
            yFbo = 0; uvFbo = 0
        }
        if (oesTextureId != 0 || yTextureId != 0 || uvTextureId != 0) {
            runCatching { GLES20.glDeleteTextures(3, intArrayOf(oesTextureId, yTextureId, uvTextureId), 0) }
            oesTextureId = 0; yTextureId = 0; uvTextureId = 0
        }
        runCatching { if (::pack.isInitialized) pack.release() }
        runCatching { pbuffer?.let { if (::egl.isInitialized) egl.destroySurface(it) } }
        pbuffer = null
        runCatching { if (::egl.isInitialized) egl.release() }
    }

    private fun onFrameAvailable() {
        synchronized(frameLock) { frameAvailableLatch++ }
        handler.post(::drainOneFrame)
    }

    private fun drainOneFrame() {
        synchronized(frameLock) {
            if (frameAvailableLatch == 0) return
            frameAvailableLatch = 0
        }
        try {
            // Always advance the SurfaceTexture's buffer queue — skipping updateTexImage
            // would leave the producer (MediaProjection) blocked when its queue fills.
            surfaceTexture.updateTexImage()
            // Rate-gate the expensive successors. At 60 Hz source / 30 fps target this
            // skips half the glReadPixels + send work on the GPU side.
            if (!rateGate.shouldTick(System.nanoTime())) return
            surfaceTexture.getTransformMatrix(texTransform)

            // Pass 1: OES (sRGB float) → R16UI Y plane (10-bit Y packed in high bits of u16)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, yFbo)
            GLES20.glViewport(0, 0, config.width, config.height)
            pack.drawY(oesTextureId, texTransform)
            GLES20.glReadPixels(0, 0, config.width, config.height,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, yByteBuffer.position(0))
            yByteBuffer.position(0); yByteBuffer.get(yBytes)

            // Pass 2: OES → RG16UI UV plane at half resolution
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, uvFbo)
            GLES20.glViewport(0, 0, config.width / 2, config.height / 2)
            pack.drawUV(oesTextureId, texTransform)
            GLES20.glReadPixels(0, 0, config.width / 2, config.height / 2,
                GLES30.GL_RG_INTEGER, GLES30.GL_UNSIGNED_SHORT, uvByteBuffer.position(0))
            uvByteBuffer.position(0); uvByteBuffer.get(uvBytes)

            sink.sendP010(
                yPlane = yBytes,
                uvPlane = uvBytes,
                width = config.width,
                height = config.height,
                strideY = config.width * 2,
                strideUv = config.width * 2,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "drainOneFrame failed", t)
        }
    }

    private fun createOesTexture(): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    /** Integer textures REQUIRE GL_NEAREST filtering (linear is undefined). */
    private fun createIntegerTexture(w: Int, h: Int, internalFormat: Int, format: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, GLES30.GL_UNSIGNED_SHORT, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun createFbo(textureId: Int): Int {
        val id = IntArray(1)
        GLES20.glGenFramebuffers(1, id, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: 0x${status.toString(16)}" }
        return id[0]
    }

    private fun runOnGl(block: () -> Unit) {
        if (Thread.currentThread() == thread) { block(); return }
        val latch = java.util.concurrent.CountDownLatch(1)
        var thrown: Throwable? = null
        handler.post {
            try { block() } catch (t: Throwable) { thrown = t } finally { latch.countDown() }
        }
        latch.await()
        thrown?.let { throw it }
    }
}

/**
 * Two-pass shader that does RGB → BT.601 limited-range YCbCr conversion at 10-bit precision,
 * then packs each value into the high 10 bits of a 16-bit unsigned integer (P010 byte layout).
 *
 * The output is `uint`/`uvec2` because the FBO color attachments are GL_R16UI/GL_RG16UI;
 * regular `out vec` with a normalized format would be invalid here.
 */
private class P010PackShader {
    private val yProgram: ShaderProgram
    private val uvProgram: ShaderProgram
    private val quadVerts: java.nio.FloatBuffer

    init {
        val vertexSrc = """#version 300 es
in vec4 aPos;
in vec2 aTex;
uniform mat4 uTexTransform;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTexTransform * vec4(aTex, 0.0, 1.0)).xy;
}
"""
        val yFragmentSrc = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
in vec2 vTex;
out uint oY;
void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.601 limited-range Y at 10-bit precision: 64..940.
    float y10 = (0.257 * rgb.r + 0.504 * rgb.g + 0.098 * rgb.b) * 1023.0 + 64.0;
    uint y10u = uint(clamp(y10, 0.0, 1023.0));
    // Pack into the high 10 bits of a 16-bit word (P010 layout: 10 high bits + 6 zero pad).
    oY = y10u << 6u;
}
"""
        val uvFragmentSrc = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
in vec2 vTex;
out uvec2 oUV;
void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.601 limited-range Cb,Cr at 10-bit precision: 64..960, centered at 512.
    float u10 = (-0.148 * rgb.r - 0.291 * rgb.g + 0.439 * rgb.b) * 1023.0 + 512.0;
    float v10 = ( 0.439 * rgb.r - 0.368 * rgb.g - 0.071 * rgb.b) * 1023.0 + 512.0;
    uint u10u = uint(clamp(u10, 0.0, 1023.0));
    uint v10u = uint(clamp(v10, 0.0, 1023.0));
    oUV = uvec2(u10u << 6u, v10u << 6u);
}
"""
        yProgram = ShaderProgram(vertexSrc, yFragmentSrc)
        uvProgram = ShaderProgram(vertexSrc, uvFragmentSrc)

        quadVerts = java.nio.ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f,
                ))
                position(0)
            }
    }

    fun drawY(externalTextureId: Int, texTransform: FloatArray) =
        drawWith(yProgram, externalTextureId, texTransform)

    fun drawUV(externalTextureId: Int, texTransform: FloatArray) =
        drawWith(uvProgram, externalTextureId, texTransform)

    private fun drawWith(p: ShaderProgram, tex: Int, texTransform: FloatArray) {
        p.use()
        val aPos = p.attrib("aPos")
        val aTex = p.attrib("aTex")
        val uTex = p.uniform("uTex")
        val uTexTransform = p.uniform("uTexTransform")
        quadVerts.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aPos)
        quadVerts.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(uTexTransform, 1, false, texTransform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        yProgram.release()
        uvProgram.release()
    }
}
