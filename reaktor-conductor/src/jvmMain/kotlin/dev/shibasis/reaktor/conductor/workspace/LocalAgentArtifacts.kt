package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ArtifactRef
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** Local private artifacts. Range offsets and sizes are bytes, including for UTF-8 text. */
class LocalAgentArtifacts(private val directory: Path) {
    init { privateDirectory(directory) }
    @Synchronized fun put(text: String, kind: String): ArtifactRef {
        val bytes = text.toByteArray()
        require(bytes.size <= 16_000_000) { "Artifact exceeds 16 MB" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val path = directory.resolve(hash)
        if (!Files.exists(path)) atomicWrite(path, text)
        return ArtifactRef(hash, bytes.size.toLong(), kind)
    }
    fun read(ref: ArtifactRef, offset: Long = 0, limit: Int = 24000): AgentArtifactPage {
        require(ref.id.matches(Regex("[a-f0-9]{64}"))) { "Invalid artifact reference" }
        require(offset in 0..ref.bytes && limit in 4..100000)
        val path = directory.resolve(ref.id)
        require(Files.size(path) == ref.bytes) { "Artifact size changed" }
        val read = RandomAccessFile(path.toFile(), "r").use { file ->
            file.seek(offset)
            ByteArray(minOf(limit.toLong(), ref.bytes - offset).toInt()).also { file.readFully(it) }
        }
        require(read.isEmpty() || read[0].toInt() and 0xc0 != 0x80) { "Offset splits a UTF-8 character; use nextOffset" }
        var count = read.size
        if (offset + count < ref.bytes && count > 0) {
            var lead = count - 1
            while (lead > 0 && read[lead].toInt() and 0xc0 == 0x80) lead--
            val first = read[lead].toInt() and 0xff
            val width = when { first < 0x80 -> 1; first < 0xe0 -> 2; first < 0xf0 -> 3; else -> 4 }
            if (lead + width > count) count = lead
        }
        val bytes = read.copyOf(count)
        val next = (offset + bytes.size).takeIf { it < ref.bytes }
        return AgentArtifactPage(ref, offset, bytes.decodeToString(), next, next != null)
    }
    fun text(ref: ArtifactRef): String {
        require(ref.bytes <= 16_000_000)
        require(ref.id.matches(Regex("[a-f0-9]{64}")))
        return Files.readString(directory.resolve(ref.id))
    }
}
