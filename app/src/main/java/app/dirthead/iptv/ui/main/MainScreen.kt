@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.dirthead.iptv.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.R
import app.dirthead.iptv.ui.common.focusWhiteRing
import app.dirthead.iptv.ui.common.tvRemoteLongSelectConsumesClick
import app.dirthead.iptv.ui.LocalPlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MainActionCardMinHeight = 96.dp

private const val MainMenuUnavailableAlpha = 0.42f

private const val MainMenuUnavailableSubtitle = "Option not provided by the playlist"

@Composable
fun MainScreen(
    onSelectItem: (itemId: String) -> Unit,
    onFavoritesLongPress: () -> Unit = {},
    onChangePlaylist: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val playlistRepository = LocalPlaylistRepository.current
    val offersMovies = playlistRepository.playlistOffersMovies()
    val offersSeries = playlistRepository.playlistOffersSeries()
    val offersCatchup = playlistRepository.playlistOffersCatchup()
    val scope = rememberCoroutineScope()
    var refreshBusy by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshNote) {
        val note = refreshNote ?: return@LaunchedEffect
        delay(4500)
        if (refreshNote == note) refreshNote = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 26.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.dirthead_logo),
                        contentDescription = "Dirthead IPTV",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .padding(bottom = 1.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        text = "Dirthead IPTV",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 30.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = playlistRepository.playlistExpirationDisplayText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    playlistRepository.stalkerPortalConnection()?.let { (portal, mac) ->
                        Text(
                            text = portal,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = mac,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    refreshNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (note.startsWith("Could not", ignoreCase = true) ||
                                note.contains("failed", ignoreCase = true)
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MainMenuStyleActionCard(
                            title = if (refreshBusy) "Refreshing…" else "Refresh playlist",
                            subtitle = "",
                            icon = Icons.Filled.Refresh,
                            onClick = {
                                if (refreshBusy) return@MainMenuStyleActionCard
                                scope.launch {
                                    refreshBusy = true
                                    refreshNote = null
                                    val result = withContext(Dispatchers.IO) {
                                        playlistRepository.loadVerifiedPlaylist(forceRefresh = true)
                                    }
                                    result.fold(
                                        onSuccess = {
                                            refreshNote = "Playlist refreshed"
                                            launch(Dispatchers.IO) {
                                                runCatching { playlistRepository.prefetchLogoArtwork() }
                                            }
                                        },
                                        onFailure = { e ->
                                            refreshNote = e.message ?: "Refresh failed"
                                        },
                                    )
                                    refreshBusy = false
                                }
                            },
                            enabled = !refreshBusy,
                            showProgress = refreshBusy,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        MainMenuStyleActionCard(
                            title = "Change playlist",
                            subtitle = "Pick another source from the playlist list",
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            onClick = onChangePlaylist,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        MainMenuStyleActionCard(
                            title = "Settings",
                            subtitle = "",
                            icon = Icons.Filled.Settings,
                            onClick = { onSelectItem(MainMenuItems.SettingsId) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            // Fixed-width right rail: categories share height so the column fits the screen without scrolling.
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 248.dp, max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MainMenuItems.all.forEach { item ->
                    val unavailable = when (item.id) {
                        MainMenuItems.MoviesId -> !offersMovies
                        MainMenuItems.SeriesId -> !offersSeries
                        MainMenuItems.CatchupId -> !offersCatchup
                        else -> false
                    }
                    val subtitleText =
                        if (unavailable) MainMenuUnavailableSubtitle else item.subtitle
                    val titleColor = if (unavailable) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = MainMenuUnavailableAlpha)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    val subtitleColor = if (unavailable) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = MainMenuUnavailableAlpha,
                        )
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val cardModifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .focusWhiteRing(CardDefaults.shape)
                        .then(
                            if (item.id == MainMenuItems.FavoritesId) {
                                Modifier
                                    .tvRemoteLongSelectConsumesClick(onLongSelect = onFavoritesLongPress)
                                    .combinedClickable(
                                        onClick = { onSelectItem(item.id) },
                                        onLongClick = onFavoritesLongPress,
                                    )
                            } else {
                                Modifier.clickable { onSelectItem(item.id) }
                            },
                        )
                    Card(
                        modifier = cardModifier,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = titleColor,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = subtitleColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainMenuStyleActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showProgress: Boolean = false,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .heightIn(min = MainActionCardMinHeight)
            .padding(vertical = 4.dp)
            .focusWhiteRing(CardDefaults.shape)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(30.dp),
                        tint = Color.White,
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                maxLines = 6,
                overflow = TextOverflow.Clip,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

