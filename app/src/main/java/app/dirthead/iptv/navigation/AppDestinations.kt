package app.dirthead.iptv.navigation

object AppDestinations {
    /** Query arg: when false (e.g. Change playlist), do not auto-load last saved pointer. */
    const val ARG_LOAD_AUTO_RESUME = "autoResume"
    const val Load = "load?$ARG_LOAD_AUTO_RESUME={$ARG_LOAD_AUTO_RESUME}"
    const val Main = "main"

    fun loadRoute(autoResume: Boolean) = "load?$ARG_LOAD_AUTO_RESUME=$autoResume"

    const val Detail = "detail/{itemId}"
    const val ARG_ITEM_ID = "itemId"

    const val RecentChannels = "recent_channels"

    const val FavoritesHub = "favorites"
    const val FavoritesList = "favorites_list/{userId}"
    const val ARG_FAVORITE_USER_ID = "userId"

    fun favoritesListRoute(userId: String) = "favorites_list/${LiveTvGroupNav.encode(userId)}"

    const val LiveTvGroups = "live_tv_groups"
    const val LiveTvGroupChannels = "live_tv_group_channels/{encodedGroup}"
    const val ARG_ENCODED_GROUP = "encodedGroup"
    const val ARG_ENCODED_SERIES_ID = "encodedSeriesId"
    const val ARG_ENCODED_STREAM_URL = "encodedStreamUrl"
    const val ARG_ENCODED_STREAM_TITLE = "encodedStreamTitle"

    const val Player = "player/{encodedStreamUrl}/{encodedStreamTitle}"

    fun detailRoute(itemId: String) = "detail/$itemId"

    fun liveTvGroupChannelsRoute(encodedGroup: String) =
        "live_tv_group_channels/$encodedGroup"

    const val MovieGroups = "movie_groups"
    const val MovieGroupItems = "movie_group_items/{encodedGroup}"

    const val SeriesGroups = "series_groups"
    const val SeriesTitles = "series_titles/{encodedGroup}"
    const val SeriesEpisodes = "series_episodes/{encodedSeriesId}"

    fun movieGroupItemsRoute(encodedGroup: String) = "movie_group_items/$encodedGroup"

    fun seriesTitlesRoute(encodedGroup: String) = "series_titles/$encodedGroup"

    fun seriesEpisodesRoute(encodedSeriesId: String) = "series_episodes/$encodedSeriesId"

    fun playerRoute(encodedStreamUrl: String, encodedStreamTitle: String) =
        "player/$encodedStreamUrl/$encodedStreamTitle"
}
