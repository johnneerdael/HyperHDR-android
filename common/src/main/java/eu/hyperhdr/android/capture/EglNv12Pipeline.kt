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

class EglNv12Pipeline(
    private val config: CaptureConfig,
    private val sink: FrameSink,
) {
    companion object { private const val TAG = "HyperHdr.Capture" }

    private val thread = HandlerThread("HyperHdrEgl").also { it.start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private var pbuffer: android.opengl.EGLSurface? = null
    private var sdrDownscale: DownscaleProgram? = null
    private var hdrDownscale: HdrToneMapShader? = null
    private lateinit var pack: Nv12PackProgram

    private var oesTextureId = 0
    private var rgbaTextureId = 0
    private var yTextureId = 0
    private var uvTextureId = 0
    private var rgbaFbo = 0
    private var yFbo = 0
    private var uvFbo = 0

    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface
    private val texTransform = FloatArray(16)

    private val ySize = config.width * config.height
    private val uvSize = (config.width * config.height) / 2 // packed UV at half-res, 2 bytes per pixel
    private val yBuffer: ByteBuffer = ByteBuffer.allocateDirect(ySize).order(ByteOrder.nativeOrder())
    private val uvBuffer: ByteBuffer = ByteBuffer.allocateDirect(uvSize).order(ByteOrder.nativeOrder())
    private val yBytes = ByteArray(ySize)
    private val uvBytes = ByteArray(uvSize)

    private var frameAvailableLatch = 0
    private val frameLock = Object()

    fun start(): Surface {
        runOnGl {
            egl = EglCore()
            pbuffer = egl.createPbufferSurface(1, 1)
            egl.makeCurrent(pbuffer!!)

            when (config.tier) {
                CaptureTier.SDR -> sdrDownscale = DownscaleProgram()
                CaptureTier.HDR_AWARE -> hdrDownscale = HdrToneMapShader()
            }
            pack = Nv12PackProgram()

            oesTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            rgbaTextureId = createTexture2D(config.width, config.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, GLES20.GL_RGBA)
            yTextureId = createTexture2D(config.width, config.height, GLES30.GL_R8, GLES20.GL_UNSIGNED_BYTE, GLES30.GL_RED)
            uvTextureId = createTexture2D(config.width / 2, config.height / 2, GLES30.GL_RG8, GLES20.GL_UNSIGNED_BYTE, GLES30.GL_RG)

            rgbaFbo = createFbo(rgbaTextureId)
            yFbo = createFbo(yTextureId)
            uvFbo = createFbo(uvTextureId)

            surfaceTexture = SurfaceTexture(oesTextureId)
            // The source resolution is whatever the VirtualDisplay was created at — usually the
            // physical display size. We don't constrain it here; the downscale shader handles it.
            surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
            surface = Surface(surfaceTexture)
        }
        return surface
    }

    fun stop() {
        runOnGl {
            surface.release()
            surfaceTexture.release()
            GLES20.glDeleteFramebuffers(3, intArrayOf(rgbaFbo, yFbo, uvFbo), 0)
            GLES20.glDeleteTextures(4, intArrayOf(oesTextureId, rgbaTextureId, yTextureId, uvTextureId), 0)
            sdrDownscale?.release(); sdrDownscale = null
            hdrDownscale?.release(); hdrDownscale = null
            pack.release()
            pbuffer?.let { egl.destroySurface(it) }
            egl.release()
        }
        thread.quitSafely()
    }

    private fun onFrameAvailable() {
        synchronized(frameLock) { frameAvailableLatch++ }
        handler.post(::drainOneFrame)
    }

    private fun drainOneFrame() {
        synchronized(frameLock) {
            if (frameAvailableLatch == 0) return
            frameAvailableLatch = 0 // collapse — latest wins
        }
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texTransform)

            // Pass 1: OES → RGBA at target size
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, rgbaFbo)
            GLES20.glViewport(0, 0, config.width, config.height)
            if (config.tier == CaptureTier.SDR) {
                sdrDownscale!!.draw(oesTextureId, texTransform)
            } else {
                hdrDownscale!!.draw(oesTextureId, texTransform)
            }

            // Pass 2: RGBA → Y at target size
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, yFbo)
            GLES20.glViewport(0, 0, config.width, config.height)
            pack.drawY(rgbaTextureId)
            GLES20.glReadPixels(0, 0, config.width, config.height, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, yBuffer.position(0))
            yBuffer.position(0); yBuffer.get(yBytes)

            // Pass 3: RGBA → UV at half size
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, uvFbo)
            GLES20.glViewport(0, 0, config.width / 2, config.height / 2)
            pack.drawUV(rgbaTextureId)
            GLES20.glReadPixels(0, 0, config.width / 2, config.height / 2, GLES30.GL_RG, GLES20.GL_UNSIGNED_BYTE, uvBuffer.position(0))
            uvBuffer.position(0); uvBuffer.get(uvBytes)

            // Hand off to the sink. Strides equal width because we read pixels tightly packed.
            sink.sendNv12(
                yPlane = yBytes,
                uvPlane = uvBytes,
                width = config.width,
                height = config.height,
                strideY = config.width,
                strideUv = config.width,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "drainOneFrame failed", t)
        }
    }

    private fun createTexture(target: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(target, id[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun createTexture2D(w: Int, h: Int, internalFormat: Int, type: Int, format: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        // Use glTexImage2D instead of glTexStorage2D for broader driver compatibility
        // (some Android 9 / Mali drivers reject R8 / RG8 immutable storage as FBO attachments).
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
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
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: $status" }
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
