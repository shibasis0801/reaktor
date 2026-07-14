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
@file:OptIn(ExperimentalUnsignedTypes::class)
@file:Suppress("NOTHING_TO_INLINE")

package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlin.jvm.JvmInline

@JvmInline
public value class BitWidth(public val value: Int) {
  public inline fun max(other: BitWidth): BitWidth = if (this.value >= other.value) this else other
}

@JvmInline public value class ByteWidth(public val value: Int)

@JvmInline
public value class FlexBufferType(public val value: Int) {
  public operator fun minus(other: FlexBufferType): FlexBufferType =
    FlexBufferType(this.value - other.value)

  public operator fun plus(other: FlexBufferType): FlexBufferType =
    FlexBufferType(this.value + other.value)

  public operator fun compareTo(other: FlexBufferType): Int = this.value - other.value
}

internal operator fun Int.times(width: ByteWidth): Int = this * width.value

internal operator fun Int.minus(width: ByteWidth): Int = this - width.value

internal operator fun Int.plus(width: ByteWidth): Int = this + width.value

internal operator fun Int.minus(type: FlexBufferType): Int = this - type.value

// ───────────────────────────────────────────────────────────────────────────
// Direct ByteArray navigation — the reader hot path.
// ───────────────────────────────────────────────────────────────────────────

/** Resolves a relative offset: position of the element that [offset] points to. */
internal inline fun ByteArray.indirectAt(offset: Int, byteWidth: Int): Int =
  offset - ldUOffW(offset, byteWidth)

/** Finds the next zero byte at or after [start] (T_KEY terminator). */
internal fun ByteArray.findZero(start: Int): Int {
  var i = start
  val n = size
  while (i < n) {
    if (this[i] == ZeroByte) return i
    i++
  }
  return n
}

/**
 * Returns a Key string from the buffer starting at index [start]. Key Strings are stored as
 * C-Strings, ending with '\0'. If zero byte not found returns empty string.
 */
internal inline fun ByteArray.getKeyString(start: Int): String {
  val end = findZero(start)
  return if (end > start) fastDecodeUtf8(this, start, end) else ""
}

// ───────────────────────────────────────────────────────────────────────────
// Legacy ReadBuffer helpers (kept for ArrayReadBuffer-facing compatibility).
// ───────────────────────────────────────────────────────────────────────────

internal inline fun ReadBuffer.getKeyString(start: Int): String {
  val i = findFirst(0.toByte(), start)
  return if (i >= 0) getString(start, i - start) else ""
}

// read unsigned int with size byteWidth and return as a 64-bit integer
internal inline fun ReadBuffer.readULong(end: Int, byteWidth: ByteWidth): ULong {
  return when (byteWidth.value) {
    1 -> this.getUByte(end).toULong()
    2 -> this.getUShort(end).toULong()
    4 -> this.getUInt(end).toULong()
    8 -> this.getULong(end)
    else -> error("invalid byte width $byteWidth for scalar unsigned integer")
  }
}

internal inline fun ReadBuffer.readFloat(end: Int, byteWidth: ByteWidth): Double {
  return when (byteWidth.value) {
    4 -> this.getFloat(end).toDouble()
    8 -> this.getDouble(end)
    else ->
      error("invalid byte width $byteWidth for floating point scalar") // we should never reach here
  }
}

// return position on the [ReadBuffer] of the element that the offset is pointing to
// we assume all offset fits on a int, since ReadBuffer operates with that assumption
internal inline fun ReadBuffer.indirect(offset: Int, byteWidth: ByteWidth): Int =
  offset - readInt(offset, byteWidth)

// returns the size of an array-like element from [ReadBuffer].
internal inline fun ReadBuffer.readSize(end: Int, byteWidth: ByteWidth) =
  readInt(end - byteWidth, byteWidth)

internal inline fun ReadBuffer.readUInt(end: Int, byteWidth: ByteWidth): UInt =
  readULong(end, byteWidth).toUInt()

internal inline fun ReadBuffer.readInt(end: Int, byteWidth: ByteWidth): Int =
  readULong(end, byteWidth).toInt()

