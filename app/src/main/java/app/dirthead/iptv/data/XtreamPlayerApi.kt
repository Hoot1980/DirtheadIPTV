package app.dirthead.iptv.data

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.defaultPort
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Loads live, VOD (movies), and series metadata via Xtream Codes-style [player_api.php].
 */
internal object XtreamPlayerApi {

    private const val LOG_TAG: String = "DirtheadPlaylist"

    data class FullXtreamLoadResult(
        val liveStreams: List<PlaylistStream>,
        val movies: List<PlaylistStream>,
        val series: List<SeriesSummary>,
        val expirationEpochSeconds: Long?,
        val accountInfoText: String,
    )

    fun loadFullXtreamCatalog(client: OkHttpClient, creds: XtreamCredentials): FullXtreamLoadResult {
        val accountBody = apiGet(client, creds, emptyMap())
        val root = parseAndValidatePlayerApiAccountBody(accountBody)
        val userInfo = root.getJSONObject("user_info")
        val serverInfo = root.getJSONObject("server_info")
        Log.i(LOG_TAG, "Xtream player_api: validated JSON account response (user_info.auth=1)")
        val expiration = parseExpDate(userInfo)
        val baseUrl = resolveStreamBaseUrl(creds, serverInfo)

        val liveCategories = runCatching {
            parseCategoryMap(apiGet(client, creds, mapOf("action" to "get_live_categories")))
        }.getOrDefault(emptyMap())
        val liveStreams = loadLiveStreams(client, creds, liveCategories, baseUrl)

        val vodCategories = runCatching {
            parseCategoryMap(apiGet(client, creds, mapOf("action" to "get_vod_categories")))
        }.getOrDefault(emptyMap())
        val movies = loadVodStreams(client, creds, vodCategories, baseUrl)

        val seriesCategories = runCatching {
            parseCategoryMap(apiGet(client, creds, mapOf("action" to "get_series_categories")))
        }.getOrDefault(emptyMap())
        val series = loadSeriesSummaries(client, creds, seriesCategories)

        val catchupAny = liveStreams.any { it.tvArchive }
        Log.d(
            LOG_TAG,
            "Xtream features: livePathExtension=${creds.liveStreamPathExtension()}, catchupAnyChannel=$catchupAny",
        )

        val accountInfoText = buildAccountInfoText(
            userInfo = userInfo,
            serverInfo = serverInfo,
            liveCategoryCount = liveCategories.size,
            liveStreamCount = liveStreams.size,
            vodCategoryCount = vodCategories.size,
            vodCount = movies.size,
            seriesCategoryCount = seriesCategories.size,
            seriesCount = series.size,
        )

        return FullXtreamLoadResult(
            liveStreams = liveStreams,
            movies = movies,
            series = series,
            expirationEpochSeconds = expiration,
            accountInfoText = accountInfoText,
        )
    }

