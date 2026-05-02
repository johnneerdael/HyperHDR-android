package eu.hyperhdr.android.flatbuf

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class HyperHdrFlatBufferReconnectorTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `reconnects after server-side disconnect, with backoff between attempts`() = runTest {
        FakeHyperHdrServer().use { server ->
            val reconnector = HyperHdrFlatBufferReconnector(
                host = "127.0.0.1",
                port = server.port,
                priority = 100,
                origin = "HyperHDR-Android",
                backoff = BackoffSchedule(longArrayOf(50L, 100L), capMillis = 100L),
            )
            reconnector.start()

            // First connect: receive the Register frame.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }
            assertThat(reconnector.state.value).isEqualTo(ConnectionState.CONNECTED)

            // Simulate server-side drop.
            server.forceDisconnect()

            // Reconnector should re-register within ~150 ms (50 ms backoff + connect).
            // Wait for the second Register frame.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                val frame2 = withTimeout(3_000) { server.receiveFrame() }
                assertThat(frame2).isNotEmpty()
            }
            // Allow state to settle.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                delay(200)
            }
            assertThat(reconnector.state.value).isEqualTo(ConnectionState.CONNECTED)

            reconnector.close()
        }
    }
}
