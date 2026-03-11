package dev.shibasis.reaktor.core

import dev.shibasis.reaktor.core.serialization.decodeFromFlexBuffer
import dev.shibasis.reaktor.core.serialization.encodeToFlexBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * iOS integration tests for FlexBuffer encode-decode round-trip.
 */
class FlexDecoderTests {

    private inline fun <reified T> roundTrip(value: T): T {
        val pointer = encodeToFlexBuffer(value)
        FlexBuffer.Finish(pointer)
        val result = decodeFromFlexBuffer<T>(pointer)
        FlexBuffer.Destroy(pointer)
        return result
    }

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

    @Test
    fun testRoundTripComplexCase() {
        val original = EncodingComplexCase()
        val decoded = roundTrip(original)

        assertEquals(original.booleanField, decoded.booleanField)
        assertEquals(original.byteField, decoded.byteField)
        assertEquals(original.shortField, decoded.shortField)
        assertEquals(original.intField, decoded.intField)
        assertEquals(original.longField, decoded.longField)
        assertEquals(original.floatField, decoded.floatField)
        assertEquals(original.doubleField, decoded.doubleField)
        assertEquals(original.charField, decoded.charField)
        assertEquals(original.stringField, decoded.stringField)
        assertContentEquals(original.byteArrayField, decoded.byteArrayField)
        assertEquals(original.shortListField, decoded.shortListField)
        assertEquals(original.intSetField, decoded.intSetField)
        assertEquals(original.longListField, decoded.longListField)
        assertEquals(original.floatSetField, decoded.floatSetField)
        assertEquals(original.doubleListField, decoded.doubleListField)
        assertEquals(original.charListField, decoded.charListField)
        assertEquals(original.stringSetField, decoded.stringSetField)
        assertEquals(original.listOfLists, decoded.listOfLists)
        assertEquals(original.mapOfStringToInt, decoded.mapOfStringToInt)
        assertEquals(original.mapOfIntToBoolean, decoded.mapOfIntToBoolean)
        assertEquals(original.setOfSets, decoded.setOfSets)
    }

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

    @Serializable
    data class Outer(val name: String = "outer", val inner: Inner = Inner())

    @Serializable
    data class Inner(val value: Int = 99, val label: String = "inner")

    @Test
    fun testRoundTripNestedObjects() {
        val original = Outer()
        val decoded = roundTrip(original)
        assertEquals(original, decoded)
    }

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

    @Test
    fun benchRoundTripComplexCase() {
        val complexCase = EncodingComplexCase()

        val flexEncodeTime = repeatedAverage(10) {
            measureTime {
                val ptr = encodeToFlexBuffer(complexCase)
                FlexBuffer.Finish(ptr)
            }.inWholeMicroseconds
        }

        // Pre-encode for decode benchmark
        val ptr = encodeToFlexBuffer(complexCase)
        FlexBuffer.Finish(ptr)

        val flexDecodeTime = repeatedAverage(10) {
            measureTime {
                decodeFromFlexBuffer<EncodingComplexCase>(ptr)
            }.inWholeMicroseconds
        }

        val jsonEncodeTime = repeatedAverage(10) {
            measureTime {
                Json.encodeToString(complexCase)
            }.inWholeMicroseconds
        }

        val json = Json.encodeToString(complexCase)
        val jsonDecodeTime = repeatedAverage(10) {
            measureTime {
                Json.decodeFromString(EncodingComplexCase.serializer(), json)
            }.inWholeMicroseconds
        }

        println("FlexBuffer Encode: $flexEncodeTime us")
        println("FlexBuffer Decode: $flexDecodeTime us")
        println("Json Encode: $jsonEncodeTime us")
        println("Json Decode: $jsonDecodeTime us")

        // Sanity check
        val decoded = decodeFromFlexBuffer<EncodingComplexCase>(ptr)
        assertEquals(complexCase.intField, decoded.intField)
        assertEquals(complexCase.stringField, decoded.stringField)
        FlexBuffer.Destroy(ptr)
    }
}
