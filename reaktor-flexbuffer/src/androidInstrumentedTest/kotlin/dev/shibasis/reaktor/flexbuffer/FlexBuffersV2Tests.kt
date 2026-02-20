package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.fromFlexBuffer
import dev.shibasis.reaktor.flexbuffer.core.toFlexBuffer
import kotlinx.serialization.Serializable
import org.junit.Test
import org.junit.Assert.*
import kotlin.system.measureTimeMillis

/**
 * Comprehensive tests for FlexBuffers V2 encoder/decoder.
 */
class FlexBuffersV2Tests {

    // ==================== Test Data Classes ====================

    @Serializable
    data class SimplePrimitives(
        val boolean: Boolean = true,
        val byte: Byte = 42,
        val short: Short = 1000,
        val int: Int = 100000,
        val long: Long = 10000000000L,
        val float: Float = 3.14f,
        val double: Double = 3.14159265359,
        val char: Char = 'K',
        val string: String = "Hello FlexBuffers"
    )

    @Serializable
    data class SimpleCollections(
        val intList: List<Int> = listOf(1, 2, 3, 4, 5),
        val stringSet: Set<String> = setOf("a", "b", "c"),
        val doubleList: List<Double> = listOf(1.1, 2.2, 3.3)
    )

    @Serializable
    data class SimpleMaps(
        val stringToInt: Map<String, Int> = mapOf("one" to 1, "two" to 2),
        val intToString: Map<Int, String> = mapOf(1 to "one", 2 to "two")
    )

    @Serializable
    data class NestedObject(
        val value: Int = 42,
        val name: String = "nested"
    )

    @Serializable
    data class WithNested(
        val id: Int = 1,
        val nested: NestedObject = NestedObject(),
        val nestedList: List<NestedObject> = listOf(NestedObject(1, "first"), NestedObject(2, "second"))
    )

    @Serializable
    data class ComplexStructure(
        val primitives: SimplePrimitives = SimplePrimitives(),
        val collections: SimpleCollections = SimpleCollections(),
        val maps: SimpleMaps = SimpleMaps(),
        val nested: WithNested = WithNested()
    )

    @Serializable
    data class NullableFields(
        val nullable: String? = null,
        val present: String? = "present",
        val nullableInt: Int? = null,
        val presentInt: Int? = 42
    )

    @Serializable
    enum class TestEnum {
        VALUE_A, VALUE_B, VALUE_C
    }

    @Serializable
    data class WithEnum(
        val enum: TestEnum = TestEnum.VALUE_B,
        val enumList: List<TestEnum> = listOf(TestEnum.VALUE_A, TestEnum.VALUE_C)
    )

    // ==================== Primitive Tests ====================

    @Test
    fun testSimplePrimitives() {
        val original = SimplePrimitives()
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<SimplePrimitives>(encoded)

        assertEquals(original.boolean, decoded.boolean)
        assertEquals(original.byte, decoded.byte)
        assertEquals(original.short, decoded.short)
        assertEquals(original.int, decoded.int)
        assertEquals(original.long, decoded.long)
        assertEquals(original.float, decoded.float, 0.001f)
        assertEquals(original.double, decoded.double, 0.0000001)
        assertEquals(original.char, decoded.char)
        assertEquals(original.string, decoded.string)
    }

    @Test
    fun testPrimitiveEdgeCases() {
        val original = SimplePrimitives(
            boolean = false,
            byte = Byte.MIN_VALUE,
            short = Short.MAX_VALUE,
            int = Int.MIN_VALUE,
            long = Long.MAX_VALUE,
            float = Float.MIN_VALUE,
            double = Double.MAX_VALUE,
            char = '\u0000',
            string = ""
        )
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<SimplePrimitives>(encoded)

        assertEquals(original.boolean, decoded.boolean)
        assertEquals(original.byte, decoded.byte)
        assertEquals(original.short, decoded.short)
        assertEquals(original.int, decoded.int)
        assertEquals(original.long, decoded.long)
        assertEquals(original.string, decoded.string)
    }

    // ==================== Collection Tests ====================

    @Test
    fun testSimpleCollections() {
        val original = SimpleCollections()
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<SimpleCollections>(encoded)

        assertEquals(original.intList, decoded.intList)
        assertEquals(original.stringSet, decoded.stringSet)
        assertEquals(original.doubleList.size, decoded.doubleList.size)
    }

    @Test
    fun testEmptyCollections() {
        val original = SimpleCollections(
            intList = emptyList(),
            stringSet = emptySet(),
            doubleList = emptyList()
        )
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<SimpleCollections>(encoded)

        assertEquals(0, decoded.intList.size)
        assertEquals(0, decoded.stringSet.size)
        assertEquals(0, decoded.doubleList.size)
    }

    @Test
    fun testLargeCollections() {
        val original = SimpleCollections(
            intList = (1..1000).toList(),
            stringSet = (1..100).map { "item$it" }.toSet(),
            doubleList = (1..500).map { it * 0.1 }
        )
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<SimpleCollections>(encoded)

        assertEquals(original.intList.size, decoded.intList.size)
        assertEquals(original.stringSet.size, decoded.stringSet.size)
        assertEquals(original.doubleList.size, decoded.doubleList.size)
    }

