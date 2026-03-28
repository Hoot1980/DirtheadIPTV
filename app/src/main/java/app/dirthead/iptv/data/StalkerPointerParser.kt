package app.dirthead.iptv.data

import android.util.Log

/**
 * Parses the Dropbox / pointer `.txt` format: portal base URL on one line, MAC address below
 * (blank lines, `#` comments, and `MAC:` labels allowed in between).
 * Also accepts an optional `stalker` prefix on the portal line.
 *
 * Example:
 * ```
 * http://example.com:8080/c/
 * 00:1A:79:12:34:56
 * ```
 */
internal object StalkerPointerParser {

    private const val TAG = "StalkerPointer"

    private val MAC_WITH_SEP = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
    private val MAC_12_HEX = Regex("^[0-9A-Fa-f]{12}$")

    data class StalkerPointer(val portalBaseUrl: String, val mac: String)

    /**
     * Finds the first `http(s)` portal line, then the next MAC on a following line
     * (skipping empty lines and `#` comments). Skips Xtream `get.php?username=…&password=…` URLs.
     */
    fun parse(pointerFileText: String): StalkerPointer? {
        val lines = PlaylistPointerClassifier.stripUtf8Bom(pointerFileText)
            .lineSequence()
            .map { it.trim() }
            .toList()
        for (i in lines.indices) {
            var urlLine = lines[i]
            if (urlLine.isEmpty()) continue
            if (urlLine.startsWith("stalker", ignoreCase = true)) {
                urlLine = urlLine.removePrefix("stalker").trim()
                if (urlLine.startsWith(":")) urlLine = urlLine.removePrefix(":").trim()
            }
            if (!urlLine.startsWith("http://", ignoreCase = true) &&
                !urlLine.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            if (urlLine.contains("#EXTINF", ignoreCase = true)) continue
            if (looksLikeXtreamGetPhpUrl(urlLine)) continue
            var j = i + 1
            while (j < lines.size) {
                val raw = lines[j]
                if (raw.isEmpty()) {
                    j++
                    continue
                }
                if (raw.startsWith("#")) {
                    j++
                    continue
                }
                val macCandidate = extractMacFromLine(raw)
                if (macCandidate != null) {
                    Log.i(TAG, "Stalker pointer: portal=${normalizePortalBaseUrl(urlLine)} mac=$macCandidate")
                    return StalkerPointer(
                        portalBaseUrl = normalizePortalBaseUrl(urlLine),
                        mac = macCandidate,
                    )
                }
                if (raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true)
                ) {
                    break
                }
                j++
            }
        }
        return null
    }

    private fun extractMacFromLine(line: String): String? {
        var t = line.trim()
        if (t.startsWith("mac", ignoreCase = true)) {
            t = t.removePrefix("mac").trim()
            if (t.startsWith(":")) t = t.removePrefix(":").trim()
            if (t.startsWith("=")) t = t.removePrefix("=").trim()
        }
        return if (isMacLine(t)) t else null
    }

    private fun looksLikeXtreamGetPhpUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.contains("get.php")) return false
        return lower.contains("username=") && lower.contains("password=")
    }

    fun isMacLine(line: String): Boolean {
        val t = line.trim()
        return MAC_WITH_SEP.matches(t) || MAC_12_HEX.matches(t)
    }

    private fun normalizePortalBaseUrl(url: String): String {
        var s = url.trim().trimEnd('/')
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "http://$s"
        }
        return s.trimEnd('/') + "/"
    }

    private fun normalizeUrlForCompare(url: String): String = url.trim().trimEnd('/').lowercase()

    /**
     * Next `http(s)` line in the pointer file after a Stalker block, e.g. an Xtream `get.php` URL on line 3.
     */
    fun firstHttpUrlExcludingPortal(pointerFileText: String, portalBaseUrlToSkip: String?): String? {
        val skip = portalBaseUrlToSkip?.let { normalizeUrlForCompare(it) }
        for (line in pointerFileText.lineSequence().map { it.trim() }) {
            if (!line.startsWith("http://", ignoreCase = true) &&
                !line.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            if (skip != null && normalizeUrlForCompare(line) == skip) continue
            return line
        }
        return null
    }
}
