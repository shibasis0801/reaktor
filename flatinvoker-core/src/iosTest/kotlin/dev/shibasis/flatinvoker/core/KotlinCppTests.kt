package dev.shibasis.flatinvoker.core

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.flexbuffer.encodeToFlexBuffer
import dev.shibasis.reaktor.native.Flex_Create
import dev.shibasis.reaktor.native.Flex_Finish
import dev.shibasis.reaktor.native.Flex_GetBuffer
import dev.shibasis.reaktor.native.Flex_ParseJson
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinCppTests {
    @Test
    fun testNSString() {
        assertTrue(KotlinCpp.t() == "Hello, World!", "Able to call Objective - C++")
    }

    @Test
    fun testInt() {
        assertTrue(KotlinCpp.getIntFromC() > 100)
    }

    @Test
    fun testString() {
        println(KotlinCpp.getNameFromC())
        assertTrue(KotlinCpp.getNameFromC() == "shibasis")
    }

    @Test
    fun testCppString() {
        println(KotlinCpp.getNameFromCpp())
        assertTrue(KotlinCpp.getNameFromCpp() == "shibasisCpp")
    }

    @Test
    fun testCppBytes() {
        assertTrue(KotlinCpp.testSendBytesArray(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun testCppFlexBufferThroughC() {
        val bytes = KotlinCpp.cppFlexBufferThroughC()

        println("Size: ${bytes?.size}")
        assertTrue(bytes != null)

        val data = FlexBuffersBuilder().apply {
            putVector {
                put(42)
                put("shibasis")
            }
        }.finish().data()

        val buffer = ArrayReadBuffer(bytes)
        val root = getRoot(buffer)
//
        assertTrue(root.isVector)
        assertTrue(root.toVector()[0].toInt() == 42)
        assertTrue(root.toVector()[1].toString() == "Shibasis Patnaik")
    }

    @Test
    fun testFlexEncoder() {
        val person = Person(1, "Shibasis", 27.9)
        val flexBuffer = encodeToFlexBuffer(person)
        Flex_Finish(flexBuffer)
        val flexResult = Flex_GetBuffer(flexBuffer)
        val array = flexResult.useContents {
            buffer?.readBytes(size.toInt())
        }

        assertTrue { array != null }

        val root = getRoot(ArrayReadBuffer(array!!))

        assertTrue(root.isMap)
        assertTrue(root.toMap()["id"].isInt)
        assertTrue(root.toMap()["id"].toInt() == 1)
        assertTrue(root.toMap()["name"].isString)
        assertTrue(root.toMap()["name"].toString() == "Shibasis")
        assertTrue(root.toMap()["age"].isFloat)
        assertTrue(root.toMap()["age"].toDouble() == 27.9)


        val json = Json.encodeToString(person)
        val jsonFlexBuffer = Flex_Create()
        Flex_ParseJson(jsonFlexBuffer, json)
        val jsonBuffer = Flex_GetBuffer(jsonFlexBuffer)

        val jsonArray = jsonBuffer.useContents {
            buffer?.readBytes(size.toInt())
        }
        assertTrue { jsonArray != null }

        val jsonRoot = getRoot(ArrayReadBuffer(jsonArray!!))

        assertTrue(jsonRoot.isMap)
        assertTrue(jsonRoot.toMap()["id"].isInt)
        assertTrue(jsonRoot.toMap()["id"].toInt() == 1)
        assertTrue(jsonRoot.toMap()["name"].isString)
        assertTrue(jsonRoot.toMap()["name"].toString() == "Shibasis")
        assertTrue(jsonRoot.toMap()["age"].isFloat)
        assertTrue(jsonRoot.toMap()["age"].toDouble() == 27.9)
    }

    @Test
    fun testArrayEncode() {
        val array = arrayListOf(1, 2, 3)

        val flexBuffer = encodeToFlexBuffer(array)
        Flex_Finish(flexBuffer)
        val flexResult = Flex_GetBuffer(flexBuffer)
        val retrieved = flexResult.useContents {
            buffer?.readBytes(size.toInt())
        }

        assertTrue { retrieved != null }
        val root = getRoot(ArrayReadBuffer(retrieved!!))

        assertTrue { root.isVector || root.isTypedVector }
        val vec = root.toVector()

        array.forEachIndexed { index, i ->
            assertEquals(i, vec[index].toInt())
        }
    }
}

@Serializable
data class Person(val id: Int, val name: String, val age: Double)