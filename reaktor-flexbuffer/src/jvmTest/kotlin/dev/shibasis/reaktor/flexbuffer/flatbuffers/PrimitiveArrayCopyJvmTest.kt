package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimitiveArrayCopyJvmTest {

  @Test
  fun hotspotUsesOnlyTheJmhWinningPrimitiveCopyPolicies() {
    assertTrue(UnsafeOps.ON, "HotSpot Unsafe little-endian fast path must be available in JVM tests")

    val shorts = shortArrayOf(Short.MIN_VALUE, -1, 0, Short.MAX_VALUE)
    val shortBytes = ByteArray(shorts.size * 2 + 6)
    assertTrue(bulkCopyShortsToLittleEndian(shorts, shortBytes, 3))
    val shortResult = ShortArray(shorts.size)
    assertFalse(bulkCopyShortsFromLittleEndian(shortBytes, 3, shortResult))
    readNaturalShortArray(shortBytes, 3, shortResult)
    assertContentEquals(shorts, shortResult)

    val ints = intArrayOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)
    val intBytes = ByteArray(ints.size * 4 + 6)
    assertTrue(bulkCopyIntsToLittleEndian(ints, intBytes, 3))
    val intResult = IntArray(ints.size)
    assertFalse(bulkCopyIntsFromLittleEndian(intBytes, 3, intResult))
    readNaturalIntArray(intBytes, 3, intResult)
    assertContentEquals(ints, intResult)

    val longs = longArrayOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)
    val longBytes = ByteArray(longs.size * 8 + 6)
    assertFalse(bulkCopyLongsToLittleEndian(longs, longBytes, 3))
    writeNaturalLongArray(longs, longBytes, 3)
    val longResult = LongArray(longs.size)
    assertFalse(bulkCopyLongsFromLittleEndian(longBytes, 3, longResult))
    readNaturalLongArray(longBytes, 3, longResult)
    assertContentEquals(longs, longResult)

    val floats = floatArrayOf(-0.0f, Float.fromBits(0x7FC0_4321), Float.POSITIVE_INFINITY)
    val floatBytes = ByteArray(floats.size * 4 + 6)
    assertTrue(bulkCopyFloatsToLittleEndian(floats, floatBytes, 3))
    val floatResult = FloatArray(floats.size)
    assertTrue(bulkCopyFloatsFromLittleEndian(floatBytes, 3, floatResult))
    assertContentEquals(floats.map { it.toRawBits() }, floatResult.map { it.toRawBits() })

    val doubles = doubleArrayOf(
      -0.0,
      Double.fromBits(0x7FF8_4321_1234_5678L),
      Double.NEGATIVE_INFINITY,
    )
    val doubleBytes = ByteArray(doubles.size * 8 + 6)
    assertTrue(bulkCopyDoublesToLittleEndian(doubles, doubleBytes, 3))
    val doubleResult = DoubleArray(doubles.size)
    assertTrue(bulkCopyDoublesFromLittleEndian(doubleBytes, 3, doubleResult))
    assertContentEquals(doubles.map { it.toRawBits() }, doubleResult.map { it.toRawBits() })
  }
}
