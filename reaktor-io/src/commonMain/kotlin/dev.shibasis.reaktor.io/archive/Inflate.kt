package dev.shibasis.reaktor.io.archive

/** A compressed stream that ends early or holds codes DEFLATE doesn't have. */
class ArchiveException(message: String) : Exception(message)

/**
 * Raw DEFLATE (RFC 1951) decoding in common code, for reading zip files (an `.xlsx`, an `.epub`, a
 * backup) the same way on every platform without a native library.
 *
 * Written after Mark Adler's `puff.c`: codes are decoded a bit at a time against canonical
 * Huffman tables, which keeps it short and checkable rather than fast. It is meant for files a
 * person picks (a spreadsheet of a few megabytes), not for streaming.
 */
object Inflate {
    fun inflate(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, expectedSize: Int = length * 4): ByteArray {
        val input = Bits(data, offset, offset + length)
        val out = Output(maxOf(expectedSize, 64))
        do {
            val last = input.bits(1) == 1
            when (input.bits(2)) {
                0 -> stored(input, out)
                1 -> codes(input, out, FixedLengths, FixedDistances)
                2 -> dynamic(input, out)
                else -> throw ArchiveException("Unknown DEFLATE block type")
            }
        } while (!last)
        return out.toByteArray()
    }

    private class Bits(private val data: ByteArray, var pos: Int, private val end: Int) {
        private var buffer = 0
        private var count = 0

        fun bits(n: Int): Int {
            var value = buffer
            while (count < n) {
                if (pos >= end) throw ArchiveException("Compressed data ends early")
                value = value or ((data[pos++].toInt() and 0xFF) shl count)
                count += 8
            }
            buffer = value ushr n
            count -= n
            return value and ((1 shl n) - 1)
        }

        /** Drops what is left of the current byte: a stored block starts on a byte boundary. */
        fun align() {
            buffer = 0
            count = 0
        }

        fun byte(): Int {
            if (pos >= end) throw ArchiveException("Compressed data ends early")
            return data[pos++].toInt() and 0xFF
        }
    }

    private class Output(capacity: Int) {
        private var bytes = ByteArray(capacity)
        var size = 0
            private set

        fun add(b: Int) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = b.toByte()
        }

        fun copy(distance: Int, length: Int) {
            if (distance > size) throw ArchiveException("A back-reference reaches before the start")
            repeat(length) { add(bytes[size - distance].toInt()) }
        }

        fun toByteArray() = bytes.copyOf(size)
    }

    /** A canonical Huffman code: how many codes of each length, and the symbols in code order. */
    private class Huffman(lengths: IntArray, n: Int = lengths.size) {
        val count = IntArray(MAX_BITS + 1)
        val symbol = IntArray(n)

        init {
            for (i in 0 until n) count[lengths[i]]++
            val offsets = IntArray(MAX_BITS + 2)
            for (len in 1..MAX_BITS) offsets[len + 1] = offsets[len] + count[len]
            for (i in 0 until n) if (lengths[i] != 0) symbol[offsets[lengths[i]]++] = i
        }
    }

    private fun decode(input: Bits, h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..MAX_BITS) {
            code = code or input.bits(1)
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first += count
            first = first shl 1
            code = code shl 1
        }
        throw ArchiveException("A code that isn't in the table")
    }

    private fun stored(input: Bits, out: Output) {
        input.align()
        val len = input.byte() or (input.byte() shl 8)
        val nlen = input.byte() or (input.byte() shl 8)
        if (len != (nlen.inv() and 0xFFFF)) throw ArchiveException("A stored block's length doesn't check")
        repeat(len) { out.add(input.byte()) }
    }

    private fun codes(input: Bits, out: Output, lengths: Huffman, distances: Huffman) {
        while (true) {
            val symbol = decode(input, lengths)
            when {
                symbol < 256 -> out.add(symbol)
                symbol == 256 -> return
                else -> {
                    val s = symbol - 257
                    if (s >= LENGTH_BASE.size) throw ArchiveException("A length code out of range")
                    val length = LENGTH_BASE[s] + input.bits(LENGTH_EXTRA[s])
                    val d = decode(input, distances)
                    if (d >= DISTANCE_BASE.size) throw ArchiveException("A distance code out of range")
                    out.copy(DISTANCE_BASE[d] + input.bits(DISTANCE_EXTRA[d]), length)
                }
            }
        }
    }

    private fun dynamic(input: Bits, out: Output) {
        val nlen = input.bits(5) + 257
        val ndist = input.bits(5) + 1
        val ncode = input.bits(4) + 4
        if (nlen > 286 || ndist > 30) throw ArchiveException("Too many codes in a block")
        val codeLengths = IntArray(19)
        for (i in 0 until ncode) codeLengths[ORDER[i]] = input.bits(3)
        val lencode = Huffman(codeLengths)
        val lengths = IntArray(nlen + ndist)
        var i = 0
        while (i < nlen + ndist) {
            val symbol = decode(input, lencode)
            if (symbol < 16) {
                lengths[i++] = symbol
                continue
            }
            val (value, times) = when (symbol) {
                16 -> {
                    if (i == 0) throw ArchiveException("A repeat with nothing before it")
                    lengths[i - 1] to 3 + input.bits(2)
                }
                17 -> 0 to 3 + input.bits(3)
                else -> 0 to 11 + input.bits(7)
            }
            if (i + times > nlen + ndist) throw ArchiveException("Repeated lengths run past the end")
            repeat(times) { lengths[i++] = value }
        }
        if (lengths[256] == 0) throw ArchiveException("A block with no end code")
        codes(input, out, Huffman(lengths.copyOfRange(0, nlen)), Huffman(lengths.copyOfRange(nlen, nlen + ndist)))
    }

    private const val MAX_BITS = 15
    private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DISTANCE_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
        4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DISTANCE_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    private val FixedLengths = Huffman(IntArray(288) { i -> when { i < 144 -> 8; i < 256 -> 9; i < 280 -> 7; else -> 8 } })
    private val FixedDistances = Huffman(IntArray(30) { 5 })
}
