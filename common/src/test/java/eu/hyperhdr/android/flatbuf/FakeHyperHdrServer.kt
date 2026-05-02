package eu.hyperhdr.android.flatbuf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Loopback TCP server that captures length-prefixed FlatBuffer frames.
 * Each call to [receiveFrame] suspends until the next frame arrives.
 * The server holds the latest accepted [Socket] so tests can simulate
 * server-side disconnects.
 */
class FakeHyperHdrServer : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    private val acceptedSocket = AtomicReference<Socket?>()
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val acceptJob: Job

    val port: Int get() = serverSocket.localPort

    init {
        acceptJob = scope.launch {
            while (!serverSocket.isClosed) {
                val sock = try { serverSocket.accept() } catch (_: Exception) { return@launch }
                acceptedSocket.set(sock)
                scope.launch { readFrames(sock) }
            }
        }
    }

    suspend fun receiveFrame(): ByteArray = frames.receive()

    fun forceDisconnect() { acceptedSocket.get()?.close() }

    private fun readFrames(sock: Socket) {
        try {
            val input = DataInputStream(sock.getInputStream())
            while (!sock.isClosed) {
                val size = input.readInt()
                val buf = ByteArray(size)
                input.readFully(buf)
                frames.trySend(buf)
            }
        } catch (_: Exception) { /* socket closed */ }
    }

    override fun close() {
        scope.cancel()
        acceptedSocket.get()?.close()
        serverSocket.close()
        frames.close()
    }
}
