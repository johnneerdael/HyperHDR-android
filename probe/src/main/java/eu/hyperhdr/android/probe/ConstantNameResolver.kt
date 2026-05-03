package eu.hyperhdr.android.probe

import android.hardware.DataSpace
import android.hardware.HardwareBuffer

/**
 * Resolves Android system constants (DataSpace.*, HardwareBuffer.*) from int back to symbolic
 * name via reflection. Used to make the probe's metadata.jsonl self-describing — even if the
 * int encoding of a constant changes between API levels, the dump still records what it was.
 */
object ConstantNameResolver {

    private val dataSpaceNames: Map<Int, String> by lazy {
        DataSpace::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && it.name.startsWith("DATASPACE_") }
            .associate { runCatching { it.getInt(null) to it.name }.getOrNull() ?: (-1 to it.name) }
            .filterKeys { it != -1 }
    }

    private val hardwareBufferFormatNames: Map<Int, String> by lazy {
        HardwareBuffer::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .filter { it.name in EXPECTED_HW_FORMATS }
            .associate { runCatching { it.getInt(null) to it.name }.getOrNull() ?: (-1 to it.name) }
            .filterKeys { it != -1 }
    }

    private val EXPECTED_HW_FORMATS = setOf(
        "RGBA_8888", "RGBX_8888", "RGB_888", "RGB_565",
        "RGBA_FP16", "RGBA_1010102",
        "BLOB", "DEPTH_16", "DEPTH_24", "DEPTH_24_STENCIL_8", "DEPTH_32F", "DEPTH_32F_STENCIL_8",
        "STENCIL_8", "YCBCR_420_888", "YCBCR_P010",
    )

    fun dataSpaceName(value: Int): String = dataSpaceNames[value] ?: "UNKNOWN(0x%08X)".format(value)

    fun hardwareBufferFormatName(value: Int): String =
        hardwareBufferFormatNames[value] ?: "UNKNOWN(0x%08X)".format(value)
}
