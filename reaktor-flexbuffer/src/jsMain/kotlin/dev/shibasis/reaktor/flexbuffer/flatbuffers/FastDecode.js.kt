package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * JS: stdlib delegate. V8's `TextDecoder` is what stdlib uses internally and
 * is already near-optimal for UTF-8 decoding.
 */
internal actual fun fastDecodeUtf8(bytes: ByteArray, startIndex: Int, endIndex: Int): String =
    bytes.decodeToString(startIndex, endIndex)

internal actual fun fastEncodeUtf8(input: CharSequence, out: ByteArray, offset: Int): Int =
    Utf8.encodeUtf8Array(input, out, offset, out.size - offset)
