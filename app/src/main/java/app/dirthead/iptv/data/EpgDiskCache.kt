package app.dirthead.iptv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Persists merged XMLTV EPG and Xtream short-EPG entries under [Context.filesDir] for faster cold starts.
 * Entries older than [MAX_AGE_MS] are ignored so the next playlist load refetches from the network.
 */
internal class EpgDiskCache(private val context: Context) {

    data class Bundle(
        val xmlTvByChannelId: Map<String, List<EpgProgram>>,
        val shortEpgByStreamId: Map<String, List<EpgProgram>>,
    )

    private val root: File
        get() = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    fun computeKey(
        embeddedUrl: String,
        playlistSourceUrl: String?,
        xtreamCredentials: XtreamCredentials?,
        m3uText: String?,
        stalkerPortalUrl: String? = null,
        stalkerMac: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("v2\n")
        sb.append(embeddedUrl).append('\n')
        sb.append(playlistSourceUrl ?: "").append('\n')
        if (xtreamCredentials != null) {
            sb.append(xtreamCredentials.originUrl.toString()).append('\n')
            sb.append(xtreamCredentials.username).append('\n')
            runCatching { xtreamCredentials.xmlTvUrl().toString() }
                .getOrNull()
                ?.let { sb.append(it).append('\n') }
        }
        stalkerPortalUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append("stalker\n").append(it).append('\n')
            sb.append(stalkerMac?.trim().orEmpty()).append('\n')
        }
        m3uText?.let { txt ->
            M3uParser.extractUrlTvg(txt)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                sb.append(it).append('\n')
            }
        }
        return sha256Hex(sb.toString())
    }

    fun loadBundleIfFresh(key: String, forceRefresh: Boolean): Bundle? {
        if (forceRefresh) return null
        val dir = File(root, key)
        val metaFile = File(dir, FILE_META)
        val xmlFile = File(dir, FILE_XMLTV)
        if (!metaFile.exists() || !xmlFile.exists()) return null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return null
        val savedAt = meta.optLong(JSON_UPDATED_AT, 0L)
        if (savedAt <= 0L) return null
        if (System.currentTimeMillis() - savedAt > MAX_AGE_MS) return null
        val xmlTv = runCatching { parseXmlTvJson(JSONObject(xmlFile.readText())) }.getOrNull()
            ?: return null
        val shortFile = File(dir, FILE_SHORT)
        val short = if (shortFile.exists()) {
            runCatching { parseShortRoot(JSONObject(shortFile.readText())) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        return Bundle(xmlTv, short)
    }

    /**
     * Loads XMLTV + short EPG files for [key] if present, ignoring [MAX_AGE_MS].
     * Used when restoring the catalog from [PlaylistDiskCache] so EPG matches the saved playlist.
     */
    fun loadBundleIgnoringMaxAge(key: String): Bundle? {
        val dir = File(root, key)
        val metaFile = File(dir, FILE_META)
        val xmlFile = File(dir, FILE_XMLTV)
        if (!metaFile.exists() || !xmlFile.exists()) return null
        val xmlTv = runCatching { parseXmlTvJson(JSONObject(xmlFile.readText())) }.getOrNull()
            ?: return null
        val shortFile = File(dir, FILE_SHORT)
        val short = if (shortFile.exists()) {
            runCatching { parseShortRoot(JSONObject(shortFile.readText())) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        return Bundle(xmlTv, short)
    }

    fun saveBundle(
        key: String,
        xmlTvByChannelId: Map<String, List<EpgProgram>>,
        shortEpgByStreamId: Map<String, List<EpgProgram>>,
    ) {
        synchronized(this) {
            root.mkdirs()
            root.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name != key) {
                    child.deleteRecursively()
                }
            }
            val dir = File(root, key).apply { mkdirs() }
            File(dir, FILE_XMLTV).writeText(xmlTvToJson(xmlTvByChannelId).toString())
            File(dir, FILE_SHORT).writeText(shortToJson(shortEpgByStreamId).toString())
            File(dir, FILE_META).writeText(
                JSONObject()
                    .put(JSON_UPDATED_AT, System.currentTimeMillis())
                    .put(JSON_KEY, key)
                    .toString(),
            )
        }
    }

    fun loadShortPrograms(key: String, streamId: String): List<EpgProgram>? {
        val f = File(File(root, key), FILE_SHORT)
        if (!f.exists()) return null
        val rootObj = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val arr = rootObj.optJSONArray(streamId) ?: return null
        return parseProgramsArray(arr)
    }

    fun clearAll() {
        synchronized(this) {
            val dir = File(context.filesDir, CACHE_DIR_NAME)
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    fun appendShortEpg(key: String, streamId: String, programs: List<EpgProgram>) {
        synchronized(this) {
            val dir = File(root, key)
            if (!dir.exists()) return
            val shortFile = File(dir, FILE_SHORT)
            val short = if (shortFile.exists()) {
                runCatching { parseShortRoot(JSONObject(shortFile.readText())) }
                    .getOrDefault(emptyMap())
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
            short[streamId] = programs
            shortFile.writeText(shortToJson(short).toString())
        }
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private fun xmlTvToJson(map: Map<String, List<EpgProgram>>): JSONObject {
        val o = JSONObject()
        for ((channelId, programs) in map) {
            o.put(channelId, programsToJsonArray(programs))
        }
        return o
    }

    private fun shortToJson(map: Map<String, List<EpgProgram>>): JSONObject {
        val o = JSONObject()
        for ((streamId, programs) in map) {
            o.put(streamId, programsToJsonArray(programs))
        }
        return o
    }

    private fun programsToJsonArray(programs: List<EpgProgram>): JSONArray {
        val arr = JSONArray()
        for (p in programs) {
            arr.put(
                JSONObject().apply {
                    put(JSON_TITLE, p.title)
                    put(JSON_START, p.startMillis)
                    put(JSON_END, p.endMillis)
                    p.description?.trim()?.takeIf { it.isNotEmpty() }?.let { put(JSON_DESC, it) }
                },
            )
        }
        return arr
    }

    private fun parseXmlTvJson(o: JSONObject): Map<String, List<EpgProgram>> {
        val names = o.names() ?: return emptyMap()
        val out = LinkedHashMap<String, List<EpgProgram>>()
        for (i in 0 until names.length()) {
            val id = names.getString(i)
            val arr = o.optJSONArray(id) ?: continue
            out[id] = parseProgramsArray(arr)
        }
        return out
    }

    private fun parseShortRoot(o: JSONObject): Map<String, List<EpgProgram>> {
        val names = o.names() ?: return emptyMap()
        val out = LinkedHashMap<String, List<EpgProgram>>()
        for (i in 0 until names.length()) {
            val id = names.getString(i)
            val arr = o.optJSONArray(id) ?: continue
            out[id] = parseProgramsArray(arr)
        }
        return out
    }

    private fun parseProgramsArray(arr: JSONArray): List<EpgProgram> {
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val title = decodeEpgTextIfBase64(p.optString(JSON_TITLE, "").trim()).trim()
                if (title.isEmpty()) continue
                val start = p.optLong(JSON_START, -1L)
                val end = p.optLong(JSON_END, -1L)
                if (start < 0L || end < 0L) continue
                val descRaw = p.optString(JSON_DESC, "").trim().takeIf { it.isNotEmpty() }
                val desc = descRaw?.let { decodeEpgTextIfBase64(it).trim() }?.takeIf { it.isNotEmpty() }
                add(EpgProgram(title = title, startMillis = start, endMillis = end, description = desc))
            }
        }
    }

    companion object {
        private const val CACHE_DIR_NAME = "epg_disk_cache_v1"
        private const val FILE_META = "meta.json"
        private const val FILE_XMLTV = "xmltv.json"
        private const val FILE_SHORT = "short_epg.json"
        private const val JSON_UPDATED_AT = "updatedAt"
        private const val JSON_KEY = "key"
        private const val JSON_TITLE = "t"
        private const val JSON_START = "s"
        private const val JSON_END = "e"
        private const val JSON_DESC = "d"

        /** How long cached EPG is considered valid before refetching on playlist load. */
        const val MAX_AGE_MS: Long = 8L * 60L * 60L * 1000L
    }
}
