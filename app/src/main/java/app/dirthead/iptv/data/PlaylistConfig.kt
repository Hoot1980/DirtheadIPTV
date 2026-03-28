package app.dirthead.iptv.data

object PlaylistConfig {
    /**
     * Dropbox `list.txt` (`dl=1`): plain text, one playlist pointer URL per line (or `Label | URL`).
     * Each URL is fetched like the former single embedded pointer; see [PlaylistPointerClassifier].
     */
    const val PlaylistListCatalogUrl: String =
        "https://www.dropbox.com/scl/fi/gyju6hxq3szhv82llgqn0/list.txt?rlkey=choat2dnf92cs0rn8wabrxsyi&dl=1"
}
