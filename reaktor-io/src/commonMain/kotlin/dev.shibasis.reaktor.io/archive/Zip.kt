package dev.shibasis.reaktor.io.archive

/**
 * Reads the files inside a zip held in memory: stored and DEFLATE entries, which is every zip an
 * office suite, an e-book tool or a phone writes. No zip64 (over 4 GB) and no encryption.
 *
 * The central directory at the end is the source of truth, so entries written with a data
 * descriptor (sizes after the data, as streaming writers do) read correctly too.
 */
class Zip(private val bytes: ByteArray) {
    class Entry internal constructor(val name: String, internal val method: Int, internal val compressed: Int, val size: Int, internal val local: Int)

    val entries: List<Entry> = readDirectory()

    operator fun get(name: String): ByteArray? = entries.firstOrNull { it.name == name }?.let(::read)

    fun read(entry: Entry): ByteArray {
        if (u32(entry.local) != LOCAL) throw ArchiveException("${entry.name}: no local header where the directory says")
        val start = entry.local + 30 + u16(entry.local + 26) + u16(entry.local + 28)
        if (start + entry.compressed > bytes.size) throw ArchiveException("${entry.name} runs past the end of the file")
        return when (entry.method) {
            0 -> bytes.copyOfRange(start, start + entry.compressed)
            8 -> Inflate.inflate(bytes, start, entry.compressed, entry.size)
            else -> throw ArchiveException("${entry.name} is compressed a way this reader doesn't know (${entry.method})")
        }
    }

    private fun readDirectory(): List<Entry> {
        // The end record is the last thing in the file, before a comment of up to 64 KB.
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 0xFFFF)).firstOrNull { u32(it) == END }
            ?: throw ArchiveException("Not a zip file")
        val count = u16(end + 10)
        var at = u32(end + 16).toInt()
        return List(count) {
            if (u32(at) != CENTRAL) throw ArchiveException("The zip's directory is damaged")
            val nameLength = u16(at + 28)
            val entry = Entry(
                name = bytes.decodeToString(at + 46, at + 46 + nameLength),
                method = u16(at + 10),
                compressed = u32(at + 20).toInt(),
                size = u32(at + 24).toInt(),
                local = u32(at + 42).toInt(),
            )
            at += 46 + nameLength + u16(at + 30) + u16(at + 32)
            entry
        }
    }

    private fun u16(at: Int): Int {
        if (at + 2 > bytes.size) throw ArchiveException("Not a zip file")
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(at: Int): Long {
        if (at < 0 || at + 4 > bytes.size) return -1
        return (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
    }

    companion object {
        private const val LOCAL = 0x04034b50L
        private const val CENTRAL = 0x02014b50L
        private const val END = 0x06054b50L

        /** Whether [bytes] start like a zip: "PK", 3, 4. */
        fun looksLikeZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
    }
}
