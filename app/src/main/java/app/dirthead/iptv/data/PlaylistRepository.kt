package app.dirthead.iptv.data

import android.content.Context
import android.util.Log
import app.dirthead.iptv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.text.Charsets
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PlaylistRepository(
    appContext: Context,
    private val client: OkHttpClient = defaultClient(),
) {
    private val appContext = appContext.applicationContext
    private val recentChannelsStore = RecentChannelsStore(appContext)
    private val favoritesStore = FavoritesStore(appContext)
    private val epgDiskCache = EpgDiskCache(appContext)
    private val playlistDiskCache = PlaylistDiskCache(appContext)
    private val hiddenLiveTvGroupsStore = HiddenLiveTvGroupsStore(appContext)
    private val playlistLoadPrefs =
        appContext.getSharedPreferences(PREFS_PLAYLIST_LOAD, Context.MODE_PRIVATE)
    private val playlistPointerPrefs =
        appContext.getSharedPreferences(PREFS_PLAYLIST_POINTER, Context.MODE_PRIVATE)
    private val playlistLoadHistoryPrefs =
        appContext.getSharedPreferences(PREFS_PLAYLIST_LOAD_HISTORY, Context.MODE_PRIVATE)

    /** Cache key = lowercase display name; value null means lookup returned no plot. */
    private val omdbPlotCache = ConcurrentHashMap<String, String?>()

    /** Pointer URL whose catalog is currently held in memory (null = none / cleared). */
    @Volatile
    private var catalogLoadedForPointerUrl: String? = null

    private var cached: List<PlaylistStream>? = null
    private var cachedMovies: List<PlaylistStream> = emptyList()
    private var cachedSeries: List<SeriesSummary> = emptyList()
    private var cachedExpirationEpochSeconds: Long? = null
    private var cachedAccountInfoText: String? = null
    private var xtreamCredentials: XtreamCredentials? = null
    /** Xtream live TV: categories only in memory; channels fetched per category via [loadXtreamLiveStreamsForGroup]. */
    @Volatile
    private var xtreamLiveLazy: Boolean = false

    private var xtreamLiveCategoriesById: Map<String, String> = emptyMap()
    private var xtreamStreamBaseUrlResolved: String = ""
    private val xtreamCategoryStreamsCache = mutableMapOf<String, List<PlaylistStream>>()
    private val xtreamLazyCatchupStreams = mutableListOf<PlaylistStream>()
    /** When set, the catalog was loaded from a Stalker / Ministra portal (pointer file: URL + MAC). */
    private var cachedStalkerPortalUrl: String? = null
    private var cachedStalkerMac: String? = null
    private val shortEpgCache = ConcurrentHashMap<String, List<EpgProgram>>()
    /** XMLTV `<programme channel="...">` lists keyed by channel id (matches [PlaylistStream.tvgId]). */
    private var xmlTvByChannelId: Map<String, List<EpgProgram>> = emptyMap()

    /** Matches on-disk EPG bundle for persisting short EPG rows. */
    @Volatile
    private var activeEpgCacheKey: String? = null

    private val epgMergeJobSupervisor = SupervisorJob()
    private val epgMergeScope = CoroutineScope(epgMergeJobSupervisor + Dispatchers.IO)
    private var epgBackgroundJob: Job? = null

    private val _epgGuideRevision = MutableStateFlow(0L)
    /** Bumps when XMLTV / merged EPG is applied so the live TV guide can reload listings. */
    val epgGuideRevision: StateFlow<Long> = _epgGuideRevision.asStateFlow()

    private fun bumpEpgGuideRevision() {
        _epgGuideRevision.value = _epgGuideRevision.value + 1L
    }

    /** Full account/server summary from [player_api.php] when the playlist is Xtream-style; empty if M3U-only. */
    fun playlistAccountInfoText(): String = cachedAccountInfoText.orEmpty()

    /** Persisted choice from [fetchPlaylistCatalog] / load screen; each value is a pointer file URL. */
    fun selectedPlaylistPointerUrlOrNull(): String? =
        playlistPointerPrefs.getString(KEY_SELECTED_PLAYLIST_POINTER_URL, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setSelectedPlaylistPointerUrl(url: String) {
        val trimmed = url.trim()
        require(trimmed.isNotEmpty()) { "Playlist URL must not be empty." }
        val prev = selectedPlaylistPointerUrlOrNull()
        // commit() so the IO load coroutine always sees the same URL as the UI (apply() can lag on some devices).
        playlistPointerPrefs.edit().putString(KEY_SELECTED_PLAYLIST_POINTER_URL, trimmed).commit()
        if (prev != trimmed) {
            clearInMemoryCatalogOnly()
        }
    }

    /** Load screen: pointer URLs that completed a successful [loadVerifiedPlaylist]. */
    fun playlistPointerPreviouslyLoadedOk(pointerUrl: String): Boolean {
        val u = pointerUrl.trim()
        if (u.isEmpty()) return false
        return playlistLoadHistoryPrefs.getStringSet(KEY_LOAD_OK_POINTERS, emptySet())?.contains(u) == true
    }

    /** Load screen: pointer URLs that failed or timed out on last attempt. */
    fun playlistPointerPreviouslyLoadFailed(pointerUrl: String): Boolean {
        val u = pointerUrl.trim()
        if (u.isEmpty()) return false
        return playlistLoadHistoryPrefs.getStringSet(KEY_LOAD_BAD_POINTERS, emptySet())?.contains(u) == true
    }

    fun recordPlaylistPointerLoadSucceeded(pointerUrl: String) {
        val u = pointerUrl.trim()
        if (u.isEmpty()) return
        val ok = mutableStringSetFromPrefs(KEY_LOAD_OK_POINTERS)
        ok.add(u)
        val bad = mutableStringSetFromPrefs(KEY_LOAD_BAD_POINTERS)
        bad.remove(u)
        playlistLoadHistoryPrefs.edit()
            .putStringSet(KEY_LOAD_OK_POINTERS, ok)
            .putStringSet(KEY_LOAD_BAD_POINTERS, bad)
            .apply()
    }

    fun recordPlaylistPointerLoadFailed(pointerUrl: String) {
        val u = pointerUrl.trim()
        if (u.isEmpty()) return
        val bad = mutableStringSetFromPrefs(KEY_LOAD_BAD_POINTERS)
        bad.add(u)
        playlistLoadHistoryPrefs.edit().putStringSet(KEY_LOAD_BAD_POINTERS, bad).apply()
    }

    private fun mutableStringSetFromPrefs(key: String): MutableSet<String> =
        HashSet(playlistLoadHistoryPrefs.getStringSet(key, emptySet()) ?: emptySet())

    /** Fetches and parses [PlaylistConfig.PlaylistListCatalogUrl] (`list.txt`). */
    suspend fun fetchPlaylistCatalog(): Result<List<PlaylistCatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetchBody(PlaylistConfig.PlaylistListCatalogUrl)
            PlaylistCatalogParser.parse(body).also { list ->
                if (list.isEmpty()) {
                    error("Playlist list is empty or has no valid http(s) lines.")
                }
            }
        }
    }

    private fun clearInMemoryCatalogOnly() {
        cached = null
        cachedMovies = emptyList()
        cachedSeries = emptyList()
        cachedExpirationEpochSeconds = null
        cachedAccountInfoText = null
        xtreamCredentials = null
        xtreamLiveLazy = false
        xtreamLiveCategoriesById = emptyMap()
        xtreamStreamBaseUrlResolved = ""
        synchronized(xtreamCategoryStreamsCache) {
            xtreamCategoryStreamsCache.clear()
        }
        xtreamLazyCatchupStreams.clear()
        cachedStalkerPortalUrl = null
        cachedStalkerMac = null
        shortEpgCache.clear()
        xmlTvByChannelId = emptyMap()
        activeEpgCacheKey = null
        catalogLoadedForPointerUrl = null
        cancelPendingEpgMerge()
        bumpEpgGuideRevision()
    }

    private fun ensureMemoryMatchesPointer(pointerUrl: String) {
        val loaded = catalogLoadedForPointerUrl
        if (loaded != null && loaded != pointerUrl) {
            clearInMemoryCatalogOnly()
        }
    }

    private fun hasAnyCatalogContent(): Boolean {
        val live = cached?.size ?: 0
        return live > 0 ||
            cachedMovies.isNotEmpty() ||
            cachedSeries.isNotEmpty() ||
            (xtreamLiveLazy && xtreamLiveCategoriesById.isNotEmpty())
    }

    fun isXtreamLiveLazyMode(): Boolean = xtreamLiveLazy

    /**
     * Fetches live channels for one Xtream category (or returns cached). Call from UI when opening a group.
     */
    suspend fun loadXtreamLiveStreamsForGroup(groupTitle: String): Result<List<PlaylistStream>> =
        withContext(Dispatchers.IO) {
            if (!xtreamLiveLazy) {
                return@withContext Result.success(streamsInLiveTvGroup(groupTitle))
            }
            if (groupTitle.equals(LiveTvCatchup.GROUP_TITLE, ignoreCase = true)) {
                return@withContext Result.success(
                    xtreamLazyCatchupStreams.distinctBy { it.streamUrl },
                )
            }
            val creds = xtreamCredentials
                ?: return@withContext Result.failure(IllegalStateException("No Xtream credentials"))
            val categoryId = xtreamLiveCategoriesById.entries.firstOrNull { it.value == groupTitle }?.key
                ?: return@withContext Result.success(emptyList())
            synchronized(xtreamCategoryStreamsCache) {
                xtreamCategoryStreamsCache[categoryId]?.let { return@withContext Result.success(it) }
            }
            var baseUrl = xtreamStreamBaseUrlResolved
            if (baseUrl.isEmpty()) {
                baseUrl = XtreamPlayerApi.resolveStreamBaseUrlForPlayback(client, creds)
                xtreamStreamBaseUrlResolved = baseUrl
            }
            val list = XtreamPlayerApi.loadLiveStreamsForCategory(
                client,
                creds,
                categoryId,
                xtreamLiveCategoriesById,
                baseUrl,
            )
            synchronized(xtreamCategoryStreamsCache) {
                val keys = xtreamCategoryStreamsCache.keys.toList()
                for (k in keys) {
                    if (k != categoryId) xtreamCategoryStreamsCache.remove(k)
                }
                xtreamCategoryStreamsCache[categoryId] = list
            }
            for (s in list) {
                if (s.tvArchive && xtreamLazyCatchupStreams.none { it.streamUrl == s.streamUrl }) {
                    xtreamLazyCatchupStreams.add(s)
                }
            }
            Result.success(list)
        }

    /** Last played streams (newest first), max 10; persisted. */
    fun recentChannels(): List<PlaylistStream> = recentChannelsStore.asPlaylistStreams()

    fun recordRecentChannel(stream: PlaylistStream) {
        recentChannelsStore.record(stream)
    }

    fun favoriteUsers(): List<FavoriteUser> = favoritesStore.users()

    fun createFavoriteUser(displayName: String): FavoriteUser? = favoritesStore.createUser(displayName)

    fun deleteFavoriteUser(userId: String) {
        favoritesStore.deleteUser(userId)
    }

    fun favoriteStreamsForUser(userId: String): List<PlaylistStream> =
        favoritesStore.streamsForUser(userId)

    fun addFavoriteForUser(userId: String, stream: PlaylistStream) {
        favoritesStore.addStream(userId, stream)
    }

    fun removeFavoriteForUser(userId: String, streamUrl: String) {
        favoritesStore.removeStream(userId, streamUrl)
    }

    /** Distinct live TV group names from the cached playlist, sorted, with [LiveTvCatchup.GROUP_TITLE] first when applicable; excludes user-hidden groups. */
    fun liveTvGroupsSorted(): List<String> {
        val hidden = hiddenLiveTvGroupsStore.getHidden()
        if (xtreamLiveLazy) {
            val groups = xtreamLiveCategoriesById.values
                .distinct()
                .filter { it !in hidden }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toMutableList()
            if (xtreamLazyCatchupStreams.isNotEmpty() && LiveTvCatchup.GROUP_TITLE !in hidden) {
                groups.removeAll { it.equals(LiveTvCatchup.GROUP_TITLE, ignoreCase = true) }
                groups.add(0, LiveTvCatchup.GROUP_TITLE)
            }
            return groups
        }
        val live = cached ?: return emptyList()
        val groups = live
            .map { it.groupTitle }
            .distinct()
            .filter { it !in hidden }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toMutableList()
        if (live.any { it.tvArchive } && LiveTvCatchup.GROUP_TITLE !in hidden) {
            groups.removeAll { it.equals(LiveTvCatchup.GROUP_TITLE, ignoreCase = true) }
            groups.add(0, LiveTvCatchup.GROUP_TITLE)
        }
        return groups
    }

    fun hiddenLiveTvGroupTitles(): Set<String> = hiddenLiveTvGroupsStore.getHidden()

    fun hideLiveTvGroup(groupTitle: String) {
        hiddenLiveTvGroupsStore.add(groupTitle)
    }

    fun hideLiveTvGroups(groupTitles: Collection<String>) {
        hiddenLiveTvGroupsStore.addAll(groupTitles)
    }

    fun unhideLiveTvGroup(groupTitle: String) {
        hiddenLiveTvGroupsStore.remove(groupTitle)
    }

    fun streamsInLiveTvGroup(groupTitle: String): List<PlaylistStream> {
        if (xtreamLiveLazy) {
            if (groupTitle.equals(LiveTvCatchup.GROUP_TITLE, ignoreCase = true)) {
                return xtreamLazyCatchupStreams.distinctBy { it.streamUrl }
            }
            val catId = xtreamLiveCategoriesById.entries.firstOrNull { it.value == groupTitle }?.key
                ?: return emptyList()
            return synchronized(xtreamCategoryStreamsCache) {
                xtreamCategoryStreamsCache[catId].orEmpty()
            }
        }
        val c = cached ?: return emptyList()
        return if (groupTitle.equals(LiveTvCatchup.GROUP_TITLE, ignoreCase = true)) {
            c.filter { it.tvArchive }
        } else {
            c.filter { it.groupTitle == groupTitle }
        }
    }

    /** Original `get.php` URL when the catalog was loaded from Xtream (needed for catch-up API). */
    fun xtreamGetPhpUrlString(): String? = xtreamCredentials?.originUrl?.toString()

    /** Whether the loaded catalog includes any VOD movies. */
    fun playlistOffersMovies(): Boolean = cachedMovies.isNotEmpty()

    /** Whether the loaded catalog includes any series. */
    fun playlistOffersSeries(): Boolean = cachedSeries.isNotEmpty()

    /** Whether any live channel has TV archive / catch-up ([PlaylistStream.tvArchive]). */
    fun playlistOffersCatchup(): Boolean =
        xtreamLazyCatchupStreams.isNotEmpty() || cached?.any { it.tvArchive } == true

    fun movieGroupsSorted(): List<String> =
        cachedMovies
            .map { it.groupTitle }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun moviesInGroup(groupTitle: String): List<PlaylistStream> =
        cachedMovies.filter { it.groupTitle == groupTitle }

    fun seriesGroupsSorted(): List<String> =
        cachedSeries
            .map { it.groupTitle }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun seriesInGroup(groupTitle: String): List<SeriesSummary> =
        cachedSeries.filter { it.groupTitle == groupTitle }

    fun seriesTitleForId(seriesId: String): String =
        cachedSeries.find { it.seriesId == seriesId }?.name ?: "Series"

    suspend fun loadSeriesEpisodes(seriesId: String): Result<List<PlaylistStream>> =
        withContext(Dispatchers.IO) {
            val creds = xtreamCredentials
                ?: return@withContext Result.failure(IllegalStateException("Series playback needs Xtream login."))
            runCatching {
                XtreamPlayerApi.loadSeriesEpisodes(client, creds, seriesId)
            }
        }

    suspend fun loadShortEpg(streamId: String): Result<List<EpgProgram>> =
        withContext(Dispatchers.IO) {
            shortEpgCache[streamId]?.let { return@withContext Result.success(it) }
            activeEpgCacheKey?.let { key ->
                epgDiskCache.loadShortPrograms(key, streamId)?.let { list ->
                    if (list.isNotEmpty()) {
                        shortEpgCache[streamId] = list
                        return@withContext Result.success(list)
                    }
                }
            }
            val creds = xtreamCredentials
                ?: return@withContext Result.failure(IllegalStateException("EPG requires Xtream login."))
            runCatching {
                XtreamPlayerApi.getShortEpg(client, creds, streamId).also { list ->
                    shortEpgCache[streamId] = list
                    activeEpgCacheKey?.let { epgDiskCache.appendShortEpg(it, streamId, list) }
                }
            }
        }

    /**
     * Deletes on-disk playlist and EPG caches, clears in-memory catalog state and image caches,
     * and resets playlist load timing so the next load refetches from the network.
     */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearCachesAndResetCatalog() = withContext(Dispatchers.IO) {
        playlistDiskCache.clear()
        epgDiskCache.clearAll()
        playlistLoadPrefs.edit().remove(KEY_LAST_NETWORK_LOAD_MS).apply()
        playlistPointerPrefs.edit().remove(KEY_SELECTED_PLAYLIST_POINTER_URL).apply()
        playlistLoadHistoryPrefs.edit().clear().apply()
        catalogLoadedForPointerUrl = null
        cached = null
        cachedMovies = emptyList()
        cachedSeries = emptyList()
        cachedExpirationEpochSeconds = null
        cachedAccountInfoText = null
        xtreamCredentials = null
        xtreamLiveLazy = false
        xtreamLiveCategoriesById = emptyMap()
        xtreamStreamBaseUrlResolved = ""
        synchronized(xtreamCategoryStreamsCache) {
            xtreamCategoryStreamsCache.clear()
        }
        xtreamLazyCatchupStreams.clear()
        cachedStalkerPortalUrl = null
        cachedStalkerMac = null
        shortEpgCache.clear()
        xmlTvByChannelId = emptyMap()
        activeEpgCacheKey = null
        cancelPendingEpgMerge()
        bumpEpgGuideRevision()
        runCatching {
            val loader = appContext.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
    }

    /**
     * Warms Coil's on-disk logo cache for live, movie, and series artwork (best-effort, capped count).
     */
    suspend fun prefetchLogoArtwork() = withContext(Dispatchers.IO) {
        val urls = LinkedHashSet<String>()
        cached?.forEach { s ->
            s.logoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        }
        if (xtreamLiveLazy) {
            synchronized(xtreamCategoryStreamsCache) {
                for (list in xtreamCategoryStreamsCache.values) {
                    list.forEach { s ->
                        s.logoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
                    }
                }
            }
            xtreamLazyCatchupStreams.forEach { s ->
                s.logoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
            }
        }
        cachedMovies.forEach { s ->
            s.logoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        }
        cachedSeries.forEach { show ->
            show.coverUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        }
        if (urls.isEmpty()) return@withContext
        val loader = appContext.imageLoader
        for (url in urls.toList().take(280)) {
            runCatching {
                val req = coil.request.ImageRequest.Builder(appContext).data(url).build()
                loader.execute(req)
            }
        }
    }

    /**
     * EPG for a live row: XMLTV from playlist / `xmltv.php` when [PlaylistStream.tvgId] matches a channel id,
     * otherwise Xtream `get_short_epg` via [PlaylistStream.epgStreamId].
     */
    suspend fun loadEpgForLiveStream(stream: PlaylistStream): List<EpgProgram> = withContext(Dispatchers.IO) {
        val tid = stream.tvgId?.trim()?.takeIf { it.isNotEmpty() }
        if (tid != null) {
            xmlTvProgramsForTvgId(tid)?.let { return@withContext it.withDecodedEpgText() }
        }
        val sid = stream.epgStreamId ?: return@withContext emptyList()
        loadShortEpg(sid).getOrElse { emptyList() }.withDecodedEpgText()
    }

    private fun xmlTvProgramsForTvgId(tvgId: String): List<EpgProgram>? {
        xmlTvByChannelId[tvgId]?.let { return it }
        return xmlTvByChannelId.entries.firstOrNull { (k, _) ->
            k.equals(tvgId, ignoreCase = true)
        }?.value
    }

    /**
     * Live channel search: case-insensitive match on [PlaylistStream.displayName] and
     * [PlaylistStream.groupTitle] (multi-word group matches use the same token guard as before).
     */
    suspend fun searchChannels(query: String, maxResults: Int = 100): List<PlaylistStream> =
        withContext(Dispatchers.Default) {
            val q = query.trim().lowercase(Locale.getDefault())
            if (q.isEmpty()) return@withContext emptyList()
            val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val multiWord = tokens.size > 1
            val live: List<PlaylistStream> = if (xtreamLiveLazy) {
                buildList {
                    synchronized(xtreamCategoryStreamsCache) {
                        for (v in xtreamCategoryStreamsCache.values) addAll(v)
                    }
                    addAll(xtreamLazyCatchupStreams)
                }
            } else {
                cached ?: return@withContext emptyList()
            }
            val seen = LinkedHashSet<String>()
            val out = ArrayList<PlaylistStream>()
            fun take(s: PlaylistStream) {
                if (out.size >= maxResults) return
                val url = s.streamUrl
                if (url !in seen) {
                    seen.add(url)
                    out.add(s)
                }
            }
            fun streamRelatesToMultiWordQuery(s: PlaylistStream): Boolean {
                if (!multiWord) return true
                val name = s.displayName.lowercase(Locale.getDefault())
                val group = s.groupTitle.lowercase(Locale.getDefault())
                return tokens.all { name.contains(it) } || tokens.all { group.contains(it) }
            }
            for (s in live) {
                if (out.size >= maxResults) break
                val name = s.displayName.lowercase(Locale.getDefault())
                val group = s.groupTitle.lowercase(Locale.getDefault())
                when {
                    name.contains(q) -> take(s)
                    group.contains(q) &&
                        (!multiWord || streamRelatesToMultiWordQuery(s)) -> take(s)
                }
            }
            out
        }

    /** `true` when [BuildConfig.OMDB_API_KEY] is set (see project `local.properties`). */
    fun isOmdbPlotLookupConfigured(): Boolean = BuildConfig.OMDB_API_KEY.isNotBlank()

    /**
     * Fetches a plot summary from OMDb (IMDb-linked) when the app is built with an API key.
     * Results are cached in memory for the session.
     */
    suspend fun lookupOmdbPlot(displayName: String): String? =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.OMDB_API_KEY.trim()
            if (apiKey.isEmpty()) return@withContext null
            val cacheKey = displayName.trim().lowercase(Locale.getDefault())
            if (omdbPlotCache.containsKey(cacheKey)) return@withContext omdbPlotCache[cacheKey]
            val title = OmdbApi.cleanMovieTitleForLookup(displayName)
            val plot = OmdbApi.fetchPlot(client, apiKey, title)
            omdbPlotCache[cacheKey] = plot
            plot
        }

    /** Portal URL and MAC from the pointer file when using a Stalker / Ministra source. */
    fun stalkerPortalConnection(): Pair<String, String>? {
        val p = cachedStalkerPortalUrl?.trim().orEmpty()
        val m = cachedStalkerMac?.trim().orEmpty()
        if (p.isEmpty() || m.isEmpty()) return null
        return p to m
    }

    /** Human-readable expiration for the current playlist subscription, if known. */
    fun playlistExpirationDisplayText(): String {
        val epoch = cachedExpirationEpochSeconds
        if (epoch == null) {
            return if (stalkerPortalConnection() != null) {
                "Subscription Expiration: not reported by portal"
            } else {
                "Subscription Expiration: unavailable"
            }
        }
        if (epoch <= 0L) {
            return "Subscription Expiration: unlimited"
        }
        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        return "Subscription Expiration: ${fmt.format(Date(epoch * 1000))}"
    }

    /**
     * Applies a full Xtream [player_api.php] catalog (live + VOD + series) to memory, EPG, and disk cache.
     */
    private fun commitXtreamFullCatalogLoad(
        apiResult: XtreamPlayerApi.FullXtreamLoadResult,
        creds: XtreamCredentials,
        playlistSourceUrl: String,
        pointerUrl: String,
        epgForceRefresh: Boolean,
    ): List<PlaylistStream> {
        cachedExpirationEpochSeconds = apiResult.expirationEpochSeconds
        cachedAccountInfoText = apiResult.accountInfoText
        cachedMovies = apiResult.movies
        cachedSeries = apiResult.series
        xtreamCredentials = creds
        val lazyCats = apiResult.liveCategoriesById
        if (lazyCats != null && lazyCats.isNotEmpty()) {
            xtreamLiveLazy = true
            xtreamLiveCategoriesById = lazyCats
            xtreamStreamBaseUrlResolved = apiResult.streamBaseUrl
            cached = emptyList()
            synchronized(xtreamCategoryStreamsCache) {
                xtreamCategoryStreamsCache.clear()
            }
            xtreamLazyCatchupStreams.clear()
        } else {
            xtreamLiveLazy = false
            xtreamLiveCategoriesById = emptyMap()
            xtreamStreamBaseUrlResolved = ""
            synchronized(xtreamCategoryStreamsCache) {
                xtreamCategoryStreamsCache.clear()
            }
            xtreamLazyCatchupStreams.clear()
            cached = apiResult.liveStreams
        }
        persistCatalogSnapshot(pointerIdentityUrl = pointerUrl, playlistSourceUrl = playlistSourceUrl)
        recordNetworkPlaylistEpgLoadComplete()
        catalogLoadedForPointerUrl = pointerUrl
        val liveForEpg = cached ?: emptyList()
        scheduleEpgMerge(
            catalogPointerUrl = pointerUrl,
            m3uText = null,
            liveStreams = liveForEpg,
            creds = creds,
            playlistSourceUrl = playlistSourceUrl,
            forceRefresh = epgForceRefresh,
        )
        return cached ?: emptyList()
    }

    suspend fun loadPlaylist(forceRefresh: Boolean = false): Result<List<PlaylistStream>> =
        loadPlaylistForPointer(
            pointerUrlRaw = selectedPlaylistPointerUrlOrNull()?.trim().orEmpty(),
            forceRefresh = forceRefresh,
        )

    /**
     * Loads the playlist for an explicit pointer URL (e.g. the row the user tapped). Prefer this from the
     * load screen so prefs and UI selection cannot disagree.
     */
    suspend fun loadPlaylistForPointer(
        pointerUrlRaw: String,
        forceRefresh: Boolean = false,
    ): Result<List<PlaylistStream>> =
        withContext(Dispatchers.IO) {
            val pointerUrl = pointerUrlRaw.trim()
            if (pointerUrl.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Select a playlist on the loading screen."),
                )
            }
            try {
                ensureMemoryMatchesPointer(pointerUrl)

                val stale = playlistEpgStaleVsLastNetworkLoad()
                val skipFastPath = forceRefresh || stale

                if (!skipFastPath &&
                    catalogLoadedForPointerUrl == pointerUrl &&
                    hasAnyCatalogContent() &&
                    (cached != null || xtreamLiveLazy)
                ) {
                    logPlaylistSource(
                        source = "memory",
                        lastNetworkRefreshMillis = lastNetworkPlaylistEpgLoadMillis(),
                    )
                    return@withContext Result.success(cached ?: emptyList())
                }
                if (!skipFastPath && restoreCatalogFromDisk(pointerUrl)) {
                    logPlaylistSource(
                        source = "disk_cache",
                        lastNetworkRefreshMillis = lastNetworkPlaylistEpgLoadMillis(),
                    )
                    return@withContext Result.success(cached ?: emptyList())
                }

                val streams = loadPlaylistFromNetwork(pointerUrl, forceRefresh, stale)
                Result.success(streams)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // OkHttp uses this message when Call.cancel() runs (e.g. load-screen timeout).
                if (e.message.equals("Canceled", ignoreCase = true)) {
                    throw CancellationException("Playlist load cancelled").apply { initCause(e) }
                }
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /**
     * Network-only path after memory/disk cache miss. Uses a dedicated [OkHttpClient] so in-flight
     * [Call]s can be cancelled when the loading coroutine times out or is cancelled.
     */
    private suspend fun loadPlaylistFromNetwork(
        pointerUrl: String,
        forceRefresh: Boolean,
        stale: Boolean,
    ): List<PlaylistStream> {
        val epgForceRefresh = forceRefresh || stale
        Log.d(
            LOG_TAG,
            "Fetching playlist and EPG from network (userRefresh=$forceRefresh, stale=$stale)",
        )

        val activePlaylistCalls = ConcurrentHashMap.newKeySet<Call>()
        val httpForLoad =
            client.newBuilder()
                .eventListener(
                    object : EventListener() {
                        override fun callStart(call: Call) {
                            activePlaylistCalls.add(call)
                        }

                        override fun callEnd(call: Call) {
                            activePlaylistCalls.remove(call)
                        }
                    },
                )
                .build()
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) {
                for (c in activePlaylistCalls.toList()) {
                    runCatching { c.cancel() }
                }
                activePlaylistCalls.clear()
            }
        }

        cancelPendingEpgMerge()
        shortEpgCache.clear()
        xmlTvByChannelId = emptyMap()
        activeEpgCacheKey = null
        var m3uSourceUrl: String? = null
        cachedAccountInfoText = null
        xtreamCredentials = null
        xtreamLiveLazy = false
        xtreamLiveCategoriesById = emptyMap()
        xtreamStreamBaseUrlResolved = ""
        synchronized(xtreamCategoryStreamsCache) {
            xtreamCategoryStreamsCache.clear()
        }
        xtreamLazyCatchupStreams.clear()
        cachedMovies = emptyList()
        cachedSeries = emptyList()
        cachedStalkerPortalUrl = null
        cachedStalkerMac = null
        var text = fetchBody(pointerUrl, httpForLoad)
        val pointer = PlaylistPointerClassifier.classify(text)
        Log.i(LOG_TAG, "Pointer file mode: ${pointer.mode}")

        for (creds in XtreamPointerResolver.xtreamCredentialsForPointerAndBody(pointerUrl, text)) {
            Log.d(
                LOG_TAG,
                "Xtream candidate: baseUrl=${creds.networkBaseUrlForLogging()}, username=${creds.username}, password=${creds.password}, player_api=${creds.playerApiUrlForLogging()}",
            )
            val apiResult = runCatching {
                XtreamPlayerApi.loadFullXtreamCatalog(httpForLoad, creds)
            }.onFailure { e ->
                Log.w(LOG_TAG, "Xtream player_api not used for this candidate: ${e.message}")
            }.getOrNull()
            if (apiResult != null &&
                (apiResult.liveStreams.isNotEmpty() ||
                    !apiResult.liveCategoriesById.isNullOrEmpty() ||
                    apiResult.movies.isNotEmpty() ||
                    apiResult.series.isNotEmpty())
            ) {
                val src = creds.originUrl.toString()
                m3uSourceUrl = src
                Log.i(
                    LOG_TAG,
                    "Xtream Codes API catalog loaded (live/VOD/series): ${creds.originUrl.host}",
                )
                return commitXtreamFullCatalogLoad(
                    apiResult,
                    creds,
                    playlistSourceUrl = src,
                    pointerUrl = pointerUrl,
                    epgForceRefresh = epgForceRefresh,
                )
            }
        }
        Log.d(LOG_TAG, "No Xtream candidate produced a non-empty API catalog; continuing (Stalker / nested / M3U)")

        if (pointer.mode == PlaylistPointerClassifier.Mode.STALKER_PORTAL) {
            val ptr = pointer.stalker!!
            val stalkerResult = runCatching {
                StalkerPortalApi.loadFullCatalog(httpForLoad, ptr.portalBaseUrl, ptr.mac)
            }
            stalkerResult.onFailure {
                Log.w(LOG_TAG, "Stalker portal load failed", it)
            }
            val sr = stalkerResult.getOrNull()
            if (sr != null &&
                (sr.liveStreams.isNotEmpty() || sr.movies.isNotEmpty() || sr.series.isNotEmpty())
            ) {
                cachedExpirationEpochSeconds = null
                cachedAccountInfoText = sr.accountInfoText
                cached = sr.liveStreams
                cachedMovies = sr.movies
                cachedSeries = sr.series
                xtreamCredentials = null
                xtreamLiveLazy = false
                xtreamLiveCategoriesById = emptyMap()
                xtreamStreamBaseUrlResolved = ""
                synchronized(xtreamCategoryStreamsCache) {
                    xtreamCategoryStreamsCache.clear()
                }
                xtreamLazyCatchupStreams.clear()
                cachedStalkerPortalUrl = ptr.portalBaseUrl.trimEnd('/')
                cachedStalkerMac = ptr.mac.trim()
                persistCatalogSnapshot(
                    pointerIdentityUrl = pointerUrl,
                    playlistSourceUrl = null,
                    stalkerPortalUrl = ptr.portalBaseUrl,
                    stalkerMac = ptr.mac.trim(),
                )
                recordNetworkPlaylistEpgLoadComplete()
                catalogLoadedForPointerUrl = pointerUrl
                scheduleEpgMerge(
                    catalogPointerUrl = pointerUrl,
                    m3uText = null,
                    liveStreams = sr.liveStreams,
                    creds = null,
                    playlistSourceUrl = null,
                    forceRefresh = epgForceRefresh,
                    stalkerPortalUrl = ptr.portalBaseUrl,
                    stalkerMac = ptr.mac.trim(),
                )
                return sr.liveStreams
            }
        }

        val useNestedPlaylistUrl =
            pointer.mode != PlaylistPointerClassifier.Mode.EMBEDDED_PLAYLIST
        var m3uNestedPlaylistUrl: String? = null
        if (useNestedPlaylistUrl) {
            val nested = StalkerPointerParser.firstHttpUrlExcludingPortal(
                text,
                pointer.stalker?.portalBaseUrl,
            )
            if (nested != null) {
                m3uSourceUrl = nested
                val xtreamCreds = XtreamCredentials.fromGetPhpUrl(nested)
                if (xtreamCreds != null) {
                    Log.d(
                        LOG_TAG,
                        "Xtream nested get.php: baseUrl=${xtreamCreds.networkBaseUrlForLogging()}, username=${xtreamCreds.username}, password=${xtreamCreds.password}, player_api=${xtreamCreds.playerApiUrlForLogging()}",
                    )
                    val apiResult = runCatching {
                        XtreamPlayerApi.loadFullXtreamCatalog(httpForLoad, xtreamCreds)
                    }.onFailure { e ->
                        Log.w(LOG_TAG, "Nested Xtream player_api failed: ${e.message}")
                    }.getOrNull()
                    if (apiResult != null &&
                        (apiResult.liveStreams.isNotEmpty() ||
                            !apiResult.liveCategoriesById.isNullOrEmpty() ||
                            apiResult.movies.isNotEmpty() ||
                            apiResult.series.isNotEmpty())
                    ) {
                        Log.i(LOG_TAG, "Nested URL: Xtream Codes API catalog loaded")
                        return commitXtreamFullCatalogLoad(
                            apiResult,
                            xtreamCreds,
                            playlistSourceUrl = nested,
                            pointerUrl = pointerUrl,
                            epgForceRefresh = epgForceRefresh,
                        )
                    }
                }
                m3uNestedPlaylistUrl = nested
            }
        }
        cachedExpirationEpochSeconds = m3uSourceUrl?.let { url ->
            runCatching { XtreamExpiryFetcher.fetchExpirationEpochSeconds(url, httpForLoad) }
                .getOrNull()
        }
        cachedMovies = emptyList()
        cachedSeries = emptyList()
        Log.i(LOG_TAG, "Playlist load: using M3U parse fallback (Xtream player_api not used or returned no catalog)")
        val (m3uHeader, streams) =
            if (m3uNestedPlaylistUrl != null) {
                fetchAndParseM3uPlaylist(m3uNestedPlaylistUrl, httpForLoad)
            } else {
                M3uParser.parseBuffered(text.byteInputStream().bufferedReader(Charsets.UTF_8))
            }
        val m3uCreds = m3uSourceUrl?.let { XtreamCredentials.fromGetPhpUrl(it) }
        xtreamCredentials = m3uCreds
        xtreamLiveLazy = false
        xtreamLiveCategoriesById = emptyMap()
        xtreamStreamBaseUrlResolved = ""
        synchronized(xtreamCategoryStreamsCache) {
            xtreamCategoryStreamsCache.clear()
        }
        xtreamLazyCatchupStreams.clear()
        cached = streams
        persistCatalogSnapshot(pointerIdentityUrl = pointerUrl, playlistSourceUrl = m3uSourceUrl)
        recordNetworkPlaylistEpgLoadComplete()
        catalogLoadedForPointerUrl = pointerUrl
        scheduleEpgMerge(
            catalogPointerUrl = pointerUrl,
            m3uText = m3uHeader,
            liveStreams = streams,
            creds = m3uCreds,
            playlistSourceUrl = m3uSourceUrl,
            forceRefresh = epgForceRefresh,
        )
        return streams
    }

    /** Loads the playlist and succeeds if live, movies, or series has at least one item (Xtream API or M3U). */
    suspend fun loadVerifiedPlaylist(forceRefresh: Boolean = false): Result<List<PlaylistStream>> =
        verifyLoadedCatalog(loadPlaylist(forceRefresh))

    suspend fun loadVerifiedPlaylistForPointer(
        pointerUrl: String,
        forceRefresh: Boolean = false,
    ): Result<List<PlaylistStream>> =
        verifyLoadedCatalog(loadPlaylistForPointer(pointerUrlRaw = pointerUrl, forceRefresh = forceRefresh))

    private fun verifyLoadedCatalog(result: Result<List<PlaylistStream>>): Result<List<PlaylistStream>> =
        result.fold(
            onSuccess = { streams ->
                if (!hasAnyCatalogContent()) {
                    Result.failure(
                        IllegalStateException("No live channels, movies, or series were returned."),
                    )
                } else {
                    Result.success(streams)
                }
            },
            onFailure = { Result.failure(it) },
        )

    private fun restoreCatalogFromDisk(pointerUrl: String): Boolean {
        val snap = playlistDiskCache.read(pointerUrl) ?: return false
        val ok = restoreSnapshot(snap)
        if (ok) {
            catalogLoadedForPointerUrl = pointerUrl
        }
        return ok
    }

    private fun restoreSnapshot(snap: PlaylistDiskCache.Snapshot): Boolean {
        if (snap.live.isEmpty() &&
            snap.movies.isEmpty() &&
            snap.series.isEmpty() &&
            snap.liveLazyCategories.isNullOrEmpty()
        ) {
            return false
        }
        val epgBundle = snap.epgCacheKey?.takeIf { it.isNotBlank() }?.let { key ->
            epgDiskCache.loadBundleIgnoringMaxAge(key)
        }
        return runCatching {
            cached = snap.live
            cachedMovies = snap.movies
            cachedSeries = snap.series
            cachedExpirationEpochSeconds = snap.expirationEpochSeconds
            cachedAccountInfoText = snap.accountInfoText
            xtreamCredentials = snap.xtreamGetPhpUrl?.let { XtreamCredentials.fromGetPhpUrl(it) }
            if (!snap.liveLazyCategories.isNullOrEmpty() && snap.live.isEmpty() && snap.xtreamGetPhpUrl != null) {
                xtreamLiveLazy = true
                xtreamLiveCategoriesById = snap.liveLazyCategories.toMap()
                xtreamStreamBaseUrlResolved = ""
                synchronized(xtreamCategoryStreamsCache) {
                    xtreamCategoryStreamsCache.clear()
                }
                xtreamLazyCatchupStreams.clear()
            } else {
                xtreamLiveLazy = false
                xtreamLiveCategoriesById = emptyMap()
                xtreamStreamBaseUrlResolved = ""
                synchronized(xtreamCategoryStreamsCache) {
                    xtreamCategoryStreamsCache.clear()
                }
                xtreamLazyCatchupStreams.clear()
            }
            cachedStalkerPortalUrl = snap.stalkerPortalUrl
            cachedStalkerMac = snap.stalkerMac
            activeEpgCacheKey = snap.epgCacheKey
            shortEpgCache.clear()
            if (epgBundle != null) {
                xmlTvByChannelId = epgBundle.xmlTvByChannelId
                shortEpgCache.putAll(epgBundle.shortEpgByStreamId)
            } else {
                xmlTvByChannelId = emptyMap()
            }
            hasAnyCatalogContent()
        }.getOrDefault(false)
    }

    private fun lastNetworkPlaylistEpgLoadMillis(): Long =
        playlistLoadPrefs.getLong(KEY_LAST_NETWORK_LOAD_MS, -1L)

    /** True if we never recorded a successful network load, or [EpgDiskCache.MAX_AGE_MS] has passed since then. */
    private fun playlistEpgStaleVsLastNetworkLoad(): Boolean {
        val last = lastNetworkPlaylistEpgLoadMillis()
        if (last < 0L) return true
        return System.currentTimeMillis() - last > EpgDiskCache.MAX_AGE_MS
    }

    private fun formatPlaylistLoadLogTime(epochMs: Long): String {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
        return fmt.format(Date(epochMs))
    }

    private fun logPlaylistSource(source: String, lastNetworkRefreshMillis: Long) {
        val lastStr =
            if (lastNetworkRefreshMillis >= 0L) {
                formatPlaylistLoadLogTime(lastNetworkRefreshMillis)
            } else {
                "unknown"
            }
        val nowStr = formatPlaylistLoadLogTime(System.currentTimeMillis())
        Log.i(
            LOG_TAG,
            "Playlist and EPG ready (source=$source, now=$nowStr, lastNetworkRefresh=$lastStr)",
        )
    }

    private fun recordNetworkPlaylistEpgLoadComplete() {
        val now = System.currentTimeMillis()
        playlistLoadPrefs.edit().putLong(KEY_LAST_NETWORK_LOAD_MS, now).apply()
        Log.i(
            LOG_TAG,
            "Playlist catalog ready at ${formatPlaylistLoadLogTime(now)} (epochMs=$now); EPG merges in background when applicable",
        )
    }

    private fun persistCatalogSnapshot(
        pointerIdentityUrl: String,
        playlistSourceUrl: String?,
        stalkerPortalUrl: String? = null,
        stalkerMac: String? = null,
    ) {
        if (!hasAnyCatalogContent()) return
        playlistDiskCache.write(
            PlaylistDiskCache.Snapshot(
                embeddedUrl = pointerIdentityUrl,
                playlistSourceUrl = playlistSourceUrl,
                epgCacheKey = activeEpgCacheKey,
                expirationEpochSeconds = cachedExpirationEpochSeconds,
                accountInfoText = cachedAccountInfoText,
                xtreamGetPhpUrl = xtreamCredentials?.originUrl?.toString(),
                stalkerPortalUrl = stalkerPortalUrl,
                stalkerMac = stalkerMac,
                live = cached ?: emptyList(),
                movies = cachedMovies,
                series = cachedSeries,
                liveLazyCategories =
                    if (xtreamLiveLazy) {
                        xtreamLiveCategoriesById.entries.map { it.key to it.value }
                    } else {
                        null
                    },
            ),
        )
    }

    private fun cancelPendingEpgMerge() {
        epgBackgroundJob?.cancel()
        epgBackgroundJob = null
    }

    private data class EpgMergeOutcome(
        val cacheKey: String,
        val xmlTv: Map<String, List<EpgProgram>>,
        val shortEpgSnapshot: Map<String, List<EpgProgram>>,
    )

    /**
     * Builds XMLTV + disk EPG state without touching in-memory guide maps (safe to run while UI already shows channels).
     */
    private fun buildEpgMergeOutcome(
        pointerIdentityUrl: String,
        m3uText: String?,
        liveStreams: List<PlaylistStream>,
        creds: XtreamCredentials?,
        playlistSourceUrl: String?,
        forceRefresh: Boolean,
        stalkerPortalUrl: String? = null,
        stalkerMac: String? = null,
    ): EpgMergeOutcome {
        val key = epgDiskCache.computeKey(
            embeddedUrl = pointerIdentityUrl,
            playlistSourceUrl = playlistSourceUrl,
            xtreamCredentials = creds,
            m3uText = m3uText,
            stalkerPortalUrl = stalkerPortalUrl,
            stalkerMac = stalkerMac,
        )

        val fromDisk = epgDiskCache.loadBundleIfFresh(key, forceRefresh)
        if (fromDisk != null) {
            return EpgMergeOutcome(
                cacheKey = key,
                xmlTv = fromDisk.xmlTvByChannelId,
                shortEpgSnapshot = fromDisk.shortEpgByStreamId,
            )
        }

        val tvgIds = liveStreams.mapNotNull { s ->
            s.tvgId?.trim()?.takeIf { it.isNotEmpty() }
        }.toSet()
        val filter: Set<String>? = tvgIds.takeIf { it.isNotEmpty() }
        val merged = mutableMapOf<String, MutableList<EpgProgram>>()
        val urls = LinkedHashSet<String>()
        m3uText?.let { M3uParser.extractUrlTvg(it) }?.let { urls.add(it.trim()) }
        runCatching { creds?.xmlTvUrl()?.toString() }?.getOrNull()?.let { urls.add(it) }
        val shortSnapshot = shortEpgCache.toMap()
        if (urls.isEmpty()) {
            epgDiskCache.saveBundle(key, emptyMap(), shortSnapshot)
            return EpgMergeOutcome(key, emptyMap(), shortSnapshot)
        }
        for (url in urls) {
            val xml = runCatching { fetchBody(url) }.getOrNull() ?: continue
            val trimmed = xml.trimStart()
            if (!trimmed.startsWith("<")) continue
            val map = XmlTvParser.parse(trimmed, filter)
            for ((k, v) in map) {
                merged.getOrPut(k) { mutableListOf() }.addAll(v)
            }
        }
        val xmlTv = merged.mapValues { (_, list) ->
            list.sortedBy { it.startMillis }.distinctBy { it.startMillis to it.title }
        }
        val shortAfter = shortEpgCache.toMap()
        epgDiskCache.saveBundle(key, xmlTv, shortAfter)
        return EpgMergeOutcome(key, xmlTv, shortAfter)
    }

    private fun applyEpgMergeOutcome(outcome: EpgMergeOutcome, catalogPointerUrl: String) {
        if (catalogLoadedForPointerUrl != catalogPointerUrl) {
            Log.d(LOG_TAG, "Skipping EPG apply (catalog pointer changed during merge)")
            return
        }
        activeEpgCacheKey = outcome.cacheKey
        xmlTvByChannelId = outcome.xmlTv
        shortEpgCache.clear()
        shortEpgCache.putAll(outcome.shortEpgSnapshot)
        bumpEpgGuideRevision()
    }

    private fun scheduleEpgMerge(
        catalogPointerUrl: String,
        m3uText: String?,
        liveStreams: List<PlaylistStream>,
        creds: XtreamCredentials?,
        playlistSourceUrl: String?,
        forceRefresh: Boolean,
        stalkerPortalUrl: String? = null,
        stalkerMac: String? = null,
    ) {
        cancelPendingEpgMerge()
        epgBackgroundJob =
            epgMergeScope.launch {
                try {
                    val outcome =
                        buildEpgMergeOutcome(
                            pointerIdentityUrl = catalogPointerUrl,
                            m3uText = m3uText,
                            liveStreams = liveStreams,
                            creds = creds,
                            playlistSourceUrl = playlistSourceUrl,
                            forceRefresh = forceRefresh,
                            stalkerPortalUrl = stalkerPortalUrl,
                            stalkerMac = stalkerMac,
                        )
                    applyEpgMergeOutcome(outcome, catalogPointerUrl)
                    if (catalogLoadedForPointerUrl == catalogPointerUrl) {
                        persistCatalogSnapshot(
                            pointerIdentityUrl = catalogPointerUrl,
                            playlistSourceUrl = playlistSourceUrl,
                            stalkerPortalUrl = stalkerPortalUrl,
                            stalkerMac = stalkerMac,
                        )
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Background EPG merge failed", e)
                }
            }
    }

    /** Xtream short EPG often stores title/description as Base64; normalize for display. */
    private fun List<EpgProgram>.withDecodedEpgText(): List<EpgProgram> = map { p ->
        EpgProgram(
            title = decodeEpgTextIfBase64(p.title.trim()).trim().ifEmpty { p.title },
            startMillis = p.startMillis,
            endMillis = p.endMillis,
            description = p.description?.let { desc ->
                decodeEpgTextIfBase64(desc.trim()).trim().takeIf { it.isNotEmpty() }
            },
        )
    }

    private fun fetchBody(url: String, httpClient: OkHttpClient = client): String {
        val request = buildHttpGetForUrl(url)
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.string() ?: error("Empty response")
        }
    }

    /** Parses a remote M3U without loading the whole response into a [String] first. */
    private fun fetchAndParseM3uPlaylist(url: String, httpClient: OkHttpClient): Pair<String, List<PlaylistStream>> {
        val request = buildHttpGetForUrl(url)
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            val body = response.body ?: error("Empty response")
            return body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                M3uParser.parseBuffered(reader)
            }
        }
    }

    /**
     * Dropbox shared links often return an HTML interstitial or odd redirects unless the client looks
     * like a normal browser; IPTV URLs keep using a simple VLC-style agent.
     */
    private fun buildHttpGetForUrl(url: String): Request {
        val b = Request.Builder().url(url)
        if (isDropboxHost(url)) {
            b.header("User-Agent", DROPBOX_POINTER_USER_AGENT)
            b.header("Accept", "text/plain,text/*,*/*;q=0.8")
        } else {
            b.header("User-Agent", "VLC/3.0.0")
        }
        return b.build()
    }

    private fun isDropboxHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("dropbox.com") || lower.contains("dropboxusercontent.com")
    }

    private companion object {
        private const val LOG_TAG: String = "DirtheadPlaylist"

        /** Browser-like UA so `dl=1` Dropbox links return the raw `.txt` instead of preview HTML. */
        private const val DROPBOX_POINTER_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val PREFS_PLAYLIST_LOAD: String = "dirthead_playlist_load"
        private const val KEY_LAST_NETWORK_LOAD_MS: String = "last_network_load_ms"

        private const val PREFS_PLAYLIST_POINTER: String = "dirthead_playlist_pointer"
        private const val KEY_SELECTED_PLAYLIST_POINTER_URL: String = "selected_playlist_pointer_url"

        private const val PREFS_PLAYLIST_LOAD_HISTORY: String = "dirthead_playlist_load_history"
        private const val KEY_LOAD_OK_POINTERS: String = "load_ok_pointer_urls"
        private const val KEY_LOAD_BAD_POINTERS: String = "load_failed_pointer_urls"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}
