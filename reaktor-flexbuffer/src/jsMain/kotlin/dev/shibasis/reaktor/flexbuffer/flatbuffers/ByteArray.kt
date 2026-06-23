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

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Float64Array
import org.khronos.webgl.Int32Array

/** This implementation uses Little Endian order. */
public actual inline fun ByteArray.getUByte(index: Int): UByte = ByteArrayOps.getUByte(this, index)

public actual inline fun ByteArray.getShort(index: Int): Short = ByteArrayOps.getShort(this, index)

public actual inline fun ByteArray.getUShort(index: Int): UShort =
  ByteArrayOps.getUShort(this, index)

public actual inline fun ByteArray.getInt(index: Int): Int = ByteArrayOps.getInt(this, index)

public actual inline fun ByteArray.getUInt(index: Int): UInt = ByteArrayOps.getUInt(this, index)

public actual inline fun ByteArray.getLong(index: Int): Long = ByteArrayOps.getLong(this, index)

public actual inline fun ByteArray.getULong(index: Int): ULong = ByteArrayOps.getULong(this, index)

public actual inline fun ByteArray.getFloat(index: Int): Float = ByteArrayOps.getFloat(this, index)

public actual inline fun ByteArray.getDouble(index: Int): Double =
  ByteArrayOps.getDouble(this, index)

public actual inline fun ByteArray.setUByte(index: Int, value: UByte): Unit =
  ByteArrayOps.setUByte(this, index, value)

public actual inline fun ByteArray.setShort(index: Int, value: Short): Unit =
  ByteArrayOps.setShort(this, index, value)

public actual inline fun ByteArray.setUShort(index: Int, value: UShort): Unit =
  ByteArrayOps.setUShort(this, index, value)

public actual inline fun ByteArray.setInt(index: Int, value: Int): Unit =
  ByteArrayOps.setInt(this, index, value)

public actual inline fun ByteArray.setUInt(index: Int, value: UInt): Unit =
  ByteArrayOps.setUInt(this, index, value)

public actual inline fun ByteArray.setLong(index: Int, value: Long): Unit =
  ByteArrayOps.setLong(this, index, value)

public actual inline fun ByteArray.setULong(index: Int, value: ULong): Unit =
  ByteArrayOps.setULong(this, index, value)

public actual inline fun ByteArray.setFloat(index: Int, value: Float): Unit =
  ByteArrayOps.setFloat(this, index, value)

public actual inline fun ByteArray.setDouble(index: Int, value: Double): Unit =
  ByteArrayOps.setDouble(this, index, value)

// ─── Direct memory layer (JS) ───
//
// Kotlin Long is emulated on JS (a heap object per value), so the cardinal rule
// here is: widths 1/2/4 never touch Long, and floats convert through a shared
// 8-byte Float64Array/Int32Array view instead of Double.fromBits(Long).
// Int composition from byte loads JITs to straight-line code in V8.

/** Shared 8-byte conversion scratch: f64/f32 bits in, number out — no Long. */
@PublishedApi internal val convBuf: ArrayBuffer = ArrayBuffer(8)
@PublishedApi internal val convF64: Float64Array = Float64Array(convBuf)
@PublishedApi internal val convF32: Float32Array = Float32Array(convBuf)
@PublishedApi internal val convI32: Int32Array = Int32Array(convBuf)

internal actual inline fun ByteArray.ld8(index: Int): Int = this[index].toInt()

internal actual inline fun ByteArray.ldU8(index: Int): Int = this[index].toInt() and 0xFF

internal actual inline fun ByteArray.ld16(index: Int): Int =
  (this[index + 1].toInt() shl 8) or (this[index].toInt() and 0xFF)

internal actual inline fun ByteArray.ldU16(index: Int): Int =
  ((this[index + 1].toInt() and 0xFF) shl 8) or (this[index].toInt() and 0xFF)

internal actual inline fun ByteArray.ld32(index: Int): Int =
  (this[index + 3].toInt() shl 24) or
    ((this[index + 2].toInt() and 0xFF) shl 16) or
    ((this[index + 1].toInt() and 0xFF) shl 8) or
    (this[index].toInt() and 0xFF)

internal actual inline fun ByteArray.ld64(index: Int): Long =
  (ld32(index).toLong() and 0xFFFFFFFFL) or (ld32(index + 4).toLong() shl 32)

internal actual inline fun ByteArray.ldF32(index: Int): Float {
  convI32.asDynamic()[0] = ld32(index)
  return (convF32.asDynamic()[0] as Double).toFloat()
}

internal actual inline fun ByteArray.ldF64(index: Int): Double {
  convI32.asDynamic()[0] = ld32(index)
  convI32.asDynamic()[1] = ld32(index + 4)
  return convF64.asDynamic()[0] as Double
}

internal actual inline fun ByteArray.st8(index: Int, value: Int) {
  this[index] = value.toByte()
}

internal actual inline fun ByteArray.st16(index: Int, value: Int) {
  this[index] = value.toByte()
  this[index + 1] = (value shr 8).toByte()
}

internal actual inline fun ByteArray.st32(index: Int, value: Int) {
  this[index] = value.toByte()
  this[index + 1] = (value shr 8).toByte()
  this[index + 2] = (value shr 16).toByte()
  this[index + 3] = (value shr 24).toByte()
}

internal actual inline fun ByteArray.st64(index: Int, value: Long) {
  st32(index, value.toInt())
  st32(index + 4, (value shr 32).toInt())
}

internal actual inline fun ByteArray.stF32(index: Int, value: Float) {
  convF32.asDynamic()[0] = value
  st32(index, convI32.asDynamic()[0] as Int)
}

internal actual inline fun ByteArray.stF64(index: Int, value: Double) {
  convF64.asDynamic()[0] = value
  st32(index, convI32.asDynamic()[0] as Int)
  st32(index + 4, convI32.asDynamic()[1] as Int)
}
