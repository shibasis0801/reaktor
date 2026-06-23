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
@file:Suppress("NOTHING_TO_INLINE")

package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlin.experimental.and

internal fun ByteArray.getString(index: Int, size: Int): String =
  Utf8.decodeUtf8Array(this, index, size)

internal fun ByteArray.setCharSequence(index: Int, value: CharSequence): Int =
  fastEncodeUtf8(value, this, index)

// List of functions that needs to be implemented on all platforms.
internal expect inline fun ByteArray.getUByte(index: Int): UByte

internal expect inline fun ByteArray.getShort(index: Int): Short

internal expect inline fun ByteArray.getUShort(index: Int): UShort

internal expect inline fun ByteArray.getInt(index: Int): Int

internal expect inline fun ByteArray.getUInt(index: Int): UInt

internal expect inline fun ByteArray.getLong(index: Int): Long

internal expect inline fun ByteArray.getULong(index: Int): ULong

internal expect inline fun ByteArray.getFloat(index: Int): Float

internal expect inline fun ByteArray.getDouble(index: Int): Double

internal expect inline fun ByteArray.setUByte(index: Int, value: UByte)

public expect inline fun ByteArray.setShort(index: Int, value: Short)

internal expect inline fun ByteArray.setUShort(index: Int, value: UShort)

internal expect inline fun ByteArray.setInt(index: Int, value: Int)

internal expect inline fun ByteArray.setUInt(index: Int, value: UInt)

internal expect inline fun ByteArray.setLong(index: Int, value: Long)

internal expect inline fun ByteArray.setULong(index: Int, value: ULong)

internal expect inline fun ByteArray.setFloat(index: Int, value: Float)

internal expect inline fun ByteArray.setDouble(index: Int, value: Double)

// ═══════════════════════════════════════════════════════════════════════════
// Direct memory layer.
//
// Single-load little-endian reads/writes mirroring C++ ReadScalar<T>/Write<T>.
// JVM/Android: sun.misc.Unsafe (one mov, no bounds check, ART/HotSpot intrinsic).
// Native: getIntAt/getLongAt intrinsics (single load + bounds check).
// JS: byte composition for ints; Float64Array view for floats — and crucially,
//     no Kotlin Long is materialised for widths 1/2/4 (Long is emulated on JS).
//
// Naming: ld = load, st = store; U = zero-extended; number = bit width.
// All Int-returning loads of widths 1/2 return the value in an Int register.
// ═══════════════════════════════════════════════════════════════════════════

/** Sign-extended 8-bit load. */
internal expect inline fun ByteArray.ld8(index: Int): Int

/** Zero-extended 8-bit load. */
internal expect inline fun ByteArray.ldU8(index: Int): Int

/** Sign-extended little-endian 16-bit load. */
internal expect inline fun ByteArray.ld16(index: Int): Int

/** Zero-extended little-endian 16-bit load. */
internal expect inline fun ByteArray.ldU16(index: Int): Int

/** Little-endian 32-bit load. */
internal expect inline fun ByteArray.ld32(index: Int): Int

/** Little-endian 64-bit load. */
internal expect inline fun ByteArray.ld64(index: Int): Long

/** Little-endian IEEE-754 32-bit load. */
internal expect inline fun ByteArray.ldF32(index: Int): Float

/** Little-endian IEEE-754 64-bit load. */
internal expect inline fun ByteArray.ldF64(index: Int): Double

internal expect inline fun ByteArray.st8(index: Int, value: Int)

internal expect inline fun ByteArray.st16(index: Int, value: Int)

internal expect inline fun ByteArray.st32(index: Int, value: Int)

internal expect inline fun ByteArray.st64(index: Int, value: Long)

internal expect inline fun ByteArray.stF32(index: Int, value: Float)

internal expect inline fun ByteArray.stF64(index: Int, value: Double)

// ───────────────────────────────────────────────────────────────────────────
// Width-dispatched reads. Two-comparison binary tree, like C++ ReadSizedScalar.
// Offsets and sizes always fit in Int (buffers < 2 GB), so the Int-typed
// variants never touch Long for widths 1/2/4 — the common case.
// ───────────────────────────────────────────────────────────────────────────

/** Unsigned read of width [w] as Int. Use for offsets, sizes, indices. */
internal inline fun ByteArray.ldUOffW(pos: Int, w: Int): Int =
  if (w < 4) {
    if (w == 1) ldU8(pos) else ldU16(pos)
  } else {
    if (w == 4) ld32(pos) else ld64(pos).toInt()
  }

/** Zero-extended read of width [w] as Long. Use for T_UINT values. */
internal inline fun ByteArray.ldULongW(pos: Int, w: Int): Long =
  if (w < 4) {
    (if (w == 1) ldU8(pos) else ldU16(pos)).toLong()
  } else {
    if (w == 4) ld32(pos).toLong() and 0xFFFFFFFFL else ld64(pos)
  }

