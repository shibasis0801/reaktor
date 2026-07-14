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
@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlin.jvm.JvmField

/**
 * Reads a FlexBuffer message from a raw [ByteArray] and returns a [Reference] to the root.
 *
 * The reader operates directly on the array — no wrapper, no interface dispatch.
 * Every scalar access compiles to a direct load via the platform memory layer.
 *
 * @param bytes array containing the FlexBuffer message
 * @param limit logical end of the message (defaults to the full array)
 */
public fun getRoot(bytes: ByteArray, limit: Int = bytes.size): Reference {
  var end = limit
  val byteWidth = bytes.ldU8(--end)
  val packedType = bytes.ldU8(--end)
  end -= byteWidth // The root data item.
  return Reference(bytes, end, byteWidth, packedType)
}

/**
 * Compatibility entry point for [ReadBuffer]-typed callers.
 *
 * Array-backed buffers (the only implementation in practice) are unwrapped to their
 * backing array with zero copies — positions in the message are relative to its end,
 * so a non-zero slice offset needs no translation. Exotic implementations fall back
 * to one defensive copy.
 */
public fun getRoot(buffer: ReadBuffer): Reference =
  when (buffer) {
    is ArrayReadBuffer -> getRoot(buffer.data(), buffer.offset + buffer.limit)
    else -> getRoot(buffer.data(), buffer.limit)
  }

/**
 * Represents an generic element in the buffer. It can be specialized into scalar types, using for
 * example, [Reference.toInt], or casted into Flexbuffer object types, like [Reference.toMap] or
 * [Reference.toBlob].
 */
