package eu.hyperhdr.android.flatbuf

import com.google.common.truth.Truth.assertThat
import hyperhdrnet.Command
import hyperhdrnet.Register
import hyperhdrnet.Request
import kotlinx.coroutines.test.runTest
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
}
