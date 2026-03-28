package app.dirthead.iptv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Named favorite profiles ("users") each with their own list of stream URLs.
 */
internal class FavoritesStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun users(): List<FavoriteUser> {
        synchronized(this) {
            val root = parseRoot() ?: return emptyList()
            val arr = root.optJSONArray(KEY_USERS) ?: return emptyList()
            return buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").trim()
                    val name = o.optString("name", "").trim()
                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        add(FavoriteUser(id = id, displayName = name))
                    }
                }
            }
        }
    }

    fun createUser(displayName: String): FavoriteUser? {
        val name = displayName.trim()
        if (name.isEmpty()) return null
        synchronized(this) {
            val root = parseRoot() ?: JSONObject()
            val arr = root.optJSONArray(KEY_USERS) ?: JSONArray()
            val id = UUID.randomUUID().toString()
            arr.put(JSONObject().put("id", id).put("name", name))
            root.put(KEY_USERS, arr)
            val favMap = root.optJSONObject(KEY_FAVORITES) ?: JSONObject()
            favMap.put(id, JSONArray())
            root.put(KEY_FAVORITES, favMap)
            saveRoot(root)
            return FavoriteUser(id = id, displayName = name)
        }
    }

    fun deleteUser(userId: String) {
        synchronized(this) {
            val root = parseRoot() ?: return
            val arr = root.optJSONArray(KEY_USERS) ?: return
            val nextUsers = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id", "") != userId) nextUsers.put(o)
            }
            root.put(KEY_USERS, nextUsers)
            val favMap = root.optJSONObject(KEY_FAVORITES) ?: JSONObject()
            favMap.remove(userId)
            root.put(KEY_FAVORITES, favMap)
            saveRoot(root)
        }
    }

    fun streamsForUser(userId: String): List<PlaylistStream> {
        synchronized(this) {
            val root = parseRoot() ?: return emptyList()
            val favMap = root.optJSONObject(KEY_FAVORITES) ?: return emptyList()
            val arr = favMap.optJSONArray(userId) ?: return emptyList()
            return buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("u", "").trim()
                    if (u.isEmpty()) continue
                    val d = o.optString("d", "").trim().ifEmpty { u }
                    val logo = o.optString("l", "").trim().takeIf { it.isNotEmpty() }
                    add(
                        PlaylistStream(
                            displayName = d,
                            streamUrl = u,
                            groupTitle = FAVORITES_GROUP,
                            logoUrl = logo,
                        ),
                    )
                }
            }
        }
    }

    fun addStream(userId: String, stream: PlaylistStream) {
        val url = stream.streamUrl.trim()
        if (url.isEmpty()) return
        val name = stream.displayName.trim().ifEmpty { url }
        val logo = stream.logoUrl?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(this) {
            val root = parseRoot() ?: JSONObject()
            val favMap = root.optJSONObject(KEY_FAVORITES) ?: JSONObject()
            val arr = favMap.optJSONArray(userId) ?: JSONArray()
            val list = mutableListOf<Triple<String, String, String?>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("u", "").trim()
                if (u.isNotEmpty()) {
                    val d = o.optString("d", "").trim().ifEmpty { u }
                    val l = o.optString("l", "").trim().takeIf { it.isNotEmpty() }
                    list.add(Triple(u, d, l))
                }
            }
            list.removeAll { it.first == url }
            list.add(0, Triple(url, name, logo))
            val next = JSONArray()
            for ((u, d, l) in list) {
                val jo = JSONObject().put("u", u).put("d", d)
                l?.let { jo.put("l", it) }
                next.put(jo)
            }
            favMap.put(userId, next)
            root.put(KEY_FAVORITES, favMap)
            if (root.optJSONArray(KEY_USERS) == null) root.put(KEY_USERS, JSONArray())
            saveRoot(root)
        }
    }

    fun removeStream(userId: String, streamUrl: String) {
        val url = streamUrl.trim()
        if (url.isEmpty()) return
        synchronized(this) {
            val root = parseRoot() ?: return
            val favMap = root.optJSONObject(KEY_FAVORITES) ?: return
            val arr = favMap.optJSONArray(userId) ?: return
            val next = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("u", "").trim() != url) next.put(o)
            }
            favMap.put(userId, next)
            root.put(KEY_FAVORITES, favMap)
            saveRoot(root)
        }
    }

    private fun parseRoot(): JSONObject? {
        val raw = prefs.getString(KEY_ROOT, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun saveRoot(root: JSONObject) {
        prefs.edit().putString(KEY_ROOT, root.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "dirthead_favorites"
        private const val KEY_ROOT = "root_v1"
        private const val KEY_USERS = "users"
        private const val KEY_FAVORITES = "favorites"
        const val FAVORITES_GROUP: String = "Favorites"
    }
}
