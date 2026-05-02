package eu.hyperhdr.android.json

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * subscribes to the server-side update streams we care about, and exposes parsed events via
 * [flow] as a [SharedFlow]. The HTTP one-shot client ([HyperHdrJsonApiClient]) is unaffected
 * — that one stays the default for register-time calls; this class is only opened when a UI
 * surface (settings page, main screen) wants live state.
 *
 * Reconnect on disconnect is added in Task 7. v0.3.0 baseline opens once and stops on close.
 */
class HyperHdrJsonApiEvents(
    private val host: String,
    private val port: Int = 19444,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        // No read timeout: WebSocket is long-lived.
        .readTimeout(0, TimeUnit.SECONDS)
        .build(),
) : Closeable {

    private val tanCounter = AtomicInteger(1)
    private val _flow = MutableSharedFlow<JsonEvent>(replay = 0, extraBufferCapacity = 16)
    val flow: SharedFlow<JsonEvent> = _flow.asSharedFlow()

    private var ws: WebSocket? = null

    fun start(token: String?) {
        val request = Request.Builder()
            .url("ws://$host:$port/")
            .build()
        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSubscribeRequest(token))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseEvent(text)?.let { _flow.tryEmit(it) }
            }
            // No reconnect; consumer decides.
        })
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
        ws?.close(1000, "client closing")
        ws = null
    }
}
