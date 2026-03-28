package app.dirthead.iptv.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.dirthead.iptv.data.LiveTvCatchup
import app.dirthead.iptv.data.PlaylistStream
import app.dirthead.iptv.ui.LocalAddStreamToFavorites
import app.dirthead.iptv.ui.LocalPlaylistRepository
import app.dirthead.iptv.ui.common.ScreenCornerClock
import app.dirthead.iptv.ui.detail.DetailScreen
import app.dirthead.iptv.ui.settings.SettingsScreen
import app.dirthead.iptv.ui.livetv.LiveTvGroupChannelsScreen
import app.dirthead.iptv.ui.livetv.LiveTvGroupsScreen
import app.dirthead.iptv.ui.load.LoadScreen
import app.dirthead.iptv.ui.main.AddStreamToFavoritesDialog
import app.dirthead.iptv.ui.main.FavoritesHubScreen
import app.dirthead.iptv.ui.main.FavoritesListScreen
import app.dirthead.iptv.ui.main.FavoritesUsersManageDialog
import app.dirthead.iptv.ui.main.MainMenuItems
import app.dirthead.iptv.ui.main.MainScreen
import app.dirthead.iptv.ui.main.RecentChannelsScreen
import app.dirthead.iptv.ui.player.PlayerScreen
import app.dirthead.iptv.ui.series.SeriesEpisodesScreen
import app.dirthead.iptv.ui.series.SeriesGroupsScreen
import app.dirthead.iptv.ui.series.SeriesTitlesScreen
import app.dirthead.iptv.ui.vod.MovieGroupItemsScreen
import app.dirthead.iptv.ui.vod.MovieGroupsScreen

