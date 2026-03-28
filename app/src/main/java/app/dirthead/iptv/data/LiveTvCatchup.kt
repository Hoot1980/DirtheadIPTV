package app.dirthead.iptv.data

/**
 * Virtual Live TV category for Xtream catch-up / archive (`tv_archive == 1`).
 * Streams stay in their real `group-title`; [PlaylistRepository.streamsInLiveTvGroup] maps this title
 * to all archive-enabled channels.
 */
object LiveTvCatchup {
    const val GROUP_TITLE: String = "Catch-up"
    const val CATEGORY_ID: String = "catchup"
}
