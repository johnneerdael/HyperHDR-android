package eu.hyperhdr.android.json

import eu.hyperhdr.android.flatbuf.BackoffSchedule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket-backed live-events client for HyperHDR JSON-RPC. Opens the socket on [start],
 * subscribes, exposes parsed events via [flow], and reconnects on disconnect using
 * [backoff]. Consumer just calls [start] once and [close] once; everything in between
 * (including server-side drops, NAT timeouts, transient Wi-Fi blips) is invisible.
 */
class HyperHdrJsonApiEvents(
    private val host: String,
    private val port: Int = 19444,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build(),
    private val backoff: BackoffSchedule = BackoffSchedule.default(),
) : Closeable {

    private val tanCounter = AtomicInteger(1)
    private val _flow = MutableSharedFlow<JsonEvent>(replay = 0, extraBufferCapacity = 16)
    val flow: SharedFlow<JsonEvent> = _flow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    @Volatile private var ws: WebSocket? = null

    fun start(token: String?) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (true) {
                val opened = openOnce(token)
                if (opened) backoff.reset() else delay(backoff.nextDelayMillis())
            }
        }
    }

    /**
     * Opens the socket and suspends until it closes (gracefully or by failure).
     * Returns true if the socket made it to onOpen successfully (so backoff resets);
     * false if it failed before opening (so backoff advances).
     */
    private suspend fun openOnce(token: String?): Boolean {
        val request = Request.Builder().url("ws://$host:$port/").build()
        val openedSignal = CompletableDeferred<Boolean>()
        val closedSignal = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedSignal.complete(true)
                webSocket.send(buildSubscribeRequest(token))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseEvent(text)?.let { _flow.tryEmit(it) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Acknowledge the server-initiated close so onClosed fires immediately rather than
                // after OkHttp's 60-second cancel-after-close timeout.
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closedSignal.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!openedSignal.isCompleted) openedSignal.complete(false)
                closedSignal.complete(Unit)
            }
        }
        ws = httpClient.newWebSocket(request, listener)
        val ok = openedSignal.await()
        closedSignal.await()
        ws = null
        return ok
    }

    private fun buildSubscribeRequest(token: String?): String {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "serverinfo")
            put("tan", tan)
            put("subscribe", JSONArray(listOf(
                "components-update",
                "instance-update",
                "videomode-update",
            )))
            if (token != null) put("token", token)
        }
        return req.toString()
    }

    private fun parseEvent(text: String): JsonEvent? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (obj.optString("command")) {
            "components-update" -> {
                val data = obj.optJSONObject("data") ?: return null
                JsonEvent.ComponentChanged(
                    name = data.optString("name"),
                    enabled = data.optBoolean("enabled", false),
                )
            }
            "instance-update" -> {
                val arr = obj.optJSONArray("data") ?: return null
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(Instance(
                            id = o.getInt("instance"),
                            name = o.optString("friendly_name", "instance ${o.getInt("instance")}"),
                            running = o.optBoolean("running", false),
                        ))
                    }
                }
                JsonEvent.InstanceChanged(list)
            }
            "videomode-update" -> {
                val hdr = obj.optJSONObject("data")?.optInt("HDR", 0) ?: 0
                JsonEvent.VideoModeChanged(hdr = hdr != 0)
            }
            "serverinfo" -> {
                val info = obj.optJSONObject("info") ?: return null
                val instArray = info.optJSONArray("instance")
                val instances = buildList {
                    if (instArray != null) for (i in 0 until instArray.length()) {
                        val o = instArray.getJSONObject(i)
                        add(Instance(
                            id = o.getInt("instance"),
                            name = o.optString("friendly_name", "instance ${o.getInt("instance")}"),
                            running = o.optBoolean("running", false),
                        ))
                    }
                }
                JsonEvent.ServerInfoSnapshot(ServerInfo(
                    version = info.optString("hyperhdr_version", ""),
                    instances = instances,
                    authRequired = false,
                ))
            }
            else -> null
        }
    }

    override fun close() {
        loopJob?.cancel()
        scope.cancel()
        ws?.close(1000, "client closing")
        ws = null
    }
}
