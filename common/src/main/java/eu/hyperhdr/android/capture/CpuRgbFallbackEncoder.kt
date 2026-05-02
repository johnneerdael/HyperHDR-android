package eu.hyperhdr.android.capture

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import eu.hyperhdr.android.flatbuf.FrameSink

class CpuRgbFallbackEncoder(
    private val mediaProjection: MediaProjection,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val density: Int,
    private val config: CaptureConfig,
    private val sink: FrameSink,
) {
    private val thread = HandlerThread("HyperHdrCpuFallback").also { it.start() }
    private val handler = Handler(thread.looper)
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val ySize = config.width * config.height
    private val uvSize = ySize / 2
    private val yBytes = ByteArray(ySize)
    private val uvBytes = ByteArray(uvSize)

    fun start() {
        // ImageReader at the target capture size — Android scales the VirtualDisplay output to match.
        val reader = ImageReader.newInstance(config.width, config.height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try { convertAndSend(img) } finally { img.close() }
        }, handler)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "HyperHdrCpuFallback",
            config.width, config.height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface, null, handler,
        )
    }

    fun stop() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        thread.quitSafely()
    }

    private fun convertAndSend(img: android.media.Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val w = img.width; val h = img.height
        // BT.601 limited-range RGBA → NV12.
        var yIdx = 0; var uvIdx = 0
        for (row in 0 until h) {
            val rowOff = row * rowStride
            for (col in 0 until w) {
                val off = rowOff + col * 4
                val r = buf.get(off).toInt() and 0xFF
                val g = buf.get(off + 1).toInt() and 0xFF
                val b = buf.get(off + 2).toInt() and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBytes[yIdx++] = y.coerceIn(0, 255).toByte()
                if ((row and 1) == 0 && (col and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uvBytes[uvIdx++] = u.coerceIn(0, 255).toByte()
                    uvBytes[uvIdx++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        sink.sendNv12(yBytes, uvBytes, w, h, w, w)
    }
}
