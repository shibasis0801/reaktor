package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.core.EncodingComplexCase
import dev.shibasis.reaktor.core.EncodingSimpleCase
import dev.shibasis.reaktor.core.InnerNestedData
import dev.shibasis.reaktor.core.NestedData
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Integration tests for FlexBuffer encode-decode round-trip.
 * Verifies that objects survive serialization and deserialization intact.
 */
class FlexDecoderTests {

    // ---- Helper ----

    private inline fun <reified T> roundTrip(value: T): T {
        val encoded = FlexBuffers.encode(value)
        return FlexBuffers.decode(encoded)
    }

    @Serializable
    data class PrimitiveBulkCase(
        val bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
        val ints: List<Int> = listOf(1, 2, 3, 4, 5),
        val doubles: List<Double> = listOf(1.5, 2.5, 3.5),
        val flags: List<Boolean> = listOf(true, false, true),
        val chars: List<Char> = listOf('a', 'b', 'c')
    )

    @Serializable
    data class NegativePrimitiveCase(
        val ints: List<Int> = listOf(-1, 0, 1)
    )

    // ---- Simple Case ----

    @Test
    fun testRoundTripSimpleCase() {
        val original = EncodingSimpleCase()
        val decoded = roundTrip(original)

        assertEquals(original.mapOfStringToInt, decoded.mapOfStringToInt)
        assertEquals(original.arrayOfInt, decoded.arrayOfInt)
        assertEquals(original.mutableMapOfStringToList.size, decoded.mutableMapOfStringToList.size)
        original.mutableMapOfStringToList.forEach { (key, value) ->
            assertEquals(value, decoded.mutableMapOfStringToList[key])
        }
    }

    // ---- Complex Case: all primitive types, collections, nested objects, maps ----

    @Test
    fun testRoundTripComplexCase() {
        val original = EncodingComplexCase()
        val decoded = roundTrip(original)

        // Primitives
        assertEquals(original.booleanField, decoded.booleanField)
        assertEquals(original.byteField, decoded.byteField)
        assertEquals(original.shortField, decoded.shortField)
        assertEquals(original.intField, decoded.intField)
        assertEquals(original.longField, decoded.longField)
        assertEquals(original.floatField, decoded.floatField)
        assertEquals(original.doubleField, decoded.doubleField)
        assertEquals(original.charField, decoded.charField)
        assertEquals(original.stringField, decoded.stringField)

        // ByteArray
        assertContentEquals(original.byteArrayField, decoded.byteArrayField)

        // Typed lists
        assertEquals(original.shortListField, decoded.shortListField)
        assertEquals(original.intSetField, decoded.intSetField)
        assertEquals(original.longListField, decoded.longListField)
        assertEquals(original.floatSetField, decoded.floatSetField)
        assertEquals(original.doubleListField, decoded.doubleListField)
        assertEquals(original.charListField, decoded.charListField)
        assertEquals(original.stringSetField, decoded.stringSetField)

        // Nested lists
        assertEquals(original.listOfLists, decoded.listOfLists)

        // Maps
        assertEquals(original.mapOfStringToInt, decoded.mapOfStringToInt)
        assertEquals(original.mapOfIntToBoolean, decoded.mapOfIntToBoolean)

        // Set of sets
        assertEquals(original.setOfSets, decoded.setOfSets)

        // Map of string to list
        assertEquals(original.mutableMapOfStringToList.size, decoded.mutableMapOfStringToList.size)
        original.mutableMapOfStringToList.forEach { (key, value) ->
            assertEquals(value, decoded.mutableMapOfStringToList[key])
        }

        // Nested data
        assertEquals(original.nestedData.nestedInt, decoded.nestedData.nestedInt)
        assertEquals(original.nestedData.nestedString, decoded.nestedData.nestedString)
        assertEquals(original.nestedData.innerNestedData.size, decoded.nestedData.innerNestedData.size)
        original.nestedData.innerNestedData.forEachIndexed { i, inner ->
            assertEquals(inner.innerValue, decoded.nestedData.innerNestedData[i].innerValue)
            assertEquals(inner.innerList, decoded.nestedData.innerNestedData[i].innerList)
        }

        // Map of string to nested data
        assertEquals(original.mapOfStringToNestedData.size, decoded.mapOfStringToNestedData.size)
        original.mapOfStringToNestedData.forEach { (key, value) ->
            val decodedNested = decoded.mapOfStringToNestedData[key]!!
            assertEquals(value.nestedInt, decodedNested.nestedInt)
            assertEquals(value.nestedString, decodedNested.nestedString)
            assertEquals(value.innerNestedData.size, decodedNested.innerNestedData.size)
        }
    }