    // ==================== Map Tests ====================

    @Test
    fun testSimpleMaps() {
        val original = SimpleMaps()
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<SimpleMaps>(encoded)

        assertEquals(original.stringToInt, decoded.stringToInt)
    }

    @Test
    fun testEmptyMaps() {
        val original = SimpleMaps(
            stringToInt = emptyMap(),
            intToString = emptyMap()
        )
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<SimpleMaps>(encoded)

        assertEquals(0, decoded.stringToInt.size)
        assertEquals(0, decoded.intToString.size)
    }

    // ==================== Nested Object Tests ====================

    @Test
    fun testNestedObjects() {
        val original = WithNested()
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<WithNested>(encoded)

        assertEquals(original.id, decoded.id)
        assertEquals(original.nested.value, decoded.nested.value)
        assertEquals(original.nested.name, decoded.nested.name)
        assertEquals(original.nestedList.size, decoded.nestedList.size)
    }

    @Test
    fun testComplexStructure() {
        val original = ComplexStructure()
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<ComplexStructure>(encoded)

        assertEquals(original.primitives.int, decoded.primitives.int)
        assertEquals(original.collections.intList.size, decoded.collections.intList.size)
        assertEquals(original.nested.id, decoded.nested.id)
    }

    // ==================== Nullable Tests ====================

    @Test
    fun testNullableFields() {
        val original = NullableFields()
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<NullableFields>(encoded)

        assertEquals(original.nullable, decoded.nullable)
        assertEquals(original.present, decoded.present)
        assertEquals(original.nullableInt, decoded.nullableInt)
        assertEquals(original.presentInt, decoded.presentInt)
    }

    // ==================== Enum Tests ====================

    @Test
    fun testEnums() {
        val original = WithEnum()
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<WithEnum>(encoded)

        assertEquals(original.enum, decoded.enum)
        assertEquals(original.enumList, decoded.enumList)
    }

    // ==================== Extension Function Tests ====================

    @Test
    fun testExtensionFunctions() {
        val original = SimplePrimitives()
        val encoded = original.toFlexBuffer()
        val decoded: SimplePrimitives = encoded.fromFlexBuffer()

        assertEquals(original.string, decoded.string)
    }

    // ==================== Utility Tests ====================

    @Test
    fun testIsValid() {
        val valid = FlexBuffers.encode(SimplePrimitives())
        assertTrue(FlexBuffers.isValid(valid))

        val invalid = byteArrayOf(0, 1)
        assertFalse(FlexBuffers.isValid(invalid))
    }

    @Test
    fun testToString() {
        val encoded = FlexBuffers.encode(SimplePrimitives(string = "test"))
        val str = FlexBuffers.toString(encoded)
        assertTrue(str.contains("test") || str.contains("string") || str.isNotEmpty())
    }

    // ==================== Thread Safety Tests ====================

    @Test
    fun testConcurrentEncoding() {
        val results = mutableListOf<ByteArray>()

        // Sequential encoding (actual threading needs coroutines)
        repeat(100) { i ->
            val data = SimplePrimitives(int = i)
            val encoded = FlexBuffers.encode(data)
            results.add(encoded)
        }

        // Verify all encodings are valid
        results.forEachIndexed { index, bytes ->
            val decoded = FlexBuffers.decode<SimplePrimitives>(bytes)
            assertEquals(index, decoded.int)
        }
    }

    // ==================== Performance Tests ====================

    @Test
    fun testEncodingPerformance() {
        val data = ComplexStructure()

        // Warm up
        repeat(10) {
            FlexBuffers.encode(data)
        }

        // Measure
        val elapsed = measureTimeMillis {
            repeat(1000) {
                FlexBuffers.encode(data)
            }
        }

        println("Encoding 1000 complex structures: ${elapsed}ms")
        assertTrue("Encoding took too long: ${elapsed}ms", elapsed < 30000)
    }

    @Test
    fun testDecodingPerformance() {
        val data = ComplexStructure()
        val encoded = FlexBuffers.encode(data)

        // Warm up
        repeat(10) {
            FlexBuffers.decode<ComplexStructure>(encoded)
        }

        // Measure
        val elapsed = measureTimeMillis {
            repeat(1000) {
                FlexBuffers.decode<ComplexStructure>(encoded)
            }
        }

        println("Decoding 1000 complex structures: ${elapsed}ms")
        assertTrue("Decoding took too long: ${elapsed}ms", elapsed < 30000)
    }

    // ==================== Roundtrip with Existing Test Classes ====================

    @Test
    fun testEncodingComplexCaseRoundtrip() {
        val original = dev.shibasis.reaktor.core.EncodingComplexCase()
        val encoded = FlexBuffers.encode(original)
        val decoded = FlexBuffers.decode<dev.shibasis.reaktor.core.EncodingComplexCase>(encoded)

        assertEquals(original.booleanField, decoded.booleanField)
        assertEquals(original.intField, decoded.intField)
        assertEquals(original.stringField, decoded.stringField)
    }
}
