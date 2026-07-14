package dev.shibasis.reaktor.flexbuffer.flatbuffers

/**
 * Android's public/libcore `sun.misc.Unsafe` surface does not guarantee the
 * object-to-object `copyMemory` overload across supported API levels. Returning
 * false keeps ART on the width-specialized scalar path instead of relying on a
 * hidden method that can disappear under API verification.
 */
internal actual fun bulkCopyShortsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: ShortArray,
): Boolean = false

internal actual fun bulkCopyIntsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: IntArray,
): Boolean = false

internal actual fun bulkCopyLongsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: LongArray,
): Boolean = false

internal actual fun bulkCopyFloatsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: FloatArray,
): Boolean = false

internal actual fun bulkCopyDoublesFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: DoubleArray,
): Boolean = false

internal actual fun bulkCopyShortsToLittleEndian(
  source: ShortArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = false

internal actual fun bulkCopyIntsToLittleEndian(
  source: IntArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = false

internal actual fun bulkCopyLongsToLittleEndian(
  source: LongArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = false

internal actual fun bulkCopyFloatsToLittleEndian(
  source: FloatArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = false

internal actual fun bulkCopyDoublesToLittleEndian(
  source: DoubleArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = false
