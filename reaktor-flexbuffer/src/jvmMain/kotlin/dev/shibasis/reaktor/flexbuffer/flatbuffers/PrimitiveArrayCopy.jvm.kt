package dev.shibasis.reaktor.flexbuffer.flatbuffers

/** HotSpot primitive-array base offsets. These are VM-provided values, never guessed. */
private object PrimitiveArrayBases {
  private val unsafe = UnsafeOps.U

  @JvmField val SHORT: Long = unsafe?.arrayBaseOffset(ShortArray::class.java)?.toLong() ?: -1L
  @JvmField val INT: Long = unsafe?.arrayBaseOffset(IntArray::class.java)?.toLong() ?: -1L
  @JvmField val FLOAT: Long = unsafe?.arrayBaseOffset(FloatArray::class.java)?.toLong() ?: -1L
  @JvmField val DOUBLE: Long = unsafe?.arrayBaseOffset(DoubleArray::class.java)?.toLong() ?: -1L

  @JvmField val ON: Boolean =
    UnsafeOps.ON && SHORT >= 0L && INT >= 0L && FLOAT >= 0L && DOUBLE >= 0L
}

private fun unsafeCopy(
  source: Any,
  sourceOffset: Long,
  destination: Any,
  destinationOffset: Long,
  byteCount: Long,
): Boolean {
  if (!PrimitiveArrayBases.ON) return false
  UnsafeOps.U!!.copyMemory(source, sourceOffset, destination, destinationOffset, byteCount)
  return true
}

/**
 * HotSpot policy is intentionally asymmetric. Phase-1 JMH showed that copying
 * wire bytes into freshly allocated integer arrays prevents C2 from eliminating
 * their mandatory zero stores. The width-specialized scalar loops are therefore
 * faster for integer decode (and for Long encode), while Unsafe remains a clear
 * win for Short/Int encode and floating-point traffic.
 */

internal actual fun bulkCopyShortsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: ShortArray,
): Boolean = false // JMH: scalar decode is 1.97x faster at 4096 elements.

internal actual fun bulkCopyIntsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: IntArray,
): Boolean = false // JMH: scalar decode is 1.55x faster at 4096 elements.

internal actual fun bulkCopyLongsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: LongArray,
): Boolean = false // JMH: scalar decode is 1.16x faster at 4096 elements.

internal actual fun bulkCopyFloatsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: FloatArray,
): Boolean = unsafeCopy(
  source,
  UnsafeOps.BASE + sourceOffset,
  destination,
  PrimitiveArrayBases.FLOAT,
  destination.size.toLong() * 4L,
)

internal actual fun bulkCopyDoublesFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: DoubleArray,
): Boolean = unsafeCopy(
  source,
  UnsafeOps.BASE + sourceOffset,
  destination,
  PrimitiveArrayBases.DOUBLE,
  destination.size.toLong() * 8L,
)

internal actual fun bulkCopyShortsToLittleEndian(
  source: ShortArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = unsafeCopy(
  source,
  PrimitiveArrayBases.SHORT,
  destination,
  UnsafeOps.BASE + destinationOffset,
  source.size.toLong() * 2L,
)

internal actual fun bulkCopyIntsToLittleEndian(
  source: IntArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = unsafeCopy(
  source,
  PrimitiveArrayBases.INT,
  destination,
  UnsafeOps.BASE + destinationOffset,
  source.size.toLong() * 4L,
)

internal actual fun bulkCopyLongsToLittleEndian(
  source: LongArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = false // JMH: scalar encode is 1.32x faster at 4096 elements.

internal actual fun bulkCopyFloatsToLittleEndian(
  source: FloatArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = unsafeCopy(
  source,
  PrimitiveArrayBases.FLOAT,
  destination,
  UnsafeOps.BASE + destinationOffset,
  source.size.toLong() * 4L,
)

internal actual fun bulkCopyDoublesToLittleEndian(
  source: DoubleArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = unsafeCopy(
  source,
  PrimitiveArrayBases.DOUBLE,
  destination,
  UnsafeOps.BASE + destinationOffset,
  source.size.toLong() * 8L,
)
