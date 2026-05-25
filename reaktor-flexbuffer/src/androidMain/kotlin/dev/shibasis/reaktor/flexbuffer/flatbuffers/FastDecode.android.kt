package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * Android (ART): same as JVM — stdlib delegate is fastest.
 */
internal actual fun fastDecodeUtf8(bytes: ByteArray, startIndex: Int, endIndex: Int): String =
    bytes.decodeToString(startIndex, endIndex)

internal actual fun fastEncodeUtf8(input: CharSequence, out: ByteArray, offset: Int): Int =
    Utf8.encodeUtf8Array(input, out, offset, out.size - offset)
