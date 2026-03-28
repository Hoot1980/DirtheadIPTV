@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.dirthead.iptv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.data.PlaylistStream
import app.dirthead.iptv.ui.LocalAddStreamToFavorites
import app.dirthead.iptv.ui.LocalPlaylistRepository
import app.dirthead.iptv.ui.common.OmdbSynopsisSection
import app.dirthead.iptv.ui.common.omdbSynopsisPaletteMaterial
import app.dirthead.iptv.ui.common.StreamLogoImage
import app.dirthead.iptv.ui.common.focusWhiteRing
import app.dirthead.iptv.ui.common.tvRemoteLongSelectConsumesClick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieGroupsScreen(
    onBack: () -> Unit,
    onSelectGroup: (groupTitle: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val groups = repository.movieGroupsSorted()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Movies") },
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
                    text = "No movie categories were found. Use an Xtream account with VOD enabled.",
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
fun MovieGroupItemsScreen(
    groupTitle: String,
    onBack: () -> Unit,
    onPlayStream: (PlaylistStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val movies = repository.moviesInGroup(groupTitle)

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
        if (movies.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No movies in this category.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            var highlightedMovie by remember { mutableStateOf<PlaylistStream?>(null) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = "${movies.size} titles",
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
                    items(movies, key = { it.streamUrl }) { stream ->
                        MovieStreamCard(
                            stream = stream,
                            onPlayStream = onPlayStream,
                            onFocusedChange = { focused ->
                                if (focused) {
                                    highlightedMovie = stream
                                } else if (highlightedMovie == stream) {
                                    highlightedMovie = null
                                }
                            },
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                MovieDescriptionStrip(
                    highlighted = highlightedMovie,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MovieDescriptionStrip(
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
                    text = "Focus a movie to see its description.",
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
private fun MovieStreamCard(
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
