package eu.hyperhdr.android.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import eu.hyperhdr.android.flatbuf.FrameSink

/** Internal-only abstraction over EglNv12Pipeline / EglP010Pipeline so HyperHdrGpuEncoder dispatches on tier. */
private interface CapturePipeline {
    fun start(): android.view.Surface
    fun stop()
}

private class Nv12Wrapper(private val inner: EglNv12Pipeline) : CapturePipeline {
    override fun start(): android.view.Surface = inner.start()
    override fun stop() = inner.stop()
}

private class P010Wrapper(private val inner: EglP010Pipeline) : CapturePipeline {
    override fun start(): android.view.Surface = inner.start()
    override fun stop() = inner.stop()
}

class HyperHdrGpuEncoder(
    private val mediaProjection: MediaProjection,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val density: Int,
    private val config: CaptureConfig,
    private val sink: FrameSink,
    private val onProjectionStopped: (() -> Unit)? = null,  // <-- NEW
) {
    companion object { private const val TAG = "HyperHdr.Capture" }

    private val callbackThread = HandlerThread("HyperHdrEncoderCb").also { it.start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private var pipeline: CapturePipeline? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
            onProjectionStopped?.invoke()
        }
    }

    fun start() {
        check(pipeline == null) { "HyperHdrGpuEncoder already started" }
        mediaProjection.registerCallback(projectionCallback, callbackHandler)

        val p: CapturePipeline = when (config.tier) {
            CaptureTier.HDR_NATIVE -> P010Wrapper(EglP010Pipeline(config, sink))
            CaptureTier.SDR, CaptureTier.HDR_AWARE -> Nv12Wrapper(EglNv12Pipeline(config, sink))
        }
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
