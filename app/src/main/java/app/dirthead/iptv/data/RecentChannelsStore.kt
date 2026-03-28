package app.dirthead.iptv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last [maxEntries] played streams (URL + title) for quick reopening.
 */
internal class RecentChannelsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(stream: PlaylistStream) {
        val url = stream.streamUrl.trim()
        if (url.isEmpty()) return
        val name = stream.displayName.trim().ifEmpty { url }
        synchronized(this) {
            val list = loadEntries().toMutableList()
            list.removeAll { it.streamUrl == url }
            val logo = stream.logoUrl?.trim()?.takeIf { it.isNotEmpty() }
            list.add(0, RecentEntry(displayName = name, streamUrl = url, logoUrl = logo))
            while (list.size > MAX_ENTRIES) {
                list.removeAt(list.lastIndex)
            }
            saveEntries(list)
        }
    }

    fun asPlaylistStreams(): List<PlaylistStream> =
        loadEntries().map { e ->
            PlaylistStream(
                displayName = e.displayName,
                streamUrl = e.streamUrl,
                groupTitle = RECENT_GROUP,
                logoUrl = e.logoUrl,
            )
        }

    private fun loadEntries(): List<RecentEntry> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("u", "").trim()
                    if (u.isEmpty()) continue
                    val d = o.optString("d", "").trim().ifEmpty { u }
                    val logo = o.optString("l", "").trim().takeIf { it.isNotEmpty() }
                    add(RecentEntry(displayName = d, streamUrl = u, logoUrl = logo))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntries(entries: List<RecentEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
                .put("d", e.displayName)
                .put("u", e.streamUrl)
            e.logoUrl?.let { o.put("l", it) }
            arr.put(o)
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private data class RecentEntry(
        val displayName: String,
        val streamUrl: String,
        val logoUrl: String? = null,
    )

    companion object {
        private const val PREFS_NAME = "dirthead_recent_channels"
        private const val KEY_LIST = "recent_list"
        private const val MAX_ENTRIES = 10
        const val RECENT_GROUP: String = "Recent"
    }
}
