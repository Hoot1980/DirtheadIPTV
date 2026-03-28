package app.dirthead.iptv.data

/**
 * Decides how to interpret the downloaded pointer `.txt` from each catalog line URL in [PlaylistConfig.PlaylistListCatalogUrl]:
 * embedded M3U content, a Stalker / Ministra portal + MAC, or plain playlist URL(s).
 * Any Xtream `get.php?username=…&password=…` URL in the file is handled via [XtreamPointerResolver] and
 * [XtreamPlayerApi] so live, VOD, and series stay in separate catalogs; M3U is used when API load is unavailable.
 */
internal object PlaylistPointerClassifier {

    enum class Mode {
        /** The response body is M3U / M3U8 text (`#EXTM3U` / `#EXTINF`). */
        EMBEDDED_PLAYLIST,

        /** Portal URL with MAC (possibly after blank / comment lines). */
        STALKER_PORTAL,

        /** No embedded playlist; use HTTP(S) URL(s) in the file (nested fetch, Xtream, etc.). */
        PLAYLIST_URL,
    }

    data class Result(
        val mode: Mode,
        /** Set only when [mode] is [Mode.STALKER_PORTAL]. */
        val stalker: StalkerPointerParser.StalkerPointer? = null,
    )

    fun classify(pointerFileText: String): Result {
        val body = stripUtf8Bom(pointerFileText).trimEnd()
        if (isEmbeddedPlaylistContent(body)) {
            return Result(mode = Mode.EMBEDDED_PLAYLIST)
        }
        val stalker = StalkerPointerParser.parse(body)
        if (stalker != null) {
            return Result(mode = Mode.STALKER_PORTAL, stalker = stalker)
        }
        return Result(mode = Mode.PLAYLIST_URL)
    }

    /**
     * True only for real M3U markers on their own lines — avoids treating random text
     * (or HTML) that contains the substring `#EXTINF` as an embedded playlist, which would
     * skip Stalker detection entirely.
     */
    private fun isEmbeddedPlaylistContent(text: String): Boolean {
        val lines = text.lineSequence()
            .map { stripUtf8Bom(it).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return false
        val first = lines.first()
        if (first.startsWith("#EXTM3U", ignoreCase = true)) return true
        return lines.any { line ->
            line.startsWith("#EXTINF:", ignoreCase = true) ||
                line.startsWith("#EXTINF ", ignoreCase = true)
        }
    }

    internal fun stripUtf8Bom(s: String): String =
        if (s.startsWith("\uFEFF")) s.substring(1) else s
}
