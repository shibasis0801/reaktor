package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * JVM UTF-8 fast paths.
 *
 * Decode: stdlib `decodeToString` routes to `String(byte[], Charset)` with JIT
 * intrinsics (hasNegatives + compress) — already optimal.
 *
 * Encode: JDK 9+ compact strings store ASCII/Latin-1 text as a `byte[]` with
 * coder == LATIN1. For the dominant case (pure-ASCII payload strings), encoding
 * is then a single arraycopy of `String.value` — the C++ `memcpy` equivalent —
 * instead of a char-by-char walk. Falls back to the portable encoder whenever
 * Unsafe or compact strings are unavailable.
 */
internal object StringAccess {
  @JvmField val VALUE_OFFSET: Long = try {
    if (UnsafeOps.ON) UnsafeOps.U!!.objectFieldOffset(String::class.java.getDeclaredField("value")) else -1L
  } catch (t: Throwable) {
    -1L
  }

  @JvmField val CODER_OFFSET: Long = try {
    if (UnsafeOps.ON) UnsafeOps.U!!.objectFieldOffset(String::class.java.getDeclaredField("coder")) else -1L
  } catch (t: Throwable) {
    -1L
  }

  @JvmField val ON: Boolean = UnsafeOps.ON && VALUE_OFFSET >= 0 && CODER_OFFSET >= 0
}

private const val BYTE_HIGH_BITS: Long = -0x7F7F7F7F7F7F7F80L

/** Returns the UTF-8 size of a compact LATIN1 String backing array. */
private fun latin1Utf8Length(value: ByteArray): Int {
    val u = UnsafeOps.U!!
    var extra = 0
    var index = 0
    val wordLimit = value.size and -Long.SIZE_BYTES

    while (index < wordLimit) {
        val word = u.getLong(value, UnsafeOps.BASE + index)
        extra += java.lang.Long.bitCount(word and BYTE_HIGH_BITS)
        index += Long.SIZE_BYTES
    }
    while (index < value.size) {
        if (value[index] < 0) extra++
        index++
    }
    return value.size + extra
}

internal actual fun fastDecodeUtf8(bytes: ByteArray, startIndex: Int, endIndex: Int): String =
    bytes.decodeToString(startIndex, endIndex)

internal actual fun fastEncodeUtf8(input: CharSequence, out: ByteArray, offset: Int): Int {
    if (StringAccess.ON && input is String) {
        val u = UnsafeOps.U!!
        if (u.getByte(input, StringAccess.CODER_OFFSET).toInt() == 0) { // LATIN1
            val v = u.getObject(input, StringAccess.VALUE_OFFSET) as ByteArray
            var neg = false
            for (b in v) {
                if (b < 0) { neg = true; break }
            }
            if (!neg) {
                v.copyInto(out, offset)
                return offset + v.size
            }
            // Latin-1 high bytes → 2-byte UTF-8 sequences.
            var p = offset
            for (b in v) {
                val c = b.toInt() and 0xFF
                if (c < 0x80) {
                    out[p++] = b
                } else {
                    out[p++] = (0xC0 or (c shr 6)).toByte()
                    out[p++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
            return p
        }
    }
    return Utf8.encodeUtf8Array(input, out, offset, out.size - offset)
}

internal actual fun fastEncodeUtf8KnownLength(
    input: CharSequence,
    out: ByteArray,
    offset: Int,
    encodedLength: Int,
): Int {
    if (StringAccess.ON && input is String) {
        val u = UnsafeOps.U!!
        if (u.getByte(input, StringAccess.CODER_OFFSET).toInt() == 0) { // LATIN1
            val v = u.getObject(input, StringAccess.VALUE_OFFSET) as ByteArray
            if (encodedLength == v.size) {
                v.copyInto(out, offset)
                return offset + v.size
            }
            var p = offset
            for (b in v) {
                val c = b.toInt() and 0xFF
                if (c < 0x80) {
                    out[p++] = b
                } else {
                    out[p++] = (0xC0 or (c shr 6)).toByte()
                    out[p++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
            return p
        }
    }
    return Utf8.encodeUtf8Array(input, out, offset, out.size - offset)
}

internal actual fun fastEncodedLength(input: CharSequence): Int {
    if (StringAccess.ON && input is String) {
        val u = UnsafeOps.U!!
        if (u.getByte(input, StringAccess.CODER_OFFSET).toInt() == 0) { // LATIN1
            val v = u.getObject(input, StringAccess.VALUE_OFFSET) as ByteArray
            return latin1Utf8Length(v)
        }
    }
    return Utf8.encodedLength(input)
}
