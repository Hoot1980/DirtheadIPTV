package app.dirthead.iptv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the last successfully loaded catalog (live / movies / series) under [Context.filesDir]
 * so cold starts can skip network until the user refreshes.
 */
internal class PlaylistDiskCache(private val context: Context) {

    data class Snapshot(
        val embeddedUrl: String,
        val playlistSourceUrl: String?,
        val epgCacheKey: String?,
        val expirationEpochSeconds: Long?,
        val accountInfoText: String?,
        /** Full get.php URL when the catalog came from Xtream APIs. */
        val xtreamGetPhpUrl: String?,
        /** Stalker / Ministra portal base URL when the catalog came from a portal + MAC pointer. */
        val stalkerPortalUrl: String? = null,
        val stalkerMac: String? = null,
        val live: List<PlaylistStream>,
        val movies: List<PlaylistStream>,
        val series: List<SeriesSummary>,
        /**
         * When non-null, live TV is loaded per-category from Xtream [player_api.php] (no full live list on disk).
         */
        val liveLazyCategories: List<Pair<String, String>>? = null,
    )

    private val dest: File
        get() = File(context.filesDir, FILE_NAME)

    fun read(expectedEmbeddedUrl: String): Snapshot? {
        val f = dest
        if (!f.exists() || !f.isFile || f.length() == 0L) return null
        val obj = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val ver = obj.optInt(JSON_VERSION, 0)
        if (ver != 1 && ver != FORMAT_VERSION) return null
        val embedded = obj.optString(JSON_EMBEDDED_URL, "")
        if (embedded != expectedEmbeddedUrl) return null
        val live = parseStreamArray(obj.optJSONArray(JSON_LIVE))
        val movies = parseStreamArray(obj.optJSONArray(JSON_MOVIES))
        val series = parseSeriesArray(obj.optJSONArray(JSON_SERIES))
        val liveLazy = if (ver >= 2) parseLiveLazyCategories(obj.optJSONArray(JSON_LIVE_LAZY)) else null
        if (live.isEmpty() && movies.isEmpty() && series.isEmpty() && liveLazy.isNullOrEmpty()) return null
        val xtreamUrl = obj.optString(JSON_XTREAM_URL, "").trim().takeIf { it.isNotEmpty() }
        val stalkerPortal = obj.optString(JSON_STALKER_PORTAL, "").trim().takeIf { it.isNotEmpty() }
        val stalkerMac = obj.optString(JSON_STALKER_MAC, "").trim().takeIf { it.isNotEmpty() }
        val expirationEpochSeconds =
            if (obj.has(JSON_EXPIRATION) && !obj.isNull(JSON_EXPIRATION)) obj.optLong(JSON_EXPIRATION) else null
        return Snapshot(
            embeddedUrl = embedded,
            playlistSourceUrl = obj.optString(JSON_PLAYLIST_SOURCE_URL, "").trim().takeIf { it.isNotEmpty() },
            epgCacheKey = obj.optString(JSON_EPG_KEY, "").trim().takeIf { it.isNotEmpty() },
            expirationEpochSeconds = expirationEpochSeconds,
            accountInfoText = obj.optString(JSON_ACCOUNT_INFO, "").trim().takeIf { it.isNotEmpty() },
            xtreamGetPhpUrl = xtreamUrl,
            stalkerPortalUrl = stalkerPortal,
            stalkerMac = stalkerMac,
            live = live,
            movies = movies,
            series = series,
            liveLazyCategories = liveLazy,
        )
    }

    fun write(snapshot: Snapshot) {
        if (snapshot.live.isEmpty() &&
            snapshot.movies.isEmpty() &&
            snapshot.series.isEmpty() &&
            snapshot.liveLazyCategories.isNullOrEmpty()
        ) {
            return
        }
        val o = JSONObject()
            .put(JSON_VERSION, FORMAT_VERSION)
            .put(JSON_EMBEDDED_URL, snapshot.embeddedUrl)
            .put(JSON_LIVE, streamsToJson(snapshot.live))
            .put(JSON_MOVIES, streamsToJson(snapshot.movies))
            .put(JSON_SERIES, seriesToJson(snapshot.series))
        if (!snapshot.liveLazyCategories.isNullOrEmpty()) {
            o.put(JSON_LIVE_LAZY, liveLazyCategoriesToJson(snapshot.liveLazyCategories))
        }
        snapshot.playlistSourceUrl?.let { o.put(JSON_PLAYLIST_SOURCE_URL, it) }
        snapshot.epgCacheKey?.let { o.put(JSON_EPG_KEY, it) }
        snapshot.expirationEpochSeconds?.let { o.put(JSON_EXPIRATION, it) }
        snapshot.accountInfoText?.let { o.put(JSON_ACCOUNT_INFO, it) }
        snapshot.xtreamGetPhpUrl?.let { o.put(JSON_XTREAM_URL, it) }
        snapshot.stalkerPortalUrl?.let { o.put(JSON_STALKER_PORTAL, it) }
        snapshot.stalkerMac?.let { o.put(JSON_STALKER_MAC, it) }
        val dir = context.filesDir
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(o.toString())
        val finalFile = dest
        if (finalFile.exists()) finalFile.delete()
        if (!tmp.renameTo(finalFile)) {
            tmp.copyTo(finalFile, overwrite = true)
            tmp.delete()
        }
    }

