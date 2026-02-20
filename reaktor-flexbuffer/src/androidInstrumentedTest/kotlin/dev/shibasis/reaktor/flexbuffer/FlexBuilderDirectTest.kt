package dev.shibasis.reaktor.flexbuffer

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import kotlinx.serialization.Serializable
import org.junit.Test
import org.junit.Assert.*

/**
 * Direct tests for FlexBuffersBuilder to verify it works correctly.
 */
class FlexBuilderDirectTest {

    @Serializable
    data class SimpleData(val name: String, val value: Int)

    @Test
    fun testEncoderProducesValidOutput() {
        val data = SimpleData("test", 42)
        val encoded = FlexBuffers.encode(data)

        println("Encoded size: ${encoded.size}")
        println("Encoded bytes: ${encoded.joinToString(", ") { it.toString() }}")

        assertTrue("Encoded data should not be empty", encoded.isNotEmpty())
        assertTrue("Encoded data should be more than 3 bytes", encoded.size > 3)

        // Try to decode
        val root = getRoot(com.google.flatbuffers.kotlin.ArrayReadBuffer(encoded))
        println("Root type: ${root.type}")
        println("Root is map: ${root.isMap}")

        assertTrue("Root should be a map", root.isMap)
    }

    @Serializable
    data class WithList(val items: List<Int>)

    @Test
    fun testEncoderWithList() {
        val data = WithList(listOf(1, 2, 3))
        val encoded = FlexBuffers.encode(data)

        println("WithList encoded size: ${encoded.size}")
        assertTrue("Encoded data should not be empty", encoded.isNotEmpty())

        val root = getRoot(com.google.flatbuffers.kotlin.ArrayReadBuffer(encoded))
        assertTrue("Root should be a map", root.isMap)

        val map = root.toMap()
        val items = map["items"]
        println("items type: ${items.type}, isVector: ${items.isVector}")
        assertTrue("items should be a vector", items.isVector || items.isTypedVector)
    }

    @Serializable
    data class WithNestedObject(val nested: SimpleData)

    @Test
    fun testEncoderWithNestedObject() {
        val data = WithNestedObject(SimpleData("inner", 99))
        val encoded = FlexBuffers.encode(data)

        println("WithNestedObject encoded size: ${encoded.size}")
        assertTrue("Encoded data should not be empty", encoded.isNotEmpty())

        val root = getRoot(com.google.flatbuffers.kotlin.ArrayReadBuffer(encoded))
        assertTrue("Root should be a map", root.isMap)

        val map = root.toMap()
        val nested = map["nested"]
        println("nested type: ${nested.type}, isMap: ${nested.isMap}")
        assertTrue("nested should be a map", nested.isMap)
    }

    @Serializable
    data class WithStringMap(val data: Map<String, Int>)

    @Test
    fun testEncoderWithStringMap() {
        val data = WithStringMap(mapOf("one" to 1, "two" to 2))
        val encoded = FlexBuffers.encode(data)

        println("WithStringMap encoded size: ${encoded.size}")
        assertTrue("Encoded data should not be empty", encoded.isNotEmpty())

        val root = getRoot(com.google.flatbuffers.kotlin.ArrayReadBuffer(encoded))
        assertTrue("Root should be a map", root.isMap)
    }

    @Serializable
    data class AllPrimitives(
        val boolean: Boolean = true,
        val byte: Byte = 42,
        val short: Short = 1000,
        val int: Int = 100000,
        val long: Long = 10000000000L,
        val float: Float = 3.14f,
        val double: Double = 3.14159265359,
        val char: Char = 'K',
        val string: String = "Hello"
    )

    @Test
    fun testEncoderWithAllPrimitives() {
        val data = AllPrimitives()
        val encoded = FlexBuffers.encode(data)

        println("AllPrimitives encoded size: ${encoded.size}")
        assertTrue("Encoded data should not be empty", encoded.isNotEmpty())

        val root = getRoot(com.google.flatbuffers.kotlin.ArrayReadBuffer(encoded))
        assertTrue("Root should be a map", root.isMap)

        val map = root.toMap()
        println("boolean: ${map["boolean"]}")
        println("int: ${map["int"]}")
        println("string: ${map["string"]}")
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun testSimpleMapDirect() {
        val builder = FlexBuffersBuilder(1024, FlexBuffersBuilder.SHARE_KEYS)

        val start = builder.startMap()
        builder.set("name", "test")
        builder.set("value", 42)
        builder.endMap(start)

        val buffer = builder.finish()
        println("Buffer limit: ${buffer.limit}")
        assertTrue("Buffer should have content", buffer.limit > 0)

        val root = getRoot(buffer)
        assertTrue("Root should be a map", root.isMap)

        val map = root.toMap()
        assertEquals("Map should have 2 entries", 2, map.size)
        assertEquals("name should be 'test'", "test", map["name"].toString())
        assertEquals("value should be 42", 42, map["value"].toInt())
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun testNestedMapDirect() {
        val builder = FlexBuffersBuilder(1024, FlexBuffersBuilder.SHARE_KEYS)

        val start = builder.startMap()
        builder.set("outer", "value")

        // Nested map
        val nestedStart = builder.startMap()
        builder.set("inner", 123)
        builder.endMap(nestedStart, "nested")

        builder.endMap(start)

        val buffer = builder.finish()
        println("Nested buffer limit: ${buffer.limit}")
        assertTrue("Buffer should have content", buffer.limit > 0)

        val root = getRoot(buffer)
        val map = root.toMap()
        assertEquals("outer should be 'value'", "value", map["outer"].toString())

        val nested = map["nested"]
        assertTrue("nested should be a map", nested.isMap)
        assertEquals("inner should be 123", 123, nested.toMap()["inner"].toInt())
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun testVectorDirect() {
        val builder = FlexBuffersBuilder(1024, FlexBuffersBuilder.SHARE_KEYS)

        val start = builder.startVector()
        builder.put(1)
        builder.put(2)
        builder.put(3)
        builder.endVector(start)

        val buffer = builder.finish()
        println("Vector buffer limit: ${buffer.limit}")
        assertTrue("Buffer should have content", buffer.limit > 0)

        val root = getRoot(buffer)
        assertTrue("Root should be a vector", root.isVector || root.isTypedVector)

        val vec = root.toVector()
        assertEquals("Vector should have 3 elements", 3, vec.size)
        assertEquals(1, vec[0].toInt())
        assertEquals(2, vec[1].toInt())
        assertEquals(3, vec[2].toInt())
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun testPooledBuilderReuse() {
        val builder = FlexBuffersBuilder(1024, FlexBuffersBuilder.SHARE_KEYS)

        // First use
        var start = builder.startMap()
        builder.set("key1", "value1")
        builder.endMap(start)
        var buffer = builder.finish()
        var root = getRoot(buffer)
        assertEquals("value1", root.toMap()["key1"].toString())

        // Clear and reuse
        builder.clear()

        start = builder.startMap()
        builder.set("key2", "value2")
        builder.endMap(start)
        buffer = builder.finish()
        root = getRoot(buffer)
        assertEquals("value2", root.toMap()["key2"].toString())
    }
}
