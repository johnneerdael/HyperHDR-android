package eu.hyperhdr.android.settings

import org.json.JSONObject

interface ProfileStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class ProfileStore(private val storage: ProfileStorage) {
    fun load(): ServerProfile? {
        val raw = storage.read(KEY) ?: return null
        val o = JSONObject(raw)
        return ServerProfile(
            host = o.getString("host"),
            flatbufPort = o.optInt("flatbufPort", 19400),
            jsonPort = o.optInt("jsonPort", 19444),
            instanceId = o.optInt("instanceId", 0),
            priority = o.optInt("priority", 100),
            token = if (o.has("token")) o.getString("token") else null,
            highQuality = o.optBoolean("highQuality", false),
        )
    }

    fun save(p: ServerProfile) {
        val o = JSONObject().apply {
            put("host", p.host)
            put("flatbufPort", p.flatbufPort)
            put("jsonPort", p.jsonPort)
            put("instanceId", p.instanceId)
            put("priority", p.priority)
            if (p.token != null) put("token", p.token)
            put("highQuality", p.highQuality)
        }
        storage.write(KEY, o.toString())
    }

    fun clear() = storage.write(KEY, null)

    companion object { private const val KEY = "hyperhdr_profile" }
}