    @Test
    fun testBulkPrimitiveCollectionsUseCompactWireTypes() {
        val encoded = FlexBuffers.encode(PrimitiveBulkCase())
        val root = FlexBuffers.getRoot(encoded).toMap()

        assertTrue(root["bytes"].isBlob)
        assertTrue(root["ints"].isTypedVector)
        assertTrue(root["doubles"].isTypedVector)
        assertTrue(root["flags"].isTypedVector)
        assertTrue(root["chars"].isTypedVector)

        val decoded = FlexBuffers.decode<PrimitiveBulkCase>(encoded)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), decoded.bytes)
        assertEquals(listOf(1, 2, 3, 4, 5), decoded.ints)
        assertEquals(listOf(1.5, 2.5, 3.5), decoded.doubles)
        assertEquals(listOf(true, false, true), decoded.flags)
        assertEquals(listOf('a', 'b', 'c'), decoded.chars)
    }

    @Test
    fun testNegativeSignedCollectionsStayGenericVectors() {
        val encoded = FlexBuffers.encode(NegativePrimitiveCase())
        val root = FlexBuffers.getRoot(encoded).toMap()

        assertTrue(root["ints"].isVector)
        assertFalse(root["ints"].isTypedVector)

        val decoded = FlexBuffers.decode<NegativePrimitiveCase>(encoded)
        assertEquals(listOf(-1, 0, 1), decoded.ints)
    }

    // ---- Primitives in isolation ----

    @Serializable
    data class PrimitivesOnly(
        val b: Boolean = true,
        val i: Int = 42,
        val l: Long = 123456789L,
        val f: Float = 3.14f,
        val d: Double = 2.718281828,
        val s: String = "hello world",
        val c: Char = 'Z',
        val by: Byte = 127,
        val sh: Short = 32000
    )

    @Test
    fun testRoundTripPrimitivesOnly() {
        val original = PrimitivesOnly()
        val decoded = roundTrip(original)
        assertEquals(original, decoded)
    }

    // ---- Empty collections ----

    @Serializable
    data class EmptyCollections(
        val emptyList: List<Int> = emptyList(),
        val emptyMap: Map<String, String> = emptyMap(),
        val emptySet: Set<Double> = emptySet()
    )

    @Test
    fun testRoundTripEmptyCollections() {
        val original = EmptyCollections()
        val decoded = roundTrip(original)
        assertEquals(original.emptyList, decoded.emptyList)
        assertEquals(original.emptyMap, decoded.emptyMap)
        assertEquals(original.emptySet, decoded.emptySet)
    }

    // ---- Nested objects ----

    @Serializable
    data class Outer(
        val name: String = "outer",
        val inner: Inner = Inner()
    )

    @Serializable
    data class Inner(
        val value: Int = 99,
        val label: String = "inner"
    )

    @Test
    fun testRoundTripNestedObjects() {
        val original = Outer()
        val decoded = roundTrip(original)
        assertEquals(original, decoded)
    }

    // ---- Map with non-string keys ----

    @Serializable
    data class IntKeyMap(
        val data: Map<Int, String> = mapOf(1 to "one", 2 to "two", 3 to "three")
    )

    @Test
    fun testRoundTripIntKeyMap() {
        val original = IntKeyMap()
        val decoded = roundTrip(original)
        assertEquals(original.data.size, decoded.data.size)
        original.data.forEach { (key, value) ->
            assertEquals(value, decoded.data[key])
        }
    }

    // ---- List of objects ----

    @Serializable
    data class ListOfObjects(
        val items: List<Inner> = listOf(
            Inner(1, "first"),
            Inner(2, "second"),
            Inner(3, "third")
        )
    )

    @Test
    fun testRoundTripListOfObjects() {
        val original = ListOfObjects()
        val decoded = roundTrip(original)
        assertEquals(original.items.size, decoded.items.size)
        original.items.forEachIndexed { i, item ->
            assertEquals(item, decoded.items[i])
        }
    }

    // ---- Map of string to list of objects ----

    @Serializable
    data class MapOfLists(
        val groups: Map<String, List<Inner>> = mapOf(
            "group1" to listOf(Inner(1, "a"), Inner(2, "b")),
            "group2" to listOf(Inner(3, "c"))
        )
    )

    @Test
    fun testRoundTripMapOfLists() {
        val original = MapOfLists()
        val decoded = roundTrip(original)
        assertEquals(original.groups.size, decoded.groups.size)
        original.groups.forEach { (key, list) ->
            val decodedList = decoded.groups[key]!!
            assertEquals(list.size, decodedList.size)
            list.forEachIndexed { i, item ->
                assertEquals(item, decodedList[i])
            }
        }
    }

    // ---- Deeply nested structure ----

    @Test
    fun testRoundTripNestedData() {
        val original = NestedData(
            nestedInt = 42,
            nestedString = "deep",
            innerNestedData = listOf(
                InnerNestedData(1.1, listOf("a", "b")),
                InnerNestedData(2.2, listOf("c", "d", "e"))
            )
        )
        val decoded = roundTrip(original)
        assertEquals(original.nestedInt, decoded.nestedInt)
        assertEquals(original.nestedString, decoded.nestedString)
        assertEquals(original.innerNestedData.size, decoded.innerNestedData.size)
        original.innerNestedData.forEachIndexed { i, inner ->
            assertEquals(inner.innerValue, decoded.innerNestedData[i].innerValue)
            assertEquals(inner.innerList, decoded.innerNestedData[i].innerList)
        }
    }

    // ---- Performance: encode-decode vs JSON ----

    @Test
    fun benchRoundTripComplexCase() {
        val complexCase = EncodingComplexCase()
        val times = 20

        var avgFlexEncode = 0L
        var avgFlexDecode = 0L
        var avgJsonEncode = 0L
        var avgJsonDecode = 0L

        repeat(times) {
            val flexEncodeTime = measureTime {
                FlexBuffers.encode(complexCase)
            }.inWholeMicroseconds

            // Encode once for decode timing
            val encoded = FlexBuffers.encode(complexCase)

            val flexDecodeTime = measureTime {
                FlexBuffers.decode<EncodingComplexCase>(encoded)
            }.inWholeMicroseconds

            var json = ""
            val jsonEncodeTime = measureTime {
                json = Json.encodeToString(complexCase)
            }.inWholeMicroseconds

            val jsonDecodeTime = measureTime {
                Json.decodeFromString(EncodingComplexCase.serializer(), json)
            }.inWholeMicroseconds

            avgFlexEncode += flexEncodeTime
            avgFlexDecode += flexDecodeTime
            avgJsonEncode += jsonEncodeTime
            avgJsonDecode += jsonDecodeTime
        }

        avgFlexEncode /= times
        avgFlexDecode /= times
        avgJsonEncode /= times
        avgJsonDecode /= times

        println("--- Round-trip Benchmark (${times} iterations avg, microseconds) ---")
        println("FlexBuffer Encode: $avgFlexEncode")
        println("FlexBuffer Decode: $avgFlexDecode")
        println("FlexBuffer Total:  ${avgFlexEncode + avgFlexDecode}")
        println("Json Encode: $avgJsonEncode")
        println("Json Decode: $avgJsonDecode")
        println("Json Total:  ${avgJsonEncode + avgJsonDecode}")

        // Sanity: decoded values must match
        val decoded = roundTrip(complexCase)
        assertEquals(complexCase.intField, decoded.intField)
        assertEquals(complexCase.stringField, decoded.stringField)
        assertEquals(complexCase.booleanField, decoded.booleanField)
    }
}