@Suppress("NOTHING_TO_INLINE")
public class Reference
internal constructor(
  @JvmField internal val buf: ByteArray,
  @JvmField internal val end: Int,
  @JvmField internal val parentWidth: Int,
  @JvmField internal val byteWidth: Int,
  public val type: FlexBufferType,
) {

  internal constructor(
    buf: ByteArray,
    end: Int,
    parentWidth: Int,
    packedType: Int,
  ) : this(
    buf,
    end,
    parentWidth,
    1 shl (packedType and 3),
    FlexBufferType(packedType shr 2),
  )

  /**
   * Checks whether the element is null type
   *
   * @return true if null type
   */
  public val isNull: Boolean
    get() = type == T_NULL

  /**
   * Checks whether the element is boolean type
   *
   * @return true if boolean type
   */
  public val isBoolean: Boolean
    get() = type == T_BOOL

  /**
   * Checks whether the element type is numeric (signed/unsigned integers and floats)
   *
   * @return true if numeric type
   */
  public val isNumeric: Boolean
    get() = isIntOrUInt || isFloat

  /**
   * Checks whether the element type is signed or unsigned integers
   *
   * @return true if an integer type
   */
  public val isIntOrUInt: Boolean
    get() = isInt || isUInt

  /**
   * Checks whether the element type is float
   *
   * @return true if a float type
   */
  public val isFloat: Boolean
    get() = type == T_FLOAT || type == T_INDIRECT_FLOAT

  /**
   * Checks whether the element type is signed integer
   *
   * @return true if a signed integer type
   */
  public val isInt: Boolean
    get() = type == T_INT || type == T_INDIRECT_INT

  /**
   * Checks whether the element type is signed integer
   *
   * @return true if a signed integer type
   */
  public val isUInt: Boolean
    get() = type == T_UINT || type == T_INDIRECT_UINT

  /**
   * Checks whether the element type is string
   *
   * @return true if a string type
   */
  public val isString: Boolean
    get() = type == T_STRING

  /**
   * Checks whether the element type is key
   *
   * @return true if a key type
   */
  public val isKey: Boolean
    get() = type == T_KEY

  /**
   * Checks whether the element type is vector or a map. [TypedVector] are considered different
   * types and will return false.
   *
   * @return true if a vector type
   */
  public val isVector: Boolean
    get() = type == T_VECTOR || type == T_MAP

  /**
   * Checks whether the element type is typed vector
   *
   * @return true if a typed vector type
   */
  public val isTypedVector: Boolean
    get() = type.isTypedVector()

  /**
   * Checks whether the element type is a map
   *
   * @return true if a map type
   */
  public val isMap: Boolean
    get() = type == T_MAP

  /**
   * Checks whether the element type is a blob
   *
   * @return true if a blob type
   */
  public val isBlob: Boolean
    get() = type == T_BLOB

  /** Assumes [Reference] as a [Vector] and returns a [Reference] at index [index]. */
  public operator fun get(index: Int): Reference = toVector()[index]

  /** Assumes [Reference] as a [Map] and returns a [Reference] for the value at key [key]. */
  public operator fun get(key: String): Reference = toMap()[key]

  /**
   * Returns element as a [Boolean]. If element type is not boolean, it will be casted to integer
   * and compared against 0
   *
   * @return element as [Boolean]
   */
  public fun toBoolean(): Boolean =
    if (isBoolean) buf.ldU8(end) != 0 else toULong() != 0UL

  /**
   * Returns element as [Byte]. Unsigned elements will become signed (with possible overflow).
   *
   * @return [Byte] or 0 if fail to convert element to integer.
   */
  public fun toByte(): Byte = toLong().toByte()

  /**
   * Returns element as [Short]. Unsigned elements will become signed (with possible overflow).
   *
   * @return [Short] or 0 if fail to convert element to integer.
   */
  public fun toShort(): Short = toLong().toShort()

  /**
   * Returns element as [Int]. Unsigned elements will become signed (with possible overflow).
   *
   * @return [Int] or 0 if fail to convert element to integer.
   */
  public fun toInt(): Int =
    when (type) {
      T_INT -> buf.ldSIntW(end, parentWidth)
      T_UINT,
      T_BOOL -> buf.ldUOffW(end, parentWidth)
      T_INDIRECT_INT -> buf.ldSIntW(buf.indirectAt(end, parentWidth), byteWidth)
      T_INDIRECT_UINT -> buf.ldUOffW(buf.indirectAt(end, parentWidth), byteWidth)
      else -> toLong().toInt()
    }

  /**
   * Returns element as [Long]. For vector types, it will return size of the vector For String type,
   * it will type to be parsed as integer Unsigned elements will become negative Float elements will
   * be casted to integer
   *
   * @return [Long] integer or 0 if fail to convert element to long.
   */
  public fun toLong(): Long =
    when (type) {
      T_INT -> buf.ldSLongW(end, parentWidth)
      T_UINT,
      T_BOOL -> buf.ldULongW(end, parentWidth)
      T_INDIRECT_INT -> buf.ldSLongW(buf.indirectAt(end, parentWidth), byteWidth)
      T_INDIRECT_UINT -> buf.ldULongW(buf.indirectAt(end, parentWidth), byteWidth)
      T_FLOAT -> buf.ldFloatW(end, parentWidth).toLong()
      T_INDIRECT_FLOAT -> buf.ldFloatW(buf.indirectAt(end, parentWidth), byteWidth).toLong()
      T_STRING -> toString().toLongOrNull() ?: 0L
      T_VECTOR -> toVector().size.toLong()
      else -> 0L
    }

  /**
   * Returns element as [UByte]. Negative elements will become unsigned counterpart.
   *
   * @return [UByte] or 0 if fail to convert element to integer.
   */
  public fun toUByte(): UByte = toULong().toUByte()

  /**
   * Returns element as [UShort]. Negative elements will become unsigned counterpart.
   *
   * @return [UShort] or 0 if fail to convert element to integer.
   */
  public fun toUShort(): UShort = toULong().toUShort()

  /**
   * Returns element as [UInt]. Negative elements will become unsigned counterpart.
   *
   * @return [UInt] or 0 if fail to convert element to integer.
   */
  public fun toUInt(): UInt = toULong().toUInt()

  /**
   * Returns element as [ULong] integer. For vector types, it will return size of the vector For
   * String type, it will type to be parsed as integer Negative elements will become unsigned
   * counterpart. Float elements will be casted to integer
   *
   * @return [ULong] integer or 0 if fail to convert element to long.
   */
  public fun toULong(): ULong =
    when (type) {
      T_INT -> buf.ldSLongW(end, parentWidth).toULong()
      T_UINT,
      T_BOOL -> buf.ldULongW(end, parentWidth).toULong()
      T_INDIRECT_INT -> buf.ldSLongW(buf.indirectAt(end, parentWidth), byteWidth).toULong()
      T_INDIRECT_UINT -> buf.ldULongW(buf.indirectAt(end, parentWidth), byteWidth).toULong()
      T_FLOAT -> buf.ldFloatW(end, parentWidth).toULong()
      T_INDIRECT_FLOAT -> buf.ldFloatW(buf.indirectAt(end, parentWidth), byteWidth).toULong()
      T_STRING -> toString().toULongOrNull() ?: 0UL
      T_VECTOR -> toVector().size.toULong()
      else -> 0UL
    }

  /**
   * Returns element as [Float].
   *
   * @return [Float] or 0 if fail to convert element to float.
   */
  public fun toFloat(): Float = toDouble().toFloat()

  /**
   * Returns element as [Double].
   *
   * @return [Double] or 0 if fail to convert element to double.
   */
  public fun toDouble(): Double =
    when (type) {
      T_FLOAT -> buf.ldFloatW(end, parentWidth)
      T_INDIRECT_FLOAT -> buf.ldFloatW(buf.indirectAt(end, parentWidth), byteWidth)
      T_INT -> buf.ldSLongW(end, parentWidth).toDouble()
      T_UINT,
      T_BOOL -> buf.ldULongW(end, parentWidth).toDouble()
      T_INDIRECT_INT -> buf.ldSLongW(buf.indirectAt(end, parentWidth), byteWidth).toDouble()
      T_INDIRECT_UINT -> buf.ldULongW(buf.indirectAt(end, parentWidth), byteWidth).toDouble()
      T_NULL -> 0.0
      T_STRING -> toString().toDoubleOrNull() ?: 0.0
      T_VECTOR -> toVector().size.toDouble()
      else -> 0.0
    }

  /** Returns element as [Key] or invalid key. */
  public fun toKey(): Key =
    when (type) {
      T_KEY -> Key(buf, buf.indirectAt(end, parentWidth))
      else -> nullKey()
    }

  /**
   * Returns element as a [String]
   *
   * @return element as [String] or empty [String] if fail
   */
  override fun toString(): String =
    when (type) {
      T_STRING -> {
        val start = buf.indirectAt(end, parentWidth)
        val size = buf.ldUOffW(start - byteWidth, byteWidth)
        fastDecodeUtf8(buf, start, start + size)
      }
      T_KEY -> buf.getKeyString(buf.indirectAt(end, parentWidth))
      T_MAP -> "{ ${toMap().entries.joinToString(", ") { "${it.key}: ${it.value}"}} }"
      T_VECTOR,
      T_VECTOR_BOOL,
      T_VECTOR_FLOAT,
      T_VECTOR_INT,
      T_VECTOR_UINT,
      T_VECTOR_KEY,
      T_VECTOR_STRING_DEPRECATED -> "[ ${toVector().joinToString(", ") { it.toString() }} ]"
      T_INT -> toLong().toString()
      T_UINT -> toULong().toString()
      T_FLOAT -> toDouble().toString()
      else -> "${type.typeToString()}(end=$end)"
    }

  /**
   * Returns element as a [ByteArray], converting scalar types when possible.
   *
   * @return element as [ByteArray] or empty [ByteArray] if fail.
   */
  public fun toByteArray(): ByteArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_INT -> if (vec.byteWidth == 1) {
        ByteArray(vec.size).also { vec.buf.copyInto(it, startIndex = vec.end, endIndex = vec.end + vec.size) }
      } else {
        ByteArray(vec.size) { vec.readTypedInt(it).toByte() }
      }
      T_VECTOR_UINT -> if (vec.byteWidth == 1) {
        ByteArray(vec.size).also { vec.buf.copyInto(it, startIndex = vec.end, endIndex = vec.end + vec.size) }
      } else {
        ByteArray(vec.size) { vec.readTypedUInt(it).toByte() }
      }
      T_VECTOR -> ByteArray(vec.size) { vec[it].toByte() }
      T_VECTOR_FLOAT -> ByteArray(vec.size) { vec.readTypedFloat(it).toInt().toByte() }
      else -> ByteArray(0)
    }
  }

  /**
   * Returns element as a [ShortArray], converting scalar types when possible.
   *
   * @return element as [ShortArray] or empty [ShortArray] if fail.
   */
  public fun toShortArray(): ShortArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_INT -> if (vec.byteWidth == 2) {
        FlexRead.toShortArray(vec.buf, vec.end, vec.byteWidth, vec.size)
      } else {
        ShortArray(vec.size) { vec.readTypedInt(it).toShort() }
      }
      T_VECTOR_UINT -> if (vec.byteWidth == 2) {
        // At the natural width the signed destination observes the same low 16 bits.
        FlexRead.toShortArray(vec.buf, vec.end, vec.byteWidth, vec.size)
      } else {
        ShortArray(vec.size) { vec.readTypedUInt(it).toShort() }
      }
      T_VECTOR -> ShortArray(vec.size) { vec[it].toShort() }
      T_VECTOR_FLOAT -> ShortArray(vec.size) { vec.readTypedFloat(it).toInt().toShort() }
      else -> ShortArray(0)
    }
  }

  /**
   * Returns element as a [IntArray], converting scalar types when possible.
   *
   * @return element as [IntArray] or empty [IntArray] if fail.
   */
  public fun toIntArray(): IntArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_INT -> vec.toIntArray()
      T_VECTOR_UINT -> vec.toIntArray()
      T_VECTOR -> IntArray(vec.size) { vec[it].toInt() }
      T_VECTOR_FLOAT -> IntArray(vec.size) { vec.readTypedFloat(it).toInt() }
      else -> IntArray(0)
    }
  }

  /**
   * Returns element as a [LongArray], converting scalar types when possible.
   *
   * @return element as [LongArray] or empty [LongArray] if fail.
   */
  public fun toLongArray(): LongArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_INT -> vec.toLongArray()
      T_VECTOR_UINT -> vec.toLongArray()
      T_VECTOR -> LongArray(vec.size) { vec[it].toLong() }
      T_VECTOR_FLOAT -> LongArray(vec.size) { vec.readTypedFloat(it).toLong() }
      else -> LongArray(0)
    }
  }

  /**
   * Returns element as a [UByteArray], converting scalar types when possible.
   *
   * @return element as [UByteArray] or empty [UByteArray] if fail.
   */
  public fun toUByteArray(): UByteArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_INT -> if (vec.byteWidth == 1) {
        ByteArray(vec.size).also { vec.buf.copyInto(it, startIndex = vec.end, endIndex = vec.end + vec.size) }.asUByteArray()
      } else {
        UByteArray(vec.size) { vec.readTypedInt(it).toUByte() }
      }
      T_VECTOR_UINT -> if (vec.byteWidth == 1) {
        ByteArray(vec.size).also { vec.buf.copyInto(it, startIndex = vec.end, endIndex = vec.end + vec.size) }.asUByteArray()
      } else {
        UByteArray(vec.size) { vec.readTypedUInt(it).toUByte() }
      }
      T_VECTOR -> UByteArray(vec.size) { vec[it].toUByte() }
      T_VECTOR_FLOAT -> UByteArray(vec.size) { vec.readTypedFloat(it).toInt().toUByte() }
      else -> UByteArray(0)
    }
  }

  /**
   * Returns element as a [UShortArray], converting scalar types when possible.
   *
   * @return element as [UShortArray] or empty [UShortArray] if fail.
   */
  public fun toUShortArray(): UShortArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_INT -> FlexRead.toShortArray(vec.buf, vec.end, vec.byteWidth, vec.size).asUShortArray()
      T_VECTOR_UINT -> if (vec.byteWidth == 1) {
        UShortArray(vec.size) { vec.readTypedUInt(it).toUShort() }
      } else {
        FlexRead.toShortArray(vec.buf, vec.end, vec.byteWidth, vec.size).asUShortArray()
      }
      T_VECTOR -> UShortArray(vec.size) { vec[it].toUShort() }
      T_VECTOR_FLOAT -> UShortArray(vec.size) { vec.readTypedFloat(it).toUInt().toUShort() }
      else -> UShortArray(0)
    }
  }

  /**
   * Returns element as a [UIntArray], converting scalar types when possible.
   *
   * @return element as [UIntArray] or empty [UIntArray] if fail.
   */
  public fun toUIntArray(): UIntArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_INT -> vec.toIntArray().asUIntArray()
      T_VECTOR_UINT -> vec.toIntArray().asUIntArray()
      T_VECTOR -> UIntArray(vec.size) { vec[it].toUInt() }
      T_VECTOR_FLOAT -> UIntArray(vec.size) { vec.readTypedFloat(it).toUInt() }
      else -> UIntArray(0)
    }
  }

  /**
   * Returns element as a [ULongArray], converting scalar types when possible.
   *
   * @return element as [ULongArray] or empty [ULongArray] if fail.
   */
  public fun toULongArray(): ULongArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_INT -> vec.toLongArray().asULongArray()
      T_VECTOR_UINT -> vec.toLongArray().asULongArray()
      T_VECTOR -> ULongArray(vec.size) { vec[it].toULong() }
      T_VECTOR_FLOAT -> ULongArray(vec.size) { vec.readTypedFloat(it).toULong() }
      else -> ULongArray(0)
    }
  }

  /**
   * Returns element as a [FloatArray], converting scalar types when possible.
   *
   * @return element as [FloatArray] or empty [FloatArray] if fail.
   */
  public fun toFloatArray(): FloatArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_FLOAT -> vec.toFloatArray()
      T_VECTOR_INT -> FloatArray(vec.size) { vec.readTypedInt(it).toFloat() }
      T_VECTOR_UINT -> FloatArray(vec.size) { vec.readTypedUInt(it).toFloat() }
      T_VECTOR -> FloatArray(vec.size) { vec[it].toFloat() }
      else -> FloatArray(0)
    }
  }

  /**
   * Returns element as a [DoubleArray], converting scalar types when possible.
   *
   * @return element as [DoubleArray] or empty [DoubleArray] if fail.
   */
  public fun toDoubleArray(): DoubleArray {
    val vec = TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
    return when (type) {
      T_VECTOR_FLOAT -> vec.toDoubleArray()
      T_VECTOR_INT -> DoubleArray(vec.size) { vec.readTypedInt(it).toDouble() }
      T_VECTOR_UINT -> DoubleArray(vec.size) { vec.readTypedUInt(it).toDouble() }
      T_VECTOR -> DoubleArray(vec.size) { vec[it].toDouble() }
      else -> DoubleArray(0)
    }
  }

  /**
   * Returns element as a [Vector]
   *
   * @return element as [Vector] or empty [Vector] if fail
   */
  public fun toVector(): Vector {
    return when {
      isVector -> Vector(buf, buf.indirectAt(end, parentWidth), byteWidth)
      isTypedVector ->
        TypedVector(type.toElementTypedVector(), buf, buf.indirectAt(end, parentWidth), byteWidth)
      else -> emptyVector()
    }
  }

  /**
   * Returns element as a [Blob]
   *
   * @return element as [Blob] or empty [Blob] if fail
   */
  public fun toBlob(): Blob {
    return when (type) {
      T_BLOB,
      T_STRING -> Blob(buf, buf.indirectAt(end, parentWidth), byteWidth)
      else -> emptyBlob()
    }
  }

  /**
   * Returns element as a [Map].
   *
   * @return element as [Map] or empty [Map] if fail
   */
  public fun toMap(): Map =
    when (type) {
      T_MAP -> Map(buf, buf.indirectAt(end, parentWidth), byteWidth)
      else -> emptyMap()
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || this::class != other::class) return false
    other as Reference
    if (
      buf !== other.buf ||
        end != other.end ||
        parentWidth != other.parentWidth ||
        byteWidth != other.byteWidth ||
        type != other.type
    )
      return false
    return true
  }

  override fun hashCode(): Int {
    var result = buf.hashCode()
    result = 31 * result + end
    result = 31 * result + parentWidth
    result = 31 * result + byteWidth
    result = 31 * result + type.hashCode()
    return result
  }
}

