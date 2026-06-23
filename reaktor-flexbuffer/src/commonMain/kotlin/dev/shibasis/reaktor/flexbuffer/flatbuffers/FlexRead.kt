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
    var p = ve
    when (vw) {
      1 -> for (j in 0 until n) { out[j] = buf.ld8(p); p++ }
      2 -> for (j in 0 until n) { out[j] = buf.ld16(p); p += 2 }
      4 -> for (j in 0 until n) { out[j] = buf.ld32(p); p += 4 }
      else -> for (j in 0 until n) { out[j] = buf.ld64(p).toInt(); p += 8 }
    }
    return out
  }

  public fun toLongArray(buf: ByteArray, ve: Int, vw: Int, n: Int): LongArray {
    val out = LongArray(n)
    var p = ve
    when (vw) {
      1 -> for (j in 0 until n) { out[j] = buf.ld8(p).toLong(); p++ }
      2 -> for (j in 0 until n) { out[j] = buf.ld16(p).toLong(); p += 2 }
      4 -> for (j in 0 until n) { out[j] = buf.ld32(p).toLong(); p += 4 }
      else -> for (j in 0 until n) { out[j] = buf.ld64(p); p += 8 }
    }
    return out
  }

  public fun toDoubleArray(buf: ByteArray, ve: Int, vw: Int, n: Int): DoubleArray {
    val out = DoubleArray(n)
    var p = ve
    if (vw == 8) {
      for (j in 0 until n) { out[j] = buf.ldF64(p); p += 8 }
    } else {
      for (j in 0 until n) { out[j] = buf.ldF32(p).toDouble(); p += 4 }
    }
    return out
  }

  public fun toFloatArray(buf: ByteArray, ve: Int, vw: Int, n: Int): FloatArray {
    val out = FloatArray(n)
    var p = ve
    if (vw == 4) {
      for (j in 0 until n) { out[j] = buf.ldF32(p); p += 4 }
    } else {
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
