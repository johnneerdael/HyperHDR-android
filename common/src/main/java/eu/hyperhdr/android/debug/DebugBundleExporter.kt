package eu.hyperhdr.android.debug

import android.content.Context
import android.os.Environment
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugBundleExporter {

    /** Returns the absolute path of the written zip. */
    fun export(context: Context): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(downloads, "hyperhdr-debug-$ts.zip")

        val logcat = readLogcat()
        val profile = redactedProfileJson(context)

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("logcat.txt"))
            zip.write(logcat.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("profile-redacted.json"))
            zip.write(profile.toByteArray())
            zip.closeEntry()
        }
        return file.absolutePath
    }

    private fun readLogcat(): String {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1500", "HyperHdr.*:V", "*:S"))
        return BufferedReader(InputStreamReader(proc.inputStream)).readText()
    }

    private fun redactedProfileJson(context: Context): String {
        val store = ProfileStore(EncryptedProfileStorage.create(context))
        val p = store.load() ?: return JSONObject().put("profile", "none").toString()
        return JSONObject().apply {
            put("host", p.host)
            put("flatbufPort", p.flatbufPort)
            put("jsonPort", p.jsonPort)
            put("instanceId", p.instanceId)
            put("priority", p.priority)
            put("highQuality", p.highQuality)
            put("hdrAware", p.hdrAware)
            put("token", if (p.token != null) "(redacted, length=${p.token!!.length})" else null)
        }.toString(2)
    }
}