internal inline fun ReadBuffer.readLong(end: Int, byteWidth: ByteWidth): Long =
  readULong(end, byteWidth).toLong()

// ───────────────────────────────────────────────────────────────────────────
// Width computation.
// ───────────────────────────────────────────────────────────────────────────

internal fun ULong.widthInUBits(): BitWidth =
  when {
    this <= MAX_UBYTE_ULONG -> W_8
    this <= UShort.MAX_VALUE -> W_16
    this <= UInt.MAX_VALUE -> W_32
    else -> W_64
  }

/**
 * Minimum bit width that stores [this] as a sign-extended two's-complement value.
 * Mirrors C++ flexbuffers::WidthI: shift the magnitude left so the sign bit is
 * counted, then measure as unsigned. Negative values no longer force 64-bit
 * storage — `-5` fits in one byte, exactly like the C++ reference.
 */
internal fun Long.signedWidthInUBits(): BitWidth {
  val u = this shl 1
  return (if (this >= 0) u else u.inv()).toULong().widthInUBits()
}

/** Int-only [signedWidthInUBits]: no Long math — JS encodes ints without emulation. */
internal fun Int.signedWidthInUBits(): BitWidth {
  val u = this shl 1
  val x = if (this >= 0) u else u.inv()
  return when {
    x ushr 8 == 0 -> W_8
    x ushr 16 == 0 -> W_16
    else -> W_32
  }
}

internal fun IntArray.widthInUBits(): BitWidth =
  arrayWidthInUBits(this.size, W_32) { this[it].signedWidthInUBits() }

internal fun ShortArray.widthInUBits(): BitWidth =
  arrayWidthInUBits(this.size, W_16) { this[it].toInt().signedWidthInUBits() }

internal fun LongArray.widthInUBits(): BitWidth =
  arrayWidthInUBits(this.size, W_64) { this[it].signedWidthInUBits() }

internal fun UByteArray.widthInUBits(): BitWidth =
  arrayWidthInUBits(this.size, W_8) { W_8 }

internal fun UShortArray.widthInUBits(): BitWidth =
  arrayWidthInUBits(this.size, W_16) { this[it].toULong().widthInUBits() }

internal fun UIntArray.widthInUBits(): BitWidth =
  arrayWidthInUBits(this.size, W_32) { this[it].toULong().widthInUBits() }

internal fun ULongArray.widthInUBits(): BitWidth =
  arrayWidthInUBits(this.size, W_64) { this[it].widthInUBits() }

internal fun listIntWidthInUBits(list: List<Int>): BitWidth =
  arrayWidthInUBits(list.size, W_32) { list[it].signedWidthInUBits() }

internal fun listLongWidthInUBits(list: List<Long>): BitWidth =
  arrayWidthInUBits(list.size, W_64) { list[it].signedWidthInUBits() }

private inline fun arrayWidthInUBits(
  size: Int,
  maximumElementWidth: BitWidth,
  crossinline elemWidthBlock: (Int) -> BitWidth,
): BitWidth {
  // The vector prefix uses the same width as its elements. If the size prefix
  // already reaches the element type's maximum, scanning cannot increase it.
  val sizeWidth = size.toULong().widthInUBits()
  var bitWidth = W_8.max(sizeWidth)
  val terminalWidth = maximumElementWidth.max(sizeWidth)
  if (bitWidth == terminalWidth) return bitWidth

  // Check bit widths and types for all elements.
  for (i in 0 until size) {
    // Since these are inline scalar types, element width is value width.
    bitWidth = bitWidth.max(elemWidthBlock(i))
    if (bitWidth == terminalWidth) return terminalWidth
  }
  return bitWidth
}

// returns the number of bytes needed for padding the scalar of size scalarSize.
internal inline fun paddingBytes(bufSize: Int, scalarSize: Int): Int =
  bufSize.inv() + 1 and scalarSize - 1

internal inline fun FlexBufferType.isInline(): Boolean =
  this.value <= T_FLOAT.value || this == T_BOOL