/**
 * Represents any element that has a size property to it, like: [Map], [Vector] and [TypedVector].
 */
public open class Sized
internal constructor(
  @JvmField public val buf: ByteArray,
  @JvmField public val end: Int,
  @JvmField public val byteWidth: Int,
) {
  public open val size: Int = buf.ldUOffW(end - byteWidth, byteWidth)
}

/** Represent an array of bytes in the buffer. */
public open class Blob internal constructor(buf: ByteArray, end: Int, byteWidth: Int) :
  Sized(buf, end, byteWidth) {
  /**
   * Return [Blob] as [ReadBuffer]
   *
   * @return blob as [ReadBuffer]
   */
  public fun data(): ReadBuffer = ArrayReadBuffer(buf, end, size)

  /**
   * Copy [Blob] into a [ByteArray]
   *
   * @return A [ByteArray] containing the blob data.
   */
  public fun toByteArray(): ByteArray {
    val result = ByteArray(size)
    buf.copyInto(result, 0, end, end + size)
    return result
  }

  /**
   * Return individual byte at a given position
   *
   * @param pos position of the byte to be read
   */
  public operator fun get(pos: Int): Byte {
    if (pos !in 0 until size) error("$pos index out of bounds. Should be in range 0 until $size")
    return buf[end + pos]
  }

  override fun toString(): String = fastDecodeUtf8(buf, end, end + size)
}

