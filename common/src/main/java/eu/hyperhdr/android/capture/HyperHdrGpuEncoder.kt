package eu.hyperhdr.android.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import eu.hyperhdr.android.flatbuf.FrameSink

class HyperHdrGpuEncoder(
    private val mediaProjection: MediaProjection,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val density: Int,
    private val config: CaptureConfig,
    private val sink: FrameSink,
) {
    companion object { private const val TAG = "HyperHdr.Capture" }

    private val callbackThread = HandlerThread("HyperHdrEncoderCb").also { it.start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private var pipeline: EglNv12Pipeline? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stop() }
    }

    fun start() {
        check(pipeline == null) { "HyperHdrGpuEncoder already started" }
        mediaProjection.registerCallback(projectionCallback, callbackHandler)

        val p = EglNv12Pipeline(config, sink)
        pipeline = p
        val inputSurface = p.start()

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "HyperHdrCapture",
            sourceWidth, sourceHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            inputSurface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() { Log.i(TAG, "VirtualDisplay stopped") }
            },
            callbackHandler,
        )
    }

    fun stop() {
        try { mediaProjection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        virtualDisplay?.release(); virtualDisplay = null
        pipeline?.stop(); pipeline = null
        callbackThread.quitSafely()
    }
}
