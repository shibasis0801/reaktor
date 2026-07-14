@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/** Apple targets are little-endian; pinned primitive arrays are contiguous C arrays. */
internal actual fun bulkCopyShortsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: ShortArray,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(0), input.addressOf(sourceOffset), destination.size.toULong() * 2UL)
    }
  }
  return true
}

internal actual fun bulkCopyIntsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: IntArray,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(0), input.addressOf(sourceOffset), destination.size.toULong() * 4UL)
    }
  }
  return true
}

internal actual fun bulkCopyLongsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: LongArray,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(0), input.addressOf(sourceOffset), destination.size.toULong() * 8UL)
    }
  }
  return true
}

internal actual fun bulkCopyFloatsFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: FloatArray,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(0), input.addressOf(sourceOffset), destination.size.toULong() * 4UL)
    }
  }
  return true
}

internal actual fun bulkCopyDoublesFromLittleEndian(
  source: ByteArray,
  sourceOffset: Int,
  destination: DoubleArray,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(0), input.addressOf(sourceOffset), destination.size.toULong() * 8UL)
    }
  }
  return true
}

internal actual fun bulkCopyShortsToLittleEndian(
  source: ShortArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(destinationOffset), input.addressOf(0), source.size.toULong() * 2UL)
    }
  }
  return true
}

internal actual fun bulkCopyIntsToLittleEndian(
  source: IntArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(destinationOffset), input.addressOf(0), source.size.toULong() * 4UL)
    }
  }
  return true
}

internal actual fun bulkCopyLongsToLittleEndian(
  source: LongArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(destinationOffset), input.addressOf(0), source.size.toULong() * 8UL)
    }
  }
  return true
}

internal actual fun bulkCopyFloatsToLittleEndian(
  source: FloatArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(destinationOffset), input.addressOf(0), source.size.toULong() * 4UL)
    }
  }
  return true
}

internal actual fun bulkCopyDoublesToLittleEndian(
  source: DoubleArray,
  destination: ByteArray,
  destinationOffset: Int,
): Boolean {
  source.usePinned { input ->
    destination.usePinned { output ->
      memcpy(output.addressOf(destinationOffset), input.addressOf(0), source.size.toULong() * 8UL)
    }
  }
  return true
}
