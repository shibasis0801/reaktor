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

@file:JvmName("JVMByteArray")
@file:Suppress("NOTHING_TO_INLINE")

package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * sun.misc.Unsafe holder. A read through [Unsafe.getInt(Object, long)] compiles to a
 * single unaligned mov on x86/ARM — identical codegen to C++ ReadScalar<T>. No array
 * bounds check, no byte-by-byte composition.
 *
 * [ON] is a static final boolean: HotSpot constant-folds the branch after class init,
 * so the fallback path costs nothing when Unsafe is available. Falls back to
 * ByteArrayOps (byte composition) when Unsafe is unavailable or the platform is
 * big-endian (the wire format is little-endian).
 */
@PublishedApi
internal object UnsafeOps {
  @JvmField val U: sun.misc.Unsafe? = try {
    val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
    f.isAccessible = true
    f.get(null) as sun.misc.Unsafe
  } catch (t: Throwable) {
    null
  }

  @JvmField val BASE: Long = try {
    (U?.arrayBaseOffset(ByteArray::class.java) ?: 16).toLong()
  } catch (t: Throwable) {
    16L
  }

  @JvmField val ON: Boolean =
    U != null && java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN
}

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

// ─── Direct memory layer: Unsafe-backed single-instruction loads/stores ───

internal actual inline fun ByteArray.ld8(index: Int): Int =
  if (UnsafeOps.ON) UnsafeOps.U!!.getByte(this, UnsafeOps.BASE + index).toInt()
  else this[index].toInt()

internal actual inline fun ByteArray.ldU8(index: Int): Int =
  if (UnsafeOps.ON) UnsafeOps.U!!.getByte(this, UnsafeOps.BASE + index).toInt() and 0xFF
  else this[index].toInt() and 0xFF

internal actual inline fun ByteArray.ld16(index: Int): Int =
  if (UnsafeOps.ON) UnsafeOps.U!!.getShort(this, UnsafeOps.BASE + index).toInt()
  else ByteArrayOps.getShort(this, index).toInt()

internal actual inline fun ByteArray.ldU16(index: Int): Int =
  if (UnsafeOps.ON) UnsafeOps.U!!.getShort(this, UnsafeOps.BASE + index).toInt() and 0xFFFF
  else ByteArrayOps.getShort(this, index).toInt() and 0xFFFF

internal actual inline fun ByteArray.ld32(index: Int): Int =
  if (UnsafeOps.ON) UnsafeOps.U!!.getInt(this, UnsafeOps.BASE + index)
  else ByteArrayOps.getInt(this, index)

internal actual inline fun ByteArray.ld64(index: Int): Long =
  if (UnsafeOps.ON) UnsafeOps.U!!.getLong(this, UnsafeOps.BASE + index)
  else ByteArrayOps.getLong(this, index)

internal actual inline fun ByteArray.ldF32(index: Int): Float = Float.fromBits(ld32(index))

internal actual inline fun ByteArray.ldF64(index: Int): Double = Double.fromBits(ld64(index))

internal actual inline fun ByteArray.st8(index: Int, value: Int) {
  this[index] = value.toByte()
}

internal actual inline fun ByteArray.st16(index: Int, value: Int) {
  if (UnsafeOps.ON) UnsafeOps.U!!.putShort(this, UnsafeOps.BASE + index, value.toShort())
  else ByteArrayOps.setShort(this, index, value.toShort())
}

internal actual inline fun ByteArray.st32(index: Int, value: Int) {
  if (UnsafeOps.ON) UnsafeOps.U!!.putInt(this, UnsafeOps.BASE + index, value)
  else ByteArrayOps.setInt(this, index, value)
}

internal actual inline fun ByteArray.st64(index: Int, value: Long) {
  if (UnsafeOps.ON) UnsafeOps.U!!.putLong(this, UnsafeOps.BASE + index, value)
  else ByteArrayOps.setLong(this, index, value)
}

internal actual inline fun ByteArray.stF32(index: Int, value: Float): Unit =
  st32(index, value.toRawBits())

internal actual inline fun ByteArray.stF64(index: Int, value: Double): Unit =
  st64(index, value.toRawBits())
