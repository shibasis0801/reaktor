package dev.shibasis.flatinvoker.core

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.serialization.encodeToFlexBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime


inline fun repeatedAverage(count: Int, crossinline fn: () -> Number): Double {
    var sum = 0.0
    repeat(count) {
        sum += fn().toDouble()
    }
    return sum / count.toDouble()
}

class FlexEncoderTests {
    /*
    This test is meant for you to modify the simplecase with some class you wish to check.
    Then you write the assertions to check if the fields match those in the class.
    Check the testFlexEncoder function to understand how.
     */
    @Test
    fun testFlexEncoderSimple() {
        val simpleTest = measureTime {
            val flexBuffer = encodeToFlexBuffer(EncodingSimpleCase())
            FlexBuffer.Finish(flexBuffer)
            FlexBuffer.GetBuffer(flexBuffer)
        }
        println("FlexBuffer Encode Time: $simpleTest")
        assertTrue(true)
    }

    @Test
    fun testFlexEncoderComplex() {
        val complexCase = EncodingComplexCase()
        var flexBuffer: Long
        lateinit var array: ByteArray

        val flexEncodingTime = repeatedAverage(10) {
            measureTime {
                flexBuffer = encodeToFlexBuffer(complexCase)
                FlexBuffer.Finish(flexBuffer)
                array = FlexBuffer.GetBuffer(flexBuffer)
            }.inWholeMicroseconds
        }


        val jsonEncodingTime = repeatedAverage(10) {
            measureTime {
                Json.encodeToJsonElement(complexCase)
            }.inWholeMicroseconds
        }


        println("FlexBuffer Encode Time: $flexEncodingTime")
        println("Json Encode Time: $jsonEncodingTime")
        assertTrue { array != null }

        val root = getRoot(ArrayReadBuffer(array))

        // write assertions to check if the fields match those in complex case

        assertTrue(root.isMap)
        val map = root.toMap()

        assertTrue(map["booleanField"].isBoolean)
        assertEquals(map["booleanField"].toBoolean(), complexCase.booleanField)

        assertTrue(map["intField"].isNumeric)
        assertEquals(map["intField"].toInt(), complexCase.intField)

        assertTrue(map["longField"].isNumeric)
        assertEquals(map["longField"].toLong(), complexCase.longField)

        assertTrue(map["doubleField"].isFloat)
        assertEquals(map["doubleField"].toDouble(), complexCase.doubleField)

        assertTrue(map["stringField"].isString)
        assertEquals(map["stringField"].toString(), complexCase.stringField)

        assertTrue(map["byteArrayField"].isVector)
        val byteArray = map["byteArrayField"].toVector()
        assertEquals(byteArray.size, complexCase.byteArrayField.size)
        byteArray.forEachIndexed { idx, it ->
            assertTrue { it.isNumeric }
            assertEquals(complexCase.byteArrayField[idx], it.toByte())
        }

        assertTrue(map["shortListField"].isVector)
        val shortList = map["shortListField"].toVector()
        assertEquals(shortList.size, complexCase.shortListField.size)
        shortList.forEachIndexed { idx, it ->
            assertTrue { it.isNumeric }
            assertEquals(complexCase.shortListField[idx], it.toShort())
        }

        assertTrue(map["intSetField"].isVector)
        val intSet = map["intSetField"].toVector()
        assertEquals(intSet.size, complexCase.intSetField.size)
        intSet.forEachIndexed { idx, it ->
            assertTrue { it.isNumeric }
            assertEquals(complexCase.intSetField.elementAt(idx), it.toInt())
        }

        assertTrue(map["longListField"].isVector)
        val longList = map["longListField"].toVector()
        assertEquals(longList.size, complexCase.longListField.size)
        longList.forEachIndexed { idx, it ->
            assertTrue { it.isNumeric }
            assertEquals(complexCase.longListField[idx], it.toLong())
        }

        assertTrue(map["floatSetField"].isVector)
        val floatSet = map["floatSetField"].toVector()
        assertEquals(floatSet.size, complexCase.floatSetField.size)
        floatSet.forEachIndexed { idx, it ->
            assertTrue { it.isFloat }
            assertEquals(complexCase.floatSetField.elementAt(idx), it.toFloat())
        }

        assertTrue(map["doubleListField"].isVector)
        val doubleList = map["doubleListField"].toVector()
        assertEquals(doubleList.size, complexCase.doubleListField.size)
        doubleList.forEachIndexed { idx, it ->
            assertTrue { it.isFloat }
            assertEquals(complexCase.doubleListField[idx], it.toDouble())
        }

        assertTrue(map["charListField"].isVector)
        val charList = map["charListField"].toVector()
        assertEquals(charList.size, complexCase.charListField.size)
        charList.forEachIndexed { idx, it ->
            assertTrue { it.isNumeric }
            assertEquals(complexCase.charListField[idx], it.toInt().toChar())
        }

        assertTrue(map["stringSetField"].isVector)
        val stringSet = map["stringSetField"].toVector()
        assertEquals(stringSet.size, complexCase.stringSetField.size)
        stringSet.forEachIndexed { idx, it ->
            assertTrue { it.isString }
            assertEquals(complexCase.stringSetField.elementAt(idx), it.toString())
        }

        assertTrue(map["listOfLists"].isVector)
        val listOfLists = map["listOfLists"].toVector()
        assertEquals(listOfLists.size, complexCase.listOfLists.size)
        listOfLists.forEachIndexed { idx, it ->
            assertTrue { it.isVector }
            val innerList = it.toVector()
            assertEquals(innerList.size, complexCase.listOfLists[idx].size)
            innerList.forEachIndexed { innerIdx, innerIt ->
                assertTrue { innerIt.isNumeric }
                assertEquals(complexCase.listOfLists[idx][innerIdx], innerIt.toInt())
            }
        }


        assertTrue(map["mapOfStringToInt"].isMap)
        val mapOfStringToInt = map["mapOfStringToInt"].toMap()
        // a flexbuffer map is a vector of key, value pairs, so the size is doubled
        assertEquals(mapOfStringToInt.size, complexCase.mapOfStringToInt.size)
        mapOfStringToInt.forEach { (key, value) ->
            assertTrue { value.isNumeric }
            assertEquals(complexCase.mapOfStringToInt[key.toString()], value.toInt())
        }

        assertTrue(map["mapOfIntToBoolean"].isMap)
        val mapOfIntToBoolean = map["mapOfIntToBoolean"].toMap()
        assertEquals(mapOfIntToBoolean.size, complexCase.mapOfIntToBoolean.size)

        mapOfIntToBoolean.forEach { (key, value) ->
            assertTrue { value.isBoolean }
            assertEquals(complexCase.mapOfIntToBoolean[key.toString().toInt()], value.toBoolean())
        }


        assertTrue(map["setOfSets"].isVector)
        val setOfSets = map["setOfSets"].toVector()
        assertEquals(setOfSets.size, complexCase.setOfSets.size)
        setOfSets.forEachIndexed { idx, it ->
            assertTrue { it.isVector }
            val innerSet = it.toVector()
            assertEquals(innerSet.size, complexCase.setOfSets.elementAt(idx).size)
            innerSet.forEachIndexed { innerIdx, innerIt ->
                assertTrue { innerIt.isFloat }
                assertEquals(complexCase.setOfSets.elementAt(idx).elementAt(innerIdx), innerIt.toFloat())
            }
        }

        assertTrue(map["mutableMapOfStringToList"].isMap)
        val mutableMapOfStringToList = map["mutableMapOfStringToList"].toMap()
        assertEquals(mutableMapOfStringToList.size, complexCase.mutableMapOfStringToList.size)
        mutableMapOfStringToList.forEach { (key, value) ->
            assertTrue { value.isVector }
            val list = value.toVector()
            assertEquals(list.size, complexCase.mutableMapOfStringToList[key.toString()]!!.size)
            list.forEachIndexed { idx, it ->
                assertTrue { it.isFloat }
                assertEquals(complexCase.mutableMapOfStringToList[key.toString()]!!.elementAt(idx), it.toDouble())
            }
        }


        assertTrue(map["nestedData"].isMap)
        val nestedData = map["nestedData"].toMap()
        assertEquals(nestedData.size, 3)
        assertTrue(nestedData["nestedInt"].isNumeric)
        assertEquals(nestedData["nestedInt"].toInt(), complexCase.nestedData.nestedInt)
        assertTrue(nestedData["nestedString"].isString)
        assertEquals(nestedData["nestedString"].toString(), complexCase.nestedData.nestedString)
        assertTrue(nestedData["innerNestedData"].isVector)
        val innerNestedData = nestedData["innerNestedData"].toVector()
        assertEquals(innerNestedData.size, complexCase.nestedData.innerNestedData.size)
        innerNestedData.forEachIndexed { idx, it ->
            assertTrue { it.isMap }
            val innerMap = it.toMap()
            assertEquals(innerMap.size, 2)
            assertTrue(innerMap["innerValue"].isFloat)
            assertEquals(innerMap["innerValue"].toDouble(), complexCase.nestedData.innerNestedData[idx].innerValue)
            assertTrue(innerMap["innerList"].isVector)
            val innerList = innerMap["innerList"].toVector()
            assertEquals(innerList.size, complexCase.nestedData.innerNestedData[idx].innerList.size)
            innerList.forEachIndexed { innerIdx, innerIt ->
                assertTrue { innerIt.isString }
                assertEquals(complexCase.nestedData.innerNestedData[idx].innerList[innerIdx], innerIt.toString())
            }
        }

        assertTrue(map["mapOfStringToNestedData"].isMap)
        val mapOfStringToNestedData = map["mapOfStringToNestedData"].toMap()
        assertEquals(mapOfStringToNestedData.size, complexCase.mapOfStringToNestedData.size)
        mapOfStringToNestedData.forEach { (key, value) ->
            assertTrue { value.isMap }

            val nestedData = value.toMap()
            assertEquals(nestedData.size, 3)
            assertTrue(nestedData["nestedInt"].isNumeric)
            assertEquals(nestedData["nestedInt"].toInt(), complexCase.mapOfStringToNestedData[key.toString()]?.nestedInt)
            assertTrue(nestedData["nestedString"].isString)
            assertEquals(nestedData["nestedString"].toString(), complexCase.mapOfStringToNestedData[key.toString()]?.nestedString)

            assertTrue(nestedData["innerNestedData"].isVector)
            val innerNestedData = nestedData["innerNestedData"].toVector()
            assertEquals(innerNestedData.size, complexCase.mapOfStringToNestedData[key.toString()]!!.innerNestedData.size)
            innerNestedData.forEachIndexed { idx, it ->
                assertTrue { it.isMap }
                val innerMap = it.toMap()
                assertEquals(innerMap.size, 2)
                assertTrue(innerMap["innerValue"].isFloat)
                assertEquals(innerMap["innerValue"].toDouble(), complexCase.mapOfStringToNestedData[key.toString()]!!.innerNestedData[idx].innerValue)
                assertTrue(innerMap["innerList"].isVector)
                val innerList = innerMap["innerList"].toVector()
                assertEquals(innerList.size, complexCase.mapOfStringToNestedData[key.toString()]!!.innerNestedData[idx].innerList.size)
                innerList.forEachIndexed { innerIdx, innerIt ->
                    assertTrue { innerIt.isString }
                    assertEquals(complexCase.mapOfStringToNestedData[key.toString()]!!.innerNestedData[idx].innerList[innerIdx], innerIt.toString())
                }
            }
        }
    }


    @Test
    fun benchSophisticatedCase() {
        val sophisticatedCase = EncodingSophisticatedCase()
        repeat(10) {
            val flexEncodingTime = measureTime {
                    val flexBuffer = encodeToFlexBuffer(sophisticatedCase)
                    FlexBuffer.Finish(flexBuffer)
//                    FlexBuffer.GetBuffer(flexBuffer)
                }.inWholeMicroseconds

            val jsonEncodingTime = measureTime {
                Json.encodeToJsonElement(sophisticatedCase)
            }.inWholeMicroseconds

            println("FlexBuffer Encode Time: $it: $flexEncodingTime")
            println("Json Encode Time: $it: $jsonEncodingTime")
        }
    }

    @Test
    fun benchPolymorphicCase() {

    }

    @Test
    fun benchHugeCase() {

    }

    @Test
    fun benchHugeSophisticatedCase() {

    }
}

