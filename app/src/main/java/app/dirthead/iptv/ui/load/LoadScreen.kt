package app.dirthead.iptv.ui.load

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.R
import app.dirthead.iptv.data.PlaylistCatalogEntry
import app.dirthead.iptv.ui.LocalPlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val BrandBlack = Color(0xFF000000)
private val BrandOnBlackMuted = Color(0xFFB0B0B0)

/** Playlists that loaded successfully at least once. */
private val PlaylistRowSkyBlue = Color(0xFF87CEEB)

/** Playlists that failed or timed out. */
private val PlaylistRowInvalid = Color(0xFFFF4444)

/**
 * Full Xtream catalog + EPG uses many sequential HTTP calls (often 30s–several minutes on slow links).
 * Must exceed typical load time; OkHttp read timeout per request is 120s on the playlist client.
 */
private const val PLAYLIST_LOAD_TIMEOUT_MS = 300_000L

private val playlistLoadTimeoutMinutes: Int =
    (PLAYLIST_LOAD_TIMEOUT_MS / 60_000L).toInt().coerceAtLeast(1)

/** Visible playlist rows in the bottom panel (remainder scrolls). */
private const val PLAYLIST_VISIBLE_ROW_COUNT = 5

private val PlaylistRowGap = 4.dp
private val PlaylistCardInnerPaddingH = 8.dp
private val PlaylistCardInnerPaddingV = 4.dp

private sealed interface LoadUiState {
    data object FetchingCatalog : LoadUiState
    data object PickPlaylist : LoadUiState
    data object LoadingPlaylist : LoadUiState
    data class CatalogError(val message: String) : LoadUiState
    data class PlaylistError(val message: String) : LoadUiState
}