/**
 * [Vector] represents an array of elements in the buffer. The element can be of any type.
 *
 * Typed vectors are folded into this class via [elemType] (>= 0 means every element has that
 * fixed scalar type and the per-element packed-type array is absent). A data-dependent branch
 * on a final field replaces virtual dispatch — monomorphic call sites on every platform.
 */
public open class Vector internal constructor(
  buf: ByteArray,
  end: Int,
  byteWidth: Int,
  @JvmField internal val elemType: Int = -1,
) : Collection<Reference>, Sized(buf, end, byteWidth) {

  /** Start of the packed-type byte array (untyped vectors only), computed once. */
  @JvmField internal val typesBase: Int = end + size * byteWidth

  /**
   * Returns a [Reference] from the [Vector] at position [index]. Returns a null reference
   *
   * @param index position in the vector.
   * @return [Reference] for a key or a null [Reference] if not found.
   */
  public operator fun get(index: Int): Reference {
    if (index >= size) return nullReference()
    if (elemType >= 0) {
      return Reference(buf, end + index * byteWidth, byteWidth, 1, FlexBufferType(elemType))
    }
    val packedType = buf.ldU8(typesBase + index)
    return Reference(buf, end + index * byteWidth, byteWidth, packedType)
  }

  // ─── Direct scalar reads: bypass Reference allocation ───

  public fun readInt(index: Int): Int {
    val pos = end + index * byteWidth
    val et = elemType
    if (et >= 0) {
      return if (et == 2 /* T_UINT */) buf.ldUOffW(pos, byteWidth) else buf.ldSIntW(pos, byteWidth)
    }
    val packedType = buf.ldU8(typesBase + index)
    val t = packedType shr 2
    if (t == 1 /* T_INT */) return buf.ldSIntW(pos, byteWidth)
    if (t == 2 || t == 26 /* T_UINT, T_BOOL */) return buf.ldUOffW(pos, byteWidth)
    val bw = 1 shl (packedType and 3)
    return when (t) {
      6 /* T_INDIRECT_INT */ -> buf.ldSIntW(buf.indirectAt(pos, byteWidth), bw)
      7 /* T_INDIRECT_UINT */ -> buf.ldUOffW(buf.indirectAt(pos, byteWidth), bw)
      else -> buf.ldUOffW(pos, byteWidth)
    }
  }

  public fun readLong(index: Int): Long {
    val pos = end + index * byteWidth
    val et = elemType
    if (et >= 0) {
      return if (et == 2 /* T_UINT */) buf.ldULongW(pos, byteWidth) else buf.ldSLongW(pos, byteWidth)
    }
    val packedType = buf.ldU8(typesBase + index)
    val t = packedType shr 2
    if (t == 1 /* T_INT */) return buf.ldSLongW(pos, byteWidth)
    if (t == 2 || t == 26 /* T_UINT, T_BOOL */) return buf.ldULongW(pos, byteWidth)
    val bw = 1 shl (packedType and 3)
    return when (t) {
      6 /* T_INDIRECT_INT */ -> buf.ldSLongW(buf.indirectAt(pos, byteWidth), bw)
      7 /* T_INDIRECT_UINT */ -> buf.ldULongW(buf.indirectAt(pos, byteWidth), bw)
      else -> buf.ldULongW(pos, byteWidth)
    }
  }

  public fun readDouble(index: Int): Double {
    val pos = end + index * byteWidth
    if (elemType >= 0) return buf.ldFloatW(pos, byteWidth)
    val packedType = buf.ldU8(typesBase + index)
    val t = packedType shr 2
    return when (t) {
      3 /* T_FLOAT */ -> buf.ldFloatW(pos, byteWidth)
      1 /* T_INT */ -> buf.ldSLongW(pos, byteWidth).toDouble()
      2, 26 -> buf.ldULongW(pos, byteWidth).toDouble()
      8 /* T_INDIRECT_FLOAT */ -> {
        val bw = 1 shl (packedType and 3)
        buf.ldFloatW(buf.indirectAt(pos, byteWidth), bw)
      }
      else -> buf.ldFloatW(pos, byteWidth)
    }
  }

  public fun readString(index: Int): String {
    val pos = end + index * byteWidth
    if (elemType >= 0) {
      val start = buf.indirectAt(pos, byteWidth)
      val strSize = buf.ldUOffW(start - byteWidth, byteWidth)
      return fastDecodeUtf8(buf, start, start + strSize)
    }
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    val start = buf.indirectAt(pos, byteWidth)
    val strSize = buf.ldUOffW(start - bw, bw)
    return fastDecodeUtf8(buf, start, start + strSize)
  }

  public fun readStringByteLength(index: Int): Int {
    val pos = end + index * byteWidth
    if (elemType >= 0) {
      val start = buf.indirectAt(pos, byteWidth)
      return buf.ldUOffW(start - byteWidth, byteWidth)
    }
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    val start = buf.indirectAt(pos, byteWidth)
    return buf.ldUOffW(start - bw, bw)
  }

  public fun readBoolean(index: Int): Boolean = buf.ldU8(end + index * byteWidth) != 0

  /** O(1) null check: reads the element's packed type byte. Typed vectors are never null. */
  public fun isNullAt(index: Int): Boolean {
    if (index >= size) return true
    if (elemType >= 0) return false
    return buf.ldU8(typesBase + index) shr 2 == 0
  }

  public fun readMap(index: Int): Map {
    val pos = end + index * byteWidth
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    return Map(buf, buf.indirectAt(pos, byteWidth), bw)
  }

  public fun readVector(index: Int): Vector {
    val pos = end + index * byteWidth
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    val t = FlexBufferType(packedType shr 2)
    val indirect = buf.indirectAt(pos, byteWidth)
    return if (t.isTypedVector()) {
      TypedVector(t.toElementTypedVector(), buf, indirect, bw)
    } else {
      Vector(buf, indirect, bw)
    }
  }

  // Fixed-type element reads used by Reference.toXxxArray (vector type known from parent).

  internal fun readTypedBoolean(index: Int): Boolean = buf.ldU8(end + index * byteWidth) != 0

  internal fun readTypedInt(index: Int): Long = buf.ldSLongW(end + index * byteWidth, byteWidth)

  internal fun readTypedUInt(index: Int): ULong =
    buf.ldULongW(end + index * byteWidth, byteWidth).toULong()

  internal fun readTypedFloat(index: Int): Double = buf.ldFloatW(end + index * byteWidth, byteWidth)

  public inline fun forEachInt(block: (Int) -> Unit) {
    for (i in 0 until size) block(readInt(i))
  }

  public inline fun forEachLong(block: (Long) -> Unit) {
    for (i in 0 until size) block(readLong(i))
  }

  public inline fun forEachDouble(block: (Double) -> Unit) {
    for (i in 0 until size) block(readDouble(i))
  }

  public inline fun forEachString(block: (String) -> Unit) {
    for (i in 0 until size) block(readString(i))
  }

  public inline fun foldLong(initial: Long, operation: (acc: Long, value: Long) -> Long): Long {
    var acc = initial
    for (i in 0 until size) acc = operation(acc, readLong(i))
    return acc
  }

  /**
   * Bulk read all ints. Width-specialized loops: constant stride, single load per element —
   * the same loop shape C++ ScalarVector reads compile to.
   */
  public fun toIntArray(): IntArray {
    val n = size
    if (elemType < 0) return IntArray(n) { readInt(it) }
    val result = IntArray(n)
    var pos = end
    if (elemType == 2 /* T_UINT */) {
      when (byteWidth) {
        1 -> for (i in 0 until n) { result[i] = buf.ldU8(pos); pos++ }
        2 -> for (i in 0 until n) { result[i] = buf.ldU16(pos); pos += 2 }
        4 -> readNaturalIntArray(buf, pos, result)
        else -> for (i in 0 until n) { result[i] = buf.ld64(pos).toInt(); pos += 8 }
      }
    } else {
      when (byteWidth) {
        1 -> for (i in 0 until n) { result[i] = buf.ld8(pos); pos++ }
        2 -> for (i in 0 until n) { result[i] = buf.ld16(pos); pos += 2 }
        4 -> readNaturalIntArray(buf, pos, result)
        else -> for (i in 0 until n) { result[i] = buf.ld64(pos).toInt(); pos += 8 }
      }
    }
    return result
  }

  /** Bulk read all longs. Width-specialized, sign-extending for T_INT elements. */
  public fun toLongArray(): LongArray {
    val n = size
    if (elemType < 0) return LongArray(n) { readLong(it) }
    val result = LongArray(n)
    var pos = end
    if (elemType == 2 /* T_UINT */) {
      when (byteWidth) {
        1 -> for (i in 0 until n) { result[i] = buf.ldU8(pos).toLong(); pos++ }
        2 -> for (i in 0 until n) { result[i] = buf.ldU16(pos).toLong(); pos += 2 }
        4 -> for (i in 0 until n) { result[i] = buf.ld32(pos).toLong() and 0xFFFFFFFFL; pos += 4 }
        else -> readNaturalLongArray(buf, pos, result)
      }
    } else {
      when (byteWidth) {
        1 -> for (i in 0 until n) { result[i] = buf.ld8(pos).toLong(); pos++ }
        2 -> for (i in 0 until n) { result[i] = buf.ld16(pos).toLong(); pos += 2 }
        4 -> for (i in 0 until n) { result[i] = buf.ld32(pos).toLong(); pos += 4 }
        else -> readNaturalLongArray(buf, pos, result)
      }
    }
    return result
  }

  /** Bulk read all doubles. Width-specialized. */
  public fun toDoubleArray(): DoubleArray {
    val n = size
    if (elemType < 0) return DoubleArray(n) { readDouble(it) }
    val result = DoubleArray(n)
    var pos = end
    when (byteWidth) {
      8 -> readNaturalDoubleArray(buf, pos, result)
      4 -> for (i in 0 until n) { result[i] = buf.ldF32(pos).toDouble(); pos += 4 }
      else -> for (i in 0 until n) { result[i] = readDouble(i) }
    }
    return result
  }

  /** Bulk read all floats. Direct f32 loads when stored 4-wide. */
  public fun toFloatArray(): FloatArray {
    val n = size
    if (elemType < 0) return FloatArray(n) { readDouble(it).toFloat() }
    val result = FloatArray(n)
    var pos = end
    when (byteWidth) {
      4 -> readNaturalFloatArray(buf, pos, result)
      8 -> for (i in 0 until n) { result[i] = buf.ldF64(pos).toFloat(); pos += 8 }
      else -> for (i in 0 until n) { result[i] = readDouble(i).toFloat() }
    }
    return result
  }

  // ─── End direct scalar reads ───

  // overrides from Collection<Reference>

  override fun contains(element: Reference): Boolean = find { it == element } != null

  override fun containsAll(elements: Collection<Reference>): Boolean {
    elements.forEach { if (!contains(it)) return false }
    return true
  }

  override fun isEmpty(): Boolean = size == 0

  override fun iterator(): Iterator<Reference> =
    object : Iterator<Reference> {
      var position = 0

      override fun hasNext(): Boolean = position != size

      override fun next(): Reference = get(position++)
    }
}

