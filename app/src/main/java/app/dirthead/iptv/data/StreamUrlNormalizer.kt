package app.dirthead.iptv.data

import android.net.Uri

/**
 * ExoPlayer treats URIs without a network scheme as **local file** paths → ENOENT.
 * Normalizes playlist / API URLs so playback uses http(s) [Uri]s.
 */
object StreamUrlNormalizer {

    /** @return null only for empty input or bare filesystem-style paths (`/foo`). */
    fun toPlaybackUri(raw: String): Uri? {
        val t = raw.trim().ifEmpty { return null }
        val lower = t.lowercase()
        when {
            lower.startsWith("http://") || lower.startsWith("https://") -> return Uri.parse(t)
            lower.startsWith("rtsp://") -> return Uri.parse(t)
            t.startsWith("//") -> return Uri.parse("http:$t")
            t.startsWith("/") -> return null
            t.contains("://") -> return Uri.parse(t)
            else -> return Uri.parse("http://$t")
        }
    }
}
