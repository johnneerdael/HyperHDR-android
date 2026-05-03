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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sendP010 emits an Image frame with P010 payload and correct strides`() = runTest {
        FakeHyperHdrServer().use { server ->
            val client = HyperHdrFlatBufferClient(
                host = "127.0.0.1",
                port = server.port,
                priority = 100,
            )
            client.connect()

            // Drain the Register frame.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }

            // 4×2 P010 — 2 bytes per Y sample, 2 bytes per Cb/Cr sample at half resolution.
            // Y plane: 4 * 2 samples * 2 bytes = 16 bytes
            // UV plane: (4/2) * (2/2) Cb/Cr pairs * 2 bytes/sample * 2 samples = 8 bytes — but
            // P010 interleaves Cb,Cr at full-pair count = (W/2)*(H/2)*2*2 = W*H bytes total.
            val w = 4
            val h = 2
            val y = ByteArray(w * h * 2) { (it + 1).toByte() }
            val uv = ByteArray(w * h) { (it + 100).toByte() }

            client.sendP010(y, uv, w, h, strideY = w * 2, strideUv = w * 2)

            val frame = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }
            val request = Request.getRootAsRequest(ByteBuffer.wrap(frame))
            assertThat(request.commandType()).isEqualTo(Command.Image)
            val image = Image().also { request.command(it) }
            assertThat(image.dataType()).isEqualTo(ImageType.P010Image)

            val p010 = hyperhdrnet.P010Image().also { image.data(it) }
            assertThat(p010.width()).isEqualTo(w)
            assertThat(p010.height()).isEqualTo(h)
            assertThat(p010.strideY()).isEqualTo(w * 2)
            assertThat(p010.strideUv()).isEqualTo(w * 2)
            assertThat(p010.dataYLength()).isEqualTo(y.size)
            assertThat(p010.dataUvLength()).isEqualTo(uv.size)
            assertThat(p010.dataY(0).toInt()).isEqualTo(1)
            assertThat(p010.dataY(y.size - 1).toInt() and 0xFF).isEqualTo(16)
            assertThat(p010.dataUv(0).toInt() and 0xFF).isEqualTo(100)
            assertThat(p010.dataUv(uv.size - 1).toInt() and 0xFF).isEqualTo(107)

            client.close()
        }
    }
}
