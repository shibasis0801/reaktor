package dev.shibasis.reaktor.media.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import okio.Path
import okio.Path.Companion.toPath

const val COIL_CACHE_DIR = "coil_image_cache"

object CoilCache {
    var diskCache: DiskCache? = null
        private set

    val cacheDirectory: Path?
        get() = diskCache?.directory

    fun createImageLoader(
        context: PlatformContext,
        cacheDirectory: Path
    ): ImageLoader {
        val disk = DiskCache.Builder()
            .directory(cacheDirectory / COIL_CACHE_DIR)
            .maxSizeBytes(256L * 1024 * 1024)
            .build()

        diskCache = disk

        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache(disk)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
