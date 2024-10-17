package dev.shibasis.reaktor.media.image

import androidx.compose.ui.graphics.ImageBitmap

object BitmapCache: Cache<ImageBitmap> {
    private val inMemoryCache = hashMapOf<String, ImageBitmap>()

    override fun store(key: String, contents: ImageBitmap) {
        inMemoryCache[key] = contents
    }

    fun store(key: String, contents: ByteArray) {
        store(key, contents.toImageBitmap())
    }

    override fun retrieve(key: String): ImageBitmap? {
        return inMemoryCache[key]
    }
}


expect fun ByteArray.toImageBitmap(): ImageBitmap
expect fun ImageBitmap.toByteArray(): ByteArray

