@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PrimitiveArrayVectorTest {

  private fun encode(block: FlexBuffersBuilder.() -> Unit): ByteArray {
    val builder = FlexBuffersBuilder(initialCapacity = 16, shareFlag = FlexBuffersBuilder.SHARE_NONE)
    builder.block()
    return builder.finishToByteArray()
  }

  @Test
  fun signedArraysKeepCompactWidthsAndUseNaturalWidthsAtBoundaries() {
    val compactShorts = shortArrayOf(-128, -1, 0, 127)
    val compactShortBytes = encode { put(compactShorts) }
    val compactShortRoot = getRoot(compactShortBytes)
    assertEquals(1, compactShortRoot.byteWidth)
    assertContentEquals(compactShorts, compactShortRoot.toShortArray())

    val naturalShorts = shortArrayOf(Short.MIN_VALUE, -129, 128, Short.MAX_VALUE)
    val naturalShortBytes = encode { put(naturalShorts) }
    val naturalShortRoot = getRoot(naturalShortBytes)
    val naturalShortVector = naturalShortRoot.toVector()
    assertEquals(T_VECTOR_INT, naturalShortRoot.type)
    assertEquals(2, naturalShortVector.byteWidth)
    assertContentEquals(naturalShorts, naturalShortRoot.toShortArray())
    assertContentEquals(
      naturalShorts,
      FlexRead.toShortArray(
        naturalShortBytes,
        naturalShortVector.end,
        naturalShortVector.byteWidth,
        naturalShortVector.size,
      ),
    )

    val compactInts = intArrayOf(-128, -1, 0, 127)
    val compactIntRoot = getRoot(encode { put(compactInts) })
    assertEquals(1, compactIntRoot.byteWidth)
    assertContentEquals(compactInts, compactIntRoot.toIntArray())

    val naturalInts = intArrayOf(Int.MIN_VALUE, -32_769, 32_768, Int.MAX_VALUE)
    val naturalIntBytes = encode { put(naturalInts) }
    val naturalIntRoot = getRoot(naturalIntBytes)
    val naturalIntVector = naturalIntRoot.toVector()
    assertEquals(4, naturalIntVector.byteWidth)
    assertContentEquals(naturalInts, naturalIntRoot.toIntArray())
    assertContentEquals(
      naturalInts,
      FlexRead.toIntArray(
        naturalIntBytes,
        naturalIntVector.end,
        naturalIntVector.byteWidth,
        naturalIntVector.size,
      ),
    )

    val compactLongs = longArrayOf(-128L, -1L, 0L, 127L)
    val compactLongRoot = getRoot(encode { put(compactLongs) })
    assertEquals(1, compactLongRoot.byteWidth)
    assertContentEquals(compactLongs, compactLongRoot.toLongArray())

    val naturalLongs = longArrayOf(Long.MIN_VALUE, Int.MIN_VALUE.toLong() - 1L, Long.MAX_VALUE)
    val naturalLongBytes = encode { put(naturalLongs) }
    val naturalLongRoot = getRoot(naturalLongBytes)
    val naturalLongVector = naturalLongRoot.toVector()
    assertEquals(8, naturalLongVector.byteWidth)
    assertContentEquals(naturalLongs, naturalLongRoot.toLongArray())
    assertContentEquals(
      naturalLongs,
      FlexRead.toLongArray(
        naturalLongBytes,
        naturalLongVector.end,
        naturalLongVector.byteWidth,
        naturalLongVector.size,
      ),
    )
  }

  @Test
  fun floatingArraysPreserveEveryRawBit() {
    val floats = floatArrayOf(
      -0.0f,
      Float.fromBits(0x7FC0_1234),
      Float.POSITIVE_INFINITY,
      Float.NEGATIVE_INFINITY,
    )
    val floatBytes = encode { put(floats) }
    val floatRoot = getRoot(floatBytes)
    val floatVector = floatRoot.toVector()
    assertEquals(T_VECTOR_FLOAT, floatRoot.type)
    assertEquals(4, floatVector.byteWidth)
    val decodedFloats = floatRoot.toFloatArray()
    assertContentEquals(floats.map { it.toRawBits() }, decodedFloats.map { it.toRawBits() })
    val directFloats = FlexRead.toFloatArray(
      floatBytes,
      floatVector.end,
      floatVector.byteWidth,
      floatVector.size,
    )
    assertContentEquals(floats.map { it.toRawBits() }, directFloats.map { it.toRawBits() })

    val doubles = doubleArrayOf(
      -0.0,
      Double.fromBits(0x7FF8_1234_5678_9ABCL),
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
    )
    val doubleBytes = encode { put(doubles) }
    val doubleRoot = getRoot(doubleBytes)
    val doubleVector = doubleRoot.toVector()
    assertEquals(T_VECTOR_FLOAT, doubleRoot.type)
    assertEquals(8, doubleVector.byteWidth)
    val decodedDoubles = doubleRoot.toDoubleArray()
    assertContentEquals(doubles.map { it.toRawBits() }, decodedDoubles.map { it.toRawBits() })
    val directDoubles = FlexRead.toDoubleArray(
      doubleBytes,
      doubleVector.end,
      doubleVector.byteWidth,
      doubleVector.size,
    )
    assertContentEquals(doubles.map { it.toRawBits() }, directDoubles.map { it.toRawBits() })
  }

  @Test
  fun unsignedArraysAreDirectUIntVectorsIncludingEmptyAndCompactCases() {
    fun assertEmpty(bytes: ByteArray) {
      val root = getRoot(bytes)
      assertEquals(T_VECTOR_UINT, root.type)
      assertEquals(1, root.byteWidth)
      assertEquals(0, root.toVector().size)
    }

    val emptyUBytes = encode { put(ubyteArrayOf()) }
    val emptyUShorts = encode { put(ushortArrayOf()) }
    val emptyUInts = encode { put(uintArrayOf()) }
    val emptyULongs = encode { put(ulongArrayOf()) }
    assertEmpty(emptyUBytes)
    assertEmpty(emptyUShorts)
    assertEmpty(emptyUInts)
    assertEmpty(emptyULongs)
    assertContentEquals(ubyteArrayOf(), getRoot(emptyUBytes).toUByteArray())
    assertContentEquals(ushortArrayOf(), getRoot(emptyUShorts).toUShortArray())
    assertContentEquals(uintArrayOf(), getRoot(emptyUInts).toUIntArray())
    assertContentEquals(ulongArrayOf(), getRoot(emptyULongs).toULongArray())

    val ubytes = ubyteArrayOf(0U, 127U, 128U, UByte.MAX_VALUE)
    val ubyteRoot = getRoot(encode { put(ubytes) })
    assertEquals(T_VECTOR_UINT, ubyteRoot.type)
    assertEquals(1, ubyteRoot.byteWidth)
    assertContentEquals(ubytes, ubyteRoot.toUByteArray())

    val compactUShorts = ushortArrayOf(0U, UByte.MAX_VALUE.toUShort())
    val compactUShortRoot = getRoot(encode { put(compactUShorts) })
    assertEquals(1, compactUShortRoot.byteWidth)
    assertContentEquals(compactUShorts, compactUShortRoot.toUShortArray())

    val ushorts = ushortArrayOf(0U, 255U, 256U, UShort.MAX_VALUE)
    val ushortRoot = getRoot(encode { put(ushorts) })
    assertEquals(2, ushortRoot.byteWidth)
    assertContentEquals(ushorts, ushortRoot.toUShortArray())

    val compactUInts = uintArrayOf(0U, UShort.MAX_VALUE.toUInt())
    val compactUIntRoot = getRoot(encode { put(compactUInts) })
    assertEquals(2, compactUIntRoot.byteWidth)
    assertContentEquals(compactUInts, compactUIntRoot.toUIntArray())

    val uints = uintArrayOf(0U, 65_535U, 65_536U, UInt.MAX_VALUE)
    val uintRoot = getRoot(encode { put(uints) })
    assertEquals(4, uintRoot.byteWidth)
    assertContentEquals(uints, uintRoot.toUIntArray())

    val compactULongs = ulongArrayOf(0UL, UInt.MAX_VALUE.toULong())
    val compactULongRoot = getRoot(encode { put(compactULongs) })
    assertEquals(4, compactULongRoot.byteWidth)
    assertContentEquals(compactULongs, compactULongRoot.toULongArray())

    val ulongs = ulongArrayOf(0UL, UInt.MAX_VALUE.toULong(), UInt.MAX_VALUE.toULong() + 1UL, ULong.MAX_VALUE)
    val ulongRoot = getRoot(encode { put(ulongs) })
    assertEquals(8, ulongRoot.byteWidth)
    assertContentEquals(ulongs, ulongRoot.toULongArray())
  }

  @Test
  fun sizePrefixParticipatesInWidthAndMatchesCppLittleEndianLayout() {
    // FlexBuffers uses one width for the vector length and every element. A 256-entry
    // UByte/Short vector therefore needs W16 even though every value fits W8.
    val sizeDrivenUnsigned = UByteArray(256) { 7U.toUByte() }
    val sizeDrivenUnsignedRoot = getRoot(encode { put(sizeDrivenUnsigned) })
    assertEquals(2, sizeDrivenUnsignedRoot.byteWidth)
    assertContentEquals(sizeDrivenUnsigned, sizeDrivenUnsignedRoot.toUByteArray())

    val sizeDrivenSigned = ShortArray(256) { 7 }
    val sizeDrivenSignedRoot = getRoot(encode { put(sizeDrivenSigned) })
    assertEquals(2, sizeDrivenSignedRoot.byteWidth)
    assertContentEquals(sizeDrivenSigned, sizeDrivenSignedRoot.toShortArray())
    assertEquals(W_16, listIntWidthInUBits(List(256) { 7 }))
    assertEquals(W_16, listLongWidthInUBits(List(256) { 7L }))

    // C++ flexbuffers::Builder writes typed int vectors as: width-sized length,
    // little-endian scalar payload, then the root offset/type/parent-width trailer.
    val cppFixture = intArrayOf(0x0102_0304, -2)
    val bytes = encode { put(cppFixture) }
    val root = getRoot(bytes)
    val vector = root.toVector()
    assertEquals(T_VECTOR_INT, root.type)
    assertEquals(4, vector.byteWidth)
    assertContentEquals(
      byteArrayOf(4, 3, 2, 1, -2, -1, -1, -1),
      bytes.copyOfRange(vector.end, vector.end + cppFixture.size * 4),
    )
    assertEquals(2, bytes.ld32(vector.end - vector.byteWidth))
    assertEquals((T_VECTOR_INT.value shl 2) or W_32.value, bytes[bytes.lastIndex - 1].toInt() and 0xFF)
    assertContentEquals(cppFixture, root.toIntArray())
  }

  @Test
  fun naturalBulkReadRejectsInvalidRangesBeforePlatformMemoryAccess() {
    assertFailsWith<IndexOutOfBoundsException> {
      FlexRead.toShortArray(ByteArray(4), ve = 3, vw = 2, n = 1)
    }
    assertFailsWith<IndexOutOfBoundsException> {
      FlexRead.toIntArray(ByteArray(4), ve = 1, vw = 4, n = 1)
    }
    assertFailsWith<IndexOutOfBoundsException> {
      FlexRead.toLongArray(ByteArray(8), ve = 1, vw = 8, n = 1)
    }
  }
}