internal fun FlexBufferType.isScalar(): Boolean =
  when (this) {
    T_INT,
    T_UINT,
    T_FLOAT,
    T_BOOL -> true
    else -> false
  }

internal fun FlexBufferType.isIndirectScalar(): Boolean =
  when (this) {
    T_INDIRECT_INT,
    T_INDIRECT_UINT,
    T_INDIRECT_FLOAT -> true
    else -> false
  }

internal fun FlexBufferType.isTypedVector(): Boolean =
  this >= T_VECTOR_INT && this <= T_VECTOR_STRING_DEPRECATED || this == T_VECTOR_BOOL

internal fun FlexBufferType.isTypedVectorElementType(): Boolean =
  (this.value in T_INT.value..T_KEY.value) || this == T_BOOL

// returns the typed vector of a given scalar type.
internal fun FlexBufferType.toTypedVector(): FlexBufferType = (this - T_INT) + T_VECTOR_INT

// returns the element type of given typed vector.
internal fun FlexBufferType.toElementTypedVector(): FlexBufferType = this - T_VECTOR_INT + T_INT

// Holds information about the elements inserted on the buffer.
internal data class Value(
  var type: FlexBufferType = T_INT,
  var key: Int = -1,
  var minBitWidth: BitWidth = W_8,
  var iValue: ULong = 0UL, // integer value
  var dValue: Double = 0.0, // TODO(paulovap): maybe we can keep floating type on iValue as well.
) { // float value

  inline fun storedPackedType(parentBitWidth: BitWidth = W_8): Byte =
    packedType(storedWidth(parentBitWidth), type)

  private inline fun packedType(bitWidth: BitWidth, type: FlexBufferType): Byte =
    (bitWidth.value or (type.value shl 2)).toByte()

  private inline fun storedWidth(parentBitWidth: BitWidth): BitWidth =
    if (type.isInline()) minBitWidth.max(parentBitWidth) else minBitWidth

  fun elemWidth(bufSize: Int, elemIndex: Int): BitWidth =
    elemWidth(type, minBitWidth, iValue.toLong(), bufSize, elemIndex)
}

internal fun elemWidth(
  type: FlexBufferType,
  minBitWidth: BitWidth,
  iValue: Long,
  bufSize: Int,
  elemIndex: Int,
): BitWidth {
  if (type.isInline()) return minBitWidth

  // We have an absolute offset, but want to store a relative offset
  // elem_index elements beyond the current buffer end. Since whether
  // the relative offset fits in a certain byte_width depends on
  // the size of the elements before it (and their alignment), we have
  // to test for each size in turn.
  // Original implementation checks for largest scalar
  // which is long unsigned int
  var byteWidth = 1
  while (byteWidth <= 32) {
    // Where are we going to write this offset?
    val offsetLoc: Int = bufSize + paddingBytes(bufSize, byteWidth) + elemIndex * byteWidth
    // Compute relative offset.
    val offset: Int = offsetLoc - iValue.toInt()
    // Does it fit?
    val bitWidth = offset.toULong().widthInUBits()
    if (1 shl bitWidth.value == byteWidth) return bitWidth
    byteWidth *= 2
  }
  return W_64
}

// For debugging purposes, convert type to a human-readable string.
internal fun FlexBufferType.typeToString(): String =
  when (this) {
    T_NULL -> "Null"
    T_INT -> "Int"
    T_UINT -> "UInt"
    T_FLOAT -> "Float"
    T_KEY -> "Key"
    T_STRING -> "String"
    T_INDIRECT_INT -> "IndirectInt"
    T_INDIRECT_UINT -> "IndirectUInt"
    T_INDIRECT_FLOAT -> "IndirectFloat"
    T_MAP -> "Map"
    T_VECTOR -> "Vector"
    T_VECTOR_INT -> "IntVector"
    T_VECTOR_UINT -> "UIntVector"
    T_VECTOR_FLOAT -> "FloatVector"
    T_VECTOR_KEY -> "KeyVector"
    T_VECTOR_STRING_DEPRECATED -> "StringVectorDeprecated"
    T_VECTOR_INT2 -> "Int2Vector"
    T_VECTOR_UINT2 -> "UInt2Vector"
    T_VECTOR_FLOAT2 -> "Float2Vector"
    T_VECTOR_INT3 -> "Int3Vector"
    T_VECTOR_UINT3 -> "UInt3Vector"
    T_VECTOR_FLOAT3 -> "Float3Vector"
    T_VECTOR_INT4 -> "Int4Vector"
    T_VECTOR_UINT4 -> "UInt4Vector"
    T_VECTOR_FLOAT4 -> "Float4Vector"
    T_BLOB -> "BlobVector"
    T_BOOL -> "BoolVector"
    T_VECTOR_BOOL -> "BoolVector"
    else -> "UnknownType"
  }

