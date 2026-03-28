package app.dirthead.iptv.ui.main

data class MainMenuItem(
    val id: String,
    val title: String,
    val subtitle: String,
)

object MainMenuItems {
    const val LiveTvId: String = "live_tv"
    const val FavoritesId: String = "favorites"
    const val RecentChannelsId: String = "recent_channels"
    const val MoviesId: String = "movies"
    const val SeriesId: String = "series"
    /** Opens from the gear button on the main screen (not listed in [all]). */
    const val SettingsId: String = "settings"
    const val CatchupId: String = "catchup"

    val all: List<MainMenuItem> = listOf(
        MainMenuItem(LiveTvId, "Live TV", "Watch Live Television"),
        MainMenuItem(RecentChannelsId, "Recent Channels", "View the Previous 10 Channels"),
        MainMenuItem(MoviesId, "Movies", "On-Demand Movies"),
        MainMenuItem(SeriesId, "Series", "TV Shows and Seasons"),
        MainMenuItem(FavoritesId, "Favorites", "Saved Channels"),
        MainMenuItem(CatchupId, "Catch-up", "Replay and time-shifted TV"),
    )

    fun byId(id: String): MainMenuItem? = when (id) {
        SettingsId -> MainMenuItem(SettingsId, "Settings", "Account and Playback Options")
        else -> all.find { it.id == id }
    }
}
