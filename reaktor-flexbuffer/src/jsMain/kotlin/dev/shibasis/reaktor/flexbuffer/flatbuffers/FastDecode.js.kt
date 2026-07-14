package dev.shibasis.reaktor.flexbuffer.flatbuffers

import org.khronos.webgl.Uint8Array

private val platformTextDecoder: dynamic = js("new TextDecoder('utf-8')")
private val platformTextEncoder: dynamic = js("new TextEncoder()")

/**
 * JS: direct platform TextDecoder/TextEncoder calls. Kotlin's current JS stdlib
 * decoder still expands bytes through a Kotlin StringBuilder; bypassing it is
 * materially faster for string-heavy payloads.
 */
internal actual fun fastDecodeUtf8(bytes: ByteArray, startIndex: Int, endIndex: Int): String {
    val raw = bytes.asDynamic()
    val view = Uint8Array(raw.buffer, raw.byteOffset + startIndex, endIndex - startIndex)
    return platformTextDecoder.decode(view) as String
}

internal actual fun fastEncodeUtf8(input: CharSequence, out: ByteArray, offset: Int): Int =
    Utf8.encodeUtf8Array(input, out, offset, out.size - offset)

internal actual fun fastEncodeUtf8KnownLength(
    input: CharSequence,
    out: ByteArray,
    offset: Int,
    encodedLength: Int,
): Int {
    val raw = out.asDynamic()
    val view = Uint8Array(raw.buffer, raw.byteOffset + offset, out.size - offset)
    val result = platformTextEncoder.encodeInto(input.toString(), view)
    return offset + (result.written as Int)
}

internal actual fun fastEncodedLength(input: CharSequence): Int =
    Utf8.encodedLength(input)
