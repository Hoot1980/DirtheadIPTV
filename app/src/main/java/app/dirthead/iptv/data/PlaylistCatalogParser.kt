package app.dirthead.iptv.data

/**
 * Parses `list.txt`: one entry per line. Lines starting with `#` (after trim) are comments.
 * - `https://...` alone → label is a shortened URL.
 * - `My provider | https://...` → label + URL (pipe-separated).
 */
internal object PlaylistCatalogParser {

    fun parse(body: String): List<PlaylistCatalogEntry> {
        val byUrl = LinkedHashMap<String, PlaylistCatalogEntry>()
        for (raw in body.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val entry = parseLine(line) ?: continue
            byUrl[entry.pointerUrl] = entry
        }
        return byUrl.values.toList()
    }

    private fun parseLine(line: String): PlaylistCatalogEntry? {
        val pipe = line.indexOf('|')
        if (pipe >= 0) {
            val labelPart = line.substring(0, pipe).trim()
            val urlPart = line.substring(pipe + 1).trim()
            if (urlPart.startsWith("http://", ignoreCase = true) ||
                urlPart.startsWith("https://", ignoreCase = true)
            ) {
                val label = labelPart.ifEmpty { shortenForLabel(urlPart) }
                return PlaylistCatalogEntry(label = label, pointerUrl = urlPart)
            }
        }
        if (line.startsWith("http://", ignoreCase = true) ||
            line.startsWith("https://", ignoreCase = true)
        ) {
            val url = line.trim()
            return PlaylistCatalogEntry(label = shortenForLabel(url), pointerUrl = url)
        }
        return null
    }

    private fun shortenForLabel(url: String): String {
        val u = url.trim()
        if (u.length <= 52) return u
        return u.take(48).trimEnd() + "…"
    }
}
