@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.dirthead.iptv.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.data.FavoriteUser
import app.dirthead.iptv.data.PlaylistStream
import app.dirthead.iptv.ui.LocalPlaylistRepository
import app.dirthead.iptv.ui.common.StreamLogoImage
import app.dirthead.iptv.ui.common.focusWhiteRing
import app.dirthead.iptv.ui.common.tvRemoteLongSelectConsumesClick
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesHubScreen(
    onBack: () -> Unit,
    onSelectUser: (FavoriteUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val users = repository.favoriteUsers()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Favorites") },
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
        if (users.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No favorite profiles yet.",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Long-press Favorites on the main menu to create a profile. Then long-press a channel or title elsewhere to add it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = "${users.size} profiles — tap to open",
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
                    items(users, key = { it.id }) { user ->
                        val cardShape = CardDefaults.shape
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusWhiteRing(cardShape)
                                .clickable { onSelectUser(user) },
                            shape = cardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Text(
                                text = user.displayName,
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
fun FavoritesListScreen(
    userId: String,
    profileTitle: String,
    onBack: () -> Unit,
    onPlayStream: (PlaylistStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    var listVersion by remember(userId) { mutableIntStateOf(0) }
    val streams = remember(userId, listVersion) { repository.favoriteStreamsForUser(userId) }
    var confirmRemove by remember { mutableStateOf<PlaylistStream?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(profileTitle) },
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
        if (streams.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Nothing saved for this profile yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Long-press a channel in Live TV, a movie, an episode, or a recent channel to add it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = "${streams.size} saved — long-press to remove",
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
                    items(streams, key = { it.streamUrl }) { stream ->
                        val cardShape = CardDefaults.shape
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusWhiteRing(cardShape)
                                .tvRemoteLongSelectConsumesClick { confirmRemove = stream }
                                .combinedClickable(
                                    onClick = { onPlayStream(stream) },
                                    onLongClick = { confirmRemove = stream },
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
                }
            }
        }
    }

    confirmRemove?.let { stream ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove favorite?") },
            text = { Text(stream.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.removeFavoriteForUser(userId, stream.streamUrl)
                        confirmRemove = null
                        listVersion++
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun FavoritesUsersManageDialog(
    users: List<FavoriteUser>,
    onDismiss: () -> Unit,
    onCreateUser: (String) -> Unit,
    onDeleteUser: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Favorite profiles") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Create named lists for favorites. Add channels and titles with a long-press outside this dialog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New profile name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                TextButton(
                    onClick = {
                        onCreateUser(newName.trim())
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Create profile") }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                if (users.isEmpty()) {
                    Text("No profiles yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    users.forEach { u ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = u.displayName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            TextButton(onClick = { onDeleteUser(u.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
fun AddStreamToFavoritesDialog(
    stream: PlaylistStream,
    users: List<FavoriteUser>,
    onDismiss: () -> Unit,
    onChoseUser: (FavoriteUser) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add channel to favorites") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stream.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (users.isEmpty()) {
                    Text(
                        text = "You can save this channel to favorites after you create a saved profile.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Long-press Favorites on the main menu, then create a profile. After that, long-press any channel again and choose Add to… for that profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Add this channel to a saved profile:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    users.forEach { u ->
                        FilledTonalButton(
                            onClick = { onChoseUser(u) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text("Add to ${u.displayName}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (users.isEmpty()) "OK" else "Cancel")
            }
        },
    )
}
