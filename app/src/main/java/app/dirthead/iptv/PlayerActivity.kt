package app.dirthead.iptv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import app.dirthead.iptv.data.StreamMediaItemBuilder
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView

/**
 * Standalone full-screen ExoPlayer activity optimized for raw MPEG-TS (.ts) Xtream-style streams:
 * aggressive buffering, [DefaultHttpDataSource], auto-resume, error / stuck-buffering reload, and a 10s watchdog.
 *
 * Start with [intent]:
 * ```
 * startActivity(PlayerActivity.intent(this, "https://example.com/live/u/p/123.ts"))
 * ```
 */
class PlayerActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private fun createProgressiveMediaSourceFactory(): ProgressiveMediaSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(HTTP_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
        return ProgressiveMediaSource.Factory(httpDataSourceFactory, extractorsFactory)
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var streamUri: Uri? = null
    private var bufferingEnteredElapsedMs: Long = 0L
    private var exitConfirmDialog: AlertDialog? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed || player == null) return
            val p = player ?: return
            if (p.playbackState == Player.STATE_BUFFERING && bufferingEnteredElapsedMs > 0L) {
                val stuckMs = SystemClock.elapsedRealtime() - bufferingEnteredElapsedMs
                if (stuckMs >= BUFFERING_STUCK_THRESHOLD_MS) {
                    reloadStream("watchdog_stuck_buffering_${stuckMs}ms")
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (bufferingEnteredElapsedMs == 0L) {
                        bufferingEnteredElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
                Player.STATE_READY -> {
                    bufferingEnteredElapsedMs = 0L
                    p.playWhenReady = true
                    p.play()
                }
                Player.STATE_IDLE, Player.STATE_ENDED -> {
                    bufferingEnteredElapsedMs = 0L
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            if (!isPlaying && p.playWhenReady && p.playbackState == Player.STATE_READY) {
                p.play()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handler.postDelayed({
                if (!isDestroyed && player != null) {
                    reloadStream("onPlayerError_${error.errorCodeName}")
                }
            }, ERROR_RETRY_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamUri = resolveStreamUri()
        if (streamUri == null) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (exitConfirmDialog?.isShowing == true) return
                    exitConfirmDialog = AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Close app?")
                        .setMessage("Do you want to close the app?")
                        .setPositiveButton("Yes") { _, _ ->
                            exitConfirmDialog = null
                            releasePlayer()
                            finishAffinity()
                        }
                        .setNegativeButton("No") { d, _ -> d.dismiss() }
                        .setOnDismissListener { exitConfirmDialog = null }
                        .show()
                }
            },
        )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()

        val progressiveMediaSourceFactory = createProgressiveMediaSourceFactory()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                playWhenReady = true
                addListener(playerListener)
            }
        player = exoPlayer

        val view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerShowTimeoutMs = 3500
            player = exoPlayer
        }
        playerView = view
        setContentView(view)

        prepareAndPlay(progressiveMediaSourceFactory)
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(watchdogRunnable)
        handler.post(watchdogRunnable)
        player?.playWhenReady = true
        player?.play()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
        player?.play()
    }

    override fun onStop() {
        handler.removeCallbacks(watchdogRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
        exitConfirmDialog?.dismiss()
        exitConfirmDialog = null
        releasePlayer()
        super.onDestroy()
    }

    private fun resolveStreamUri(): Uri? {
        val fromExtra = intent.getStringExtra(EXTRA_STREAM_URL)?.trim().orEmpty()
        val url = when {
            fromExtra.isNotEmpty() -> fromExtra
            else -> intent.data?.toString()?.trim().orEmpty()
        }
        if (url.isEmpty()) return null
        return try {
            Uri.parse(url)
        } catch (_: Exception) {
            null
        }
    }

    private fun prepareAndPlay(progressiveMediaSourceFactory: ProgressiveMediaSource.Factory) {
        val uri = streamUri ?: return
        val p = player ?: return
        val mediaItem = StreamMediaItemBuilder.fromPlaybackUri(uri)
        val mediaSource = progressiveMediaSourceFactory.createMediaSource(mediaItem)
        p.stop()
        p.setMediaSource(mediaSource)
        p.prepare()
        p.playWhenReady = true
        p.play()
    }

    private fun reloadStream(reason: String) {
        Log.w(TAG, "reloadStream: $reason")
        val uri = streamUri ?: return
        val p = player ?: return
        val progressiveMediaSourceFactory = createProgressiveMediaSourceFactory()

        bufferingEnteredElapsedMs = 0L
        handler.post {
            if (isDestroyed || player == null) return@post
            try {
                p.stop()
                p.setMediaSource(
                    progressiveMediaSourceFactory.createMediaSource(
                        StreamMediaItemBuilder.fromPlaybackUri(uri),
                    ),
                )
                p.prepare()
                p.playWhenReady = true
                p.play()
            } catch (e: Exception) {
                Log.e(TAG, "reloadStream failed", e)
                handler.postDelayed({
                    if (!isDestroyed && player != null) {
                        prepareAndPlay(createProgressiveMediaSourceFactory())
                    }
                }, ERROR_RETRY_DELAY_MS)
            }
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        p.removeListener(playerListener)
        p.release()
        player = null
        playerView?.player = null
        playerView = null
    }

    companion object {
        private const val TAG: String = "PlayerActivity"

        const val EXTRA_STREAM_URL: String = "app.dirthead.iptv.PlayerActivity.EXTRA_STREAM_URL"

        private const val HTTP_USER_AGENT: String = "Mozilla/5.0"

        private const val MIN_BUFFER_MS: Int = 5000
        private const val MAX_BUFFER_MS: Int = 30000
        private const val BUFFER_FOR_PLAYBACK_MS: Int = 2000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 5000

        /** Kept behind the playhead to smooth brief network gaps on live TS. */
        private const val BACK_BUFFER_MS: Int = 20_000

        private const val HTTP_CONNECT_TIMEOUT_MS: Int = 30_000
        private const val HTTP_READ_TIMEOUT_MS: Int = 120_000

        private const val WATCHDOG_INTERVAL_MS: Long = 10_000L
        private const val BUFFERING_STUCK_THRESHOLD_MS: Long = 10_000L
        private const val ERROR_RETRY_DELAY_MS: Long = 1_500L

        fun intent(context: Context, streamUrl: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_STREAM_URL, streamUrl)
    }
}
