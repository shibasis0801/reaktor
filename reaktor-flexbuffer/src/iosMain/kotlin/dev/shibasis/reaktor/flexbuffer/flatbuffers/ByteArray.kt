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
@file:OptIn(ExperimentalNativeApi::class)

package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlin.experimental.ExperimentalNativeApi

/**
 * This implementation assumes that of native macOSX64 the byte order of the implementation is
 * Little Endian.
 */
public actual inline fun ByteArray.getUByte(index: Int): UByte = getUByteAt(index)

public actual inline fun ByteArray.getShort(index: Int): Short = getShortAt(index)

public actual inline fun ByteArray.getUShort(index: Int): UShort = getUShortAt(index)

public actual inline fun ByteArray.getInt(index: Int): Int = getIntAt(index)

public actual inline fun ByteArray.getUInt(index: Int): UInt = getUIntAt(index)

public actual inline fun ByteArray.getLong(index: Int): Long = getLongAt(index)

public actual inline fun ByteArray.getULong(index: Int): ULong = getULongAt(index)

public actual inline fun ByteArray.setUByte(index: Int, value: UByte): Unit =
  setUByteAt(index, value)

public actual inline fun ByteArray.setShort(index: Int, value: Short): Unit =
  setShortAt(index, value)

public actual inline fun ByteArray.setUShort(index: Int, value: UShort): Unit =
  setUShortAt(index, value)

public actual inline fun ByteArray.setInt(index: Int, value: Int): Unit = setIntAt(index, value)

public actual inline fun ByteArray.setUInt(index: Int, value: UInt): Unit = setUIntAt(index, value)

public actual inline fun ByteArray.setLong(index: Int, value: Long): Unit = setLongAt(index, value)

public actual inline fun ByteArray.setULong(index: Int, value: ULong): Unit =
  setULongAt(index, value)

public actual inline fun ByteArray.setFloat(index: Int, value: Float): Unit =
  setFloatAt(index, value)

public actual inline fun ByteArray.setDouble(index: Int, value: Double): Unit =
  setDoubleAt(index, value)

public actual inline fun ByteArray.getFloat(index: Int): Float = Float.fromBits(getIntAt(index))

public actual inline fun ByteArray.getDouble(index: Int): Double = Double.fromBits(getLongAt(index))

// ─── Direct memory layer: Kotlin/Native unaligned-load intrinsics ───
// getIntAt/getLongAt compile to single unaligned loads (plus a bounds check the
// AOT compiler can often hoist). Apple targets are little-endian.

internal actual inline fun ByteArray.ld8(index: Int): Int = this[index].toInt()

internal actual inline fun ByteArray.ldU8(index: Int): Int = this[index].toInt() and 0xFF

internal actual inline fun ByteArray.ld16(index: Int): Int = getShortAt(index).toInt()

internal actual inline fun ByteArray.ldU16(index: Int): Int = getShortAt(index).toInt() and 0xFFFF

internal actual inline fun ByteArray.ld32(index: Int): Int = getIntAt(index)

internal actual inline fun ByteArray.ld64(index: Int): Long = getLongAt(index)

internal actual inline fun ByteArray.ldF32(index: Int): Float = Float.fromBits(getIntAt(index))

internal actual inline fun ByteArray.ldF64(index: Int): Double = Double.fromBits(getLongAt(index))

internal actual inline fun ByteArray.st8(index: Int, value: Int) {
  this[index] = value.toByte()
}

internal actual inline fun ByteArray.st16(index: Int, value: Int): Unit =
  setShortAt(index, value.toShort())

internal actual inline fun ByteArray.st32(index: Int, value: Int): Unit = setIntAt(index, value)

internal actual inline fun ByteArray.st64(index: Int, value: Long): Unit = setLongAt(index, value)

internal actual inline fun ByteArray.stF32(index: Int, value: Float): Unit =
  setIntAt(index, value.toRawBits())

internal actual inline fun ByteArray.stF64(index: Int, value: Double): Unit =
  setLongAt(index, value.toRawBits())
