package app.dirthead.iptv.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

/**
 * IPTV URLs often lack correct [Content-Type]; hinting MIME helps ExoPlayer pick HLS vs MPEG-TS vs MP4 vs AVI, etc.
 */
object StreamMediaItemBuilder {

    fun fromRawUrl(raw: String): MediaItem? {
        val uri = StreamUrlNormalizer.toPlaybackUri(raw) ?: return null
        return fromUri(uri)
    }

    /**
     * Use when playback starts from an existing [Uri] (e.g. [android.content.Intent] data) so scheme-only URLs
     * still get the same normalization and MIME hints as [fromRawUrl].
     */
    fun fromPlaybackUri(uri: Uri): MediaItem {
        val raw = uri.toString().trim()
        if (raw.isNotEmpty()) {
            fromRawUrl(raw)?.let { return it }
        }
        return fromUri(uri)
    }

    fun fromUri(uri: Uri): MediaItem {
        val url = uri.toString().lowercase()
        val path = uri.path?.lowercase().orEmpty()
        val lastSeg = path.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val b = MediaItem.Builder().setUri(uri)
        when {
            url.contains(".m3u8") || lastSeg.endsWith(".m3u8") ->
                b.setMimeType(MimeTypes.APPLICATION_M3U8)
            lastSeg.endsWith(".mp4") || lastSeg.endsWith(".m4v") ||
                lastSeg.endsWith(".mov") || lastSeg.endsWith(".qt") ->
                b.setMimeType(MimeTypes.VIDEO_MP4)
            lastSeg.endsWith(".mkv") ->
                b.setMimeType(MimeTypes.VIDEO_MATROSKA)
            lastSeg.endsWith(".webm") ->
                b.setMimeType(MimeTypes.VIDEO_WEBM)
            lastSeg.endsWith(".avi") ->
                b.setMimeType(MimeTypes.VIDEO_AVI)
            lastSeg.endsWith(".flv") ->
                b.setMimeType(MimeTypes.VIDEO_FLV)
            lastSeg.endsWith(".mpeg") || lastSeg.endsWith(".mpg") ||
                lastSeg.endsWith(".mpe") || lastSeg.endsWith(".dat") ->
                b.setMimeType(MimeTypes.VIDEO_MPEG)
            lastSeg.endsWith(".ogv") ->
                b.setMimeType(MimeTypes.VIDEO_OGG)
            lastSeg.endsWith(".3gp") || lastSeg.endsWith(".3gpp") || lastSeg.endsWith(".3g2") ->
                b.setMimeType(MimeTypes.VIDEO_H263)
            lastSeg.endsWith(".divx") ->
                b.setMimeType(MimeTypes.VIDEO_DIVX)
            lastSeg.endsWith(".mjpeg") || lastSeg.endsWith(".mjpg") ->
                b.setMimeType(MimeTypes.VIDEO_MJPEG)
            lastSeg.endsWith(".vob") ->
                b.setMimeType(MimeTypes.VIDEO_PS)
            url.contains("/live/") ->
                b.setMimeType(MimeTypes.VIDEO_MP2T)
            lastSeg.endsWith(".ts") || url.contains(".ts") ->
                b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        return b.build()
    }
}
