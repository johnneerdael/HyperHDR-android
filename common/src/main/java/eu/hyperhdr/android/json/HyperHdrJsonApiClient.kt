package eu.hyperhdr.android.json

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HyperHdrJsonApiClient(
    private val host: String,
    private val port: Int = 8090,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
) {
    private val tanCounter = AtomicInteger(1)
    private val jsonMedia = "application/json".toMediaType()

    @Volatile private var token: String? = null

    private fun url(): String = "http://$host:$port/json-rpc"

    suspend fun serverInfo(): ServerInfo = withContext(Dispatchers.IO) {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "serverinfo")
            put("tan", tan)
            token?.let { put("token", it) }
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            val err = body.optString("error", "unknown")
            return@withContext if (err.contains("No Authorization", ignoreCase = true)) {
                ServerInfo(version = "", instances = emptyList(), authRequired = true)
            } else {
                throw JsonApiError("serverinfo failed: $err", httpCode = 200)
            }
        }
        val info = body.getJSONObject("info")
        val version = info.optString("hyperhdr_version", "")
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
        ServerInfo(version, instances, authRequired = false)
    }

    suspend fun authorize(token: String) = withContext(Dispatchers.IO) {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "authorize")
            put("subcommand", "login")
            put("token", token)
            put("tan", tan)
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            throw JsonApiError("authorize failed: ${body.optString("error","unknown")}", httpCode = 200)
        }
        this@HyperHdrJsonApiClient.token = token
    }

    suspend fun switchInstance(id: Int) = withContext(Dispatchers.IO) {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "instance")
            put("subcommand", "switchTo")
            put("instance", id)
            put("tan", tan)
            token?.let { put("token", it) }
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            throw JsonApiError("switchTo failed: ${body.optString("error","unknown")}", httpCode = 200)
        }
    }

    suspend fun setHdrVideoMode(hdr: Boolean) = withContext(Dispatchers.IO) {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "videomode")
            put("HDR", if (hdr) 1 else 0)
            put("tan", tan)
            token?.let { put("token", it) }
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            throw JsonApiError("videomode failed: ${body.optString("error","unknown")}", httpCode = 200)
        }
    }

    private fun post(json: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url())
            .post(json.toString().toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw JsonApiError("HTTP ${resp.code}", resp.code)
            return JSONObject(raw)
        }
    }
}
