package eu.hyperhdr.android.probe

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level driver: takes a granted [MediaProjection], runs HdrProbe then SdrProbe against
 * it, and surfaces progress via a [StateFlow<State>]. Output goes to
 * `<context.getExternalFilesDir(null)>/probe/<UTC-iso-timestamp>/`.
 */
class ProbeOrchestrator(
    private val context: Context,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val targetFrames: Int = 60,
) {
    sealed interface State {
        data object Idle : State
        data class CapturingHdr(val frames: Int) : State
        data class CapturingSdr(val frames: Int) : State
        data class Done(val outputFolder: File, val hdrFrames: Int, val sdrFrames: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun run(projection: MediaProjection) {
        val outputDir = makeOutputDir()
        val metadataFile = File(outputDir, "metadata.jsonl")
        val density = context.resources.displayMetrics.densityDpi

        FileWriter(metadataFile, true).use { fw ->
            val writer = MetadataWriter(fw)

            _state.value = State.CapturingHdr(0)
            val hdrResult = HdrProbe(projection, width, height, density, outputDir, writer, targetFrames).run()
            val hdrFrames = hdrResult.getOrElse {
                _state.value = State.Failed("HDR capture failed: ${it.message}")
                projection.stop()
                return
            }

            _state.value = State.CapturingSdr(0)
            val sdrResult = SdrProbe(projection, width, height, density, outputDir, writer, targetFrames).run()
            val sdrFrames = sdrResult.getOrElse {
                _state.value = State.Failed("SDR capture failed: ${it.message}")
                projection.stop()
                return
            }

            projection.stop()
            _state.value = State.Done(outputDir, hdrFrames, sdrFrames)
            Log.i(TAG, "Probe complete: hdr=$hdrFrames sdr=$sdrFrames in ${outputDir.absolutePath}")
        }
    }

    private fun makeOutputDir(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val base = context.getExternalFilesDir(null)
            ?: context.filesDir   // fallback if external storage unavailable
        val dir = File(base, "probe/$timestamp").apply { mkdirs() }
        return dir
    }

    companion object { private const val TAG = "ProbeOrchestrator" }
}
