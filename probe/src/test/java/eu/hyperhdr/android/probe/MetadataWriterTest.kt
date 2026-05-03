package eu.hyperhdr.android.probe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.StringWriter

class MetadataWriterTest {

    @Test
    fun `serializes hdr-mode frame as one JSONL line ending in newline`() {
        val out = StringWriter()
        val writer = MetadataWriter(out)
        writer.write(FrameMetadata(
            mode = "hdr",
            index = 0,
            captureTsNs = 1234567890L,
            dataspaceInt = 281542656,
            dataspaceName = "DATASPACE_BT2020_PQ",
            hardwareBufferFormatInt = 54,
            hardwareBufferFormatName = "YCBCR_P010",
            yPixelStride = 2,
            yRowStride = 7680,
            uvPixelStride = 4,
            uvRowStride = 7680,
            yMin = 196, yMax = 904, yMean = 428,
            uvUMean = 510, uvVMean = 516,
            yHistogram32Bin = intArrayOf(0, 0, 12, 89, 50, 30, 20, 10, 5, 3, 0, 0, 0, 0, 0, 0,
                                         0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        ))
        val text = out.toString()
        assertThat(text).endsWith("\n")
        assertThat(text).contains("\"mode\":\"hdr\"")
        assertThat(text).contains("\"index\":0")
        assertThat(text).contains("\"dataspace\":281542656")
        assertThat(text).contains("\"dataspace_name\":\"DATASPACE_BT2020_PQ\"")
        assertThat(text).contains("\"y_min\":196")
        assertThat(text).contains("\"y_histogram_32bin\":[0,0,12,89,50,30,20,10,5,3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]")
    }

    @Test
    fun `multiple frames produce one line each`() {
        val out = StringWriter()
        val writer = MetadataWriter(out)
        for (i in 0 until 3) {
            writer.write(FrameMetadata(
                mode = "sdr", index = i, captureTsNs = 1000L + i,
                dataspaceInt = 0, dataspaceName = "DATASPACE_UNKNOWN",
                hardwareBufferFormatInt = 1, hardwareBufferFormatName = "RGBA_8888",
                yPixelStride = 4, yRowStride = 4 * 1920,
                uvPixelStride = 0, uvRowStride = 0,
                yMin = 0, yMax = 255, yMean = 128,
                uvUMean = 0, uvVMean = 0,
                yHistogram32Bin = IntArray(32),
            ))
        }
        val lines = out.toString().split("\n").filter { it.isNotEmpty() }
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).contains("\"index\":0")
        assertThat(lines[1]).contains("\"index\":1")
        assertThat(lines[2]).contains("\"index\":2")
    }
}
