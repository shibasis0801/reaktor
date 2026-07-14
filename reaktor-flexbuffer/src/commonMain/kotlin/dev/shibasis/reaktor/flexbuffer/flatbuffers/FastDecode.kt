package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * Per-platform fast UTF-8 → String decoder.
 *
 * Micro-bench finding: Kotlin/Native's stdlib `ByteArray.decodeToString(start, end)`
 * is ~10× slower per byte than JVM's. For a 62-byte avatar URL, JVM took ~30 ns,
 * iOS took ~270 ns — and ApiResponse hits this 100+ times per decode.
 *
 * This expect/actual lets each platform pick the fastest UTF-8 decode path:
 *   - JVM: just delegate to stdlib (already calls into highly optimised native code)
 *   - Native: use ASCII fast-path with NSString fallback
 *   - JS: native TextDecoder/TextEncoder, bypassing the Kotlin StringBuilder path
 */
internal expect fun fastDecodeUtf8(bytes: ByteArray, startIndex: Int, endIndex: Int): String

/**
 * Per-platform fast String → UTF-8 byte writer.
 *
 * Writes the UTF-8 encoding of [input] into [out] starting at [offset].
 * Returns the new write position (offset + bytes written).
 *
 * On Native, the existing `Utf8.encodeUtf8Array` ASCII fast path has a tight loop
 * with `.also { cc = it }` capture that the AOT compiler doesn't fully eliminate.
 * A cleaner direct loop is measurably faster.
 */
internal expect fun fastEncodeUtf8(input: CharSequence, out: ByteArray, offset: Int): Int

/**
 * Same operation when the caller already computed the exact UTF-8 [encodedLength].
 * Platform fast paths use that fact to avoid scanning ASCII/Latin-1 input twice.
 */
internal expect fun fastEncodeUtf8KnownLength(
    input: CharSequence,
    out: ByteArray,
    offset: Int,
    encodedLength: Int,
): Int

/**
 * Per-platform fast UTF-8 byte-length computation for a CharSequence.
 *
 * iOS sample profile showed Utf8.encodedLength taking 6.9% of CPU and driving
 * Kotlin_String_get to 15.2% (it walks every char of the string). For pure-ASCII
 * strings (the majority of serialised data — URLs, names, codes), the byte length
 * equals the character count and we can skip the character-by-character walk.
 *
 * Per-platform implementations can use intrinsics: JVM uses a tight `if (c < 0x80)`
 * loop that JIT vectorises; Native uses CFString/NSString byte-length APIs.
 */
internal expect fun fastEncodedLength(input: CharSequence): Int
