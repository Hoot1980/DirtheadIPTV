package app.dirthead.iptv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal object XtreamExpiryFetcher {

    /**
     * For playlist URLs like `http://host:port/.../get.php?username=...&password=...`,
     * calls the matching `player_api.php` and reads [user_info][exp_date] (Unix seconds).
     */
    fun fetchExpirationEpochSeconds(playlistUrl: String, client: OkHttpClient): Long? {
        val creds = XtreamCredentials.fromGetPhpUrl(playlistUrl) ?: return null
        val request = Request.Builder()
            .url(creds.playerApiBuilder().build())
            .header("User-Agent", "VLC/3.0.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseExpDate(body)
        }
    }

    private fun parseExpDate(json: String): Long? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val userInfo = root.optJSONObject("user_info") ?: return null
        if (!userInfo.has("exp_date")) return null
        return when (val raw = userInfo.get("exp_date")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }
}
