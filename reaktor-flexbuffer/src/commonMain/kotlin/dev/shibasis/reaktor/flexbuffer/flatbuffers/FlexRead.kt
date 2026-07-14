/*
 * Copyright 2021 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * Allocation-free positional readers for generated FlexCoders.
 *
 * A generated `decodeAt(buf, end, byteWidth)` walks the message tree using these
 * statics with hoisted locals (`size`, `typesBase`) — no [Map]/[Vector] object per
 * node, mirroring how C++ navigates with stack references. The only allocations in
 * a generated decode are the result objects themselves.
 *
 * All functions assume the layout produced by the generated keyed encoder:
 * maps with every field present, primitive lists as typed vectors.
 */
public object FlexRead {

  /** Root container byte width, read directly from the root packed-type trailer. */
  public fun rootByteWidth(buf: ByteArray, limit: Int = buf.size): Int =
    1 shl (buf.ldU8(limit - 2) and 3)

  /**
   * Root map data position, resolved directly from the trailer without a [Reference].
   * Generated coders only call this for their map-shaped roots.
   */
  public fun rootEnd(buf: ByteArray, limit: Int = buf.size): Int {
    val parentWidth = buf.ldU8(limit - 1)
    return buf.indirectAt(limit - 2 - parentWidth, parentWidth)
  }

  /**
   * Direct, checked root-map view used by zero-copy accessors.
   *
   * The packed root type is validated before interpreting the root value as a relative
   * map offset. This retains the old [Reference.toMap] behavior for non-map roots while
   * avoiding an intermediate [Reference] allocation on the map hot path.
   */
  public fun rootMap(buf: ByteArray, limit: Int = buf.size): Map {
    if (limit < 2 || buf.ldU8(limit - 2) shr 2 != T_MAP.value) return emptyMap()
    return rootMapUnchecked(buf, limit)
  }

  /** Unchecked root-map view for callers that have already established the root schema. */
  internal fun rootMapUnchecked(buf: ByteArray, limit: Int = buf.size): Map =
    Map(buf, rootEnd(buf, limit), rootByteWidth(buf, limit))

  /** Element count of the map/vector whose data starts at [end]. */
  public fun size(buf: ByteArray, end: Int, bw: Int): Int = buf.ldUOffW(end - bw, bw)

  /** Start of the packed-type byte array for an untyped container. */
  public fun typesBase(end: Int, bw: Int, size: Int): Int = end + size * bw

  /** Resolves child element [i]'s data position (follows the relative offset). */
  public fun childEnd(buf: ByteArray, end: Int, bw: Int, i: Int): Int =
    buf.indirectAt(end + i * bw, bw)

  /** Child element [i]'s own byte width, from its packed type byte. */
  public fun childWidth(buf: ByteArray, tb: Int, i: Int): Int =
    1 shl (buf.ldU8(tb + i) and 3)

  /** True when element [i] is T_NULL. */
  public fun isNull(buf: ByteArray, tb: Int, i: Int): Boolean =
    buf.ldU8(tb + i) shr 2 == 0

  public fun getBoolean(buf: ByteArray, end: Int, bw: Int, i: Int): Boolean =
    buf.ldU8(end + i * bw) != 0

  // Generated keyed encoders know the declared scalar type at compile time, so
  // they do not need to reload and dispatch on the packed type byte for every field.
  public fun inlineInt(buf: ByteArray, end: Int, bw: Int, i: Int): Int =
    buf.ldSIntW(end + i * bw, bw)

  public fun inlineLong(buf: ByteArray, end: Int, bw: Int, i: Int): Long =
    buf.ldSLongW(end + i * bw, bw)

  public fun inlineDouble(buf: ByteArray, end: Int, bw: Int, i: Int): Double =
    buf.ldFloatW(end + i * bw, bw)

  public fun inlineFloat(buf: ByteArray, end: Int, bw: Int, i: Int): Float =
    buf.ldFloatW(end + i * bw, bw).toFloat()

