package eu.hyperhdr.android.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Driver for the in-app updater flow.
 *
 * - Checks `canRequestPackageInstalls()`; if false, returns [State.NeedsPermission] and
 *   leaves it to the caller to launch the system-settings redirect.
 * - Downloads the APK to `cacheDir/updates/<filename>.apk` while emitting [State.Downloading]
 *   with a 0..100 percent value.
 * - Exposes the file via the configured FileProvider authority and dispatches
 *   `Intent.ACTION_VIEW` with `application/vnd.android.package-archive`, which lets the
 *   system PackageInstaller take over.
 *
 * The state flow is single-shot per [start] call; subsequent calls reset it.
 */
class UpdateInstaller(private val appContext: Context) {

    sealed interface State {
        data object Idle : State
        data object NeedsPermission : State
        data class Downloading(val percent: Int) : State
        data object Dispatched : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Convenience: builds the system-settings intent the caller can use to redirect. */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Returns true on API < 26 (no permission required) or when the user has granted
     * "Install unknown apps" for this package.
     */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()

    suspend fun start(downloadUrl: String, version: String) {
        _state.value = State.Idle
        if (!canInstall()) {
            _state.value = State.NeedsPermission
            return
        }
        val apk = runCatching { download(downloadUrl, version) }
            .onFailure {
                Log.w(TAG, "APK download failed", it)
                _state.value = State.Failed(it.message ?: "Download failed")
            }
            .getOrNull() ?: return
        runCatching { dispatchInstall(apk) }
            .onFailure {
                Log.w(TAG, "Install dispatch failed", it)
                _state.value = State.Failed(it.message ?: "Install failed")
            }
            .onSuccess { _state.value = State.Dispatched }
    }

    private suspend fun download(url: String, version: String): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        // Wipe stale APKs from prior attempts so the cache doesn't accumulate.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "hyperhdr-$version.apk")

        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Empty response body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastReportedPercent = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                _state.value = State.Downloading(percent)
                                lastReportedPercent = percent
                            }
                        }
                    }
                }
            }
        }
        target
    }

    private fun dispatchInstall(apk: File) {
        val authority = "${appContext.packageName}.updateprovider"
        val uri: Uri = FileProvider.getUriForFile(appContext, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(intent)
    }

    companion object { private const val TAG = "HyperHdr.Updater" }
}