    fun clear() {
        val f = dest
        if (f.exists()) f.delete()
    }

    private fun streamsToJson(list: List<PlaylistStream>): JSONArray {
        val arr = JSONArray()
        for (s in list) {
            arr.put(streamToJson(s))
        }
        return arr
    }

    private fun streamToJson(s: PlaylistStream): JSONObject =
        JSONObject().apply {
            put("n", s.displayName)
            put("u", s.streamUrl)
            put("g", s.groupTitle)
            s.epgStreamId?.let { put("e", it) }
            s.tvgId?.let { put("t", it) }
            s.logoUrl?.let { put("l", it) }
            s.description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("desc", it) }
            if (s.tvArchive) put("tvA", true)
            s.tvArchiveDuration?.let { put("tvD", it) }
        }

    private fun liveLazyCategoriesToJson(list: List<Pair<String, String>>): JSONArray {
        val arr = JSONArray()
        for ((id, name) in list) {
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("n", name),
            )
        }
        return arr
    }

    private fun parseLiveLazyCategories(arr: JSONArray?): List<Pair<String, String>>? {
        if (arr == null || arr.length() == 0) return null
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            val n = o.optString("n", "").trim()
            if (id.isNotEmpty() && n.isNotEmpty()) out.add(id to n)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun parseStreamArray(arr: JSONArray?): List<PlaylistStream> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("u", "").trim()
                if (url.isEmpty()) continue
                add(
                    PlaylistStream(
                        displayName = o.optString("n", "").trim().ifEmpty { url },
                        streamUrl = url,
                        groupTitle = o.optString("g", PlaylistStream.DEFAULT_GROUP).trim()
                            .ifEmpty { PlaylistStream.DEFAULT_GROUP },
                        epgStreamId = o.optString("e", "").trim().takeIf { it.isNotEmpty() },
                        tvgId = o.optString("t", "").trim().takeIf { it.isNotEmpty() },
                        logoUrl = o.optString("l", "").trim().takeIf { it.isNotEmpty() },
                        description = o.optString("desc", "").trim().takeIf { it.isNotEmpty() },
                        tvArchive = o.optBoolean("tvA", false),
                        tvArchiveDuration = if (o.has("tvD") && !o.isNull("tvD")) {
                            o.optInt("tvD", 0).takeIf { it > 0 }
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }

    private fun seriesToJson(list: List<SeriesSummary>): JSONArray {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.seriesId)
                    .put("name", s.name)
                    .put("g", s.groupTitle)
                    .apply { s.coverUrl?.let { put("c", it) } },
            )
        }
        return arr
    }

    private fun parseSeriesArray(arr: JSONArray?): List<SeriesSummary> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").trim()
                if (id.isEmpty()) continue
                add(
                    SeriesSummary(
                        seriesId = id,
                        name = o.optString("name", "").trim().ifEmpty { id },
                        groupTitle = o.optString("g", PlaylistStream.DEFAULT_GROUP).trim()
                            .ifEmpty { PlaylistStream.DEFAULT_GROUP },
                        coverUrl = o.optString("c", "").trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }
    }

    companion object {
        private const val FILE_NAME = "playlist_catalog_cache_v1.json"
        private const val FORMAT_VERSION = 2
        private const val JSON_VERSION = "v"
        private const val JSON_EMBEDDED_URL = "embeddedUrl"
        private const val JSON_PLAYLIST_SOURCE_URL = "playlistSourceUrl"
        private const val JSON_EPG_KEY = "epgKey"
        private const val JSON_EXPIRATION = "expirationEpochSeconds"
        private const val JSON_ACCOUNT_INFO = "accountInfoText"
        private const val JSON_XTREAM_URL = "xtreamGetPhpUrl"
        private const val JSON_STALKER_PORTAL = "stalkerPortalUrl"
        private const val JSON_STALKER_MAC = "stalkerMac"
        private const val JSON_LIVE = "live"
        private const val JSON_MOVIES = "movies"
        private const val JSON_SERIES = "series"
        private const val JSON_LIVE_LAZY = "liveLazyCats"
    }
}
