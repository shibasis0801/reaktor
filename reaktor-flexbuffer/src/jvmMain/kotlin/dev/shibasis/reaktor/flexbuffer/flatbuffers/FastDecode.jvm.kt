package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * JVM: just delegate to stdlib. ByteArray.decodeToString() routes to
 * java.lang.String(byte[], int, int, Charset) which is heavily optimised
 * via JIT intrinsics.
 */
internal actual fun fastDecodeUtf8(bytes: ByteArray, startIndex: Int, endIndex: Int): String =
    bytes.decodeToString(startIndex, endIndex)

internal actual fun fastEncodeUtf8(input: CharSequence, out: ByteArray, offset: Int): Int =
    Utf8.encodeUtf8Array(input, out, offset, out.size - offset)
