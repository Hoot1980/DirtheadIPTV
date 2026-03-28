package app.dirthead.iptv.data

import android.content.Context
import org.json.JSONArray

/**
 * Persists live TV [group-title] values the user chose to hide from the Live TV group grid.
 */
internal class HiddenLiveTvGroupsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getHidden(): Set<String> {
        val raw = prefs.getString(KEY_TITLES, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun isHidden(groupTitle: String): Boolean = getHidden().contains(groupTitle)

    fun add(groupTitle: String) {
        val t = groupTitle.trim().ifEmpty { return }
        val next = getHidden().toMutableSet()
        if (next.add(t)) persist(next)
    }

    fun addAll(groupTitles: Collection<String>) {
        val next = getHidden().toMutableSet()
        var changed = false
        for (g in groupTitles) {
            val t = g.trim()
            if (t.isNotEmpty() && next.add(t)) changed = true
        }
        if (changed) persist(next)
    }

    fun remove(groupTitle: String) {
        val next = getHidden().toMutableSet()
        if (next.remove(groupTitle)) persist(next)
    }

    fun clear() {
        prefs.edit().remove(KEY_TITLES).apply()
    }

    private fun persist(set: Set<String>) {
        val arr = JSONArray()
        set.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { arr.put(it) }
        prefs.edit().putString(KEY_TITLES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "dirthead_hidden_live_tv_groups"
        private const val KEY_TITLES = "group_titles_json"
    }
}
