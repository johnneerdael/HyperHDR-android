package eu.hyperhdr.android.tv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AvailableUpdate(val tag: String, val downloadUrl: String?)

class UpdateChecker(
    private val owner: String = "johnneerdael",
    private val repo: String = "HyperHDR-android",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build(),
) {
    suspend fun latest(currentVersion: String): AvailableUpdate? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = runCatching {
            httpClient.newCall(req).execute().use { it.body?.string() }
        }.getOrNull() ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val tag = json.optString("tag_name").removePrefix("v")
        if (tag.isEmpty() || tag == currentVersion) return@withContext null
        val assets = json.optJSONArray("assets")
        val apkUrl = (0 until (assets?.length() ?: 0)).asSequence()
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?.optString("browser_download_url")
        AvailableUpdate(tag = tag, downloadUrl = apkUrl)
    }
}