    private fun parseAndValidatePlayerApiAccountBody(body: String): JSONObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            error("player_api empty body")
        }
        if (trimmed.startsWith("<")) {
            error("player_api response is HTML, not JSON")
        }
        val root =
            try {
                JSONObject(trimmed)
            } catch (e: JSONException) {
                error("player_api response is not valid JSON: ${e.message}")
            }
        val userInfo = root.optJSONObject("user_info")
            ?: error("player_api JSON missing user_info")
        val serverInfo = root.optJSONObject("server_info")
            ?: error("player_api JSON missing server_info")
        if (!isUserAuthOne(userInfo)) {
            error("user_info.auth must be 1 (got ${userInfo.opt("auth")})")
        }
        return root
    }

    private fun isUserAuthOne(userInfo: JSONObject): Boolean {
        if (!userInfo.has("auth") || userInfo.isNull("auth")) return false
        return when (val v = userInfo.get("auth")) {
            is Number -> v.toLong() == 1L
            is String -> v.trim() == "1"
            else -> false
        }
    }

    fun loadSeriesEpisodes(client: OkHttpClient, creds: XtreamCredentials, seriesId: String): List<PlaylistStream> {
        val body = apiGet(
            client,
            creds,
            mapOf(
                "action" to "get_series_info",
                "series_id" to seriesId,
            ),
        )
        return parseSeriesEpisodes(JSONObject(body), creds, seriesId)
    }

    private fun apiGet(
        client: OkHttpClient,
        creds: XtreamCredentials,
        params: Map<String, String>,
    ): String {
        val builder = creds.playerApiBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val url = builder.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VLC/3.0.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("player_api HTTP ${response.code} for ${params["action"] ?: "account"}")
            }
            return response.body?.string() ?: error("Empty player_api body")
        }
    }

    private fun resolveStreamBaseUrl(creds: XtreamCredentials, serverInfo: JSONObject?): String {
        val origin = creds.originUrl
        val fromServer = serverInfo?.optString("url")?.trim().orEmpty()
        if (fromServer.isNotEmpty()) {
            return mergeServerInfoUrlWithPlaylistOrigin(fromServer, origin)
        }
        return baseHttpUrlFromOrigin(origin)
    }

    private fun baseHttpUrlFromOrigin(origin: HttpUrl): String {
        val scheme = origin.scheme
        val schemeDefault = defaultPort(scheme)
        val port = if (origin.port != -1) origin.port else schemeDefault
        val b = HttpUrl.Builder().scheme(scheme).host(origin.host)
        if (port != schemeDefault) b.port(port)
        return b.build().toString().trimEnd('/')
    }

    /**
     * [server_info.url] is often `host` or `host:port` with no scheme. Prepending `http://` forces
     * port 80; panels loaded via `https://…/get.php` usually serve streams on **443** → ECONNREFUSED on 80.
     * Use the same scheme (and port when omitted) as the playlist [origin].
     */
    private fun mergeServerInfoUrlWithPlaylistOrigin(serverField: String, origin: HttpUrl): String {
        val raw = serverField.trim().trimEnd('/').removePrefix("//")
        if (raw.isEmpty()) return baseHttpUrlFromOrigin(origin)
        val lower = raw.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val parsed = raw.toHttpUrlOrNull() ?: return raw
            if (parsed.scheme == "http" && origin.scheme == "https" &&
                parsed.port == defaultPort("http")
            ) {
                return upgradeHttpBaseToHttps(parsed.host, origin)
            }
            return raw
        }
        val (host, explicitPort) = parseHostAndOptionalPort(raw)
        if (host.isEmpty()) return baseHttpUrlFromOrigin(origin)
        val scheme = origin.scheme
        val schemeDefault = defaultPort(scheme)
        val originPort = if (origin.port != -1) origin.port else schemeDefault
        val finalPort = explicitPort ?: originPort
        val b = HttpUrl.Builder().scheme(scheme).host(host)
        if (finalPort != schemeDefault) b.port(finalPort)
        return b.build().toString().trimEnd('/')
    }

    private fun upgradeHttpBaseToHttps(host: String, origin: HttpUrl): String {
        val httpsDefault = defaultPort("https")
        val originPort = if (origin.port != -1) origin.port else httpsDefault
        val b = HttpUrl.Builder().scheme("https").host(host)
        if (originPort != httpsDefault) b.port(originPort)
        return b.build().toString().trimEnd('/')
    }

    /** Hostname or `host:port` (not a full URL). Supports bracketed IPv6 `[::1]:8080`. */
    private fun parseHostAndOptionalPort(hostPortField: String): Pair<String, Int?> {
        val t = hostPortField.trim().removePrefix("//").substringBefore('/').substringBefore('?')
        if (t.isEmpty()) return "" to null
        if (t.startsWith('[')) {
            val end = t.indexOf(']')
            if (end != -1) {
                val host = t.substring(0, end + 1)
                val rest = t.substring(end + 1)
                if (rest.startsWith(':')) {
                    val p = rest.removePrefix(":").toIntOrNull()
                    if (p != null && p in 1..65535) return host to p
                }
                return host to null
            }
        }
        val colon = t.lastIndexOf(':')
        if (colon <= 0 || colon >= t.length - 1) return t to null
        val portStr = t.substring(colon + 1)
        if (portStr.any { !it.isDigit() }) return t to null
        val portNum = portStr.toIntOrNull() ?: return t to null
        if (portNum !in 1..65535) return t to null
        return t.substring(0, colon) to portNum
    }

    /**
     * [direct_source] may be absolute http(s), protocol-relative `//…`, site-root `/live/…`, or full URL without scheme.
     */
    private fun resolveXtreamStreamUrl(direct: String, baseUrl: String, pathFromRoot: String): String {
        val d = direct.trim()
        val base = baseUrl.trim().trimEnd('/')
        if (d.isEmpty()) {
            return base + pathFromRoot
        }
        val lower = d.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return d
        if (d.startsWith("//")) {
            val sch = base.toHttpUrlOrNull()?.scheme ?: "https"
            return "$sch:$d"
        }
        if (d.contains("://")) return d
        if (d.startsWith("/")) return "$base$d"
        return "$base/$d"
    }

    private fun parseExpDate(userInfo: JSONObject?): Long? {
        if (userInfo == null || !userInfo.has("exp_date")) return null
        return when (val raw = userInfo.get("exp_date")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun parseCategoryMap(body: String): Map<String, String> {
        val trimmed = body.trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("categories")
                    ?: obj.optJSONArray("data")
                    ?: return emptyMap()
            }
        }
        val map = linkedMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("category_id", c.optString("id", "")).trim()
            val name = c.optString("category_name", c.optString("name", "")).trim()
            if (id.isNotEmpty() && name.isNotEmpty()) {
                map[id] = name
            }
        }
        return map
    }

    private fun parseStreamsArray(body: String): JSONArray? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            else -> {
                val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
                val names = obj.names() ?: return null
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    when (val v = obj.opt(key)) {
                        is JSONArray -> return v
                        else -> Unit
                    }
                }
                null
            }
        }
    }

    private fun loadLiveStreams(
        client: OkHttpClient,
        creds: XtreamCredentials,
        categoryNames: Map<String, String>,
        baseUrl: String,
    ): List<PlaylistStream> {
        val allBody = runCatching {
            apiGet(client, creds, mapOf("action" to "get_live_streams"))
        }.getOrNull()
        val combined = allBody?.let { parseStreamsArray(it) }?.let { arr ->
            liveStreamsFromArray(arr, categoryNames, creds, baseUrl)
        }.orEmpty()
        return if (combined.isNotEmpty()) {
            combined
        } else {
            fetchLiveStreamsPerCategory(client, creds, categoryNames, baseUrl)
        }
    }

    private fun liveStreamsFromArray(
        arr: JSONArray,
        categoryNames: Map<String, String>,
        creds: XtreamCredentials,
        baseUrl: String,
    ): List<PlaylistStream> {
        val out = ArrayList<PlaylistStream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val stream = toLiveStream(o, categoryNames, creds, baseUrl) ?: continue
            out.add(stream)
        }
        return out
    }

    private fun fetchLiveStreamsPerCategory(
        client: OkHttpClient,
        creds: XtreamCredentials,
        categoryNames: Map<String, String>,
        baseUrl: String,
    ): List<PlaylistStream> {
        val out = mutableListOf<PlaylistStream>()
        for ((categoryId, _) in categoryNames) {
            val body = runCatching {
                apiGet(
                    client,
                    creds,
                    mapOf(
                        "action" to "get_live_streams",
                        "category_id" to categoryId,
                    ),
                )
            }.getOrNull() ?: continue
            val arr = parseStreamsArray(body) ?: continue
            out.addAll(liveStreamsFromArray(arr, categoryNames, creds, baseUrl))
        }
        return out
    }

    private fun optTvArchiveDuration(o: JSONObject): Int? {
        if (!o.has("tv_archive_duration")) return null
        return when (val v = o.get("tv_archive_duration")) {
            is Number -> v.toInt().takeIf { it > 0 }
            is String -> v.trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }

    private fun toLiveStream(
        o: JSONObject,
        categoryNames: Map<String, String>,
        creds: XtreamCredentials,
        baseUrl: String,
    ): PlaylistStream? {
        val name = o.optString("name", "").trim().ifEmpty { return null }
        val streamId = o.optGenericId("stream_id") ?: return null
        val categoryId = o.optString("category_id", "").trim()
        val groupTitle = when {
            categoryId.isEmpty() -> PlaylistStream.DEFAULT_GROUP
            else -> categoryNames[categoryId] ?: categoryId
        }
        val direct = o.optString("direct_source", "").trim()
        val u = creds.username
        val p = creds.password
        val ext = creds.liveStreamPathExtension()
        val url = resolveXtreamStreamUrl(direct, baseUrl, "/live/$u/$p/$streamId.$ext")
        val tvgId = sequenceOf(
            o.optString("epg_channel_id", "").trim(),
            o.optString("tvg_id", "").trim(),
            o.optString("tvg-id", "").trim(),
        ).firstOrNull { it.isNotEmpty() } ?: streamId
        val tvArchive = o.optInt("tv_archive", 0) == 1 ||
            o.optString("tv_archive", "").trim() == "1"
        return PlaylistStream(
            displayName = name,
            streamUrl = url,
            groupTitle = groupTitle,
            epgStreamId = streamId,
            tvgId = tvgId,
            logoUrl = o.optXtreamImageUrl(),
            tvArchive = tvArchive,
            tvArchiveDuration = optTvArchiveDuration(o),
        )
    }

    /**
     * EPG grid for catch-up / archive (`player_api.php?action=get_simple_data_table&stream_id=…`).
     */
    fun getSimpleDataTable(
        client: OkHttpClient,
        creds: XtreamCredentials,
        streamId: String,
    ): List<EpgProgram> {
        val body = runCatching {
            apiGet(
                client,
                creds,
                mapOf(
                    "action" to "get_simple_data_table",
                    "stream_id" to streamId,
                ),
            )
        }.getOrNull() ?: return emptyList()
        return parseSimpleDataTableListings(body)
    }

    /** Base URL for `/live/…` and `/timeshift/…` (uses `server_info` when present). */
    fun resolveStreamBaseUrlForPlayback(client: OkHttpClient, creds: XtreamCredentials): String {
        val body = runCatching { apiGet(client, creds, emptyMap()) }.getOrNull()
            ?: return baseHttpUrlFromOrigin(creds.originUrl)
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return baseHttpUrlFromOrigin(creds.originUrl)
        val serverInfo = root.optJSONObject("server_info")
        return resolveStreamBaseUrl(creds, serverInfo)
    }

    /**
     * Xtream-style timeshift URL:
     * `{base}/timeshift/{username}/{password}/{durationMinutes}/{yyyy-MM-dd:HH-mm}/{streamId}.ts`
     */
    fun buildTimeshiftUrl(
        baseUrl: String,
        username: String,
        password: String,
        durationMinutes: Int,
        startFormatted: String,
        streamId: String,
        streamExtension: String = "ts",
    ): String {
        val b = baseUrl.trim().trimEnd('/')
        val dur = durationMinutes.coerceAtLeast(1)
        val ext = streamExtension.trim().removePrefix(".").ifEmpty { "ts" }
        return "$b/timeshift/$username/$password/$dur/$startFormatted/$streamId.$ext"
    }

    fun getShortEpg(
        client: OkHttpClient,
        creds: XtreamCredentials,
        streamId: String,
        /** Xtream API listing count for short EPG. */
        limit: Int = 16,
    ): List<EpgProgram> {
        val lim = limit.coerceIn(1, 200)
        val body = runCatching {
            apiGet(
                client,
                creds,
                mapOf(
                    "action" to "get_short_epg",
                    "stream_id" to streamId,
                    "limit" to lim.toString(),
                ),
            )
        }.getOrNull() ?: return emptyList()
        return parseShortEpgListings(body)
    }

    private fun parseShortEpgListings(body: String): List<EpgProgram> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("epg_listings")
            ?: root.optJSONArray("listings")
            ?: root.optJSONArray("data")
            ?: return emptyList()
        return parseEpgListingObjects(arr)
    }

    private fun parseSimpleDataTableListings(body: String): List<EpgProgram> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            return parseEpgListingObjects(arr)
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("epg_listings")
            ?: root.optJSONArray("listings")
            ?: root.optJSONArray("data")
            ?: return emptyList()
        return parseEpgListingObjects(arr)
    }

    private fun parseEpgListingObjects(arr: JSONArray): List<EpgProgram> {
        val out = mutableListOf<EpgProgram>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val titleRaw = o.optString("title", "").trim()
                .ifEmpty { o.optString("name", "").trim() }
                .ifEmpty { continue }
            val title = decodeEpgTextIfBase64(titleRaw).trim()
                .ifEmpty { continue }
            val start = parseEpgTime(
                o,
                keys = listOf("start_timestamp", "start", "start_time", "time", "start_ts"),
            ) ?: continue
            val end = parseEpgTime(
                o,
                keys = listOf("stop_timestamp", "end_timestamp", "end", "end_time", "stop", "stop_ts"),
            ) ?: (start + 3600_000L)
            val description = sequenceOf(
                o.optString("description", ""),
                o.optString("desc", ""),
                o.optString("plot", ""),
                o.optString("overview", ""),
            ).map { decodeEpgTextIfBase64(it.trim()).trim() }.firstOrNull { it.isNotEmpty() }
            out.add(
                EpgProgram(
                    title = title,
                    startMillis = start,
                    endMillis = end,
                    description = description,
                ),
            )
        }
        return out.sortedBy { it.startMillis }
    }

    private fun parseEpgTime(o: JSONObject, keys: List<String>): Long? {
        for (key in keys) {
            if (!o.has(key)) continue
            when (val v = o.get(key)) {
                is Number -> {
                    val n = v.toLong()
                    return if (n < 10_000_000_000L) n * 1000L else n
                }
                is String -> {
                    val t = v.trim()
                    if (t.isEmpty()) continue
                    t.toLongOrNull()?.let { n ->
                        return if (n < 10_000_000_000L) n * 1000L else n
                    }
                    for (pattern in listOf(
                        "yyyy-MM-dd HH:mm:ss",
                        "yyyy-MM-dd'T'HH:mm:ss",
                        "yyyy-MM-dd:HH-mm",
                        "yyyy-MM-dd:HH:mm",
                        "dd/MM/yyyy HH:mm:ss",
                    )) {
                        val parsed = runCatching {
                            val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                            sdf.timeZone = java.util.TimeZone.getDefault()
                            sdf.parse(t)?.time
                        }.getOrNull()
                        if (parsed != null) return parsed
                    }
                }
            }
        }
        return null
    }

    private fun loadVodStreams(
        client: OkHttpClient,
        creds: XtreamCredentials,
        categoryNames: Map<String, String>,
        baseUrl: String,
    ): List<PlaylistStream> {
        val allBody = runCatching {
            apiGet(client, creds, mapOf("action" to "get_vod_streams"))
        }.getOrNull()
        val combined = allBody?.let { parseStreamsArray(it) }?.let { arr ->
            vodStreamsFromArray(arr, categoryNames, creds, baseUrl)
        }.orEmpty()
        return if (combined.isNotEmpty()) {
            combined
        } else {
            fetchVodStreamsPerCategory(client, creds, categoryNames, baseUrl)
        }
    }

    private fun vodStreamsFromArray(
        arr: JSONArray,
        categoryNames: Map<String, String>,
        creds: XtreamCredentials,
        baseUrl: String,
    ): List<PlaylistStream> {
        val out = ArrayList<PlaylistStream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val stream = toVodStream(o, categoryNames, creds, baseUrl) ?: continue
            out.add(stream)
        }
        return out
    }

    private fun fetchVodStreamsPerCategory(
        client: OkHttpClient,
        creds: XtreamCredentials,
        categoryNames: Map<String, String>,
        baseUrl: String,
    ): List<PlaylistStream> {
        val out = mutableListOf<PlaylistStream>()
        for ((categoryId, _) in categoryNames) {
            val body = runCatching {
                apiGet(
                    client,
                    creds,
                    mapOf(
                        "action" to "get_vod_streams",
                        "category_id" to categoryId,
                    ),
                )
            }.getOrNull() ?: continue
            val arr = parseStreamsArray(body) ?: continue
            out.addAll(vodStreamsFromArray(arr, categoryNames, creds, baseUrl))
        }
        return out
    }

    private fun vodDescriptionFromJsonObject(o: JSONObject): String? =
        sequenceOf(
            o.optString("description", ""),
            o.optString("desc", ""),
            o.optString("plot", ""),
            o.optString("overview", ""),
            o.optString("plot_outline", ""),
        ).map { decodeEpgTextIfBase64(it.trim()).trim() }.firstOrNull { it.isNotEmpty() }

    private fun vodStreamDescription(o: JSONObject): String? {
        vodDescriptionFromJsonObject(o)?.let { return it }
        val info = o.optJSONObject("info") ?: return null
        return vodDescriptionFromJsonObject(info)
    }

    private fun toVodStream(
        o: JSONObject,
        categoryNames: Map<String, String>,
        creds: XtreamCredentials,
        baseUrl: String,
    ): PlaylistStream? {
        val name = o.optString("name", "").trim().ifEmpty { return null }
        val streamId = o.optGenericId("stream_id") ?: return null
        val categoryId = o.optString("category_id", "").trim()
        val groupTitle = when {
            categoryId.isEmpty() -> PlaylistStream.DEFAULT_GROUP
            else -> categoryNames[categoryId] ?: categoryId
        }
        val ext = o.optString("container_extension", "mp4")
            .trim()
            .removePrefix(".")
            .ifEmpty { "mp4" }
        val direct = o.optString("direct_source", "").trim()
        val u = creds.username
        val p = creds.password
        val url = resolveXtreamStreamUrl(direct, baseUrl, "/movie/$u/$p/$streamId.$ext")
        return PlaylistStream(
            displayName = name,
            streamUrl = url,
            groupTitle = groupTitle,
            logoUrl = o.optXtreamImageUrl(),
            description = vodStreamDescription(o),
        )
    }

    private fun loadSeriesSummaries(
        client: OkHttpClient,
        creds: XtreamCredentials,
        categoryNames: Map<String, String>,
    ): List<SeriesSummary> {
        val allBody = runCatching {
            apiGet(client, creds, mapOf("action" to "get_series"))
        }.getOrNull()
        val combined = allBody?.let { parseStreamsArray(it) }?.let { arr ->
            seriesSummariesFromArray(arr, categoryNames)
        }.orEmpty()
        return if (combined.isNotEmpty()) {
            combined
        } else {
            fetchSeriesPerCategory(client, creds, categoryNames)
        }
    }

    private fun seriesSummariesFromArray(arr: JSONArray, categoryNames: Map<String, String>): List<SeriesSummary> {
        val out = ArrayList<SeriesSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val summary = toSeriesSummary(o, categoryNames) ?: continue
            out.add(summary)
        }
        return out
    }

    private fun fetchSeriesPerCategory(
        client: OkHttpClient,
        creds: XtreamCredentials,
        categoryNames: Map<String, String>,
    ): List<SeriesSummary> {
        val out = mutableListOf<SeriesSummary>()
        for ((categoryId, _) in categoryNames) {
            val body = runCatching {
                apiGet(
                    client,
                    creds,
                    mapOf(
                        "action" to "get_series",
                        "category_id" to categoryId,
                    ),
                )
            }.getOrNull() ?: continue
            val arr = parseStreamsArray(body) ?: continue
            out.addAll(seriesSummariesFromArray(arr, categoryNames))
        }
        return out
    }

    private fun toSeriesSummary(o: JSONObject, categoryNames: Map<String, String>): SeriesSummary? {
        val seriesId = o.optGenericId("series_id")
            ?: o.optGenericId("id")
            ?: return null
        val name = o.optString("name", "").trim()
            .ifEmpty { o.optString("title", "").trim() }
            .ifEmpty { return null }
        val categoryId = o.optString("category_id", "").trim()
        val groupTitle = when {
            categoryId.isEmpty() -> PlaylistStream.DEFAULT_GROUP
            else -> categoryNames[categoryId] ?: categoryId
        }
        return SeriesSummary(
            seriesId = seriesId,
            name = name,
            groupTitle = groupTitle,
            coverUrl = o.optXtreamImageUrl(),
        )
    }

    private fun parseSeriesEpisodes(root: JSONObject, creds: XtreamCredentials, seriesId: String): List<PlaylistStream> {
        val baseUrl = resolveStreamBaseUrl(creds, root.optJSONObject("server_info"))
        val episodesNode = root.optJSONObject("episodes") ?: return emptyList()
        val seasonNames = episodesNode.names() ?: return emptyList()
        val u = creds.username
        val p = creds.password
        val out = mutableListOf<PlaylistStream>()
        for (s in 0 until seasonNames.length()) {
            val seasonKey = seasonNames.getString(s)
            val arr = episodesNode.optJSONArray(seasonKey) ?: continue
            for (e in 0 until arr.length()) {
                val ep = arr.optJSONObject(e) ?: continue
                val epId = ep.optGenericId("id") ?: continue
                val epNum = ep.optString("episode_num", "${e + 1}").trim()
                val title = ep.optString("title", "").trim()
                    .ifEmpty { ep.optString("name", "").trim() }
                    .ifEmpty { "Episode $epNum" }
                val displayName = "S$seasonKey E$epNum · $title"
                val ext = ep.optString("container_extension", "mp4")
                    .trim()
                    .removePrefix(".")
                    .ifEmpty { "mp4" }
                val direct = ep.optString("direct_source", "").trim()
                val url = resolveXtreamStreamUrl(direct, baseUrl, "/series/$u/$p/$epId.$ext")
                out.add(
                    PlaylistStream(
                        displayName = displayName,
                        streamUrl = url,
                        groupTitle = seriesId,
                        logoUrl = ep.optXtreamImageUrl(),
                    ),
                )
            }
        }
        return out
    }

    /** Poster / channel icon fields commonly returned by Xtream-style APIs. */
    private fun JSONObject.optXtreamImageUrl(): String? {
        val keys = listOf("stream_icon", "movie_image", "cover", "cover_big", "icon", "backdrop_path")
        for (k in keys) {
            val s = optString(k, "").trim()
            if (s.isNotEmpty()) return s
        }
        val info = optJSONObject("info") ?: return null
        for (k in listOf("movie_image", "cover", "stream_icon", "cover_big", "icon", "backdrop_path")) {
            val s = info.optString(k, "").trim()
            if (s.isNotEmpty()) return s
        }
        return null
    }

    private fun JSONObject.optGenericId(key: String): String? {
        if (!has(key)) return null
        val v = get(key)
        if (v == null || v === JSONObject.NULL) return null
        return when (v) {
            is Number -> v.toInt().toString()
            is String -> v.trim().ifEmpty { null } ?: v.trim()
            else -> v.toString().trim().ifEmpty { null }
        }
    }

    private fun buildAccountInfoText(
        userInfo: JSONObject?,
        serverInfo: JSONObject?,
        liveCategoryCount: Int,
        liveStreamCount: Int,
        vodCategoryCount: Int,
        vodCount: Int,
        seriesCategoryCount: Int,
        seriesCount: Int,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("— Account (player_api) —")
        if (userInfo != null) {
            sb.appendLine("user_info:")
            sb.appendLine(prettyJsonObject(userInfo))
        } else {
            sb.appendLine("user_info: (missing)")
        }
        sb.appendLine()
        if (serverInfo != null) {
            sb.appendLine("server_info:")
            sb.appendLine(prettyJsonObject(serverInfo))
        } else {
            sb.appendLine("server_info: (missing)")
        }
        sb.appendLine()
        sb.appendLine("Live categories: $liveCategoryCount | streams: $liveStreamCount")
        sb.appendLine("VOD categories: $vodCategoryCount | movies: $vodCount")
        sb.appendLine("Series categories: $seriesCategoryCount | series: $seriesCount")
        return sb.toString().trimEnd()
    }

    private fun prettyJsonObject(obj: JSONObject): String {
        val names = obj.names() ?: return "{}"
        val lines = mutableListOf<String>()
        for (i in 0 until names.length()) {
            val key = names.getString(i)
            val v = obj.get(key)
            val valueStr = when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> v.toString()
            }
            lines.add("  $key: $valueStr")
        }
        return lines.joinToString("\n")
    }
}
