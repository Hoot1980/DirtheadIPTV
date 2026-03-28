package app.dirthead.iptv.data

/**
 * Finds Xtream Codes `get.php?…username=…&password=…` URLs inside arbitrary pointer/M3U text so we can load
 * live / VOD / series via [XtreamPlayerApi] instead of flattening everything as a single M3U.
 */
internal object XtreamPointerResolver {

    private val HTTP_URL_REGEX = Regex("""https?://[^\s<>"{}|\\^`]+""", RegexOption.IGNORE_CASE)

    /** True when [text] is almost certainly an M3U document (e.g. body of a `get.php` request). */
    fun bodyLooksLikeFetchedM3uPlaylist(text: String): Boolean {
        val t = PlaylistPointerClassifier.stripUtf8Bom(text).trimStart()
        if (t.startsWith("#EXTM3U", ignoreCase = true)) return true
        var extInf = 0
        for (line in t.lineSequence().take(500)) {
            val s = line.trim()
            if (s.startsWith("#EXTINF:", ignoreCase = true) || s.startsWith("#EXTINF ", ignoreCase = true)) {
                if (++extInf >= 20) return true
            }
        }
        return false
    }

    /**
     * First lines / bytes only — for large M3U bodies, scanning the whole file for URLs is pathologically slow.
     */
    private const val POINTER_HEAD_MAX_LINES = 160

    private fun pointerHeadForXtreamScan(text: String): String =
        text.lineSequence().take(POINTER_HEAD_MAX_LINES).joinToString("\n")

    /**
     * All distinct http(s) substrings in [text], stripped of common trailing punctuation,
     * in first-seen order.
     */
    fun extractHttpUrls(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (match in HTTP_URL_REGEX.findAll(text)) {
            val scrubbed = scrubTrailingJunk(match.value)
            if (scrubbed.isNotEmpty() && seen.add(scrubbed)) {
                out.add(scrubbed)
            }
        }
        return out
    }

    /**
     * [XtreamCredentials] for each URL in the document that parses as a get.php playlist link,
     * in document order, de-duplicated by full URL.
     */
    fun xtreamCredentialsInDocumentOrder(text: String): List<XtreamCredentials> {
        val out = ArrayList<XtreamCredentials>()
        val seenKeys = HashSet<String>()
        for (raw in extractHttpUrls(text)) {
            val creds = XtreamCredentials.fromGetPhpUrl(raw) ?: continue
            val key = creds.originUrl.toString()
            if (seenKeys.add(key)) {
                out.add(creds)
            }
        }
        return out
    }

    /**
     * Credentials to try when loading a playlist: the catalog URL if it is already `get.php`, then
     * URLs from the fetched body (full pointer file, or only a short head when the body is a huge M3U).
     */
    fun xtreamCredentialsForPointerAndBody(pointerUrl: String, fetchedBody: String): List<XtreamCredentials> {
        val out = ArrayList<XtreamCredentials>()
        val seen = HashSet<String>()
        fun add(creds: XtreamCredentials?) {
            if (creds == null) return
            val k = creds.originUrl.toString()
            if (seen.add(k)) out.add(creds)
        }
        add(XtreamCredentials.fromGetPhpUrl(pointerUrl.trim()))
        val scanTarget =
            if (bodyLooksLikeFetchedM3uPlaylist(fetchedBody)) {
                pointerHeadForXtreamScan(fetchedBody)
            } else {
                fetchedBody
            }
        for (c in xtreamCredentialsInDocumentOrder(scanTarget)) {
            add(c)
        }
        return out
    }

    private fun scrubTrailingJunk(s: String): String {
        return s.trim().trimEnd(',', '.', ';', ')', ']', '"', '\'', '>', '}', '`')
    }
}