/** [TypedVector] represents an array of scalar elements of the same type in the buffer. */
public class TypedVector internal constructor(
  elementType: FlexBufferType,
  buf: ByteArray,
  end: Int,
  byteWidth: Int,
) : Vector(buf, end, byteWidth, elementType.value)

/** Represents a key element in the buffer. Keys are used to reference objects in a [Map] */
public data class Key(
  public val buf: ByteArray,
  public val start: Int,
  public val end: Int = buf.findZero(start),
) {

  val sizeInBytes: Int = end - start

  private val codePoint = CharArray(2)

  val sizeInChars: Int
    get() {
      var count = 0
      var i = start
      while (i < end) {
        val size = codePointSizeInBytes(i)
        i += size
        count += if (size == 4) 2 else 1
      }
      return count
    }

  public operator fun get(index: Int): Char {
    var count = 0
    var i = start
    var size = 0
    // we loop over the bytes to find the right position for the "char" at index i
    while (i < end && count < index) {
      size = codePointSizeInBytes(i)
      i += size
      // 4 bytes utf8 are 2 chars wide, the rest is on char.
      count += if (size == 4) 2 else 1
    }
    return when {
      count == index -> {
        Utf8.decodeUtf8CodePoint(buf, i, codePoint)
        codePoint[0]
      }
      count == index + 1 && size == 4 -> {
        Utf8.decodeUtf8CodePoint(buf, i - size, codePoint)
        codePoint[1]
      }
      else -> error("Invalid count=$count, index=$index")
    }
  }

  private inline fun codePointSizeInBytes(pos: Int): Int {
    val b = buf[pos]
    return when {
      Utf8.isOneByte(b) -> 1
      Utf8.isTwoBytes(b) -> 2
      Utf8.isThreeBytes(b) -> 3
      else -> 4
    }
  }

  override fun toString(): String =
    if (sizeInBytes > 0) fastDecodeUtf8(buf, start, end) else ""

  /** Checks whether Key is invalid or not. */
  public fun isInvalid(): Boolean = sizeInBytes <= 0
}

