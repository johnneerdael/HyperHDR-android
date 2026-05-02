package eu.hyperhdr.android.flatbuf

import com.google.flatbuffers.FlatBufferBuilder
import hyperhdrnet.Clear
import hyperhdrnet.Color as FbColor
import hyperhdrnet.Command
import hyperhdrnet.Image
import hyperhdrnet.ImageType
import hyperhdrnet.NV12Image
import hyperhdrnet.Register
import hyperhdrnet.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class HyperHdrFlatBufferClient(
    private val host: String,
    private val port: Int,
    private val priority: Int = 100,
    private val origin: String = "HyperHDR-Android",
    private val connectTimeoutMs: Int = 1_000,
    private val statsCollector: eu.hyperhdr.android.stats.LiveStatsCollector? = null,
) : FrameSink, Closeable {

    private sealed interface Outbound {
        data class Nv12(
            val yPlane: ByteArray, val uvPlane: ByteArray,
            val width: Int, val height: Int,
            val strideY: Int, val strideUv: Int,
        ) : Outbound

        data class Color(val rgb: Int, val durationMs: Int) : Outbound
        data class Clear(val priority: Int) : Outbound
        object Register : Outbound
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var output: DataOutputStream? = null

    private val outbox = Channel<Outbound>(Channel.CONFLATED)
    private var writerJob: Job? = null
    private var readerJob: Job? = null

    private fun startWriter() {
        writerJob = scope.launch {
            for (msg in outbox) writeOutbound(msg)
        }
    }

    private fun startReader(sock: java.net.Socket) {
        readerJob = scope.launch {
            try {
                val input = sock.getInputStream()
                val buf = ByteArray(1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) {
                        // Server closed the connection cleanly.
                        throw java.io.EOFException("server closed")
                    }
                    // Ignore the bytes — Reply frames aren't consumed by v1.
                }
            } catch (_: java.io.IOException) {
                _state.value = ConnectionState.ERROR
                try { sock.close() } catch (_: Exception) {}
                output = null
            }
        }
    }

    private fun writeOutbound(msg: Outbound) {
        val out = output ?: return
        val builder = FlatBufferBuilder(64 * 1024)
        val reqOff = when (msg) {
            is Outbound.Register -> {
                val originOff = builder.createString(origin)
                val regOff = hyperhdrnet.Register.createRegister(builder, originOff, priority)
                Request.createRequest(builder, Command.Register, regOff)
            }
            is Outbound.Nv12 -> {
                val yOff = NV12Image.createDataYVector(builder, msg.yPlane)
                val uvOff = NV12Image.createDataUvVector(builder, msg.uvPlane)
                val nv12Off = NV12Image.createNV12Image(
                    builder, yOff, uvOff,
                    msg.width, msg.height, msg.strideY, msg.strideUv,
                )
                val imgOff = Image.createImage(builder, ImageType.NV12Image, nv12Off, -1)
                Request.createRequest(builder, Command.Image, imgOff)
            }
            is Outbound.Color -> {
                val colorOff = FbColor.createColor(builder, msg.rgb, msg.durationMs)
                Request.createRequest(builder, Command.Color, colorOff)
            }
            is Outbound.Clear -> {
                val clearOff = Clear.createClear(builder, msg.priority)
                Request.createRequest(builder, Command.Clear, clearOff)
            }
        }
        Request.finishRequestBuffer(builder, reqOff)
        val payloadSize = builder.dataBuffer().remaining()
        writeFrame(out, builder.dataBuffer())
        if (msg is Outbound.Nv12) {
            statsCollector?.onFrame(payloadSize, msg.width, msg.height)
        }
    }

    suspend fun connect() {
        withContext(Dispatchers.IO) {
            _state.value = ConnectionState.CONNECTING
            try {
                val targetPort = port
                val sock = Socket().apply {
                    tcpNoDelay = true
                    sendBufferSize = 8192
                    receiveBufferSize = 4096
                    connect(InetSocketAddress(host, targetPort), connectTimeoutMs)
                }
                socket = sock
                val out = DataOutputStream(sock.getOutputStream())
                output = out
                // Reader coroutine: detects server-side close (EOF) or socket error.
                // We don't process Reply frames here; their only job is to be a "still alive" signal.
                startReader(sock)
                // Send Register synchronously so callers can rely on "client is registered" once connect() returns.
                writeOutbound(Outbound.Register)
                // THEN start the async writer for subsequent frames.
                startWriter()
                _state.value = ConnectionState.CONNECTED
            } catch (e: Throwable) {
                _state.value = ConnectionState.ERROR
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                output = null
                throw e
            }
        }
    }

    private fun writeFrame(out: DataOutputStream, buf: ByteBuffer) {
        try {
            val size = buf.remaining()
            out.writeInt(size)
            val payload = ByteArray(size)
            buf.get(payload)
            out.write(payload)
            out.flush()
        } catch (_: IOException) {
            _state.value = ConnectionState.ERROR
            // Close the socket so subsequent sends become no-ops until the wrapping reconnector resets us.
            try { socket?.close() } catch (_: Exception) {}
            output = null
        }
    }

    override fun sendNv12(
        yPlane: ByteArray, uvPlane: ByteArray,
        width: Int, height: Int, strideY: Int, strideUv: Int,
    ) {
        outbox.trySend(Outbound.Nv12(yPlane, uvPlane, width, height, strideY, strideUv))
    }

    fun clear(priority: Int = -1) {
        outbox.trySend(Outbound.Clear(priority))
    }

    fun setColor(rgb: Int, durationMs: Int = -1) {
        outbox.trySend(Outbound.Color(rgb, durationMs))
    }

    override fun close() {
        readerJob?.cancel()
        readerJob = null
        writerJob?.cancel()
        writerJob = null
        outbox.close()
        scope.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        _state.value = ConnectionState.DISCONNECTED
    }
}
