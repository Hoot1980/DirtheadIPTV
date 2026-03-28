package app.dirthead.iptv.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.defaultPort
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Credentials parsed from an Xtream-style playlist URL
 * (`.../get.php?username=...&password=...`).
 */
data class XtreamCredentials(
    val originUrl: HttpUrl,
    val username: String,
    val password: String,
) {
    /**
     * Network base for logging: `scheme://host[:port]` (default port omitted), matching the server
     * hosting `get.php` / `player_api.php`.
     */
    fun networkBaseUrlForLogging(): String {
        val scheme = originUrl.scheme
        val host = originUrl.host
        val port = originUrl.port
        val defaultP = defaultPort(scheme)
        val portPart =
            if (port != -1 && port != defaultP) ":$port" else ""
        return "$scheme://$host$portPart"
    }

    /** Full `player_api.php` URL with username/password (no `action`); for logging only. */
    fun playerApiUrlForLogging(): String = playerApiBuilder().build().toString()

    /**
     * Live / timeshift stream path suffix from the original `get.php` query `output` (e.g. `ts`, `m3u_plus` → ts, `hls`/`m3u8` → m3u8).
     */
    fun liveStreamPathExtension(): String {
        val out = originUrl.queryParameter("output")?.trim()?.lowercase().orEmpty()
        return when {
            out.contains("m3u8") || out.contains("hls") -> "m3u8"
            else -> "ts"
        }
    }

    fun playerApiBuilder(): HttpUrl.Builder {
        val path = originUrl.encodedPath
        val suffix = "/get.php"
        if (!path.endsWith(suffix, ignoreCase = true)) {
            error("Not a get.php URL")
        }
        val prefix = path.dropLast(suffix.length)
        val apiPath = if (prefix.isEmpty()) "/player_api.php" else "$prefix/player_api.php"
        return originUrl.newBuilder()
            .encodedPath(apiPath)
            .query(null)
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
    }

    /** Xtream XMLTV export (same path prefix as [playerApiBuilder]). */
    fun xmlTvUrl(): HttpUrl {
        val path = originUrl.encodedPath
        val suffix = "/get.php"
        if (!path.endsWith(suffix, ignoreCase = true)) {
            error("Not a get.php URL")
        }
        val prefix = path.dropLast(suffix.length)
        val xmlPath = if (prefix.isEmpty()) "/xmltv.php" else "$prefix/xmltv.php"
        return originUrl.newBuilder()
            .encodedPath(xmlPath)
            .query(null)
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
            .build()
    }

    companion object {
        /**
         * Parses an Xtream-style playlist URL. Path must end with `get.php` (any folder prefix).
         * Accepts `username`/`password` or common aliases `user`/`pass`.
         */
        fun fromGetPhpUrl(url: String): XtreamCredentials? {
            val httpUrl = url.toHttpUrlOrNull() ?: return null
            val encodedPath = httpUrl.encodedPath
            if (!encodedPath.endsWith("/get.php", ignoreCase = true)) return null
            val username = httpUrl.queryParameter("username")
                ?: httpUrl.queryParameter("user")
                ?: return null
            val password = httpUrl.queryParameter("password")
                ?: httpUrl.queryParameter("pass")
                ?: return null
            return XtreamCredentials(httpUrl, username, password)
        }
    }
}
