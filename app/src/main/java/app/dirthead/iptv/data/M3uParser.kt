package app.dirthead.iptv.data

object M3uParser {
    private val tvgNameRegex = Regex("""tvg-name="([^"]*)"""")
    private val tvgIdRegex = Regex("""tvg-id="([^"]*)"""")
    private val tvgLogoRegex = Regex("""tvg-logo="([^"]*)"""")
    private val tvgLogoSingleRegex = Regex("""tvg-logo='([^']*)'""")
    private val groupTitleRegex = Regex("""group-title="([^"]*)"""")
    private val urlTvgRegex = Regex("""url-tvg\s*=\s*"([^"]+)"""")
    private val urlTvgSingleRegex = Regex("""url-tvg\s*=\s*'([^']+)'""")
    private val xtreamLiveStreamIdRegex =
        Regex("""/live/[^/]+/[^/]+/(\d+)(?:\.[a-zA-Z0-9]+)?(?:\?.*)?$""")

    /** XMLTV URL from `#EXTM3U` line, if present. */
    fun extractUrlTvg(m3uContent: String): String? {
        for (line in m3uContent.lineSequence().take(32)) {
            val l = line.trim()
            if (!l.startsWith("#EXTM3U", ignoreCase = true)) continue
            urlTvgRegex.find(l)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            urlTvgSingleRegex.find(l)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    fun parse(content: String): List<PlaylistStream> {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<PlaylistStream>()
        var pendingTitle: String? = null
        var pendingGroup: String? = null
        var pendingTvgId: String? = null
        var pendingLogo: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> {
                    pendingTitle = extractTitle(line)
                    pendingGroup = extractGroupTitle(line)
                    pendingTvgId = extractTvgId(line)
                    pendingLogo = extractTvgLogo(line)
                }
                line.startsWith("#") -> Unit
                line.startsWith("http://") || line.startsWith("https://") -> {
                    addStreamLine(line, pendingTitle, pendingGroup, pendingTvgId, pendingLogo, result)
                    pendingTitle = null
                    pendingGroup = null
                    pendingTvgId = null
                    pendingLogo = null
                }
                line.startsWith("//") -> {
                    addStreamLine("http:$line", pendingTitle, pendingGroup, pendingTvgId, pendingLogo, result)
                    pendingTitle = null
                    pendingGroup = null
                    pendingTvgId = null
                    pendingLogo = null
                }
            }
        }
        return result
    }

    private fun addStreamLine(
        line: String,
        pendingTitle: String?,
        pendingGroup: String?,
        pendingTvgId: String?,
        pendingLogo: String?,
        result: MutableList<PlaylistStream>,
    ) {
        val name = pendingTitle?.takeIf { it.isNotBlank() }
            ?: line.substringAfterLast('/').substringBefore('?').ifBlank { line }
        val group = pendingGroup?.takeIf { it.isNotBlank() } ?: PlaylistStream.DEFAULT_GROUP
        val epgId = xtreamLiveStreamIdRegex.find(line)?.groupValues?.getOrNull(1)
        val tvgId = pendingTvgId?.takeIf { it.isNotBlank() }
        val logo = pendingLogo?.takeIf { it.isNotBlank() }
        result.add(
            PlaylistStream(
                displayName = name,
                streamUrl = line,
                groupTitle = group,
                epgStreamId = epgId,
                tvgId = tvgId,
                logoUrl = logo,
            ),
        )
    }

    private fun extractGroupTitle(extInf: String): String? =
        groupTitleRegex.find(extInf)?.groupValues?.getOrNull(1)?.trim()

    private fun extractTvgId(extInf: String): String? =
        tvgIdRegex.find(extInf)?.groupValues?.getOrNull(1)?.trim()

    private fun extractTvgLogo(extInf: String): String? {
        tvgLogoRegex.find(extInf)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return tvgLogoSingleRegex.find(extInf)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractTitle(extInf: String): String {
        val quoted = tvgNameRegex.find(extInf)?.groupValues?.getOrNull(1)?.trim()
        if (!quoted.isNullOrBlank()) return quoted
        val comma = extInf.lastIndexOf(',')
        if (comma >= 0) {
            val tail = extInf.substring(comma + 1).trim()
            if (tail.isNotBlank()) return tail
        }
        return "Unknown"
    }
}
