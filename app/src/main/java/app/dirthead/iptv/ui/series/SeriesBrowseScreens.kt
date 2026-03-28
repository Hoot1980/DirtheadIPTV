@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.dirthead.iptv.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.data.PlaylistStream
import app.dirthead.iptv.data.SeriesSummary
import app.dirthead.iptv.ui.LocalAddStreamToFavorites
import app.dirthead.iptv.ui.LocalPlaylistRepository
import app.dirthead.iptv.ui.common.OmdbSynopsisSection
import app.dirthead.iptv.ui.common.StreamLogoImage
import app.dirthead.iptv.ui.common.omdbSynopsisPaletteMaterial
import app.dirthead.iptv.ui.common.focusWhiteRing
import app.dirthead.iptv.ui.common.tvRemoteLongSelectConsumesClick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesGroupsScreen(
    onBack: () -> Unit,
    onSelectGroup: (groupTitle: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val groups = repository.seriesGroupsSorted()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Series") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No series categories were found. Use an Xtream account with series enabled.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = "${groups.size} categories",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(groups, key = { it }) { groupTitle ->
                        val cardShape = CardDefaults.shape
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusWhiteRing(cardShape)
                                .clickable { onSelectGroup(groupTitle) },
                            shape = cardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Text(
                                text = groupTitle,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesTitlesScreen(
    groupTitle: String,
    onBack: () -> Unit,
    onSelectSeries: (SeriesSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val seriesList = repository.seriesInGroup(groupTitle)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(groupTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (seriesList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No series in this category.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = "${seriesList.size} series",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(seriesList, key = { it.seriesId }) { show ->
                        val cardShape = CardDefaults.shape
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusWhiteRing(cardShape)
                                .clickable { onSelectSeries(show) },
                            shape = cardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StreamLogoImage(
                                    imageUrl = show.coverUrl,
                                    contentDescription = show.name,
                                    size = 52.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = show.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface EpisodesLoadState {
    data object Loading : EpisodesLoadState
    data class Ready(val episodes: List<PlaylistStream>) : EpisodesLoadState
    data class Error(val message: String) : EpisodesLoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesEpisodesScreen(
    seriesId: String,
    onBack: () -> Unit,
    onPlayStream: (PlaylistStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val barTitle = remember(seriesId) { repository.seriesTitleForId(seriesId) }
    var state by remember { mutableStateOf<EpisodesLoadState>(EpisodesLoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(seriesId, attempt) {
        state = EpisodesLoadState.Loading
        state = repository.loadSeriesEpisodes(seriesId).fold(
            onSuccess = { EpisodesLoadState.Ready(it) },
            onFailure = { EpisodesLoadState.Error(it.message ?: "Could not load episodes") },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(barTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            EpisodesLoadState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is EpisodesLoadState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            ) {
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { attempt++ }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Retry")
                }
            }
            is EpisodesLoadState.Ready -> if (s.episodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No episodes returned for this series.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                var highlightedEpisode by remember { mutableStateOf<PlaylistStream?>(null) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = "${s.episodes.size} episodes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.episodes, key = { it.streamUrl }) { ep ->
                            EpisodeStreamCard(
                                stream = ep,
                                onPlayStream = onPlayStream,
                                onFocusedChange = { focused ->
                                    if (focused) {
                                        highlightedEpisode = ep
                                    } else if (highlightedEpisode == ep) {
                                        highlightedEpisode = null
                                    }
                                },
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    SeriesEpisodeDescriptionStrip(
                        highlighted = highlightedEpisode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesEpisodeDescriptionStrip(
    highlighted: PlaylistStream?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        OmdbSynopsisSection(
            lookupTitle = highlighted?.displayName.orEmpty(),
            providerDescription = highlighted?.description.orEmpty(),
            effectKey = highlighted?.streamUrl,
            idle = {
                Text(
                    text = "Focus an episode to see its description.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            showTitleAbove = true,
            palette = omdbSynopsisPaletteMaterial(),
            titleStyle = MaterialTheme.typography.titleSmall,
            bodyStyle = MaterialTheme.typography.bodyMedium,
            smallStyle = MaterialTheme.typography.bodySmall,
            labelStyle = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EpisodeStreamCard(
    stream: PlaylistStream,
    onPlayStream: (PlaylistStream) -> Unit,
    onFocusedChange: (Boolean) -> Unit = {},
) {
    val addToFavorites = LocalAddStreamToFavorites.current
    val cardShape = CardDefaults.shape
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusedChange(it.isFocused) }
            .focusWhiteRing(cardShape)
            .tvRemoteLongSelectConsumesClick { addToFavorites(stream) }
            .combinedClickable(
                onClick = { onPlayStream(stream) },
                onLongClick = { addToFavorites(stream) },
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StreamLogoImage(
                imageUrl = stream.logoUrl,
                contentDescription = stream.displayName,
                size = 52.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stream.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
