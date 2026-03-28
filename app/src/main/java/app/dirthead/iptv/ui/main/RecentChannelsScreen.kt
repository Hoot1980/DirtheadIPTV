@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.dirthead.iptv.ui.main

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.data.PlaylistStream
import app.dirthead.iptv.ui.LocalAddStreamToFavorites
import app.dirthead.iptv.ui.LocalPlaylistRepository
import app.dirthead.iptv.ui.common.StreamLogoImage
import app.dirthead.iptv.ui.common.focusWhiteRing
import app.dirthead.iptv.ui.common.tvRemoteLongSelectConsumesClick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentChannelsScreen(
    onBack: () -> Unit,
    onPlayStream: (PlaylistStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val channels = repository.recentChannels()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Recent channels") },
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
        if (channels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No recent channels yet. Open something from Live TV, Movies, or Series and it will appear here.",
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
                    text = "${channels.size} channels",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(channels, key = { it.streamUrl }) { stream ->
                        RecentChannelCard(stream = stream, onClick = { onPlayStream(stream) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentChannelCard(
    stream: PlaylistStream,
    onClick: () -> Unit,
) {
    val addToFavorites = LocalAddStreamToFavorites.current
    val cardShape = CardDefaults.shape
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .focusWhiteRing(cardShape)
            .tvRemoteLongSelectConsumesClick { addToFavorites(stream) }
            .combinedClickable(
                onClick = onClick,
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
