package eu.hyperhdr.android.probe

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * SDR baseline capture. Same MediaProjection source, same target frame count, simpler
 * ImageReader configuration: PixelFormat.RGBA_8888, no DataSpace request. Output:
 *   - `frame-sdr-NNNN.raw`  — RGBA_8888 buffer (W*H*4 bytes), native byte order
 *   - one metadata line per frame via [metadataWriter]
 *
 * Y stats / histogram are computed from the RGBA luma proxy (0.299*R + 0.587*G + 0.114*B
 * scaled to the 10-bit range for fair comparison against HdrProbe's metadata format).
 * UV-mean fields are filled with sentinel zeros.
 */
class SdrProbe(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val density: Int,
    private val outputDir: File,
    private val metadataWriter: MetadataWriter,
    private val targetFrames: Int = 60,
    private val timeoutMs: Long = 10_000,
) {
    companion object { private const val TAG = "SdrProbe" }

    private val thread = HandlerThread("SdrProbe").also { it.start() }
    private val handler = Handler(thread.looper)

    suspend fun run(): Result<Int> = suspendCancellableCoroutine { cont ->
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        var frameCount = 0
        var virtualDisplay: VirtualDisplay? = null

        val timeoutRunnable = Runnable {
            Log.w(TAG, "SDR capture timed out after ${timeoutMs}ms with $frameCount frames")
            cleanup(reader, virtualDisplay)
            cont.resume(Result.success(frameCount))
        }

        reader.setOnImageAvailableListener({ r ->
            if (frameCount >= targetFrames) return@setOnImageAvailableListener
            val image: Image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processFrame(image, frameCount)
                frameCount++
                if (frameCount >= targetFrames) {
                    handler.removeCallbacks(timeoutRunnable)
                    cleanup(reader, virtualDisplay)
                    cont.resume(Result.success(frameCount))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Frame $frameCount processing failed", t)
            } finally {
                image.close()
            }
        }, handler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "HdrPqProbe-SDR",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                reader.surface,
                null, handler,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDisplay creation failed", t)
            cleanup(reader, null)
            cont.resume(Result.failure(t)); return@suspendCancellableCoroutine
        }

        handler.postDelayed(timeoutRunnable, timeoutMs)
        cont.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable)
            cleanup(reader, virtualDisplay)
        }
    }

    private fun processFrame(image: Image, index: Int) {
        val plane = image.planes[0]
        val buf = plane.buffer

        File(outputDir, "frame-sdr-%04d.raw".format(index)).outputStream().use { fos ->
            writeBufferTo(buf, fos)
        }

        // Compute a luma proxy histogram from RGBA. R/G/B are 8-bit each; we promote each
        // computed luma to the 10-bit range (multiply by 4) so the histogram bin layout
        // matches HdrProbe's, enabling direct overlay in the offline analysis.
        val (yMin, yMax, yMean, hist) = lumaStats(buf, width, height, plane.rowStride, plane.pixelStride)

        val dataSpaceInt = image.dataSpace
        val hwFormatInt = image.hardwareBuffer?.format ?: -1
        metadataWriter.write(FrameMetadata(
            mode = "sdr",
            index = index,
            captureTsNs = image.timestamp,
            dataspaceInt = dataSpaceInt,
            dataspaceName = ConstantNameResolver.dataSpaceName(dataSpaceInt),
            hardwareBufferFormatInt = hwFormatInt,
            hardwareBufferFormatName = ConstantNameResolver.hardwareBufferFormatName(hwFormatInt),
            yPixelStride = plane.pixelStride,
            yRowStride = plane.rowStride,
            uvPixelStride = 0, uvRowStride = 0,
            yMin = yMin, yMax = yMax, yMean = yMean,
            uvUMean = 0, uvVMean = 0,
            yHistogram32Bin = hist,
        ))
    }

    private fun writeBufferTo(buf: java.nio.ByteBuffer, out: FileOutputStream) {
        val view = buf.duplicate()
        view.rewind()
        val arr = ByteArray(view.remaining())
        view.get(arr)
        out.write(arr)
    }

    /** Returns (min, max, mean, histogram32) of the 10-bit-promoted RGB luma. */
    private fun lumaStats(
        buf: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int,
    ): IntStats {
        val view = buf.duplicate()
        val hist = IntArray(32)
        var min = Int.MAX_VALUE; var max = Int.MIN_VALUE; var sum = 0L
        for (y in 0 until h) {
            val rowStart = y * rowStride
            for (x in 0 until w) {
                val pos = rowStart + x * pixelStride
                val r = view.get(pos).toInt() and 0xFF
                val g = view.get(pos + 1).toInt() and 0xFF
                val b = view.get(pos + 2).toInt() and 0xFF
                val luma8 = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                val luma10 = luma8 * 4   // 8-bit → 10-bit by left-shift by 2
                if (luma10 < min) min = luma10
                if (luma10 > max) max = luma10
                sum += luma10
                hist[luma10 / 32]++
            }
        }
        val mean = (sum / (w.toLong() * h)).toInt()
        return IntStats(min, max, mean, hist)
    }

    private data class IntStats(val min: Int, val max: Int, val mean: Int, val hist: IntArray)

    private fun cleanup(reader: ImageReader?, vd: VirtualDisplay?) {
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { thread.quitSafely() }
    }
}
