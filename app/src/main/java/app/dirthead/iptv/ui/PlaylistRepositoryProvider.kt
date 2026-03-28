package app.dirthead.iptv.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.dirthead.iptv.data.PlaylistRepository
import app.dirthead.iptv.data.PlaylistStream

val LocalPlaylistRepository = staticCompositionLocalOf<PlaylistRepository> {
    error("PlaylistRepository not provided")
}

/** Long-press on streams (Live TV, VOD, episodes, recent) adds to a favorite profile when set by [IptvNavHost]. */
val LocalAddStreamToFavorites = staticCompositionLocalOf<(PlaylistStream) -> Unit> {
    { }
}
