package app.dirthead.iptv.data

/**
 * One line from [PlaylistConfig.PlaylistListCatalogUrl] (`list.txt`): a display label and the Dropbox
 * or HTTP URL of the pointer file to fetch (same formats as the former single embedded pointer).
 */
data class PlaylistCatalogEntry(
    val label: String,
    val pointerUrl: String,
)
