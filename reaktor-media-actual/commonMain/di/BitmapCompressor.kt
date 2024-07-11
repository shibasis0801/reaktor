package app.mehmaan.media.camera

// Later, kmm does not have file i/o as of now
interface BitmapCompressor {
    fun compress(bitmapFile: ByteArray): ByteArray
    fun decompress(bitmapFile: ByteArray): ByteArray
}