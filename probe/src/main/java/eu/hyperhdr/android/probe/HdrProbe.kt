package eu.hyperhdr.android.probe

import android.hardware.DataSpace
import android.hardware.HardwareBuffer
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
import java.io.Writer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Captures up to [targetFrames] HDR frames from [projection] into an [ImageReader] configured
 * for `HardwareBuffer.YCBCR_P010` + `DataSpace.DATASPACE_BT2020_PQ` and writes:
 *   - `frame-hdr-NNNN.raw`   — Y plane bytes followed by UV plane bytes (native byte order)
 *   - one line appended to [metadataWriter] per frame
 *
 * If the configuration throws (e.g. driver doesn't accept BT2020_PQ on this device), the
 * exception is caught at [run] and reported back via the returned [Result].
 *
 * Times out after [timeoutMs] if no frames arrive.
 */
class HdrProbe(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val density: Int,
    private val outputDir: File,
    private val metadataWriter: MetadataWriter,
    private val targetFrames: Int = 60,
    private val timeoutMs: Long = 10_000,
) {
    companion object { private const val TAG = "HdrProbe" }

    private val thread = HandlerThread("HdrProbe").also { it.start() }
    private val handler = Handler(thread.looper)

    suspend fun run(): Result<Int> = suspendCancellableCoroutine { cont ->
        val reader: ImageReader = try {
            ImageReader.Builder(width, height)
                .setMaxImages(3)
                .setDefaultHardwareBufferFormat(HardwareBuffer.YCBCR_P010)
                .setDefaultDataSpace(DataSpace.DATASPACE_BT2020_PQ)
                .setUsage(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "ImageReader.Builder rejected HDR config", t)
            cleanup(null, null)
            cont.resume(Result.failure(t)); return@suspendCancellableCoroutine
        }

        var frameCount = 0
        var virtualDisplay: VirtualDisplay? = null

        val timeoutRunnable = Runnable {
            Log.w(TAG, "HDR capture timed out after ${timeoutMs}ms with $frameCount frames")
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
                "HdrPqProbe-HDR",
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
        val planes = image.planes
        require(planes.size >= 2) { "P010 image expected to have ≥2 planes, got ${planes.size}" }
        val y = planes[0]; val uv = planes[1]
        val yBuf = y.buffer; val uvBuf = uv.buffer

        // Persist raw planes to disk. Y plane first, then UV.
        File(outputDir, "frame-hdr-%04d.raw".format(index)).outputStream().use { fos ->
            writeBufferTo(yBuf, fos)
            writeBufferTo(uvBuf, fos)
        }

        // Compute Y stats. UV stats are quick scans.
        val yStats = HistogramComputer.compute(yBuf, width, height, y.rowStride, y.pixelStride)
        val (uMean, vMean) = computeUvMeans(uvBuf, width / 2, height / 2, uv.rowStride, uv.pixelStride)

        val dataSpaceInt = image.dataSpace
        val hwFormatInt = image.hardwareBuffer?.format ?: -1
        metadataWriter.write(FrameMetadata(
            mode = "hdr",
            index = index,
            captureTsNs = image.timestamp,
            dataspaceInt = dataSpaceInt,
            dataspaceName = ConstantNameResolver.dataSpaceName(dataSpaceInt),
            hardwareBufferFormatInt = hwFormatInt,
            hardwareBufferFormatName = ConstantNameResolver.hardwareBufferFormatName(hwFormatInt),
            yPixelStride = y.pixelStride,
            yRowStride = y.rowStride,
            uvPixelStride = uv.pixelStride,
            uvRowStride = uv.rowStride,
            yMin = yStats.min, yMax = yStats.max, yMean = yStats.mean,
            uvUMean = uMean, uvVMean = vMean,
            yHistogram32Bin = yStats.histogram32,
        ))
    }

    private fun writeBufferTo(buf: java.nio.ByteBuffer, out: FileOutputStream) {
        val view = buf.duplicate()
        view.rewind()
        val arr = ByteArray(view.remaining())
        view.get(arr)
        out.write(arr)
    }

    /** Returns (uMean, vMean) — 10-bit means of U (Cb) and V (Cr) interleaved samples. */
    private fun computeUvMeans(
        plane: java.nio.ByteBuffer, uvWidth: Int, uvHeight: Int, rowStride: Int, pixelStride: Int,
    ): Pair<Int, Int> {
        val view = plane.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var uSum = 0L; var vSum = 0L
        for (y in 0 until uvHeight) {
            val rowStart = y * rowStride
            for (x in 0 until uvWidth) {
                val pos = rowStart + x * pixelStride
                val uWord = view.getShort(pos).toInt() and 0xFFFF
                val vWord = view.getShort(pos + 2).toInt() and 0xFFFF
                uSum += (uWord shr 6) and 0x3FF
                vSum += (vWord shr 6) and 0x3FF
            }
        }
        val n = uvWidth.toLong() * uvHeight
        return (uSum / n).toInt() to (vSum / n).toInt()
    }

    private fun cleanup(reader: ImageReader?, vd: VirtualDisplay?) {
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { thread.quitSafely() }
    }
}
