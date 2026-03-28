package app.dirthead.iptv.ui.livetv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.dirthead.iptv.data.EpgProgram
import app.dirthead.iptv.data.LiveTvCatchup
import app.dirthead.iptv.data.PlaylistRepository
import app.dirthead.iptv.data.PlaylistStream
import app.dirthead.iptv.ui.LocalAddStreamToFavorites
import app.dirthead.iptv.ui.LocalPlaylistRepository
import app.dirthead.iptv.ui.common.OmdbSynopsisPalette
import app.dirthead.iptv.ui.common.OmdbSynopsisSection
import app.dirthead.iptv.ui.common.StreamLogoImage
import app.dirthead.iptv.ui.common.omdbSynopsisPaletteMaterial
import app.dirthead.iptv.ui.common.TvSearchKeyboard
import app.dirthead.iptv.ui.common.focusWhiteRing
import app.dirthead.iptv.ui.common.tvRemoteLongSelectConsumesClick
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cable-style guide palette (inspired by classic grid guides). */
private object TvGuidePalette {
    val background = Color(0xFF000000)
    val surfaceElevated = Color(0xFF1A1A1A)
    val gridLine = Color(0xFF2A2A2A)
    val rowDivider = Color(0xFF333333)
    val cyanAccent = Color(0xFF6DD3FF)
    val greenAccent = Color(0xFF69F0AE)
    val programBlockFill = Color(0xFF1E1E1E)
    val programBlockBorder = Color(0xFF404040)
    val slotStripe = Color(0xFF0D0D0D)
    val onBackground = Color(0xFFFFFFFF)
}

/** Left column: logo + channel number + full name (wider for long network names). */
private val ChannelColumnWidth = 220.dp

/** Half-hour columns across the guide (6 × 30 min = 3 h). */
private const val GuideHalfHourSlotCount = 6

private val GuideHalfHourMs: Long = 30 * 60 * 1000L

private fun guideGridEndMillis(gridAnchorMillis: Long): Long =
    gridAnchorMillis + GuideHalfHourSlotCount * GuideHalfHourMs

private fun Modifier.tvGuideMenuKey(
    stream: PlaylistStream,
    onOpenChannelOptions: (PlaylistStream) -> Unit,
): Modifier = this.onKeyEvent { event ->
    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
    val isMenuKey = event.key == Key.Menu ||
        event.nativeKeyEvent?.keyCode == AndroidKeyEvent.KEYCODE_MENU
    if (isMenuKey) {
        onOpenChannelOptions(stream)
        true
    } else {
        false
    }
}

private sealed interface EpgGuideLoadState {
    data object Loading : EpgGuideLoadState
    data class Ready(val programs: List<EpgProgram>) : EpgGuideLoadState
    data class Failed(val message: String) : EpgGuideLoadState
}

private fun formatCableChannelNumber(indexOneBased: Int): String =
    String.format(Locale.US, "%04d", indexOneBased)