@Composable
fun LoadScreen(
    onPlaylistReady: () -> Unit,
    autoResumeLastSelected: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val scope = rememberCoroutineScope()
    val initialPointer = remember(autoResumeLastSelected) {
        repository.selectedPlaylistPointerUrlOrNull()?.trim().orEmpty()
    }
    val initialResumeMode = autoResumeLastSelected && initialPointer.isNotEmpty()
    var resumeModeActive by remember(autoResumeLastSelected) { mutableStateOf(initialResumeMode) }
    var uiState by remember(autoResumeLastSelected) {
        mutableStateOf(
            if (initialResumeMode) LoadUiState.LoadingPlaylist else LoadUiState.FetchingCatalog,
        )
    }
    var catalogEntries by remember { mutableStateOf<List<PlaylistCatalogEntry>?>(null) }
    var chosenPointerUrl by remember(autoResumeLastSelected) {
        mutableStateOf(if (initialResumeMode) initialPointer else "")
    }
    var catalogAttempt by remember { mutableIntStateOf(0) }
    /** 0 = idle; incremented when loading playlist (retry increases → forceRefresh). */
    var loadGeneration by remember(autoResumeLastSelected) {
        mutableIntStateOf(if (initialResumeMode) 1 else 0)
    }
    /** Bumps when load history prefs change so row colors refresh. */
    var loadHistoryTick by remember { mutableIntStateOf(0) }

    val startLoadForUrl: (String) -> Unit = { url ->
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            resumeModeActive = false
            chosenPointerUrl = trimmed
            repository.setSelectedPlaylistPointerUrl(trimmed)
            loadGeneration++
        }
    }

    LaunchedEffect(catalogAttempt) {
        if (!resumeModeActive) {
            uiState = LoadUiState.FetchingCatalog
        }
        val result = withContext(Dispatchers.IO) { repository.fetchPlaylistCatalog() }
        result.fold(
            onSuccess = { list ->
                catalogEntries = list
                if (resumeModeActive && loadGeneration > 0) {
                    // Auto-resume load in progress; keep chosen URL and UI state.
                } else {
                    val stored = repository.selectedPlaylistPointerUrlOrNull()
                    val defaultUrl =
                        list.find { it.pointerUrl == stored }?.pointerUrl ?: list.first().pointerUrl
                    chosenPointerUrl = defaultUrl
                    if (list.size == 1) {
                        repository.setSelectedPlaylistPointerUrl(defaultUrl)
                        uiState = LoadUiState.LoadingPlaylist
                        loadGeneration++
                    } else {
                        // Keep prefs aligned with the highlighted default so loads always have a URL.
                        runCatching { repository.setSelectedPlaylistPointerUrl(defaultUrl) }
                        uiState = LoadUiState.PickPlaylist
                    }
                }
            },
            onFailure = { e ->
                catalogEntries = null
                if (!(resumeModeActive && loadGeneration > 0)) {
                    uiState = LoadUiState.CatalogError(e.message ?: "Could not load playlist list")
                }
            },
        )
    }

    LaunchedEffect(loadGeneration) {
        if (loadGeneration == 0) return@LaunchedEffect
        val urlToLoad = chosenPointerUrl.trim().ifEmpty {
            repository.selectedPlaylistPointerUrlOrNull()?.trim().orEmpty()
        }
        if (urlToLoad.isEmpty()) {
            uiState = LoadUiState.PlaylistError(
                "No playlist selected. Tap a playlist in the list to load it.",
            )
            return@LaunchedEffect
        }
        runCatching { repository.setSelectedPlaylistPointerUrl(urlToLoad) }
            .onFailure { e ->
                uiState = LoadUiState.PlaylistError(e.message ?: "Invalid playlist URL")
                return@LaunchedEffect
            }
        uiState = LoadUiState.LoadingPlaylist
        val result = withTimeoutOrNull(PLAYLIST_LOAD_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                repository.loadVerifiedPlaylistForPointer(
                    pointerUrl = urlToLoad,
                    forceRefresh = loadGeneration > 1,
                )
            }
        }
        if (result == null) {
            repository.recordPlaylistPointerLoadFailed(urlToLoad)
            loadHistoryTick++
            uiState = LoadUiState.PlaylistError(
                "Timed out after $playlistLoadTimeoutMinutes minutes. Large playlists or slow networks need more time — try again or pick another source.",
            )
            return@LaunchedEffect
        }
        result.fold(
            onSuccess = {
                repository.recordPlaylistPointerLoadSucceeded(urlToLoad)
                loadHistoryTick++
                onPlaylistReady()
                scope.launch(Dispatchers.IO) {
                    runCatching { repository.prefetchLogoArtwork() }
                }
            },
            onFailure = { e ->
                repository.recordPlaylistPointerLoadFailed(urlToLoad)
                loadHistoryTick++
                uiState = LoadUiState.PlaylistError(e.message ?: "Could not load playlist")
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BrandBlack),
    ) {
        when (val s = uiState) {
            LoadUiState.PickPlaylist -> {
                val entries = catalogEntries
                if (entries.isNullOrEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.dirthead_logo),
                            contentDescription = "Dirthead IPTV",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .padding(horizontal = 8.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No playlists available.",
                            color = BrandOnBlackMuted,
                        )
                    }
                } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
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
                                .heightIn(max = 220.dp)
                                .padding(horizontal = 8.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Please Select a Playlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Text(
                            text = "White · new · Sky blue · OK before · Red · failed. Tap to load (can take a few minutes).",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandOnBlackMuted,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            maxLines = 2,
                        )
                    }
                    key(loadHistoryTick) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                val gapsTotal = PlaylistRowGap * (PLAYLIST_VISIBLE_ROW_COUNT - 1)
                                val rowHeight =
                                    (maxHeight - gapsTotal) / PLAYLIST_VISIBLE_ROW_COUNT.toFloat()
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(PlaylistRowGap),
                                ) {
                                    items(entries, key = { it.pointerUrl }) { entry ->
                                        val url = entry.pointerUrl
                                        val loadedOk = repository.playlistPointerPreviouslyLoadedOk(url)
                                        val loadFailed = repository.playlistPointerPreviouslyLoadFailed(url)
                                        val (titleColor, subtitleColor, radioUnselected) = when {
                                            loadedOk -> Triple(
                                                PlaylistRowSkyBlue,
                                                PlaylistRowSkyBlue.copy(alpha = 0.88f),
                                                PlaylistRowSkyBlue.copy(alpha = 0.55f),
                                            )
                                            loadFailed -> Triple(
                                                PlaylistRowInvalid,
                                                PlaylistRowInvalid.copy(alpha = 0.88f),
                                                PlaylistRowInvalid.copy(alpha = 0.55f),
                                            )
                                            else -> Triple(
                                                Color.White,
                                                Color.White.copy(alpha = 0.92f),
                                                BrandOnBlackMuted,
                                            )
                                        }
                                        val selected = chosenPointerUrl == url
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(rowHeight)
                                                .clickable { startLoadForUrl(url) },
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (selected) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                                },
                                            ),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(
                                                        horizontal = PlaylistCardInnerPaddingH,
                                                        vertical = PlaylistCardInnerPaddingV,
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                RadioButton(
                                                    selected = selected,
                                                    onClick = { startLoadForUrl(url) },
                                                    modifier = Modifier.size(36.dp),
                                                    colors = RadioButtonDefaults.colors(
                                                        selectedColor = titleColor,
                                                        unselectedColor = radioUnselected,
                                                    ),
                                                )
                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxWidth(),
                                                ) {
                                                    Text(
                                                        text = entry.label,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = titleColor,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = entry.pointerUrl,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = subtitleColor,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.dirthead_logo),
                        contentDescription = "Dirthead IPTV",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .padding(horizontal = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    when (s) {
                        LoadUiState.FetchingCatalog -> {
                            Text(
                                text = "Loading playlist list…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = BrandOnBlackMuted,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator(color = Color.White)
                        }
                        LoadUiState.LoadingPlaylist -> {
                            Text(
                                text = "Loading playlist…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = BrandOnBlackMuted,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Loading full guide can take several minutes — please wait",
                                style = MaterialTheme.typography.labelMedium,
                                color = BrandOnBlackMuted,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator(color = Color.White)
                        }
                        is LoadUiState.CatalogError -> {
                            Text(
                                text = "Could not load playlist list",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFFF6B6B),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = BrandOnBlackMuted,
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { catalogAttempt++ },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = BrandBlack,
                                ),
                            ) {
                                Text("Retry")
                            }
                        }
                        is LoadUiState.PlaylistError -> {
                            val isTimeout = s.message.contains("Timed out", ignoreCase = true)
                            Text(
                                text = if (isTimeout) "Playlist timed out" else "Could not verify playlist",
                                style = MaterialTheme.typography.titleMedium,
                                color = PlaylistRowInvalid,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isTimeout) {
                                    PlaylistRowInvalid.copy(alpha = 0.92f)
                                } else {
                                    BrandOnBlackMuted
                                },
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = {
                                        resumeModeActive = false
                                        loadGeneration = 0
                                        loadHistoryTick++
                                        if (catalogEntries == null) {
                                            catalogAttempt++
                                        } else {
                                            uiState = LoadUiState.PickPlaylist
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text("Choose another")
                                }
                                Button(
                                    onClick = { loadGeneration++ },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = BrandBlack,
                                    ),
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                        LoadUiState.PickPlaylist -> { }
                    }
                }
            }
        }
    }
}
