package app.dirthead.iptv.ui.player

import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ExoPlayer configured like playlist fetches ([User-Agent], redirects, timeouts) so IPTV
 * sources that reject the default ExoPlayer client return 403 / empty body less often.
 *
 * Adds progressive-stream buffering tuned for unreliable MPEG-TS and TS extractors flags
 * for flaky encoders (non-IDR keyframes, access-unit detection).
 */
object IptvExoPlayerFactory {

    /** Same default as [app.dirthead.iptv.data.PlaylistRepository] HTTP requests. */
    const val StreamUserAgent: String = "VLC/3.0.0"

    private const val MIN_BUFFER_MS: Int = 5000
    private const val MAX_BUFFER_MS: Int = 30000
    private const val BUFFER_FOR_PLAYBACK_MS: Int = 2000
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 5000
    private const val BACK_BUFFER_MS: Int = 20_000

    fun create(context: Context): ExoPlayer {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(IptvPlaybackHeadersInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(StreamUserAgent)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
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
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
