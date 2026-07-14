package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimitiveArrayCopyTest {

  @Test
  fun rangeCheckAcceptsOnlyRangesFullyInsideTheBuffer() {
    assertTrue(primitiveArrayRangeFits(offset = 1, elementCount = 2, elementByteWidth = 4, byteSize = 9))
    assertTrue(primitiveArrayRangeFits(offset = 9, elementCount = 0, elementByteWidth = 8, byteSize = 9))

    assertFalse(primitiveArrayRangeFits(offset = -1, elementCount = 0, elementByteWidth = 8, byteSize = 9))
    assertFalse(primitiveArrayRangeFits(offset = 10, elementCount = 0, elementByteWidth = 8, byteSize = 9))
    assertFalse(primitiveArrayRangeFits(offset = 1, elementCount = 2, elementByteWidth = 4, byteSize = 8))
    assertFalse(primitiveArrayRangeFits(offset = 9, elementCount = 1, elementByteWidth = 1, byteSize = 9))
  }

  @Test
  fun rangeCheckDoesNotOverflowAtIntLimits() {
    assertTrue(
      primitiveArrayRangeFits(
        offset = 0,
        elementCount = Int.MAX_VALUE,
        elementByteWidth = 1,
        byteSize = Int.MAX_VALUE,
      ),
    )
    assertFalse(
      primitiveArrayRangeFits(
        offset = 0,
        elementCount = Int.MAX_VALUE,
        elementByteWidth = 8,
        byteSize = Int.MAX_VALUE,
      ),
    )
    assertFalse(
      primitiveArrayRangeFits(
        offset = Int.MAX_VALUE,
        elementCount = 1,
        elementByteWidth = 8,
        byteSize = Int.MAX_VALUE,
      ),
    )
    assertFalse(primitiveArrayRangeFits(offset = 0, elementCount = -1, elementByteWidth = 1, byteSize = 1))
    assertFalse(primitiveArrayRangeFits(offset = 0, elementCount = 1, elementByteWidth = 0, byteSize = 1))
    assertFalse(primitiveArrayRangeFits(offset = 0, elementCount = 0, elementByteWidth = 1, byteSize = -1))
  }
}
