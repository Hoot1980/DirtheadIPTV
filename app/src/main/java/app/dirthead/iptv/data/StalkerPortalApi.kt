package app.dirthead.iptv.data

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Ministra / Stalker MAG-style portal — aligned with the Python reference flow:
 * [normalizeServerUrl] → cookies + device headers → handshake [portal.php] then [server/load.php] →
 * [portal.php] API with Bearer token ([get_all_channels] when available) → [player_api.php] fallback.
 */
internal object StalkerPortalApi {

    private const val TAG = "StalkerPortal"

    /** Matches Python defaults for handshake headers. */
    private const val DEFAULT_SERIAL: String = "069193N286603"
    private const val DEFAULT_STB: String = "MAG254"

    data class FullStalkerLoadResult(
        val liveStreams: List<PlaylistStream>,
        val movies: List<PlaylistStream>,
        val series: List<SeriesSummary>,
        val accountInfoText: String,
    )

    fun loadFullCatalog(client: OkHttpClient, portalBaseUrl: String, rawMac: String): FullStalkerLoadResult {
        val macCookie = normalizeMacForCookie(rawMac)
        val macDisplay = macCookie.uppercase()
        val apiBase = normalizePortalBaseForApi(portalBaseUrl)

        val (token, tokenSource) = getToken(client, apiBase, macDisplay, DEFAULT_SERIAL, DEFAULT_STB)
            ?: error("Stalker handshake failed — check portal URL and MAC (tried portal.php + load.php).")

        Log.i(TAG, "Stalker token OK (source=$tokenSource)")

        runCatching {
            portalPhpGet(
                client, apiBase, macDisplay, DEFAULT_SERIAL, DEFAULT_STB, token,
                "stb", "get_profile", emptyMap(),
            )
        }.onFailure { Log.d(TAG, "get_profile skipped: ${it.message}") }

        var live = loadLiveTv(client, apiBase, macDisplay, DEFAULT_SERIAL, DEFAULT_STB, token)
        if (live.isEmpty()) {
            live = runCatching {
                loadLiveViaPlayerApi(client, apiBase, macDisplay, token)
            }.getOrElse { emptyList() }
        }

        var movies = runCatching {
            loadVodMovies(client, apiBase, macDisplay, DEFAULT_SERIAL, DEFAULT_STB, token)
        }.onFailure { Log.w(TAG, "VOD load failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (movies.isEmpty()) {
            movies = runCatching { loadVodViaPlayerApi(client, apiBase, macDisplay, token) }
                .getOrElse { emptyList() }
        }

        return FullStalkerLoadResult(
            liveStreams = live,
            movies = movies,
            series = emptyList(),
            accountInfoText = buildAccountInfo(live.size, movies.size, tokenSource),
        )
    }

    private fun buildAccountInfo(live: Int, vod: Int, tokenSource: String): String = buildString {
        append("Stalker / Ministra · token: ")
        append(tokenSource)
        append(" · ")
        append(live)
        append(" channels")
        if (vod > 0) {
            append(" · ")
            append(vod)
            append(" movies")
        }
    }

    /**
     * Strip trailing `/c` or `/c/`, ensure scheme; keep host/path (unlike Python’s host-only normalize,
     * so `http://host/stalker_portal/c/` → `http://host/stalker_portal`).
     */
    private fun normalizePortalBaseForApi(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("/c/?$", RegexOption.IGNORE_CASE), "").trimEnd('/')
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "http://$s"
        }
        return s
    }

    private fun normalizeMacForCookie(raw: String): String {
        val hex = raw.trim().replace(":", "").replace("-", "").lowercase()
        require(hex.length == 12 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "MAC must be 12 hexadecimal digits (with optional : or - separators)"
        }
        return hex.chunked(2).joinToString(":")
    }

    private fun buildHeadersCommon(
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        bearerToken: String?,
    ): okhttp3.Headers.Builder {
        val referer = if (serverBase.endsWith("/")) "${serverBase}c/" else "$serverBase/c/"
        val ua =
            "Mozilla/5.0 (QtEmbedded; U; Linux; $stb; en) AppleWebKit/533.3 (KHTML, like Gecko) $stb Safari/533.3"
        return okhttp3.Headers.Builder()
            .add("User-Agent", ua)
            .add("X-User-Agent", "Model: $stb; Link: WiFi")
            .add("X-User-Device", stb)
            .add("X-User-Device-ID", mac)
            .add("X-Serial-Number", serial)
            .add("X-Device-Serial", serial)
            .add("X-Device-Id", serial)
            .add("X-Device-Id2", serial)
            .add("Referer", referer)
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.8")
            .add("X-Request-Type", "JSON")
            .add("Connection", "keep-alive")
            .apply {
                if (bearerToken != null) {
                    add("Authorization", "Bearer $bearerToken")
                    add("X-User-Authorization", "Bearer $bearerToken")
                }
            }
    }

    private fun stalkerCookies(mac: String): String =
        "mac=$mac; stb_lang=en; timezone=America/New_York"

    private fun getToken(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
    ): Pair<String, String>? {
        handshakePortalPhp(client, serverBase, mac, serial, stb)?.let { return it to "portal.php" }
        handshakeLoadPhp(client, serverBase, mac, serial, stb)?.let { return it.first to it.second }
        return null
    }

    /** `GET …/portal.php?action=handshake&type=stb&token=&JsHttpRequest=1-xml` + `Authorization: MAC mac` */
    private fun handshakePortalPhp(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
    ): String? {
        val url = "${serverBase}/portal.php".toHttpUrlOrNull() ?: return null
        val http = url.newBuilder()
            .addQueryParameter("action", "handshake")
            .addQueryParameter("type", "stb")
            .addQueryParameter("token", "")
            .addQueryParameter("JsHttpRequest", "1-xml")
            .build()
        val req = Request.Builder()
            .url(http)
            .headers(
                buildHeadersCommon(serverBase, mac, serial, stb, bearerToken = null)
                    .add("Authorization", "MAC $mac")
                    .add("Cookie", stalkerCookies(mac))
                    .build(),
            )
            .get()
            .build()
        val body = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return null
        return extractTokenFromJson(body)
    }

    private fun handshakeLoadPhp(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
    ): Pair<String, String>? {
        val params = mapOf(
            "type" to "stb",
            "action" to "handshake",
            "token" to "",
            "prehash" to "",
            "JsHttpRequest" to "1-xml",
        )
        val endpoints = listOf(
            "$serverBase/server/load.php",
            "$serverBase/stalker_portal/server/load.php",
        )
        for (ep in endpoints) {
            val url = ep.toHttpUrlOrNull() ?: continue
            val (_, body) = loadTryGetPost(client, serverBase, url, mac, serial, stb, params) ?: continue
            val token = extractTokenFromJson(body) ?: continue
            return token to ep.substringAfter(serverBase).trimStart('/')
        }
        return null
    }

    private fun loadTryGetPost(
        client: OkHttpClient,
        serverBase: String,
        loadPhp: HttpUrl,
        mac: String,
        serial: String,
        stb: String,
        params: Map<String, String>,
    ): Pair<String, String>? {
        val hb = buildHeadersCommon(serverBase, mac, serial, stb, null).add("Cookie", stalkerCookies(mac))
        for (preferPost in listOf(false, true)) {
            val order = if (preferPost) listOf("POST", "GET") else listOf("GET", "POST")
            for (method in order) {
                val text = runCatching {
                    if (method == "GET") {
                        val b = loadPhp.newBuilder()
                        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
                        client.newCall(
                            Request.Builder().url(b.build()).headers(hb.build()).get().build(),
                        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                    } else {
                        val form = params.entries.joinToString("&") { (k, v) ->
                            "${URLEncoder.encode(k, Charsets.UTF_8.name())}=${URLEncoder.encode(v, Charsets.UTF_8.name())}"
                        }.toRequestBody("application/x-www-form-urlencoded".toMediaType())
                        client.newCall(
                            Request.Builder().url(loadPhp).headers(hb.build()).post(form).build(),
                        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                    }
                }.getOrNull()
                if (!text.isNullOrBlank() && text.trimStart().startsWith("{")) {
                    return method to text
                }
            }
        }
        return null
    }

    private fun extractTokenFromJson(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        root.optJSONObject("js")?.optString("token", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        root.optString("token", "").trim().takeIf { it.isNotEmpty() }?.let { return it }
        root.optJSONObject("data")?.optString("token", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        root.optJSONObject("js")?.optJSONObject("data")?.optString("token", "")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return null
    }

    private fun portalPhpGet(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
        type: String,
        action: String,
        extraQuery: Map<String, String>,
    ): String? {
        val base = "${serverBase}/portal.php".toHttpUrlOrNull() ?: return null
        val b = base.newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("action", action)
            .addQueryParameter("JsHttpRequest", "1-xml")
        extraQuery.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val req = Request.Builder()
            .url(b.build())
            .headers(buildHeadersCommon(serverBase, mac, serial, stb, token).add("Cookie", stalkerCookies(mac)).build())
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    private fun portalJsData(json: String): JSONArray? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        when (val js = root.opt("js")) {
            is JSONArray -> return js
            is JSONObject -> {
                when (val data = js.opt("data")) {
                    is JSONArray -> return data
                    is JSONObject -> {
                        data.optJSONArray("data")?.let { return it }
                        data.optJSONArray("channels")?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun loadLiveTv(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
    ): List<PlaylistStream> {
        val allCh = runCatching {
            portalPhpGet(client, serverBase, mac, serial, stb, token, "itv", "get_all_channels", emptyMap())
        }.getOrNull()
        val arr = allCh?.let { portalJsData(it) }
        if (arr != null && arr.length() > 0) {
            val genres = loadGenreMap(client, serverBase, mac, serial, stb, token)
            val out = LinkedHashMap<String, PlaylistStream>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val gid = o.optString("tv_genre_id", "")
                    .ifEmpty { o.optString("genre_id", "") }
                    .ifEmpty { o.optString("category_id", "") }
                    .ifEmpty { o.optString("cat_id", "") }
                val groupTitle = genres[gid] ?: PlaylistStream.DEFAULT_GROUP
                channelToStream(client, serverBase, mac, serial, stb, token, o, groupTitle)?.let { s ->
                    out[s.streamUrl] = s
                }
            }
            if (out.isNotEmpty()) return out.values.toList()
        }
        return loadLiveOrderedListFallback(client, serverBase, mac, serial, stb, token)
    }

    private fun loadGenreMap(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
    ): Map<String, String> {
        var json = portalPhpGet(client, serverBase, mac, serial, stb, token, "itv", "get_genres", emptyMap())
        if (json.isNullOrBlank()) {
            json = portalPhpGet(client, serverBase, mac, serial, stb, token, "itv", "get_categories", emptyMap())
        }
        val arr = json?.let { portalJsData(it) } ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").ifEmpty { o.optString("category_id", "") }.ifEmpty { o.optString("genre_id", "") }.trim()
            if (id.isEmpty()) continue
            val title = o.optString("title", "")
                .ifEmpty { o.optString("name", "") }
                .ifEmpty { o.optString("category_name", "") }
                .trim()
                .ifEmpty { id }
            map[id] = title
        }
        return map
    }

    private fun loadLiveOrderedListFallback(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
    ): List<PlaylistStream> {
        val genres = parseIdTitleListFromPortal(
            client, serverBase, mac, serial, stb, token, "itv", "get_genres",
        ).ifEmpty {
            parseIdTitleListFromPortal(client, serverBase, mac, serial, stb, token, "itv", "get_categories")
        }
        val out = LinkedHashMap<String, PlaylistStream>()
        if (genres.isNotEmpty()) {
            for (g in genres) {
                var page = 1
                while (page <= 80) {
                    val json = portalPhpGet(
                        client, serverBase, mac, serial, stb, token, "itv", "get_ordered_list",
                        mapOf("genre" to g.id, "p" to page.toString(), "sortby" to "number"),
                    ) ?: break
                    val dataArr = portalJsData(json) ?: break
                    if (dataArr.length() == 0) break
                    for (i in 0 until dataArr.length()) {
                        val o = dataArr.optJSONObject(i) ?: continue
                        channelToStream(client, serverBase, mac, serial, stb, token, o, g.title)?.let { s ->
                            out[s.streamUrl] = s
                        }
                    }
                    page++
                }
            }
        } else {
            var page = 1
            while (page <= 80) {
                val json = portalPhpGet(
                    client, serverBase, mac, serial, stb, token, "itv", "get_ordered_list",
                    mapOf("p" to page.toString(), "sortby" to "number"),
                ) ?: break
                val dataArr = portalJsData(json) ?: break
                if (dataArr.length() == 0) break
                for (i in 0 until dataArr.length()) {
                    val o = dataArr.optJSONObject(i) ?: continue
                    channelToStream(client, serverBase, mac, serial, stb, token, o, PlaylistStream.DEFAULT_GROUP)?.let { s ->
                        out[s.streamUrl] = s
                    }
                }
                page++
            }
        }
        return out.values.toList()
    }

    private data class IdTitle(val id: String, val title: String)

    private fun parseIdTitleListFromPortal(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
        type: String,
        action: String,
    ): List<IdTitle> {
        val json = portalPhpGet(client, serverBase, mac, serial, stb, token, type, action, emptyMap())
            ?: return emptyList()
        val arr = portalJsData(json) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                    .ifEmpty { o.optString("category_id", "") }
                    .ifEmpty { o.optString("genre_id", "") }
                    .trim()
                if (id.isEmpty()) continue
                val title = o.optString("title", "")
                    .ifEmpty { o.optString("category_name", "") }
                    .ifEmpty { o.optString("name", "") }
                    .trim()
                    .ifEmpty { "Group $id" }
                add(IdTitle(id, title))
            }
        }
    }

    /** `…/player_api.php?username=MAC&password=token&action=…` (Python-style fallback; path relative to portal base). */
    private fun loadLiveViaPlayerApi(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        token: String,
    ): List<PlaylistStream> {
        val url = playerApiUrl(serverBase, mac, token, "get_live_streams") ?: return emptyList()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; DirtheadIPTV)")
            .get()
            .build()
        val body = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            r.body?.string()
        } ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<PlaylistStream>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            val sid = o.optString("stream_id", "").ifEmpty { o.optString("id", "") }.trim()
            val direct = o.optString("direct_source", "").trim()
            val urlStr = when {
                direct.startsWith("http") -> direct
                else -> {
                    val u = o.optString("url", "").trim()
                    if (u.startsWith("http")) u else null
                }
            } ?: continue
            out.add(
                PlaylistStream(
                    displayName = name.ifEmpty { sid.ifEmpty { "Live" } },
                    streamUrl = urlStr,
                    groupTitle = o.optString("category_name", PlaylistStream.DEFAULT_GROUP).ifEmpty { PlaylistStream.DEFAULT_GROUP },
                    epgStreamId = sid.takeIf { it.isNotEmpty() },
                    tvgId = o.optString("epg_channel_id", "").ifEmpty { sid }.takeIf { it.isNotEmpty() },
                    logoUrl = o.optString("stream_icon", "").takeIf { it.isNotEmpty() },
                    tvArchive = o.optString("tv_archive", "0") == "1",
                    tvArchiveDuration = o.optInt("tv_archive_duration", 0).takeIf { it > 0 },
                ),
            )
        }
        return out
    }

    private fun loadVodViaPlayerApi(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        token: String,
    ): List<PlaylistStream> {
        val url = playerApiUrl(serverBase, mac, token, "get_vod_streams") ?: return emptyList()
        val body = client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        } ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val streamId = o.optString("stream_id", "").ifEmpty { o.optString("id", "") }
                val name = o.optString("name", "").trim().ifEmpty { streamId }
                val cat = o.optString("category_name", "").ifEmpty { PlaylistStream.DEFAULT_GROUP }
                val icon = o.optString("stream_icon", "").takeIf { it.isNotEmpty() }
                val direct = o.optString("direct_source", "").trim()
                if (!direct.startsWith("http://", ignoreCase = true) &&
                    !direct.startsWith("https://", ignoreCase = true)
                ) {
                    continue
                }
                add(
                    PlaylistStream(
                        displayName = name,
                        streamUrl = direct,
                        groupTitle = cat.ifEmpty { PlaylistStream.DEFAULT_GROUP },
                        epgStreamId = streamId.takeIf { it.isNotEmpty() },
                        logoUrl = icon,
                        description = sequenceOf(
                            o.optString("description", ""),
                            o.optString("plot", ""),
                            o.optString("info", ""),
                        ).map { it.trim() }.firstOrNull { it.isNotEmpty() },
                    ),
                )
            }
        }
    }

    private fun playerApiUrl(serverBase: String, mac: String, token: String, action: String): HttpUrl? {
        val root = serverBase.trimEnd('/')
        return "$root/player_api.php".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("username", mac)
            ?.addQueryParameter("password", token)
            ?.addQueryParameter("action", action)
            ?.build()
    }

    private fun loadVodMovies(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
    ): List<PlaylistStream> {
        var categories = parseIdTitleListFromPortal(client, serverBase, mac, serial, stb, token, "vod", "get_categories")
        if (categories.isEmpty()) {
            categories = parseIdTitleListFromPortal(client, serverBase, mac, serial, stb, token, "vod", "get_genres")
        }
        val out = LinkedHashMap<String, PlaylistStream>()
        for (c in categories) {
            var page = 1
            while (page <= 60) {
                val json = runCatching {
                    portalPhpGet(
                        client, serverBase, mac, serial, stb, token, "vod", "get_ordered_list",
                        mapOf("category" to c.id, "p" to page.toString(), "sortby" to "added"),
                    )
                }.getOrNull() ?: portalPhpGet(
                    client, serverBase, mac, serial, stb, token, "vod", "get_ordered_list",
                    mapOf("genre" to c.id, "p" to page.toString(), "sortby" to "added"),
                )
                if (json.isNullOrBlank()) break
                val dataArr = portalJsData(json) ?: break
                if (dataArr.length() == 0) break
                for (i in 0 until dataArr.length()) {
                    val o = dataArr.optJSONObject(i) ?: continue
                    vodToMovie(client, serverBase, mac, serial, stb, token, o, c.title)?.let { s ->
                        out[s.streamUrl] = s
                    }
                }
                page++
            }
        }
        return out.values.toList()
    }

    private fun channelToStream(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
        o: JSONObject,
        groupTitle: String,
    ): PlaylistStream? {
        val name = o.optString("name", "").ifEmpty { o.optString("title", "") }.trim()
        val cmd = o.optString("cmd", "").trim()
        if (name.isEmpty() && cmd.isEmpty()) return null
        val logo = o.optString("logo", "").ifEmpty { o.optString("icon", "") }.trim().takeIf { it.isNotEmpty() }
        val id = o.optString("id", "").ifEmpty { o.optString("cmd_id", "") }.trim()
        val url = resolvePlayableUrl(client, serverBase, mac, serial, stb, token, cmd, "itv") ?: return null
        val archive = o.optInt("tv_archive", 0) == 1 || o.optBoolean("tv_archive", false)
        val archiveDur = if (o.has("tv_archive_duration") && !o.isNull("tv_archive_duration")) {
            o.optInt("tv_archive_duration", 0).takeIf { it > 0 }
        } else {
            null
        }
        return PlaylistStream(
            displayName = name.ifEmpty { "TV" },
            streamUrl = url,
            groupTitle = groupTitle.ifEmpty { PlaylistStream.DEFAULT_GROUP },
            epgStreamId = id.takeIf { it.isNotEmpty() },
            tvgId = id.takeIf { it.isNotEmpty() },
            logoUrl = logo,
            tvArchive = archive,
            tvArchiveDuration = archiveDur,
        )
    }

    private fun vodToMovie(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
        o: JSONObject,
        groupTitle: String,
    ): PlaylistStream? {
        val name = o.optString("name", "").ifEmpty { o.optString("title", "") }.trim()
        val cmd = o.optString("cmd", "").trim()
        if (cmd.isEmpty()) return null
        val logo = o.optString("logo", "").ifEmpty { o.optString("screenshot_uri", "") }.trim().takeIf { it.isNotEmpty() }
        val id = o.optString("id", "").trim()
        val url = resolvePlayableUrl(client, serverBase, mac, serial, stb, token, cmd, "vod") ?: return null
        return PlaylistStream(
            displayName = name.ifEmpty { "Movie" },
            streamUrl = url,
            groupTitle = groupTitle.ifEmpty { PlaylistStream.DEFAULT_GROUP },
            epgStreamId = id.takeIf { it.isNotEmpty() },
            tvgId = null,
            logoUrl = logo,
            description = sequenceOf(
                o.optString("description", ""),
                o.optString("plot", ""),
                o.optString("info", ""),
                o.optString("storyline", ""),
            ).map { it.trim() }.firstOrNull { it.isNotEmpty() },
        )
    }

    private fun resolvePlayableUrl(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
        cmd: String,
        linkType: String,
    ): String? {
        extractHttpFromCmd(cmd)?.let { return it }
        if (cmd.isBlank()) return null
        return createLink(client, serverBase, mac, serial, stb, token, cmd, linkType)
    }

    private fun createLink(
        client: OkHttpClient,
        serverBase: String,
        mac: String,
        serial: String,
        stb: String,
        token: String,
        cmd: String,
        linkType: String,
    ): String? {
        val loadUrl = "${serverBase}/server/load.php".toHttpUrlOrNull() ?: return null
        val b = loadUrl.newBuilder()
            .addQueryParameter("type", linkType)
            .addQueryParameter("action", "create_link")
            .addQueryParameter("cmd", cmd)
            .addQueryParameter("token", token)
            .addQueryParameter("JsHttpRequest", "1-xml")
        val req = Request.Builder()
            .url(b.build())
            .headers(buildHeadersCommon(serverBase, mac, serial, stb, null).add("Cookie", stalkerCookies(mac)).build())
            .get()
            .build()
        val body = runCatching {
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return null
        val js = runCatching { JSONObject(body).optJSONObject("js") }.getOrNull() ?: return null
        js.optString("url", "").trim().takeIf { it.startsWith("http") }?.let { return it }
        js.optString("link", "").trim().takeIf { it.startsWith("http") }?.let { return it }
        extractHttpFromCmd(js.optString("cmd", ""))?.let { return it }
        val portalTry = portalPhpGet(
            client, serverBase, mac, serial, stb, token, linkType, "create_link",
            mapOf("cmd" to cmd),
        )
        portalTry?.let {
            runCatching { JSONObject(it).optJSONObject("js") }?.getOrNull()?.optString("url", "")?.trim()
                ?.takeIf { u -> u.startsWith("http") }
        }?.let { return it }
        return null
    }

    private fun extractHttpFromCmd(cmd: String): String? {
        val t = cmd.trim()
        if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
            return t.split('|').firstOrNull()?.trim()?.takeIf { u ->
                u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
            }
        }
        val re = Regex(
            "(?:ffmpeg|auto|manual|vlc|useragent)\\s*[:-]?\\s*(https?://\\S+)",
            RegexOption.IGNORE_CASE,
        )
        return re.find(t)?.groupValues?.getOrNull(1)?.trim()
    }
}
