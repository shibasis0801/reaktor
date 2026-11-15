package dev.shibasis.reaktor.io.archive

/**
 * Raw DEFLATE (RFC 1951) encoding in common code, the other half of [Inflate]: what a zip entry is
 * compressed with.
 *
 * One block with the format's fixed Huffman codes, and back-references found greedily through a
 * hash of three bytes. Text that repeats itself (a spreadsheet's XML, a CSV) comes out at a sixth
 * to a tenth of its size; a platform zlib does a little better with its own code tables, at the
 * price of an `expect` per target. Made for what an app shares (a few megabytes), not for streams.
 */
object Deflate {
    fun deflate(data: ByteArray): ByteArray {
        val out = BitSink(data.size / 4 + 16)
        out.bits(1, 1) // the last block
        out.bits(1, 2) // fixed Huffman codes
        val head = IntArray(HASH_SIZE) { -1 }
        val previous = IntArray(WINDOW)
        var at = 0
        val end = data.size
        while (at < end) {
            var length = 0
            var distance = 0
            if (at + MIN_MATCH <= end) {
                val hash = hash(data, at)
                var candidate = head[hash]
                var tries = CHAIN
                val limit = minOf(MAX_MATCH, end - at)
                while (candidate >= 0 && at - candidate <= WINDOW && tries-- > 0) {
                    // The byte past the best so far has to match, or this one can't be longer.
                    if (length == 0 || data[candidate + length] == data[at + length]) {
                        var n = 0
                        while (n < limit && data[candidate + n] == data[at + n]) n++
                        if (n > length) {
                            length = n
                            distance = at - candidate
                            if (n == limit) break
                        }
                    }
                    candidate = previous[candidate and (WINDOW - 1)]
                }
            }
            if (length >= MIN_MATCH) {
                out.length(length)
                out.distance(distance)
                // Every position inside the match can start a later one.
                repeat(length) {
                    if (at + MIN_MATCH <= end) {
                        val hash = hash(data, at)
                        previous[at and (WINDOW - 1)] = head[hash]
                        head[hash] = at
                    }
                    at++
                }
            } else {
                if (at + MIN_MATCH <= end) {
                    val hash = hash(data, at)
                    previous[at and (WINDOW - 1)] = head[hash]
                    head[hash] = at
                }
                out.literal(data[at].toInt() and 0xFF)
                at++
            }
        }
        out.literal(END_OF_BLOCK)
        return out.bytes()
    }

    private fun hash(data: ByteArray, at: Int): Int {
        val three = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or ((data[at + 2].toInt() and 0xFF) shl 16)
        return ((three * -1640531535) ushr (32 - HASH_BITS)) and (HASH_SIZE - 1)
    }

    /** Bits packed from the low end of each byte, as DEFLATE reads them. */
    private class BitSink(capacity: Int) {
        private var buffer = ByteArray(capacity)
        private var size = 0
        private var hold = 0
        private var held = 0

        /** [count] bits of [value], low bit first: headers and the extra bits of a length or distance. */
        fun bits(value: Int, count: Int) {
            hold = hold or (value shl held)
            held += count
            while (held >= 8) {
                if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                buffer[size++] = hold.toByte()
                hold = hold ushr 8
                held -= 8
            }
        }

        /** A Huffman code, which goes in high bit first. */
        private fun code(code: Int, count: Int) {
            var reversed = 0
            for (i in 0 until count) reversed = reversed or (((code ushr i) and 1) shl (count - 1 - i))
            bits(reversed, count)
        }

        /** A byte, or the end of the block (256), in the fixed code. */
        fun literal(value: Int) = when {
            value < 144 -> code(0x30 + value, 8)
            value < 256 -> code(0x190 + value - 144, 9)
            value < 280 -> code(value - 256, 7)
            else -> code(0xC0 + value - 280, 8)
        }

        fun length(length: Int) {
            var index = LENGTH_BASE.size - 1
            while (LENGTH_BASE[index] > length) index--
            literal(257 + index)
            if (LENGTH_EXTRA[index] > 0) bits(length - LENGTH_BASE[index], LENGTH_EXTRA[index])
        }

        fun distance(distance: Int) {
            var index = DISTANCE_BASE.size - 1
            while (DISTANCE_BASE[index] > distance) index--
            code(index, 5)
            if (DISTANCE_EXTRA[index] > 0) bits(distance - DISTANCE_BASE[index], DISTANCE_EXTRA[index])
        }

        fun bytes(): ByteArray {
            if (held > 0) bits(0, 8 - held)
            return buffer.copyOf(size)
        }
    }

    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val WINDOW = 32_768
    private const val HASH_BITS = 15
    private const val HASH_SIZE = 1 shl HASH_BITS
    private const val END_OF_BLOCK = 256

    /** How many earlier places with the same three bytes are tried: more finds longer repeats, slower. */
    private const val CHAIN = 48

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DISTANCE_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DISTANCE_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
}
