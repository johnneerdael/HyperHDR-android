package eu.hyperhdr.android.flatbuf

import com.google.common.truth.Truth.assertThat
import hyperhdrnet.Clear
import hyperhdrnet.Color
import hyperhdrnet.Command
import hyperhdrnet.Image
import hyperhdrnet.ImageType
import hyperhdrnet.NV12Image
import hyperhdrnet.Register
import hyperhdrnet.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.nio.ByteBuffer

class HyperHdrFlatBufferClientTest {

    @Test
    fun `connect sends a Register frame with origin and priority`() = runTest {
        FakeHyperHdrServer().use { server ->
            val client = HyperHdrFlatBufferClient(
                host = "127.0.0.1",
                port = server.port,
                priority = 100,
                origin = "HyperHDR-Android",
            )
            client.connect()

            val frame = withTimeout(2_000) { server.receiveFrame() }
            val request = Request.getRootAsRequest(ByteBuffer.wrap(frame))

            assertThat(request.commandType()).isEqualTo(Command.Register)
            val register = Register().also { request.command(it) }
            assertThat(register.origin()).isEqualTo("HyperHDR-Android")
            assertThat(register.priority()).isEqualTo(100)

            client.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sendNv12 emits an Image frame with NV12 payload and correct strides`() = runTest {
        FakeHyperHdrServer().use { server ->
            val client = HyperHdrFlatBufferClient(
                host = "127.0.0.1",
                port = server.port,
                priority = 100,
            )
            client.connect()

            // Drain the Register frame so the next read is the Image frame.
            // Use real time (not virtual) since I/O happens on Dispatchers.IO threads.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }

            val w = 4
            val h = 2
            val y = ByteArray(w * h) { (it + 1).toByte() }       // 1..8
            val uv = ByteArray(w * (h / 2)) { (it + 100).toByte() } // 100..103

            client.sendNv12(y, uv, w, h, strideY = w, strideUv = w)

            val frame = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }
            val request = Request.getRootAsRequest(ByteBuffer.wrap(frame))
            assertThat(request.commandType()).isEqualTo(Command.Image)
            val image = Image().also { request.command(it) }
            assertThat(image.dataType()).isEqualTo(ImageType.NV12Image)

            val nv12 = NV12Image().also { image.data(it) }
            assertThat(nv12.width()).isEqualTo(w)
            assertThat(nv12.height()).isEqualTo(h)
            assertThat(nv12.strideY()).isEqualTo(w)
            assertThat(nv12.strideUv()).isEqualTo(w)
            assertThat(nv12.dataYLength()).isEqualTo(y.size)
            assertThat(nv12.dataUvLength()).isEqualTo(uv.size)
            // Spot-check the first and last byte of each plane.
            assertThat(nv12.dataY(0).toInt()).isEqualTo(1)
            assertThat(nv12.dataY(y.size - 1).toInt()).isEqualTo(8)
            assertThat(nv12.dataUv(0).toInt() and 0xFF).isEqualTo(100)
            assertThat(nv12.dataUv(uv.size - 1).toInt() and 0xFF).isEqualTo(103)

            client.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `clear emits a Clear command with the requested priority`() = runTest {
        FakeHyperHdrServer().use { server ->
            val client = HyperHdrFlatBufferClient(host = "127.0.0.1", port = server.port)
            client.connect()

            // Drain the Register frame so the next read is the Clear frame.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }

            client.clear(priority = 100)

            val frame = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }
            val request = Request.getRootAsRequest(ByteBuffer.wrap(frame))
            assertThat(request.commandType()).isEqualTo(Command.Clear)
            val clear = hyperhdrnet.Clear().also { request.command(it) }
            assertThat(clear.priority()).isEqualTo(100)

            client.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `setColor emits a Color command with packed RGB and duration`() = runTest {
        FakeHyperHdrServer().use { server ->
            val client = HyperHdrFlatBufferClient(host = "127.0.0.1", port = server.port)
            client.connect()

            // Drain the Register frame so the next read is the Color frame.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }

            val rgb = (0xFF shl 16) or (0xA0 shl 8) or 0x10 // 0xFFA010
            client.setColor(rgb, durationMs = 250)

            val frame = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }
            val request = Request.getRootAsRequest(ByteBuffer.wrap(frame))
            assertThat(request.commandType()).isEqualTo(Command.Color)
            val color = hyperhdrnet.Color().also { request.command(it) }
            assertThat(color.data()).isEqualTo(rgb)
            assertThat(color.duration()).isEqualTo(250)

            client.close()
        }
    }
}