/** A Map class that provide support to access Key-Value data from Flexbuffers. */
public class Map internal constructor(buf: ByteArray, end: Int, byteWidth: Int) :
  Sized(buf, end, byteWidth), kotlin.collections.Map<Key, Reference> {

  /** Start of the packed-type byte array: end + size * byteWidth, computed once. */
  @JvmField internal val typesBase: Int = end + size * byteWidth

  // Key-vector state is computed on first access. Index-based field reads
  // (Map.getInt/getString/getMap/getVector with an Int index) NEVER touch the
  // key vector — they only need (end, byteWidth, size). KSP-generated FlexCoder
  // code is index-based, so for those decodes we save 2 buffer reads + 2 field
  // writes per Map construction. Only string-keyed binarySearch and key iteration
  // pay the cost, lazily, when first needed.
  //
  // -1 is the sentinel: keyVectorEnd is always > 0 in a valid map.
  @JvmField internal var keyVectorEnd: Int = -1
  @JvmField internal var keyVectorByteWidth: Int = 0

  private inline fun ensureKeyVector() {
    if (keyVectorEnd < 0) {
      val keysOffset = end - 3 * byteWidth // 3 is number of prefixed fields
      keyVectorEnd = buf.indirectAt(keysOffset, byteWidth)
      keyVectorByteWidth = buf.ldUOffW(keysOffset + byteWidth, byteWidth)
    }
  }

  /**
   * Returns a [Reference] from the [Map] at position [index]. Returns a null reference
   *
   * @param index position in the map
   * @return [Reference] for a key or a null [Reference] if not found.
   */
  public operator fun get(index: Int): Reference {
    if (index >= size) return nullReference()
    val packedType = buf.ldU8(typesBase + index)
    return Reference(buf, end + index * byteWidth, byteWidth, packedType)
  }

  // ─── Direct scalar reads: bypass Reference allocation ───

  public fun getInt(index: Int): Int {
    val pos = end + index * byteWidth
    val t = buf.ldU8(typesBase + index) shr 2
    if (t == 1 /* T_INT */) return buf.ldSIntW(pos, byteWidth)
    if (t == 2 || t == 26 /* T_UINT, T_BOOL */) return buf.ldUOffW(pos, byteWidth)
    return getIntSlow(t, buf.ldU8(typesBase + index), pos)
  }

  private fun getIntSlow(t: Int, packedType: Int, pos: Int): Int {
    val bw = 1 shl (packedType and 3)
    return when (t) {
      6 /* T_INDIRECT_INT */ -> buf.ldSIntW(buf.indirectAt(pos, byteWidth), bw)
      7 /* T_INDIRECT_UINT */ -> buf.ldUOffW(buf.indirectAt(pos, byteWidth), bw)
      else -> buf.ldUOffW(pos, byteWidth)
    }
  }

  public fun getLong(index: Int): Long {
    val pos = end + index * byteWidth
    val t = buf.ldU8(typesBase + index) shr 2
    if (t == 1 /* T_INT */) return buf.ldSLongW(pos, byteWidth)
    if (t == 2 || t == 26 /* T_UINT, T_BOOL */) return buf.ldULongW(pos, byteWidth)
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    return when (t) {
      6 /* T_INDIRECT_INT */ -> buf.ldSLongW(buf.indirectAt(pos, byteWidth), bw)
      7 /* T_INDIRECT_UINT */ -> buf.ldULongW(buf.indirectAt(pos, byteWidth), bw)
      else -> buf.ldULongW(pos, byteWidth)
    }
  }

  public fun getBoolean(index: Int): Boolean = buf.ldU8(end + index * byteWidth) != 0

  /** Reads the packed type byte for [index] — O(1) via the precomputed base. */
  internal inline fun packedAt(index: Int): Int = buf.ldU8(typesBase + index)

  public fun getDouble(index: Int): Double {
    val pos = end + index * byteWidth
    val packedType = buf.ldU8(typesBase + index)
    val t = packedType shr 2
    if (t == 3 /* T_FLOAT */) return buf.ldFloatW(pos, byteWidth)
    return when (t) {
      1 /* T_INT */ -> buf.ldSLongW(pos, byteWidth).toDouble()
      2, 26 -> buf.ldULongW(pos, byteWidth).toDouble()
      8 /* T_INDIRECT_FLOAT */ -> {
        val bw = 1 shl (packedType and 3)
        buf.ldFloatW(buf.indirectAt(pos, byteWidth), bw)
      }
      else -> buf.ldFloatW(pos, byteWidth)
    }
  }

  public fun getFloat(index: Int): Float = getDouble(index).toFloat()

  public fun getString(index: Int): String {
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    val start = buf.indirectAt(end + index * byteWidth, byteWidth)
    val strSize = buf.ldUOffW(start - bw, bw)
    return fastDecodeUtf8(buf, start, start + strSize)
  }

  public fun getStringByteLength(index: Int): Int {
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    val start = buf.indirectAt(end + index * byteWidth, byteWidth)
    return buf.ldUOffW(start - bw, bw)
  }

  public fun getInt(key: String, default: Int = 0): Int {
    val index = binarySearch(key)
    return if (index >= 0 && !isNullAt(index)) getInt(index) else default
  }

  public fun getLong(key: String, default: Long = 0L): Long {
    val index = binarySearch(key)
    return if (index >= 0 && !isNullAt(index)) getLong(index) else default
  }

  public fun getDouble(key: String, default: Double = 0.0): Double {
    val index = binarySearch(key)
    return if (index >= 0 && !isNullAt(index)) getDouble(index) else default
  }

  public fun getBoolean(key: String, default: Boolean = false): Boolean {
    val index = binarySearch(key)
    return if (index >= 0 && !isNullAt(index)) getBoolean(index) else default
  }

  public fun getString(key: String, default: String = ""): String {
    val index = binarySearch(key)
    return if (index >= 0 && !isNullAt(index)) getString(index) else default
  }

  public fun getStringByteLength(key: String, default: Int = -1): Int {
    val index = binarySearch(key)
    return if (index >= 0 && !isNullAt(index)) getStringByteLength(index) else default
  }

  public fun getVector(index: Int): Vector {
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    val t = FlexBufferType(packedType shr 2)
    val indirect = buf.indirectAt(end + index * byteWidth, byteWidth)
    return if (t.isTypedVector()) {
      TypedVector(t.toElementTypedVector(), buf, indirect, bw)
    } else {
      Vector(buf, indirect, bw)
    }
  }

  public fun getMap(index: Int): Map {
    val packedType = buf.ldU8(typesBase + index)
    val bw = 1 shl (packedType and 3)
    return Map(buf, buf.indirectAt(end + index * byteWidth, byteWidth), bw)
  }

  /**
   * Check if the element at [index] is null. O(1) — reads one byte from the packed type array.
   */
  public fun isNullAt(index: Int): Boolean {
    if (index >= size) return true
    return buf.ldU8(typesBase + index) shr 2 == 0
  }

  // ─── End direct scalar reads ───

  /**
   * Returns a [Reference] from the [Map] for a given [String] [key].
   *
   * @param key access key to element on map
   * @return [Reference] for a key or a null [Reference] if not found.
   */
  public operator fun get(key: String): Reference {
    val index: Int = binarySearch(key)
    return if (index in 0 until size) {
      get(index)
    } else nullReference()
  }

  /**
   * Returns a [Reference] from the [Map] for a given [Key] [key].
   *
   * @param key access key to element on map
   * @return [Reference] for a key or a null [Reference] if not found.
   */
  override operator fun get(key: Key): Reference {
    val index = binarySearch(key)
    return if (index in 0 until size) {
      get(index)
    } else nullReference()
  }

  /**
   * Checks whether the map contains a [key].
   *
   * @param key [String]
   * @return true if key is found in the map, otherwise false.
   */
  public operator fun contains(key: String): Boolean = binarySearch(key) >= 0

  /**
   * Returns the sorted map index for [key], or a negative insertion point when absent.
   *
   * Generated and fallback decoders use this to keep scalar reads on the O(1) indexed
   * map path instead of allocating a [Reference] just to discover the position.
   */
  public fun indexOf(key: String): Int = binarySearch(key)

  /**
   * Returns a [Key] for a given position [index] in the [Map].
   *
   * @param index of the key in the map
   * @return a Key for the given index. Out of bounds indexes returns invalid keys.
   */
  public fun keyAt(index: Int): Key {
    ensureKeyVector()
    val childPos: Int = keyVectorEnd + index * keyVectorByteWidth
    return Key(buf, buf.indirectAt(childPos, keyVectorByteWidth))
  }

  /**
   * Returns a [Key] as [String] for a given position [index] in the [Map].
   *
   * @param index of the key in the map
   * @return a Key for the given index. Out of bounds indexes returns empty string.
   */
  public fun keyAsString(index: Int): String {
    ensureKeyVector()
    val childPos: Int = keyVectorEnd + index * keyVectorByteWidth
    val start = buf.indirectAt(childPos, keyVectorByteWidth)
    val keyEnd = buf.findZero(start)
    return if (keyEnd > start) fastDecodeUtf8(buf, start, keyEnd) else ""
  }

  public fun keyByteLength(index: Int): Int {
    ensureKeyVector()
    val childPos: Int = keyVectorEnd + index * keyVectorByteWidth
    val start = buf.indirectAt(childPos, keyVectorByteWidth)
    val keyEnd = buf.findZero(start)
    return if (keyEnd > start) keyEnd - start else 0
  }

  public fun keyEquals(index: Int, other: CharSequence): Boolean {
    ensureKeyVector()
    val childPos: Int = keyVectorEnd + index * keyVectorByteWidth
    val start = buf.indirectAt(childPos, keyVectorByteWidth)
    var bufferPos = start
    var otherPos = 0
    while (otherPos < other.length) {
      val c = other[otherPos]
      if (c.code >= 0x80) return keyAsString(index) == other.toString()
      val b = buf[bufferPos]
      if (b == ZeroByte || b < 0 || b != c.code.toByte()) return false
      bufferPos++
      otherPos++
    }
    return buf[bufferPos] == ZeroByte
  }

  public inline fun forEachStringInt(block: (key: String, value: Int) -> Unit) {
    for (i in 0 until size) block(keyAsString(i), getInt(i))
  }

  public inline fun forEachStringLong(block: (key: String, value: Long) -> Unit) {
    for (i in 0 until size) block(keyAsString(i), getLong(i))
  }

  public inline fun forEachStringDouble(block: (key: String, value: Double) -> Unit) {
    for (i in 0 until size) block(keyAsString(i), getDouble(i))
  }

  public inline fun forEachStringString(block: (key: String, value: String) -> Unit) {
    for (i in 0 until size) block(keyAsString(i), getString(i))
  }

  public inline fun forEachStringBoolean(block: (key: String, value: Boolean) -> Unit) {
    for (i in 0 until size) block(keyAsString(i), getBoolean(i))
  }

  // Overrides from kotlin.collections.Map<Key, Reference>

  public data class Entry(override val key: Key, override val value: Reference) :
    kotlin.collections.Map.Entry<Key, Reference>

  override val entries: Set<kotlin.collections.Map.Entry<Key, Reference>>
    get() = object : AbstractSet<kotlin.collections.Map.Entry<Key, Reference>>() {
      override val size: Int
        get() = this@Map.size

      override fun iterator(): Iterator<kotlin.collections.Map.Entry<Key, Reference>> =
        object : Iterator<kotlin.collections.Map.Entry<Key, Reference>> {
          private var index = 0

          override fun hasNext(): Boolean = index < this@Map.size

          override fun next(): kotlin.collections.Map.Entry<Key, Reference> {
            if (!hasNext()) throw NoSuchElementException()
            val current = index++
            return Entry(keyAt(current), get(current))
          }
        }
    }

  override val keys: Set<Key>
    get() {
      val set = LinkedHashSet<Key>(size)
      for (i in 0 until size) {
        val key = keyAt(i)
        set.add(key)
      }
      return set
    }

  /**
   * Returns a [Vector] for accessing all values in the [Map].
   *
   * @return [Vector] of values.
   */
  override val values: Collection<Reference>
    get() = Vector(buf, end, byteWidth)

  override fun containsKey(key: Key): Boolean {
    for (i in 0 until size) {
      if (key == keyAt(i)) return true
    }
    return false
  }

  override fun containsValue(value: Reference): Boolean = values.contains(value)

  override fun isEmpty(): Boolean = size == 0

  // Performs a binary search on a key vector and return index of the key in key vector
  private fun binarySearch(searchedKey: String): Int {
    var isAscii = true
    for (i in searchedKey.indices) {
      if (searchedKey[i].code >= 0x80) {
        isAscii = false
        break
      }
    }

    if (isAscii) return binarySearch { compareAsciiKey(it, searchedKey) }

    // Encode a non-ASCII query once per lookup. The old comparator allocated a
    // four-byte scratch array and re-encoded every code point for every binary-
    // search probe, turning one lookup into O(log n) allocations/encodes.
    val encodedLength = fastEncodedLength(searchedKey)
    val encodedKey = ByteArray(encodedLength)
    fastEncodeUtf8KnownLength(searchedKey, encodedKey, 0, encodedLength)
    return binarySearch { compareEncodedKey(it, encodedKey) }
  }

  // Performs a binary search on a key vector and return index of the key in key vector
  private fun binarySearch(key: Key): Int = binarySearch { compareKeys(it, key.start) }

  private inline fun binarySearch(crossinline comparisonBlock: (Int) -> Int): Int {
    ensureKeyVector()
    val kvEnd = keyVectorEnd
    val kvWidth = keyVectorByteWidth
    var low = 0
    var high = size - 1
    while (low <= high) {
      val mid = low + high ushr 1
      val keyPos: Int = buf.indirectAt(kvEnd + mid * kvWidth, kvWidth)
      val cmp: Int = comparisonBlock(keyPos)
      if (cmp < 0) low = mid + 1 else if (cmp > 0) high = mid - 1 else return mid // key found
    }
    return -(low + 1) // key not found
  }

  // compares a T_KEY (null-terminated bytes at [start]) against another T_KEY at [other]
  private fun compareKeys(start: Int, other: Int): Int {
    var bufferPos = start
    var otherPos = other
    val limit: Int = buf.size
    var c1 = 0
    var c2 = 0
    while (otherPos < limit) {
      c1 = buf[bufferPos++].toInt() and 0xFF
      c2 = buf[otherPos++].toInt() and 0xFF
      when {
        c1 == 0 -> return c1 - c2
        c1 != c2 -> return c1 - c2
      }
    }
    return c1 - c2
  }

  // Compares a T_KEY against an ASCII query without allocating or encoding it.
  private fun compareAsciiKey(start: Int, other: CharSequence): Int {
    var bufferPos = start
    var otherPos = 0
    val otherLimit = other.length
    while (otherPos < otherLimit) {
      val bufferByte = buf[bufferPos].toInt() and 0xFF
      val otherByte = other[otherPos].code
      when {
        bufferByte == 0 -> return -otherByte
        bufferByte != otherByte -> return bufferByte - otherByte
      }
      ++bufferPos
      ++otherPos
    }
    return buf[bufferPos].toInt() and 0xFF
  }

  // Compares a T_KEY against one already-encoded UTF-8 query. Bytes are unsigned,
  // matching C/C++ strcmp and the builder's map sort order.
  private fun compareEncodedKey(start: Int, other: ByteArray): Int {
    var bufferPos = start
    var otherPos = 0
    while (otherPos < other.size) {
      val bufferByte = buf[bufferPos].toInt() and 0xFF
      val otherByte = other[otherPos].toInt() and 0xFF
      when {
        bufferByte == 0 -> return -otherByte
        bufferByte != otherByte -> return bufferByte - otherByte
      }
      bufferPos++
      otherPos++
    }
    return buf[bufferPos].toInt() and 0xFF
  }
}
