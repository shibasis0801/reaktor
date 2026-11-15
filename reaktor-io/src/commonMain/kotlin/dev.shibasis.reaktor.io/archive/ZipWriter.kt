package dev.shibasis.reaktor.io.archive

/**
 * Writes a zip in memory, the other half of [Zip]: add files, then take the [bytes].
 *
 * Entries are stored, not compressed. Every reader accepts that (Excel, Numbers, LibreOffice and
 * Google Sheets open a stored `.xlsx`), it is the same code on every target, and it needs no
 * platform zlib. A stored file is as large as what is in it, so this suits the small files an app
 * shares (a spreadsheet, a handful of documents) and not a large archive. No zip64 (4 GB, 65,535
 * entries) and no encryption, as with the reader.
 *
 * The same files give the same bytes: every entry carries one fixed date (1 January 1980, the
 * earliest a zip can hold), so a test can compare output and a share doesn't leak the clock.
 */
class ZipWriter {
    private class Entry(val name: ByteArray, val crc: Int, val size: Int, val offset: Int)

    private val out = ByteSink()
    private val entries = mutableListOf<Entry>()
    private val names = mutableSetOf<String>()

    /** Adds [bytes] as [name], a path with forward slashes ("xl/workbook.xml"). */
    fun add(name: String, bytes: ByteArray): ZipWriter {
        require(name.isNotEmpty() && !name.startsWith("/") && '\\' !in name) { "A zip entry's name is a relative path with forward slashes: $name" }
        require(names.add(name)) { "$name is already in this zip" }
        require(entries.size < 0xFFFF) { "Too many entries for a zip without zip64" }
        val encoded = name.encodeToByteArray()
        val entry = Entry(encoded, Crc32.of(bytes), bytes.size, out.size)
        out.u32(LOCAL)
        header(entry)
        out.u16(encoded.size)
        out.u16(0) // no extra field
        out.bytes(encoded)
        out.bytes(bytes)
        entries += entry
        return this
    }

    fun add(name: String, text: String): ZipWriter = add(name, text.encodeToByteArray())

    /** The finished zip. The writer can go on adding afterwards; each call gives a whole zip. */
    fun bytes(): ByteArray {
        val all = ByteSink(out.size + entries.sumOf { 46 + it.name.size } + 22)
        all.bytes(out.array(), out.size)
        val directory = all.size
        for (entry in entries) {
            all.u32(CENTRAL)
            all.u16(VERSION) // made by
            all.header(entry)
            all.u16(entry.name.size)
            all.u16(0) // extra
            all.u16(0) // comment
            all.u16(0) // disk it starts on
            all.u16(0) // internal attributes
            all.u32(0) // external attributes
            all.u32(entry.offset.toLong())
            all.bytes(entry.name)
        }
        val directorySize = all.size - directory
        all.u32(END)
        all.u16(0) // this disk
        all.u16(0) // disk the directory starts on
        all.u16(entries.size)
        all.u16(entries.size)
        all.u32(directorySize.toLong())
        all.u32(directory.toLong())
        all.u16(0) // comment
        return all.array().copyOf(all.size)
    }

    /** The fields a local header and a directory entry share, from "version needed" to the sizes. */
    private fun ByteSink.header(entry: Entry) {
        u16(VERSION)
        u16(UTF8_NAMES)
        u16(0) // stored
        u16(0) // time: midnight
        u16(DOS_EPOCH)
        u32(entry.crc.toLong() and 0xFFFFFFFFL)
        u32(entry.size.toLong())
        u32(entry.size.toLong())
    }

    private fun header(entry: Entry) = out.header(entry)

    private companion object {
        const val LOCAL = 0x04034b50L
        const val CENTRAL = 0x02014b50L
        const val END = 0x06054b50L
        const val VERSION = 20
        const val UTF8_NAMES = 0x0800
        const val DOS_EPOCH = 0x0021 // 1 January 1980
    }
}

/** CRC-32 as zip, gzip and PNG use it (polynomial 0xEDB88320). */
object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var c = -1
        for (i in from until to) c = table[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }
}

/** A growing byte array with little-endian writes: what a zip is made of. */
internal class ByteSink(capacity: Int = 1024) {
    private var buffer = ByteArray(maxOf(capacity, 16))
    var size = 0
        private set

    fun array(): ByteArray = buffer

    private fun room(more: Int) {
        if (size + more > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + more))
    }

    fun u16(value: Int) {
        room(2)
        buffer[size++] = value.toByte()
        buffer[size++] = (value ushr 8).toByte()
    }

    fun u32(value: Long) {
        room(4)
        buffer[size++] = value.toByte()
        buffer[size++] = (value ushr 8).toByte()
        buffer[size++] = (value ushr 16).toByte()
        buffer[size++] = (value ushr 24).toByte()
    }

    fun bytes(from: ByteArray, count: Int = from.size) {
        room(count)
        from.copyInto(buffer, size, 0, count)
        size += count
    }
}
