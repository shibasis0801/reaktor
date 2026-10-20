package dev.shibasis.dependeasy.model.tools

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

object Compression {
    fun gzip(source: File, destination: OutputStream) {
        object : GZIPOutputStream(destination) { init { def.setLevel(9) } }.use { compressed ->
            source.inputStream().use { it.copyTo(compressed) }
        }
    }

    fun brotli(source: File, destination: OutputStream) {
        Brotli4jLoader.ensureAvailability()
        BrotliOutputStream(destination, Encoder.Parameters.create(11, 22, Encoder.Mode.GENERIC)).use { compressed ->
            source.inputStream().use { it.copyTo(compressed) }
        }
    }
}