/** Sign-extended read of width [w] as Long. Use for T_INT values. */
internal inline fun ByteArray.ldSLongW(pos: Int, w: Int): Long =
  if (w < 4) {
    (if (w == 1) ld8(pos) else ld16(pos)).toLong()
  } else {
    if (w == 4) ld32(pos).toLong() else ld64(pos)
  }

/** Sign-extended read of width [w] as Int. Use for T_INT values known to fit. */
internal inline fun ByteArray.ldSIntW(pos: Int, w: Int): Int =
  if (w < 4) {
    if (w == 1) ld8(pos) else ld16(pos)
  } else {
    if (w == 4) ld32(pos) else ld64(pos).toInt()
  }

/** Float read of width [w] (4 or 8) as Double. */
internal inline fun ByteArray.ldFloatW(pos: Int, w: Int): Double =
  if (w == 8) ldF64(pos) else ldF32(pos).toDouble()

/** Width-dispatched write of the low [w] bytes of [value]. */
internal inline fun ByteArray.stW(pos: Int, value: Long, w: Int) {
  if (w < 4) {
    if (w == 1) st8(pos, value.toInt()) else st16(pos, value.toInt())
  } else {
    if (w == 4) st32(pos, value.toInt()) else st64(pos, value)
  }
}

/** This implementation uses Little Endian order. */
public object ByteArrayOps {
  public inline fun getUByte(ary: ByteArray, index: Int): UByte = ary[index].toUByte()

  public inline fun getShort(ary: ByteArray, index: Int): Short {
    return (ary[index + 1].toInt() shl 8 or (ary[index].toInt() and 0xff)).toShort()
  }

  public inline fun getUShort(ary: ByteArray, index: Int): UShort = getShort(ary, index).toUShort()

  public inline fun getInt(ary: ByteArray, index: Int): Int {
    return ((ary[index + 3].toInt() shl 24) or
      ((ary[index + 2].toInt() and 0xff) shl 16) or
      ((ary[index + 1].toInt() and 0xff) shl 8) or
      ((ary[index].toInt() and 0xff)))
  }

  public inline fun getUInt(ary: ByteArray, index: Int): UInt = getInt(ary, index).toUInt()

  public inline fun getLong(ary: ByteArray, index: Int): Long {
    var idx = index
    return ary[idx++].toLong() and
      0xff or
      (ary[idx++].toLong() and 0xff shl 8) or
      (ary[idx++].toLong() and 0xff shl 16) or
      (ary[idx++].toLong() and 0xff shl 24) or
      (ary[idx++].toLong() and 0xff shl 32) or
      (ary[idx++].toLong() and 0xff shl 40) or
      (ary[idx++].toLong() and 0xff shl 48) or
      (ary[idx].toLong() shl 56)
  }

  public inline fun getULong(ary: ByteArray, index: Int): ULong = getLong(ary, index).toULong()

  public inline fun setUByte(ary: ByteArray, index: Int, value: UByte) {
    ary[index] = value.toByte()
  }

  public inline fun setShort(ary: ByteArray, index: Int, value: Short) {
    var idx = index
    ary[idx++] = (value and 0xff).toByte()
    ary[idx] = (value.toInt() shr 8 and 0xff).toByte()
  }

  public inline fun setUShort(ary: ByteArray, index: Int, value: UShort): Unit =
    setShort(ary, index, value.toShort())

  public inline fun setInt(ary: ByteArray, index: Int, value: Int) {
    var idx = index
    ary[idx++] = (value and 0xff).toByte()
    ary[idx++] = (value shr 8 and 0xff).toByte()
    ary[idx++] = (value shr 16 and 0xff).toByte()
    ary[idx] = (value shr 24 and 0xff).toByte()
  }

  public inline fun setUInt(ary: ByteArray, index: Int, value: UInt): Unit =
    setInt(ary, index, value.toInt())

  public inline fun setLong(ary: ByteArray, index: Int, value: Long) {
    var i = value.toInt()
    setInt(ary, index, i)
    i = (value shr 32).toInt()
    setInt(ary, index + 4, i)
  }

  public inline fun setULong(ary: ByteArray, index: Int, value: ULong): Unit =
    setLong(ary, index, value.toLong())

  public inline fun setFloat(ary: ByteArray, index: Int, value: Float) {
    setInt(ary, index, value.toRawBits())
  }

  public inline fun setDouble(ary: ByteArray, index: Int, value: Double) {
    setLong(ary, index, value.toRawBits())
  }

  public inline fun getFloat(ary: ByteArray, index: Int): Float = Float.fromBits(getInt(ary, index))

  public inline fun getDouble(ary: ByteArray, index: Int): Double =
    Double.fromBits(getLong(ary, index))
}
