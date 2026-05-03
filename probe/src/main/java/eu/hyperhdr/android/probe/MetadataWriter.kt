package eu.hyperhdr.android.probe

import java.io.Writer

/**
 * One captured frame's metadata. Mode is "hdr" or "sdr". Histogram array always has 32 entries.
 */
data class FrameMetadata(
    val mode: String,
    val index: Int,
    val captureTsNs: Long,
    val dataspaceInt: Int,
    val dataspaceName: String,
    val hardwareBufferFormatInt: Int,
    val hardwareBufferFormatName: String,
    val yPixelStride: Int,
    val yRowStride: Int,
    val uvPixelStride: Int,
    val uvRowStride: Int,
    val yMin: Int,
    val yMax: Int,
    val yMean: Int,
    val uvUMean: Int,
    val uvVMean: Int,
    val yHistogram32Bin: IntArray,
) {
    init {
        require(yHistogram32Bin.size == 32) { "histogram must have 32 bins" }
        require(mode == "hdr" || mode == "sdr") { "mode must be 'hdr' or 'sdr'" }
    }
}

/**
 * Writes one [FrameMetadata] per line as JSON, terminated by `\n`. The output is a JSONL
 * stream — `metadata.jsonl` in the probe's run directory.
 *
 * Hand-rolled string assembly (no JSON library dependency) — output is small and we control
 * every type. Strings are escaped only against the characters we actually emit (`"` and `\`);
 * mode/dataspace_name/format_name are constants under our control, no untrusted input.
 */
class MetadataWriter(private val out: Writer) {

    fun write(m: FrameMetadata) {
        val sb = StringBuilder(512)
        sb.append('{')
        appendStr(sb, "mode", m.mode); sb.append(',')
        appendInt(sb, "index", m.index); sb.append(',')
        appendLong(sb, "capture_ts_ns", m.captureTsNs); sb.append(',')
        appendInt(sb, "dataspace", m.dataspaceInt); sb.append(',')
        appendStr(sb, "dataspace_name", m.dataspaceName); sb.append(',')
        appendInt(sb, "hardware_buffer_format", m.hardwareBufferFormatInt); sb.append(',')
        appendStr(sb, "hardware_buffer_format_name", m.hardwareBufferFormatName); sb.append(',')
        appendInt(sb, "y_pixel_stride", m.yPixelStride); sb.append(',')
        appendInt(sb, "y_row_stride", m.yRowStride); sb.append(',')
        appendInt(sb, "uv_pixel_stride", m.uvPixelStride); sb.append(',')
        appendInt(sb, "uv_row_stride", m.uvRowStride); sb.append(',')
        appendInt(sb, "y_min", m.yMin); sb.append(',')
        appendInt(sb, "y_max", m.yMax); sb.append(',')
        appendInt(sb, "y_mean", m.yMean); sb.append(',')
        appendInt(sb, "uv_u_mean", m.uvUMean); sb.append(',')
        appendInt(sb, "uv_v_mean", m.uvVMean); sb.append(',')
        appendIntArray(sb, "y_histogram_32bin", m.yHistogram32Bin)
        sb.append("}\n")
        out.write(sb.toString())
        out.flush()
    }

    private fun appendStr(sb: StringBuilder, k: String, v: String) {
        sb.append('"').append(k).append("\":\"").append(v).append('"')
    }
    private fun appendInt(sb: StringBuilder, k: String, v: Int) {
        sb.append('"').append(k).append("\":").append(v)
    }
    private fun appendLong(sb: StringBuilder, k: String, v: Long) {
        sb.append('"').append(k).append("\":").append(v)
    }
    private fun appendIntArray(sb: StringBuilder, k: String, v: IntArray) {
        sb.append('"').append(k).append("\":[")
        for (i in v.indices) {
            if (i > 0) sb.append(',')
            sb.append(v[i])
        }
        sb.append(']')
    }
}
