package app.dirthead.iptv.data

data class PlaylistStream(
    val displayName: String,
    val streamUrl: String,
    /** From M3U `group-title`; defaults to [DEFAULT_GROUP] when missing. */
    val groupTitle: String = DEFAULT_GROUP,
    /** Xtream `stream_id` for [player_api.php] `get_short_epg`, when known. */
    val epgStreamId: String? = null,
    /** XMLTV / M3U `tvg-id` for matching `<programme channel="...">`. */
    val tvgId: String? = null,
    /** Channel logo or movie/episode poster URL when the provider supplies one. */
    val logoUrl: String? = null,
    /** VOD / movie synopsis when the provider supplies one (e.g. Xtream plot / description). */
    val description: String? = null,
    /** From Xtream `get_live_streams` when `tv_archive == 1`. */
    val tvArchive: Boolean = false,
    /**
     * From Xtream `tv_archive_duration` (typically **days** of replay window); null if unknown.
     */
    val tvArchiveDuration: Int? = null,
) {
    companion object {
        const val DEFAULT_GROUP: String = "Other"
    }
}