private fun floorToHalfHourMillis(epochMillis: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val min = cal.get(Calendar.MINUTE)
    if (min < 30) {
        cal.set(Calendar.MINUTE, 0)
    } else {
        cal.set(Calendar.MINUTE, 30)
    }
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveTvGroupsScreen(
    onBack: () -> Unit,
    onSelectGroup: (groupTitle: String) -> Unit,
    onPlayStream: (PlaylistStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    var showChannelSearch by remember { mutableStateOf(false) }
    var listRefresh by remember { mutableStateOf(0) }
    val groups = remember(listRefresh) { repository.liveTvGroupsSorted() }
    var longPressGroup by remember { mutableStateOf<String?>(null) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedForHide by remember { mutableStateOf(setOf<String>()) }
    var showHiddenGroupsDialog by remember { mutableStateOf(false) }
    var showRemoteOptionsDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                val isMenuKey = event.key == Key.Menu ||
                    event.nativeKeyEvent?.keyCode == AndroidKeyEvent.KEYCODE_MENU
                if (isMenuKey && !multiSelectMode) {
                    showRemoteOptionsDialog = true
                    true
                } else {
                    false
                }
            },
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (multiSelectMode) "Select groups to hide" else "Live TV",
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (multiSelectMode) {
                                multiSelectMode = false
                                selectedForHide = emptySet()
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (multiSelectMode) "Exit selection" else "Back",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (multiSelectMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            multiSelectMode = false
                            selectedForHide = emptySet()
                        },
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (selectedForHide.isNotEmpty()) {
                                repository.hideLiveTvGroups(selectedForHide)
                                listRefresh++
                                multiSelectMode = false
                                selectedForHide = emptySet()
                            }
                        },
                        enabled = selectedForHide.isNotEmpty(),
                    ) {
                        Text("Hide selected (${selectedForHide.size})")
                    }
                }
            }
        },
    ) { innerPadding ->
        if (showRemoteOptionsDialog) {
            AlertDialog(
                onDismissRequest = { showRemoteOptionsDialog = false },
                title = { Text("Group options") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Hide categories from this list, or restore hidden ones.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                selectedForHide = emptySet()
                                multiSelectMode = true
                                showRemoteOptionsDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Hide groups…")
                        }
                        TextButton(
                            onClick = {
                                showHiddenGroupsDialog = true
                                showRemoteOptionsDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Unhide groups…")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showRemoteOptionsDialog = false }) {
                        Text("Close")
                    }
                },
            )
        }

        longPressGroup?.let { target ->
            AlertDialog(
                onDismissRequest = { longPressGroup = null },
                title = {
                    Text(
                        text = target,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Hide this category from the list, or choose several at once.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                repository.hideLiveTvGroup(target)
                                listRefresh++
                                longPressGroup = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Hide this group")
                        }
                        TextButton(
                            onClick = {
                                selectedForHide = setOf(target)
                                multiSelectMode = true
                                longPressGroup = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Select multiple groups to hide…")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { longPressGroup = null }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (showHiddenGroupsDialog) {
            val hiddenSorted = remember(listRefresh, showHiddenGroupsDialog) {
                repository.hiddenLiveTvGroupTitles()
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
            AlertDialog(
                onDismissRequest = { showHiddenGroupsDialog = false },
                title = { Text("Hidden groups") },
                text = {
                    if (hiddenSorted.isEmpty()) {
                        Text(
                            text = "No hidden groups.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            hiddenSorted.forEach { title ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(
                                        onClick = {
                                            repository.unhideLiveTvGroup(title)
                                            listRefresh++
                                            if (repository.hiddenLiveTvGroupTitles().isEmpty()) {
                                                showHiddenGroupsDialog = false
                                            }
                                        },
                                    ) {
                                        Text("Unhide")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHiddenGroupsDialog = false }) {
                        Text("Close")
                    }
                },
            )
        }

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (repository.hiddenLiveTvGroupTitles().isNotEmpty()) {
                            "All groups are hidden, or the playlist has no groups."
                        } else {
                            "No channel groups were found in the playlist."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (repository.hiddenLiveTvGroupTitles().isNotEmpty()) {
                        TextButton(
                            onClick = { showHiddenGroupsDialog = true },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("Manage hidden groups")
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = when {
                        multiSelectMode -> "Tap groups to select, then Hide selected."
                        else -> "${groups.size} groups · Menu: hide/unhide · Long-press: quick hide"
                    },
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
                        val selected = multiSelectMode && groupTitle in selectedForHide
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = cardShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .focusWhiteRing(cardShape)
                                .tvRemoteLongSelectConsumesClick(enabled = !multiSelectMode) {
                                    longPressGroup = groupTitle
                                }
                                .combinedClickable(
                                    onClick = {
                                        if (multiSelectMode) {
                                            selectedForHide =
                                                selectedForHide.toMutableSet().apply {
                                                    if (!add(groupTitle)) remove(groupTitle)
                                                }
                                        } else {
                                            onSelectGroup(groupTitle)
                                        }
                                    },
                                    onLongClick = {
                                        if (!multiSelectMode) {
                                            longPressGroup = groupTitle
                                        }
                                    },
                                ),
                            shape = cardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
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

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 44.dp, end = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = { showChannelSearch = true },
                modifier = Modifier.focusWhiteRing(CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search channels",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (showChannelSearch) {
        LiveTvChannelSearchDialog(
            onDismiss = { showChannelSearch = false },
            onPlayStream = onPlayStream,
        )
    }
    }
}

private const val LiveTvChannelSearchDebounceMs = 320L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTvChannelSearchDialog(
    onDismiss: () -> Unit,
    onPlayStream: (PlaylistStream) -> Unit,
) {
    val repository = LocalPlaylistRepository.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaylistStream>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val keyboardFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        keyboardFocus.requestFocus()
    }

    LaunchedEffect(query) {
        delay(LiveTvChannelSearchDebounceMs)
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            busy = false
            return@LaunchedEffect
        }
        busy = true
        results = withContext(Dispatchers.IO) { repository.searchChannels(q) }
        busy = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search channels") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties { canFocus = false },
                        label = { Text("Channel or group name") },
                        singleLine = true,
                    )
                }
                item {
                    TvSearchKeyboard(
                        onAppend = { query += it },
                        onBackspace = {
                            if (query.isNotEmpty()) query = query.dropLast(1)
                        },
                        onClear = { query = "" },
                        focusRequester = keyboardFocus,
                    )
                }
                item {
                    when {
                        busy -> {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(28.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        query.isNotBlank() && results.isEmpty() -> {
                            Text(
                                text = "No matches.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> Unit
                    }
                }
                items(results, key = { it.streamUrl }) { stream ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusWhiteRing(CardDefaults.shape)
                            .clickable {
                                onPlayStream(stream)
                                onDismiss()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = stream.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stream.groupTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = 4.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

/** One horizontal bar in the timeline ([clipStartMillis], [clipEndMillis] clipped to the 3h window). */
private data class GuideSegment(
    val title: String,
    val subtitle: String,
    val clipStartMillis: Long,
    val clipEndMillis: Long,
    /** Set when this segment maps to a single EPG row (for detail pane and focus). */
    val epgProgram: EpgProgram? = null,
)

private sealed interface TvGuideHighlight {
    val stream: PlaylistStream

    data class TimelineListing(
        override val stream: PlaylistStream,
        val segment: GuideSegment,
    ) : TvGuideHighlight
}

/** Programs as non-duplicated segments spanning their true length within [gridAnchorMillis..gridEndMillis). */
private fun buildGuideSegments(
    programs: List<EpgProgram>,
    gridAnchorMillis: Long,
    gridEndMillis: Long,
    channelName: String,
): List<GuideSegment> {
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    if (programs.isEmpty()) {
        return listOf(
            GuideSegment(
                title = "Live",
                subtitle = "$channelName · No EPG",
                clipStartMillis = gridAnchorMillis,
                clipEndMillis = gridEndMillis,
                epgProgram = null,
            ),
        )
    }
    val sorted = programs
        .filter { it.endMillis > gridAnchorMillis && it.startMillis < gridEndMillis }
        .sortedBy { it.startMillis }
    if (sorted.isEmpty()) {
        return listOf(
            GuideSegment(
                title = "Live",
                subtitle = channelName,
                clipStartMillis = gridAnchorMillis,
                clipEndMillis = gridEndMillis,
                epgProgram = null,
            ),
        )
    }
    return sorted.map { p ->
        val clipStart = maxOf(p.startMillis, gridAnchorMillis)
        val clipEnd = minOf(p.endMillis, gridEndMillis)
        GuideSegment(
            title = p.title,
            subtitle = "${fmt.format(Date(p.startMillis))} – ${fmt.format(Date(p.endMillis))}",
            clipStartMillis = clipStart,
            clipEndMillis = clipEnd,
            epgProgram = p,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveTvGroupChannelsScreen(
    groupTitle: String,
    onBack: () -> Unit,
    onPlayStream: (PlaylistStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    var epgGuideRevision by remember { mutableStateOf(0L) }
    LaunchedEffect(repository) {
        repository.epgGuideRevision.collectLatest { epgGuideRevision = it }
    }
    val streams = repository.streamsInLiveTvGroup(groupTitle)
    val isCatchupGroup = groupTitle.equals(LiveTvCatchup.GROUP_TITLE, ignoreCase = true)
    val todayDateFmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    var gridClock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000L)
            gridClock = System.currentTimeMillis()
        }
    }
    val gridAnchorMillis = remember(gridClock) { floorToHalfHourMillis(gridClock) }
    val gridEndMillis = remember(gridAnchorMillis) { guideGridEndMillis(gridAnchorMillis) }
    var guideHighlight by remember { mutableStateOf<TvGuideHighlight?>(null) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    var channelOptionsStream by remember { mutableStateOf<PlaylistStream?>(null) }
    var epgGuideStream by remember { mutableStateOf<PlaylistStream?>(null) }
    val addToFavorites = LocalAddStreamToFavorites.current
    val guideListState = rememberLazyListState()
    val guideScrollScope = rememberCoroutineScope()
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (!isCatchupGroup && streams.isNotEmpty()) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (channelOptionsStream != null || epgGuideStream != null) {
                            return@onPreviewKeyEvent false
                        }
                        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                        val code = event.nativeKeyEvent?.keyCode
                        val forward = event.key == Key.PageDown ||
                            code == AndroidKeyEvent.KEYCODE_PAGE_DOWN ||
                            code == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        val backward = event.key == Key.PageUp ||
                            code == AndroidKeyEvent.KEYCODE_PAGE_UP ||
                            code == AndroidKeyEvent.KEYCODE_MEDIA_REWIND
                        if (!forward && !backward) return@onPreviewKeyEvent false
                        guideScrollScope.launch {
                            val info = guideListState.layoutInfo
                            var px = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                            if (px <= 0f) {
                                px = with(density) { 360.dp.toPx() }
                            }
                            guideListState.scroll {
                                scrollBy(if (forward) px else -px)
                            }
                        }
                        true
                    }
                } else {
                    Modifier
                },
            ),
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = TvGuidePalette.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TvGuidePalette.background,
                    titleContentColor = TvGuidePalette.onBackground,
                    navigationIconContentColor = TvGuidePalette.onBackground,
                    actionIconContentColor = TvGuidePalette.onBackground,
                ),
                title = {
                    Column {
                        Text(
                            text = "TV Channel Guide",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = TvGuidePalette.onBackground,
                        )
                        Text(
                            text = groupTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = TvGuidePalette.cyanAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isCatchupGroup) {
                        "No catch-up channels. Your provider may not expose tv_archive on live streams."
                    } else {
                        "No channels in this group."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else if (isCatchupGroup) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvGuidePalette.background)
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = "Channels with replay (tv_archive). Select a channel to pick a programme.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvGuidePalette.cyanAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
                CatchupVirtualGroupChannelList(
                    streams = streams,
                    getPhpUrl = repository.xtreamGetPhpUrlString(),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvGuidePalette.background)
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
            ) {
                TvGuideShowInfoCard(
                    highlight = guideHighlight,
                    timeFmt = timeFmt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 260.dp)
                        .padding(bottom = 8.dp),
                )
                TvGuideTimeHeader(
                    gridAnchorMillis = gridAnchorMillis,
                    todayLabel = todayDateFmt.format(Date(gridClock)),
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = TvGuidePalette.rowDivider,
                )
                LazyColumn(
                    state = guideListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    itemsIndexed(
                        items = streams,
                        key = { _, stream -> stream.streamUrl },
                    ) { index, stream ->
                        TvGuideChannelRow(
                            stream = stream,
                            channelNumber = index + 1,
                            repository = repository,
                            epgGuideRevision = epgGuideRevision,
                            gridAnchorMillis = gridAnchorMillis,
                            gridEndMillis = gridEndMillis,
                            onPlayStream = onPlayStream,
                            onGuideHighlight = { guideHighlight = it },
                            onOpenChannelOptions = { channelOptionsStream = it },
                            showCatchupAvailableLabel = stream.tvArchive,
                        )
                    }
                }
            }
        }
    }

    channelOptionsStream?.let { stream ->
        LiveTvChannelLongPressMenuDialog(
            stream = stream,
            onDismiss = { channelOptionsStream = null },
            onViewEpgGuide = {
                epgGuideStream = stream
                channelOptionsStream = null
            },
            onAddToFavorites = {
                addToFavorites(stream)
                channelOptionsStream = null
            },
        )
    }

    epgGuideStream?.let { stream ->
        LiveTvChannelEpgGuideDialog(
            stream = stream,
            repository = repository,
            onDismiss = { epgGuideStream = null },
        )
    }
    }
}

@Composable
private fun LiveTvChannelLongPressMenuDialog(
    stream: PlaylistStream,
    onDismiss: () -> Unit,
    onViewEpgGuide: () -> Unit,
    onAddToFavorites: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stream.displayName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onViewEpgGuide,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("View EPG guide")
                }
                FilledTonalButton(
                    onClick = onAddToFavorites,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add to favorites")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun LiveTvChannelEpgGuideDialog(
    stream: PlaylistStream,
    repository: PlaylistRepository,
    onDismiss: () -> Unit,
) {
    var epgGuideRevision by remember { mutableStateOf(0L) }
    LaunchedEffect(repository) {
        repository.epgGuideRevision.collectLatest { epgGuideRevision = it }
    }
    var loadState by remember(stream.streamUrl, epgGuideRevision) {
        mutableStateOf<EpgGuideLoadState>(EpgGuideLoadState.Loading)
    }
    LaunchedEffect(stream.streamUrl, epgGuideRevision) {
        loadState = EpgGuideLoadState.Loading
        loadState = runCatching { repository.loadEpgForLiveStream(stream) }.fold(
            onSuccess = { EpgGuideLoadState.Ready(it) },
            onFailure = { EpgGuideLoadState.Failed(it.message ?: "Could not load EPG") },
        )
    }
    val rangeFmt = remember {
        SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "EPG guide",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stream.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        text = {
            when (val s = loadState) {
                EpgGuideLoadState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is EpgGuideLoadState.Failed -> {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is EpgGuideLoadState.Ready -> {
                    if (s.programs.isEmpty()) {
                        Text(
                            text = "No EPG listings are available for this channel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            s.programs.sortedBy { it.startMillis }.forEachIndexed { i, p ->
                                if (i > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                }
                                Text(
                                    text = p.title,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = "${rangeFmt.format(Date(p.startMillis))} – ${rangeFmt.format(Date(p.endMillis))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OmdbSynopsisSection(
                                    lookupTitle = p.title,
                                    providerDescription = p.description.orEmpty(),
                                    effectKey = p.startMillis to p.title,
                                    idle = null,
                                    showTitleAbove = false,
                                    palette = omdbSynopsisPaletteMaterial(),
                                    bodyStyle = MaterialTheme.typography.bodySmall,
                                    smallStyle = MaterialTheme.typography.bodySmall,
                                    labelStyle = MaterialTheme.typography.labelSmall,
                                    maxProviderHeight = 200.dp,
                                    maxOmdbBodyHeight = 200.dp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun TvGuideShowInfoCard(
    highlight: TvGuideHighlight?,
    timeFmt: SimpleDateFormat,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = TvGuidePalette.surfaceElevated,
        ),
        border = BorderStroke(1.dp, TvGuidePalette.gridLine),
        shape = RoundedCornerShape(4.dp),
    ) {
        val infoScroll = rememberScrollState()
        Column(
            Modifier
                .padding(14.dp)
                .heightIn(max = 228.dp)
                .verticalScroll(infoScroll),
        ) {
            val omdbTvPalette = OmdbSynopsisPalette(
                title = TvGuidePalette.onBackground,
                body = TvGuidePalette.cyanAccent.copy(alpha = 0.92f),
                muted = TvGuidePalette.cyanAccent.copy(alpha = 0.55f),
                sourceAccent = TvGuidePalette.greenAccent,
                progressIndicatorColor = TvGuidePalette.cyanAccent,
            )
            val sectionLabel = when (highlight) {
                is TvGuideHighlight.TimelineListing -> "SELECTED"
                null -> "TV GUIDE"
            }
            Text(
                text = sectionLabel,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                fontFamily = FontFamily.Monospace,
                color = TvGuidePalette.greenAccent,
            )
            if (highlight == null) {
                Text(
                    text = "Use Up/Down/Left/Right on programme blocks in the grid — details show here. Enter plays that channel. The channel column is tap-only (no remote focus). Menu opens options on a focused programme.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvGuidePalette.cyanAccent.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreamLogoImage(
                        imageUrl = highlight.stream.logoUrl,
                        contentDescription = highlight.stream.displayName,
                        size = 44.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = highlight.stream.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TvGuidePalette.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (highlight) {
                    is TvGuideHighlight.TimelineListing -> {
                        val prog = highlight.segment.epgProgram
                        if (prog != null) {
                            Text(
                                text = prog.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TvGuidePalette.onBackground,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                text = "${timeFmt.format(Date(prog.startMillis))} – ${timeFmt.format(Date(prog.endMillis))}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TvGuidePalette.cyanAccent,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            OmdbSynopsisSection(
                                lookupTitle = prog.title,
                                providerDescription = prog.description.orEmpty(),
                                effectKey = prog.startMillis to prog.title,
                                idle = null,
                                showTitleAbove = false,
                                palette = omdbTvPalette,
                                bodyStyle = MaterialTheme.typography.bodySmall,
                                smallStyle = MaterialTheme.typography.bodySmall,
                                labelStyle = MaterialTheme.typography.labelSmall,
                                maxProviderHeight = 200.dp,
                                maxOmdbBodyHeight = 200.dp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                text = highlight.segment.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TvGuidePalette.onBackground,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            if (highlight.segment.subtitle.isNotEmpty()) {
                                Text(
                                    text = highlight.segment.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = TvGuidePalette.cyanAccent,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvGuideTimeHeader(
    gridAnchorMillis: Long,
    todayLabel: String,
) {
    val fmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(ChannelColumnWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TODAY: ",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                fontFamily = FontFamily.Monospace,
                color = TvGuidePalette.greenAccent,
            )
            Text(
                text = todayLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = TvGuidePalette.greenAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            repeat(GuideHalfHourSlotCount) { i ->
                val slotStart = gridAnchorMillis + i * GuideHalfHourMs
                Text(
                    text = fmt.format(Date(slotStart)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    fontFamily = FontFamily.Monospace,
                    color = TvGuidePalette.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvGuideChannelRow(
    stream: PlaylistStream,
    channelNumber: Int,
    repository: PlaylistRepository,
    epgGuideRevision: Long,
    gridAnchorMillis: Long,
    gridEndMillis: Long,
    onPlayStream: (PlaylistStream) -> Unit,
    onGuideHighlight: (TvGuideHighlight) -> Unit,
    onOpenChannelOptions: (PlaylistStream) -> Unit,
    modifier: Modifier = Modifier,
    showCatchupAvailableLabel: Boolean = false,
) {
    var programs by remember(stream.tvgId, stream.epgStreamId, stream.streamUrl, epgGuideRevision) {
        mutableStateOf<List<EpgProgram>>(emptyList())
    }
    LaunchedEffect(stream.tvgId, stream.epgStreamId, stream.streamUrl, epgGuideRevision) {
        programs = repository.loadEpgForLiveStream(stream)
    }
    val segments = remember(programs, stream.displayName, gridAnchorMillis, gridEndMillis) {
        buildGuideSegments(programs, gridAnchorMillis, gridEndMillis, stream.displayName)
    }
    val cableCh = remember(channelNumber) { formatCableChannelNumber(channelNumber) }
    val channelShape = RoundedCornerShape(4.dp)

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvGuidePalette.background)
                .heightIn(min = 80.dp)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Touch-only: no combinedClickable — it still registers a focus target on TV, so D-pad
            // could never reach the programme grid. Remote: use grid blocks; tap here on touchscreens.
            Row(
                modifier = Modifier
                    .width(ChannelColumnWidth)
                    .padding(end = 6.dp)
                    .clip(channelShape)
                    .pointerInput(stream.streamUrl) {
                        detectTapGestures(
                            onTap = { onPlayStream(stream) },
                            onLongPress = { onOpenChannelOptions(stream) },
                        )
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StreamLogoImage(
                    imageUrl = stream.logoUrl,
                    contentDescription = null,
                    size = 52.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cableCh,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TvGuidePalette.onBackground,
                    )
                    Text(
                        text = stream.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TvGuidePalette.cyanAccent,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showCatchupAvailableLabel) {
                        Text(
                            text = "Catch-up Available",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TvGuidePalette.greenAccent,
                            modifier = Modifier.padding(top = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .pointerInput(stream.streamUrl) {
                            detectTapGestures { onOpenChannelOptions(stream) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = TvGuidePalette.cyanAccent,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(TvGuidePalette.gridLine),
            )
            TvGuideTimeline(
                stream = stream,
                segments = segments,
                gridAnchorMillis = gridAnchorMillis,
                gridEndMillis = gridEndMillis,
                onPlayStream = onPlayStream,
                onListingHighlighted = { s, seg ->
                    onGuideHighlight(TvGuideHighlight.TimelineListing(s, seg))
                },
                onOpenChannelOptions = onOpenChannelOptions,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = TvGuidePalette.rowDivider,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvGuideTimeline(
    stream: PlaylistStream,
    segments: List<GuideSegment>,
    gridAnchorMillis: Long,
    gridEndMillis: Long,
    onPlayStream: (PlaylistStream) -> Unit,
    onListingHighlighted: (PlaylistStream, GuideSegment) -> Unit,
    onOpenChannelOptions: (PlaylistStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalMs = (gridEndMillis - gridAnchorMillis).toFloat().coerceAtLeast(1f)
    val segmentShape = RoundedCornerShape(2.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp),
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .fillMaxHeight(),
        ) {
            repeat(GuideHalfHourSlotCount) { i ->
                if (i > 0) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(TvGuidePalette.gridLine),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(TvGuidePalette.slotStripe),
                )
            }
        }
        val fullW = maxWidth
        segments.forEachIndexed { index, seg ->
            val startMs = seg.clipStartMillis - gridAnchorMillis
            val spanMs = seg.clipEndMillis - seg.clipStartMillis
            if (spanMs <= 0L) return@forEachIndexed
            val leftFraction = (startMs.toFloat() / totalMs).coerceIn(0f, 1f)
            val widthFraction = (spanMs.toFloat() / totalMs).coerceIn(0f, 1f - leftFraction)
            if (widthFraction <= 0f) return@forEachIndexed
            key(stream.streamUrl, index, seg.clipStartMillis, seg.clipEndMillis, seg.title) {
                val interactionSource = remember { MutableInteractionSource() }
                var segmentFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .zIndex(1f)
                        .fillMaxHeight()
                        .padding(vertical = 3.dp, horizontal = 2.dp)
                        .width(fullW * widthFraction)
                        .offset(x = fullW * leftFraction)
                        .clip(segmentShape)
                        .background(TvGuidePalette.programBlockFill, segmentShape)
                        .border(
                            width = if (segmentFocused) 2.dp else 1.dp,
                            color = if (segmentFocused) {
                                TvGuidePalette.onBackground
                            } else {
                                TvGuidePalette.programBlockBorder
                            },
                            shape = segmentShape,
                        )
                        .focusable(interactionSource = interactionSource)
                        .tvRemoteLongSelectConsumesClick { onOpenChannelOptions(stream) }
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onPlayStream(stream) },
                            onLongClick = { onOpenChannelOptions(stream) },
                        )
                        .onFocusChanged { state ->
                            segmentFocused = state.isFocused
                            if (state.isFocused) {
                                onListingHighlighted(stream, seg)
                            }
                        }
                        .tvGuideMenuKey(stream, onOpenChannelOptions)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Column {
                        Text(
                            text = seg.title,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = TvGuidePalette.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (seg.subtitle.isNotEmpty()) {
                            Text(
                                text = seg.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = TvGuidePalette.cyanAccent,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