// Few repeated values used in hot path are cached here.
// EMPTY_BUFFER index 0 is a zero byte → size reads on the empty singletons return 0.
internal val EMPTY_BUFFER = ByteArray(4)

internal fun emptyBlob() = Blob(EMPTY_BUFFER, 1, 1)

internal fun emptyVector() = Vector(EMPTY_BUFFER, 1, 1)

internal fun emptyMap() = Map(EMPTY_BUFFER, 3, 1)

internal fun nullReference() = Reference(EMPTY_BUFFER, 1, 1, T_NULL.value)

internal fun nullKey() = Key(EMPTY_BUFFER, 1)

internal const val ZeroByte = 0.toByte()
internal const val MAX_UBYTE_ULONG = 255UL
internal const val MAX_UBYTE = 255
internal const val MAX_USHORT = 65535

// value bit width possible sizes
internal val W_8 = BitWidth(0)
internal val W_16 = BitWidth(1)
internal val W_32 = BitWidth(2)
internal val W_64 = BitWidth(3)

// These are used as the upper 6 bits of a type field to indicate the actual type.
internal val T_INVALID = FlexBufferType(-1)
internal val T_NULL = FlexBufferType(0)
internal val T_INT = FlexBufferType(1)
internal val T_UINT = FlexBufferType(2)
internal val T_FLOAT =
  FlexBufferType(3) // Types above stored inline, types below are stored in an offset.
internal val T_KEY = FlexBufferType(4)
internal val T_STRING = FlexBufferType(5)
internal val T_INDIRECT_INT = FlexBufferType(6)
internal val T_INDIRECT_UINT = FlexBufferType(7)
internal val T_INDIRECT_FLOAT = FlexBufferType(8)
internal val T_MAP = FlexBufferType(9)
internal val T_VECTOR = FlexBufferType(10) // Untyped.
internal val T_VECTOR_INT = FlexBufferType(11) // Typed any size  = stores no type table).
internal val T_VECTOR_UINT = FlexBufferType(12)
internal val T_VECTOR_FLOAT = FlexBufferType(13)
internal val T_VECTOR_KEY = FlexBufferType(14)
// DEPRECATED, use FBT_VECTOR or FBT_VECTOR_KEY instead.
// more info on https://github.com/google/flatbuffers/issues/5627.
internal val T_VECTOR_STRING_DEPRECATED = FlexBufferType(15)
internal val T_VECTOR_INT2 = FlexBufferType(16) // Typed tuple  = no type table; no size field).
internal val T_VECTOR_UINT2 = FlexBufferType(17)
internal val T_VECTOR_FLOAT2 = FlexBufferType(18)
internal val T_VECTOR_INT3 = FlexBufferType(19) // Typed triple  = no type table; no size field).
internal val T_VECTOR_UINT3 = FlexBufferType(20)
internal val T_VECTOR_FLOAT3 = FlexBufferType(21)
internal val T_VECTOR_INT4 = FlexBufferType(22) // Typed quad  = no type table; no size field).
internal val T_VECTOR_UINT4 = FlexBufferType(23)
internal val T_VECTOR_FLOAT4 = FlexBufferType(24)
internal val T_BLOB = FlexBufferType(25)
internal val T_BOOL = FlexBufferType(26)
internal val T_VECTOR_BOOL =
  FlexBufferType(36) // To Allow the same type of conversion of type to vector type
