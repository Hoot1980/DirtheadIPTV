package app.dirthead.iptv.ui.player

import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dirthead.iptv.data.StreamMediaItemBuilder
import java.util.LinkedHashSet
import kotlinx.coroutines.delay
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private const val PLAYER_WATCHDOG_INTERVAL_MS: Long = 10_000L
private const val PLAYER_STUCK_BUFFERING_MS: Long = 12_000L
private const val PLAYER_MAX_ERROR_AUTO_RETRIES: Int = 5
private const val PLAYER_ERROR_RETRY_DELAY_MS: Long = 1_500L

private const val FLOATING_BACK_AUTO_HIDE_MS: Long = 3_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        IptvExoPlayerFactory.create(context)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showTopDescription by remember { mutableStateOf(true) }
    var showFloatingBack by remember { mutableStateOf(false) }
    var floatingBackRefreshKey by remember { mutableIntStateOf(0) }
    val tapToShowBackInteraction = remember { MutableInteractionSource() }
    val errorAutoRetryHolder = remember { intArrayOf(0) }
    val currentStreamUrl by rememberUpdatedState(streamUrl)

    val stopAndBack: () -> Unit = {
        exoPlayer.stop()
        onBack()
    }

    LaunchedEffect(streamUrl) {
        showTopDescription = true
        delay(3_000)
        showTopDescription = false
    }

    LaunchedEffect(showTopDescription, floatingBackRefreshKey) {
        if (showTopDescription) {
            showFloatingBack = false
        } else {
            showFloatingBack = true
            delay(FLOATING_BACK_AUTO_HIDE_MS)
            showFloatingBack = false
        }
    }

    LaunchedEffect(streamUrl) {
        errorAutoRetryHolder[0] = 0
    }

    DisposableEffect(exoPlayer) {
        val handler = Handler(Looper.getMainLooper())
        var bufferingEnteredElapsedMs = 0L

        fun recoverPlaybackFromUrl() {
            val item = StreamMediaItemBuilder.fromRawUrl(currentStreamUrl) ?: return
            exoPlayer.stop()
            exoPlayer.setMediaItem(item)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        val watchdogRunnable = object : Runnable {
            override fun run() {
                if (exoPlayer.playbackState == Player.STATE_BUFFERING && bufferingEnteredElapsedMs > 0L) {
                    val stuckMs = SystemClock.elapsedRealtime() - bufferingEnteredElapsedMs
                    if (stuckMs >= PLAYER_STUCK_BUFFERING_MS) {
                        bufferingEnteredElapsedMs = 0L
                        recoverPlaybackFromUrl()
                    }
                }
                handler.postDelayed(this, PLAYER_WATCHDOG_INTERVAL_MS)
            }
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (bufferingEnteredElapsedMs == 0L) {
                            bufferingEnteredElapsedMs = SystemClock.elapsedRealtime()
                        }
                    }
                    Player.STATE_READY -> {
                        bufferingEnteredElapsedMs = 0L
                        errorAutoRetryHolder[0] = 0
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        bufferingEnteredElapsedMs = 0L
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying &&
                    exoPlayer.playWhenReady &&
                    exoPlayer.playbackState == Player.STATE_READY
                ) {
                    exoPlayer.play()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (errorAutoRetryHolder[0] < PLAYER_MAX_ERROR_AUTO_RETRIES) {
                    errorAutoRetryHolder[0]++
                    errorMessage = null
                    handler.postDelayed(
                        { recoverPlaybackFromUrl() },
                        PLAYER_ERROR_RETRY_DELAY_MS,
                    )
                    return
                }
                val code = error.errorCodeName
                val rootMsg = error.message ?: "Playback failed"
                val parts = LinkedHashSet<String>()
                parts.add("[$code] $rootMsg")
                var c: Throwable? = error.cause
                while (c != null) {
                    val cn = c.javaClass.simpleName
                    val msg = c.message?.trim()?.takeIf { it.isNotEmpty() }
                    if (msg != null) parts.add("$cn: $msg")
                    c = c.cause
                }
                errorMessage = parts.joinToString("\n")
            }
        }
        exoPlayer.addListener(listener)
        handler.post(watchdogRunnable)
        onDispose {
            handler.removeCallbacks(watchdogRunnable)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(streamUrl) {
        errorMessage = null
        exoPlayer.stop()
        val item = StreamMediaItemBuilder.fromRawUrl(streamUrl)
        if (item == null) {
            errorMessage =
                "Invalid stream address (looks like a file path). Expected http:// or https:// URL."
            return@LaunchedEffect
        }
        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AnimatedVisibility(
                visible = showTopDescription,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = title.ifBlank { "Now playing" },
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = stopAndBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(AndroidColor.BLACK)
                        useController = true
                        controllerShowTimeoutMs = 3500
                        player = exoPlayer
                    }
                },
                update = { it.player = exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )
            if (!showTopDescription && !showFloatingBack) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = tapToShowBackInteraction,
                            indication = null,
                        ) {
                            floatingBackRefreshKey++
                        },
                )
            }
            AnimatedVisibility(
                visible = showFloatingBack && !showTopDescription,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding(),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                    tonalElevation = 3.dp,
                    modifier = Modifier.padding(8.dp),
                ) {
                    IconButton(onClick = stopAndBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                }
            }
            errorMessage?.let { msg ->
                val scroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .heightIn(max = 280.dp)
                        .verticalScroll(scroll),
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
