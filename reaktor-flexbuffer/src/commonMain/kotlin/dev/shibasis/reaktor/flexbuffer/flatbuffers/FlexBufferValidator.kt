@file:Suppress("NOTHING_TO_INLINE")

package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * Structural validation for FlexBuffers before callers enter the unchecked hot
 * reader path. It validates offsets, sizes, packed types, typed-vector ranges,
 * map key vectors, and string/key terminators without materializing values.
 */
public fun isValid(bytes: ByteArray, limit: Int = bytes.size): Boolean =
  FlexBufferValidator(bytes, limit).validate()

private class FlexBufferValidator(
  private val bytes: ByteArray,
  private val limit: Int,
) {
  private var visited = 0

  fun validate(): Boolean {
    if (limit !in 3..bytes.size) return false
    val rootWidth = u8(limit - 1)
    if (!isWidth(rootWidth)) return false
    val rootEnd = limit - 2 - rootWidth
    if (rootEnd < 0) return false
    return validateRef(rootEnd, rootWidth, u8(limit - 2), 0)
  }

  private fun validateRef(end: Int, parentWidth: Int, packedType: Int, depth: Int): Boolean {
    if (depth > MAX_DEPTH || ++visited > MAX_VISITED) return false
    if (!isWidth(parentWidth) || !has(end, parentWidth)) return false

    val type = packedType ushr 2
    val byteWidth = 1 shl (packedType and 3)
    if (!isType(type)) return false

    return when (type) {
      T_NULL.value,
      T_INT.value,
      T_UINT.value,
      T_BOOL.value -> true
      T_FLOAT.value -> parentWidth == 4 || parentWidth == 8
      T_KEY.value -> validateKey(indirect(end, parentWidth))
      T_STRING.value -> validateBlobOrString(indirect(end, parentWidth), byteWidth, string = true)
      T_BLOB.value -> validateBlobOrString(indirect(end, parentWidth), byteWidth, string = false)
      T_INDIRECT_INT.value,
      T_INDIRECT_UINT.value -> has(indirect(end, parentWidth), byteWidth)
      T_INDIRECT_FLOAT.value -> (byteWidth == 4 || byteWidth == 8) && has(indirect(end, parentWidth), byteWidth)
      T_MAP.value,
      T_VECTOR.value,
      T_VECTOR_INT.value,
      T_VECTOR_UINT.value,
      T_VECTOR_FLOAT.value,
      T_VECTOR_KEY.value,
      T_VECTOR_STRING_DEPRECATED.value,
      T_VECTOR_BOOL.value -> validateSizedVectorLike(end, parentWidth, byteWidth, type, depth)
      in T_VECTOR_INT2.value..T_VECTOR_FLOAT4.value -> validateFixedTypedVector(end, parentWidth, byteWidth, type)
      else -> false
    }
  }

  private fun validateSizedVectorLike(
    refEnd: Int,
    refWidth: Int,
    byteWidth: Int,
    type: Int,
    depth: Int,
  ): Boolean {
    val end = indirect(refEnd, refWidth)
    if (end < 0) return false
    val size = readSize(end, byteWidth)
    if (size < 0) return false

    return when (type) {
      T_MAP.value -> validateMap(end, byteWidth, size, depth)
      T_VECTOR.value -> validateUntypedVector(end, byteWidth, size, depth)
      T_VECTOR_KEY.value -> validateTypedKeyVector(end, byteWidth, size)
      T_VECTOR_STRING_DEPRECATED.value -> validateTypedStringVector(end, byteWidth, size, depth)
      T_VECTOR_FLOAT.value -> (byteWidth == 4 || byteWidth == 8) && range(end, size.toLong() * byteWidth)
      else -> range(end, size.toLong() * byteWidth)
    }
  }

  private fun validateUntypedVector(end: Int, byteWidth: Int, size: Int, depth: Int): Boolean {
    val dataBytes = size.toLong() * byteWidth
    if (!range(end, dataBytes)) return false
    val typesBase = end + dataBytes.toInt()
    if (!range(typesBase, size.toLong())) return false
    for (i in 0 until size) {
      if (!validateRef(end + i * byteWidth, byteWidth, u8(typesBase + i), depth + 1)) return false
    }
    return true
  }

  private fun validateMap(end: Int, byteWidth: Int, size: Int, depth: Int): Boolean {
    val prefix = end - 3 * byteWidth
    if (!range(prefix, 3L * byteWidth)) return false

    val keyVectorEnd = indirect(prefix, byteWidth)
    val keyVectorWidth = readUInt(prefix + byteWidth, byteWidth)
    if (keyVectorWidth !in VALID_WIDTHS) return false
    val keyCount = readSize(keyVectorEnd, keyVectorWidth)
    if (keyCount != size) return false
    if (!validateTypedKeyVector(keyVectorEnd, keyVectorWidth, size)) return false

    val dataBytes = size.toLong() * byteWidth
    if (!range(end, dataBytes)) return false
    val typesBase = end + dataBytes.toInt()
    if (!range(typesBase, size.toLong())) return false
    for (i in 0 until size) {
      if (!validateRef(end + i * byteWidth, byteWidth, u8(typesBase + i), depth + 1)) return false
    }
    return true
  }

  private fun validateTypedKeyVector(end: Int, byteWidth: Int, size: Int): Boolean {
    if (!range(end, size.toLong() * byteWidth)) return false
    for (i in 0 until size) {
      if (!validateKey(indirect(end + i * byteWidth, byteWidth))) return false
    }
    return true
  }

  private fun validateTypedStringVector(end: Int, byteWidth: Int, size: Int, depth: Int): Boolean {
    if (!range(end, size.toLong() * byteWidth)) return false
    val packedString = (T_STRING.value shl 2) or widthBits(byteWidth)
    for (i in 0 until size) {
      if (!validateRef(end + i * byteWidth, byteWidth, packedString, depth + 1)) return false
    }
    return true
  }

  private fun validateFixedTypedVector(refEnd: Int, refWidth: Int, byteWidth: Int, type: Int): Boolean {
    val end = indirect(refEnd, refWidth)
    if (end < 0) return false
    val count = when (type) {
      in T_VECTOR_INT2.value..T_VECTOR_FLOAT2.value -> 2
      in T_VECTOR_INT3.value..T_VECTOR_FLOAT3.value -> 3
      in T_VECTOR_INT4.value..T_VECTOR_FLOAT4.value -> 4
      else -> return false
    }
    val isFloat = type == T_VECTOR_FLOAT2.value || type == T_VECTOR_FLOAT3.value || type == T_VECTOR_FLOAT4.value
    return (!isFloat || byteWidth == 4 || byteWidth == 8) && range(end, count.toLong() * byteWidth)
  }

  private fun validateBlobOrString(start: Int, byteWidth: Int, string: Boolean): Boolean {
    if (start < 0 || !has(start - byteWidth, byteWidth)) return false
    val size = readUInt(start - byteWidth, byteWidth)
    if (size < 0 || !range(start, size.toLong())) return false
    if (!string) return true
    val terminator = start + size
    return terminator < limit && bytes[terminator] == ZeroByte
  }

  private fun validateKey(start: Int): Boolean {
    if (start < 0 || start >= limit) return false
    var i = start
    while (i < limit) {
      if (bytes[i] == ZeroByte) return true
      i++
    }
    return false
  }

  private fun indirect(pos: Int, byteWidth: Int): Int {
    val offset = readUInt(pos, byteWidth)
    if (offset <= 0 || offset > pos) return -1
    return pos - offset
  }

  private fun readSize(end: Int, byteWidth: Int): Int {
    val size = readUInt(end - byteWidth, byteWidth)
    return if (size in 0..MAX_COLLECTION_SIZE) size else -1
  }

  private fun readUInt(pos: Int, byteWidth: Int): Int {
    if (!has(pos, byteWidth)) return -1
    var value = 0L
    for (i in 0 until byteWidth) {
      value = value or ((bytes[pos + i].toLong() and 0xffL) shl (8 * i))
    }
    return if (value in 0..Int.MAX_VALUE.toLong()) value.toInt() else -1
  }

  private fun u8(index: Int): Int = bytes[index].toInt() and 0xff

  private fun has(pos: Int, byteWidth: Int): Boolean =
    isWidth(byteWidth) && pos >= 0 && pos <= limit - byteWidth

  private fun range(pos: Int, byteCount: Long): Boolean =
    byteCount >= 0 && byteCount <= Int.MAX_VALUE && pos >= 0 && pos <= limit - byteCount.toInt()

  private fun isWidth(width: Int): Boolean = width == 1 || width == 2 || width == 4 || width == 8

  private fun widthBits(width: Int): Int =
    when (width) {
      1 -> 0
      2 -> 1
      4 -> 2
      8 -> 3
      else -> -1
    }

  private fun isType(type: Int): Boolean =
    type in T_NULL.value..T_BOOL.value || type == T_VECTOR_BOOL.value

  private companion object {
    private val VALID_WIDTHS = setOf(1, 2, 4, 8)
    private const val MAX_DEPTH = 128
    private const val MAX_VISITED = 100_000
    private const val MAX_COLLECTION_SIZE = 1_000_000
  }
}