  public fun getInt(buf: ByteArray, end: Int, bw: Int, tb: Int, i: Int): Int {
    val pos = end + i * bw
    val packed = buf.ldU8(tb + i)
    val t = packed shr 2
    if (t == 1) return buf.ldSIntW(pos, bw)
    if (t == 2 || t == 26) return buf.ldUOffW(pos, bw)
    val cw = 1 shl (packed and 3)
    return when (t) {
      6 -> buf.ldSIntW(buf.indirectAt(pos, bw), cw)
      7 -> buf.ldUOffW(buf.indirectAt(pos, bw), cw)
      else -> buf.ldUOffW(pos, bw)
    }
  }

  public fun getLong(buf: ByteArray, end: Int, bw: Int, tb: Int, i: Int): Long {
    val pos = end + i * bw
    val packed = buf.ldU8(tb + i)
    val t = packed shr 2
    if (t == 1) return buf.ldSLongW(pos, bw)
    if (t == 2 || t == 26) return buf.ldULongW(pos, bw)
    val cw = 1 shl (packed and 3)
    return when (t) {
      6 -> buf.ldSLongW(buf.indirectAt(pos, bw), cw)
      7 -> buf.ldULongW(buf.indirectAt(pos, bw), cw)
      else -> buf.ldULongW(pos, bw)
    }
  }

  public fun getDouble(buf: ByteArray, end: Int, bw: Int, tb: Int, i: Int): Double {
    val pos = end + i * bw
    val packed = buf.ldU8(tb + i)
    val t = packed shr 2
    if (t == 3) return buf.ldFloatW(pos, bw)
    return when (t) {
      1 -> buf.ldSLongW(pos, bw).toDouble()
      2, 26 -> buf.ldULongW(pos, bw).toDouble()
      8 -> {
        val cw = 1 shl (packed and 3)
        buf.ldFloatW(buf.indirectAt(pos, bw), cw)
      }
      else -> buf.ldFloatW(pos, bw)
    }
  }

  public fun getFloat(buf: ByteArray, end: Int, bw: Int, tb: Int, i: Int): Float =
    getDouble(buf, end, bw, tb, i).toFloat()

  public fun getString(buf: ByteArray, end: Int, bw: Int, tb: Int, i: Int): String {
    val cw = 1 shl (buf.ldU8(tb + i) and 3)
    val start = buf.indirectAt(end + i * bw, bw)
    val n = buf.ldUOffW(start - cw, cw)
    return fastDecodeUtf8(buf, start, start + n)
  }

  /** Blob bytes at element [i] (size-prefixed). */
  public fun getBlob(buf: ByteArray, end: Int, bw: Int, tb: Int, i: Int): ByteArray {
    val cw = 1 shl (buf.ldU8(tb + i) and 3)
    val start = buf.indirectAt(end + i * bw, bw)
    val n = buf.ldUOffW(start - cw, cw)
    val out = ByteArray(n)
    buf.copyInto(out, 0, start, start + n)
    return out
  }

  // ─── Typed vector element reads (no per-element type byte) ───

  public fun typedInt(buf: ByteArray, ve: Int, vw: Int, j: Int): Int =
    buf.ldSIntW(ve + j * vw, vw)

  public fun typedLong(buf: ByteArray, ve: Int, vw: Int, j: Int): Long =
    buf.ldSLongW(ve + j * vw, vw)

  public fun typedDouble(buf: ByteArray, ve: Int, vw: Int, j: Int): Double =
    buf.ldFloatW(ve + j * vw, vw)

  public fun typedFloat(buf: ByteArray, ve: Int, vw: Int, j: Int): Float =
    buf.ldFloatW(ve + j * vw, vw).toFloat()

  public fun typedBoolean(buf: ByteArray, ve: Int, vw: Int, j: Int): Boolean =
    buf.ldU8(ve + j * vw) != 0

