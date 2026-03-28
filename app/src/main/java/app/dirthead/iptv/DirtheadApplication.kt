package app.dirthead.iptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import java.io.File

/**
 * Registers a singleton Coil [ImageLoader] with a persistent logo/artwork disk cache under [filesDir].
 */
class DirtheadApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val dir = File(filesDir, "logo_image_disk_cache")
        return ImageLoader.Builder(this)
            .diskCache(
                DiskCache.Builder()
                    .directory(dir)
                    .maxSizeBytes(200L * 1024L * 1024L)
                    .build(),
            )
            .respectCacheHeaders(false)
            .build()
    }
}
