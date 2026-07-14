package dev.shibasis.reaktor.flexbuffer.flatbuffers

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Float64Array
import org.khronos.webgl.Int16Array
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Uint8Array

private val typedArraysAreLittleEndian: Boolean = run {
  val buffer = ArrayBuffer(2)
  Int16Array(buffer).asDynamic()[0] = 1
  (Uint8Array(buffer).asDynamic()[0] as Int) == 1
}

private fun absoluteByteOffset(array: dynamic, relativeOffset: Int): Int =
  (array.byteOffset as Int) + relativeOffset

private fun copyPrimitiveBytesToByteArray(
  source: dynamic,
  byteCount: Int,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean {
  if (!typedArraysAreLittleEndian) return false
  val destinationRaw = destination.asDynamic()
  val sourceBytes = Uint8Array(source.buffer, source.byteOffset as Int, byteCount)
  val destinationBytes = Uint8Array(
    destinationRaw.buffer,
    absoluteByteOffset(destinationRaw, destinationOffset),
    byteCount,
  )
  destinationBytes.asDynamic().set(sourceBytes)
  return true
}

internal actual fun bulkCopyShortsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: ShortArray,
): Boolean {
  if (!typedArraysAreLittleEndian) return false
  val raw = source.asDynamic()
  val offset = absoluteByteOffset(raw, sourceOffset)
  if ((offset and 1) != 0) return false
  destination.asDynamic().set(Int16Array(raw.buffer, offset, destination.size))
  return true
}

internal actual fun bulkCopyIntsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: IntArray,
): Boolean {
  if (!typedArraysAreLittleEndian) return false
  val raw = source.asDynamic()
  val offset = absoluteByteOffset(raw, sourceOffset)
  if ((offset and 3) != 0) return false
  destination.asDynamic().set(Int32Array(raw.buffer, offset, destination.size))
  return true
}

/** Kotlin/JS LongArray elements are emulated Long values, not a safe BigInt64Array view. */
internal actual fun bulkCopyLongsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: LongArray,
): Boolean = false

internal actual fun bulkCopyFloatsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: FloatArray,
): Boolean {
  if (!typedArraysAreLittleEndian) return false
  val raw = source.asDynamic()
  val offset = absoluteByteOffset(raw, sourceOffset)
  if ((offset and 3) != 0) return false
  destination.asDynamic().set(Float32Array(raw.buffer, offset, destination.size))
  return true
}

internal actual fun bulkCopyDoublesFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: DoubleArray,
): Boolean {
  if (!typedArraysAreLittleEndian) return false
  val raw = source.asDynamic()
  val offset = absoluteByteOffset(raw, sourceOffset)
  if ((offset and 7) != 0) return false
  destination.asDynamic().set(Float64Array(raw.buffer, offset, destination.size))
  return true
}

internal actual fun bulkCopyShortsToLittleEndian(
  source: ShortArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = copyPrimitiveBytesToByteArray(
  source.asDynamic(),
  source.size * 2,
  destination,
  destinationOffset,
)

internal actual fun bulkCopyIntsToLittleEndian(
  source: IntArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = copyPrimitiveBytesToByteArray(
  source.asDynamic(),
  source.size * 4,
  destination,
  destinationOffset,
)

/** Kotlin/JS LongArray storage is not byte-compatible with the FlexBuffers wire format. */
internal actual fun bulkCopyLongsToLittleEndian(
  source: LongArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = false

internal actual fun bulkCopyFloatsToLittleEndian(
  source: FloatArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = copyPrimitiveBytesToByteArray(
  source.asDynamic(),
  source.size * 4,
  destination,
  destinationOffset,
)

internal actual fun bulkCopyDoublesToLittleEndian(
  source: DoubleArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean = copyPrimitiveBytesToByteArray(
  source.asDynamic(),
  source.size * 8,
  destination,
  destinationOffset,
)