  // ─── Untyped vector element reads (type array at vtb) ───

  public fun vecString(buf: ByteArray, ve: Int, vw: Int, vtb: Int, j: Int): String {
    val cw = 1 shl (buf.ldU8(vtb + j) and 3)
    val start = buf.indirectAt(ve + j * vw, vw)
    val n = buf.ldUOffW(start - cw, cw)
    return fastDecodeUtf8(buf, start, start + n)
  }

  // ─── Bulk typed-vector reads (width-specialized loops) ───

  public fun toIntArray(buf: ByteArray, ve: Int, vw: Int, n: Int): IntArray {
    val out = IntArray(n)
    if (vw == 4) {
      readNaturalIntArray(buf, ve, out)
      return out
    }
    var p = ve
    when (vw) {
      1 -> for (j in 0 until n) { out[j] = buf.ld8(p); p++ }
      2 -> for (j in 0 until n) { out[j] = buf.ld16(p); p += 2 }
      else -> for (j in 0 until n) { out[j] = buf.ld64(p).toInt(); p += 8 }
    }
    return out
  }

  public fun toShortArray(buf: ByteArray, ve: Int, vw: Int, n: Int): ShortArray {
    val out = ShortArray(n)
    if (vw == 2) {
      readNaturalShortArray(buf, ve, out)
      return out
    }
    var p = ve
    when (vw) {
      1 -> for (j in 0 until n) { out[j] = buf.ld8(p).toShort(); p++ }
      4 -> for (j in 0 until n) { out[j] = buf.ld32(p).toShort(); p += 4 }
      else -> for (j in 0 until n) { out[j] = buf.ld64(p).toShort(); p += 8 }
    }
    return out
  }

  public fun toLongArray(buf: ByteArray, ve: Int, vw: Int, n: Int): LongArray {
    val out = LongArray(n)
    if (vw == 8) {
      readNaturalLongArray(buf, ve, out)
      return out
    }
    var p = ve
    when (vw) {
      1 -> for (j in 0 until n) { out[j] = buf.ld8(p).toLong(); p++ }
      2 -> for (j in 0 until n) { out[j] = buf.ld16(p).toLong(); p += 2 }
      4 -> for (j in 0 until n) { out[j] = buf.ld32(p).toLong(); p += 4 }
    }
    return out
  }

  public fun toDoubleArray(buf: ByteArray, ve: Int, vw: Int, n: Int): DoubleArray {
    val out = DoubleArray(n)
    if (vw == 8) {
      readNaturalDoubleArray(buf, ve, out)
    } else {
      var p = ve
      for (j in 0 until n) { out[j] = buf.ldF32(p).toDouble(); p += 4 }
    }
    return out
  }

  public fun toFloatArray(buf: ByteArray, ve: Int, vw: Int, n: Int): FloatArray {
    val out = FloatArray(n)
    if (vw == 4) {
      readNaturalFloatArray(buf, ve, out)
    } else {
      var p = ve
      for (j in 0 until n) { out[j] = buf.ldF64(p).toFloat(); p += 8 }
    }
    return out
  }

  // ─── Map-key access (for Map<String, V> fields) ───

  /** Key vector position for the map whose data starts at [end]. */
  public fun keysEnd(buf: ByteArray, end: Int, bw: Int): Int =
    buf.indirectAt(end - 3 * bw, bw)

  /** Key vector byte width for the map whose data starts at [end]. */
  public fun keysWidth(buf: ByteArray, end: Int, bw: Int): Int =
    buf.ldUOffW(end - 3 * bw + bw, bw)

  /** Key [j] of the map as a String. */
  public fun keyString(buf: ByteArray, ke: Int, kw: Int, j: Int): String {
    val start = buf.indirectAt(ke + j * kw, kw)
    val zero = buf.findZero(start)
    return if (zero > start) fastDecodeUtf8(buf, start, zero) else ""
  }
}