@Composable
fun IptvNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        val repository = LocalPlaylistRepository.current
        val activity = LocalContext.current as ComponentActivity
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val atAppRoot =
            currentRoute == AppDestinations.Main || currentRoute?.startsWith("load?") == true
        var showExitConfirm by remember { mutableStateOf(false) }
        BackHandler(enabled = atAppRoot) {
            showExitConfirm = true
        }
        var showFavoritesManage by remember { mutableStateOf(false) }
        var favoritesManageTick by remember { mutableIntStateOf(0) }
        var addFavoriteStream by remember { mutableStateOf<PlaylistStream?>(null) }
        val addStreamToFavorites: (PlaylistStream) -> Unit = { addFavoriteStream = it }
        val openPlayerWithHistory: (PlaylistStream) -> Unit = { stream ->
            repository.recordRecentChannel(stream)
            navController.navigate(
                AppDestinations.playerRoute(
                    LiveTvGroupNav.encode(stream.streamUrl),
                    LiveTvGroupNav.encode(stream.displayName),
                ),
            )
        }
        CompositionLocalProvider(LocalAddStreamToFavorites provides addStreamToFavorites) {
        NavHost(
            navController = navController,
            startDestination = AppDestinations.loadRoute(true),
            modifier = Modifier.fillMaxSize(),
        ) {
        composable(
            route = AppDestinations.Load,
            arguments = listOf(
                navArgument(AppDestinations.ARG_LOAD_AUTO_RESUME) {
                    type = NavType.BoolType
                    defaultValue = true
                },
            ),
        ) { backStackEntry ->
            val autoResumeLastSelected =
                backStackEntry.arguments?.getBoolean(AppDestinations.ARG_LOAD_AUTO_RESUME) ?: true
            LoadScreen(
                autoResumeLastSelected = autoResumeLastSelected,
                onPlaylistReady = {
                    navController.navigate(AppDestinations.Main) {
                        popUpTo(AppDestinations.Load) { inclusive = true }
                    }
                },
            )
        }
        composable(AppDestinations.Main) {
            MainScreen(
                onFavoritesLongPress = { showFavoritesManage = true },
                onChangePlaylist = {
                    navController.navigate(AppDestinations.loadRoute(false)) {
                        popUpTo(AppDestinations.Main) { inclusive = true }
                    }
                },
                onSelectItem = { itemId ->
                    when (itemId) {
                        MainMenuItems.LiveTvId -> navController.navigate(AppDestinations.LiveTvGroups)
                        MainMenuItems.CatchupId -> navController.navigate(
                            AppDestinations.liveTvGroupChannelsRoute(
                                LiveTvGroupNav.encode(LiveTvCatchup.GROUP_TITLE),
                            ),
                        )
                        MainMenuItems.RecentChannelsId -> navController.navigate(AppDestinations.RecentChannels)
                        MainMenuItems.MoviesId -> navController.navigate(AppDestinations.MovieGroups)
                        MainMenuItems.SeriesId -> navController.navigate(AppDestinations.SeriesGroups)
                        MainMenuItems.FavoritesId -> {
                            val users = repository.favoriteUsers()
                            when (users.size) {
                                0 -> navController.navigate(AppDestinations.FavoritesHub)
                                1 -> navController.navigate(
                                    AppDestinations.favoritesListRoute(users.first().id),
                                )
                                else -> navController.navigate(AppDestinations.FavoritesHub)
                            }
                        }
                        else -> navController.navigate(AppDestinations.detailRoute(itemId))
                    }
                },
            )
        }
        composable(AppDestinations.FavoritesHub) {
            FavoritesHubScreen(
                onBack = { navController.popBackStack() },
                onSelectUser = { user ->
                    navController.navigate(AppDestinations.favoritesListRoute(user.id))
                },
            )
        }
        composable(
            route = AppDestinations.FavoritesList,
            arguments = listOf(
                navArgument(AppDestinations.ARG_FAVORITE_USER_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(AppDestinations.ARG_FAVORITE_USER_ID)
            val userId = encoded?.let(LiveTvGroupNav::decode)?.trim().orEmpty()
            if (userId.isEmpty()) {
                navController.popBackStack()
                return@composable
            }
            val profileTitle = repository.favoriteUsers()
                .find { it.id == userId }
                ?.displayName
                ?: "Favorites"
            FavoritesListScreen(
                userId = userId,
                profileTitle = profileTitle,
                onBack = { navController.popBackStack() },
                onPlayStream = openPlayerWithHistory,
            )
        }
        composable(AppDestinations.RecentChannels) {
            RecentChannelsScreen(
                onBack = { navController.popBackStack() },
                onPlayStream = openPlayerWithHistory,
            )
        }
        composable(AppDestinations.LiveTvGroups) {
            LiveTvGroupsScreen(
                onBack = { navController.popBackStack() },
                onSelectGroup = { groupTitle ->
                    val encoded = LiveTvGroupNav.encode(groupTitle)
                    navController.navigate(AppDestinations.liveTvGroupChannelsRoute(encoded))
                },
                onPlayStream = openPlayerWithHistory,
            )
        }
        composable(
            route = AppDestinations.LiveTvGroupChannels,
            arguments = listOf(
                navArgument(AppDestinations.ARG_ENCODED_GROUP) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(AppDestinations.ARG_ENCODED_GROUP)
            val groupTitle = encoded?.let(LiveTvGroupNav::decode)
            if (groupTitle.isNullOrBlank()) {
                navController.popBackStack()
                return@composable
            }
            LiveTvGroupChannelsScreen(
                groupTitle = groupTitle,
                onBack = { navController.popBackStack() },
                onPlayStream = openPlayerWithHistory,
            )
        }
        composable(AppDestinations.MovieGroups) {
            MovieGroupsScreen(
                onBack = { navController.popBackStack() },
                onSelectGroup = { groupTitle ->
                    navController.navigate(AppDestinations.movieGroupItemsRoute(LiveTvGroupNav.encode(groupTitle)))
                },
            )
        }
        composable(
            route = AppDestinations.MovieGroupItems,
            arguments = listOf(
                navArgument(AppDestinations.ARG_ENCODED_GROUP) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(AppDestinations.ARG_ENCODED_GROUP)
            val groupTitle = encoded?.let(LiveTvGroupNav::decode)
            if (groupTitle.isNullOrBlank()) {
                navController.popBackStack()
                return@composable
            }
            MovieGroupItemsScreen(
                groupTitle = groupTitle,
                onBack = { navController.popBackStack() },
                onPlayStream = openPlayerWithHistory,
            )
        }
        composable(AppDestinations.SeriesGroups) {
            SeriesGroupsScreen(
                onBack = { navController.popBackStack() },
                onSelectGroup = { groupTitle ->
                    navController.navigate(AppDestinations.seriesTitlesRoute(LiveTvGroupNav.encode(groupTitle)))
                },
            )
        }
        composable(
            route = AppDestinations.SeriesTitles,
            arguments = listOf(
                navArgument(AppDestinations.ARG_ENCODED_GROUP) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(AppDestinations.ARG_ENCODED_GROUP)
            val groupTitle = encoded?.let(LiveTvGroupNav::decode)
            if (groupTitle.isNullOrBlank()) {
                navController.popBackStack()
                return@composable
            }
            SeriesTitlesScreen(
                groupTitle = groupTitle,
                onBack = { navController.popBackStack() },
                onSelectSeries = { show ->
                    navController.navigate(
                        AppDestinations.seriesEpisodesRoute(LiveTvGroupNav.encode(show.seriesId)),
                    )
                },
            )
        }
        composable(
            route = AppDestinations.SeriesEpisodes,
            arguments = listOf(
                navArgument(AppDestinations.ARG_ENCODED_SERIES_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(AppDestinations.ARG_ENCODED_SERIES_ID)
            val seriesId = encoded?.let(LiveTvGroupNav::decode)
            if (seriesId.isNullOrBlank()) {
                navController.popBackStack()
                return@composable
            }
            SeriesEpisodesScreen(
                seriesId = seriesId,
                onBack = { navController.popBackStack() },
                onPlayStream = openPlayerWithHistory,
            )
        }
        composable(
            route = AppDestinations.Player,
            arguments = listOf(
                navArgument(AppDestinations.ARG_ENCODED_STREAM_URL) { type = NavType.StringType },
                navArgument(AppDestinations.ARG_ENCODED_STREAM_TITLE) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val urlEnc = backStackEntry.arguments?.getString(AppDestinations.ARG_ENCODED_STREAM_URL)
            val titleEnc = backStackEntry.arguments?.getString(AppDestinations.ARG_ENCODED_STREAM_TITLE)
            val streamUrl = urlEnc?.let(LiveTvGroupNav::decode)?.trim()
            val streamTitle = titleEnc?.let(LiveTvGroupNav::decode).orEmpty()
            if (streamUrl.isNullOrEmpty()) {
                navController.popBackStack()
                return@composable
            }
            PlayerScreen(
                streamUrl = streamUrl,
                title = streamTitle,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = AppDestinations.Detail,
            arguments = listOf(
                navArgument(AppDestinations.ARG_ITEM_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString(AppDestinations.ARG_ITEM_ID)
            val item = itemId?.let(MainMenuItems::byId)
            if (item == null) {
                navController.popBackStack()
                return@composable
            }
            if (item.id == MainMenuItems.SettingsId) {
                SettingsScreen(
                    title = item.title,
                    subtitle = item.subtitle,
                    onBack = { navController.popBackStack() },
                    onChangePlaylist = {
                        navController.navigate(AppDestinations.loadRoute(false)) {
                            popUpTo(AppDestinations.Main) { inclusive = true }
                        }
                    },
                    onCacheCleared = {
                        navController.navigate(AppDestinations.loadRoute(false)) {
                            popUpTo(AppDestinations.Main) { inclusive = true }
                        }
                    },
                )
            } else {
                DetailScreen(
                    title = item.title,
                    subtitle = item.subtitle,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        }
        }
        if (showFavoritesManage) {
            val manageUsers = remember(favoritesManageTick) { repository.favoriteUsers() }
            FavoritesUsersManageDialog(
                users = manageUsers,
                onDismiss = { showFavoritesManage = false },
                onCreateUser = { name ->
                    repository.createFavoriteUser(name)
                    favoritesManageTick++
                },
                onDeleteUser = { id ->
                    repository.deleteFavoriteUser(id)
                    favoritesManageTick++
                },
            )
        }
        addFavoriteStream?.let { stream ->
            val pickUsers = remember(addFavoriteStream, favoritesManageTick) {
                repository.favoriteUsers()
            }
            AddStreamToFavoritesDialog(
                stream = stream,
                users = pickUsers,
                onDismiss = { addFavoriteStream = null },
                onChoseUser = { user ->
                    repository.addFavoriteForUser(user.id, stream)
                    addFavoriteStream = null
                },
            )
        }
        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("Close app?") },
                text = { Text("Do you want to close the app?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitConfirm = false
                            activity.finishAffinity()
                        },
                    ) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirm = false }) { Text("No") }
                },
            )
        }
        val onPlayerScreen = currentRoute?.startsWith("player/") == true
        if (!onPlayerScreen) {
            ScreenCornerClock(Modifier.align(Alignment.TopEnd))
        }
    }
}
