package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * Platform kernels for copying naturally aligned primitive vectors to and from the
 * little-endian FlexBuffers wire representation.
 *
 * A kernel returns `false` when the platform cannot safely reinterpret the array
 * storage (for example LongArray on Kotlin/JS or an unavailable Unsafe on Android).
 * Callers then use the scalar little-endian path. Compact vectors deliberately never
 * enter these kernels: widening and sign extension must still happen element by element.
 */
internal expect fun bulkCopyShortsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: ShortArray,
): Boolean

internal expect fun bulkCopyIntsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: IntArray,
): Boolean

internal expect fun bulkCopyLongsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: LongArray,
): Boolean

internal expect fun bulkCopyFloatsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: FloatArray,
): Boolean

internal expect fun bulkCopyDoublesFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: DoubleArray,
): Boolean

internal expect fun bulkCopyShortsToLittleEndian(
  source: ShortArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean

internal expect fun bulkCopyIntsToLittleEndian(
  source: IntArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean

internal expect fun bulkCopyLongsToLittleEndian(
  source: LongArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean

internal expect fun bulkCopyFloatsToLittleEndian(
  source: FloatArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean

internal expect fun bulkCopyDoublesToLittleEndian(
  source: DoubleArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean

/**
 * Checks a primitive-array byte range without multiplying [elementCount] by
 * [elementByteWidth]. Avoiding that multiplication both prevents Int overflow and
 * keeps Kotlin/JS from lowering the hot-path bounds check through Long/BigInt.
 */
internal fun primitiveArrayRangeFits(
  offset: Int,
  elementCount: Int,
  elementByteWidth: Int,
  byteSize: Int,
): Boolean =
  offset >= 0 &&
    elementCount >= 0 &&
    elementByteWidth > 0 &&
    byteSize >= 0 &&
    offset <= byteSize &&
    elementCount <= (byteSize - offset) / elementByteWidth

private fun primitiveArrayBoundsError(
  offset: Int,
  elementCount: Int,
  elementByteWidth: Int,
  byteSize: Int,
  bufferName: String,
): Nothing {
  val required = if (elementCount <= Int.MAX_VALUE / elementByteWidth) {
    "${elementCount * elementByteWidth} bytes"
  } else {
    "$elementCount elements of $elementByteWidth bytes"
  }
  throw IndexOutOfBoundsException("$required at $offset exceed $bufferName size $byteSize")
}

internal fun readNaturalShortArray(
  source: ByteArray,
  sourceOffset: Int,
  destination: ShortArray,
) {
  if (!primitiveArrayRangeFits(sourceOffset, destination.size, 2, source.size)) {
    primitiveArrayBoundsError(sourceOffset, destination.size, 2, source.size, "source")
  }
  if (destination.isEmpty() || bulkCopyShortsFromLittleEndian(source, sourceOffset, destination)) return
  var p = sourceOffset
  for (i in destination.indices) {
    destination[i] = source.ld16(p).toShort()
    p += 2
  }
}

internal fun readNaturalIntArray(
  source: ByteArray,
  sourceOffset: Int,
  destination: IntArray,
) {
  if (!primitiveArrayRangeFits(sourceOffset, destination.size, 4, source.size)) {
    primitiveArrayBoundsError(sourceOffset, destination.size, 4, source.size, "source")
  }
  if (destination.isEmpty() || bulkCopyIntsFromLittleEndian(source, sourceOffset, destination)) return
  var p = sourceOffset
  for (i in destination.indices) {
    destination[i] = source.ld32(p)
    p += 4
  }
}

internal fun readNaturalLongArray(
  source: ByteArray,
  sourceOffset: Int,
  destination: LongArray,
) {
  if (!primitiveArrayRangeFits(sourceOffset, destination.size, 8, source.size)) {
    primitiveArrayBoundsError(sourceOffset, destination.size, 8, source.size, "source")
  }
  if (destination.isEmpty() || bulkCopyLongsFromLittleEndian(source, sourceOffset, destination)) return
  var p = sourceOffset
  for (i in destination.indices) {
    destination[i] = source.ld64(p)
    p += 8
  }
}

internal fun readNaturalFloatArray(
  source: ByteArray,
  sourceOffset: Int,
  destination: FloatArray,
) {
  if (!primitiveArrayRangeFits(sourceOffset, destination.size, 4, source.size)) {
    primitiveArrayBoundsError(sourceOffset, destination.size, 4, source.size, "source")
  }
  if (destination.isEmpty() || bulkCopyFloatsFromLittleEndian(source, sourceOffset, destination)) return
  var p = sourceOffset
  for (i in destination.indices) {
    destination[i] = source.ldF32(p)
    p += 4
  }
}

internal fun readNaturalDoubleArray(
  source: ByteArray,
  sourceOffset: Int,
  destination: DoubleArray,
) {
  if (!primitiveArrayRangeFits(sourceOffset, destination.size, 8, source.size)) {
    primitiveArrayBoundsError(sourceOffset, destination.size, 8, source.size, "source")
  }
  if (destination.isEmpty() || bulkCopyDoublesFromLittleEndian(source, sourceOffset, destination)) return
  var p = sourceOffset
  for (i in destination.indices) {
    destination[i] = source.ldF64(p)
    p += 8
  }
}

internal fun writeNaturalShortArray(
  source: ShortArray,
  destination: ByteArray,
  destinationOffset: Int,
) {
  if (!primitiveArrayRangeFits(destinationOffset, source.size, 2, destination.size)) {
    primitiveArrayBoundsError(destinationOffset, source.size, 2, destination.size, "destination")
  }
  if (source.isEmpty() || bulkCopyShortsToLittleEndian(source, destination, destinationOffset)) return
  var p = destinationOffset
  for (value in source) {
    destination.st16(p, value.toInt())
    p += 2
  }
}

internal fun writeNaturalIntArray(
  source: IntArray,
  destination: ByteArray,
  destinationOffset: Int,
) {
  if (!primitiveArrayRangeFits(destinationOffset, source.size, 4, destination.size)) {
    primitiveArrayBoundsError(destinationOffset, source.size, 4, destination.size, "destination")
  }
  if (source.isEmpty() || bulkCopyIntsToLittleEndian(source, destination, destinationOffset)) return
  var p = destinationOffset
  for (value in source) {
    destination.st32(p, value)
    p += 4
  }
}

internal fun writeNaturalLongArray(
  source: LongArray,
  destination: ByteArray,
  destinationOffset: Int,
) {
  if (!primitiveArrayRangeFits(destinationOffset, source.size, 8, destination.size)) {
    primitiveArrayBoundsError(destinationOffset, source.size, 8, destination.size, "destination")
  }
  if (source.isEmpty() || bulkCopyLongsToLittleEndian(source, destination, destinationOffset)) return
  var p = destinationOffset
  for (value in source) {
    destination.st64(p, value)
    p += 8
  }
}

internal fun writeNaturalFloatArray(
  source: FloatArray,
  destination: ByteArray,
  destinationOffset: Int,
) {
  if (!primitiveArrayRangeFits(destinationOffset, source.size, 4, destination.size)) {
    primitiveArrayBoundsError(destinationOffset, source.size, 4, destination.size, "destination")
  }
  if (source.isEmpty() || bulkCopyFloatsToLittleEndian(source, destination, destinationOffset)) return
  var p = destinationOffset
  for (value in source) {
    destination.stF32(p, value)
    p += 4
  }
}

internal fun writeNaturalDoubleArray(
  source: DoubleArray,
  destination: ByteArray,
  destinationOffset: Int,
) {
  if (!primitiveArrayRangeFits(destinationOffset, source.size, 8, destination.size)) {
    primitiveArrayBoundsError(destinationOffset, source.size, 8, destination.size, "destination")
  }
  if (source.isEmpty() || bulkCopyDoublesToLittleEndian(source, destination, destinationOffset)) return
  var p = destinationOffset
  for (value in source) {
    destination.stF64(p, value)
    p += 8
  }
}
