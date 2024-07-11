package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.adapters.*
import dev.shibasis.reaktor.io.adapters.CompressionRequest
import kotlin.test.Test


class CompressorTest {
    @Test
    fun testSmallStringCompression() {
        val string = "Hello how are you, World!"
        val compressor = DarwinCompressionAdapter()
        val compressed = compressor.compress(CompressionRequest(string))
        assert(compressed != null)
        val decompressed = compressor.decompress(DecompressionRequest(compressed!!.base64EncodedString))
        assert(decompressed != null)
        assert(string == decompressed?.data)
    }
}
