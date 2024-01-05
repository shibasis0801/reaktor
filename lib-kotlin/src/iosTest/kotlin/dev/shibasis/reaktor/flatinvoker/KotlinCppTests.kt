package dev.shibasis.reaktor.flatinvoker

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadWriteBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.flatinvoker.KotlinCpp
import dev.shibasis.reaktor.flatinvoker.flexbuffer.FlexEncoder
import dev.shibasis.reaktor.flatinvoker.flexbuffer.encodeToFlexBuffer
import dev.shibasis.reaktor.native.Flex_GetBuffer
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import kotlinx.serialization.Serializable
import kotlin.test.Test
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
        val flexResult = Flex_GetBuffer(flexBuffer)
        val array = flexResult.useContents {
            buffer?.readBytes(size.toInt())
        }

        assertTrue { array != null }

        val root = getRoot(ArrayReadBuffer(array!!))

        assertTrue(root.isMap)
        assertTrue(root.toMap()["id"].isInt)
        assertTrue(root.toMap()["id"]!!.toInt() == 1)
        assertTrue(root.toMap()["name"].isString)
        assertTrue(root.toMap()["name"].toString() == "Shibasis")
        assertTrue(root.toMap()["age"].isFloat)
        assertTrue(root.toMap()["age"]!!.toDouble() == 27.9)

    }
}

@Serializable
data class Person(val id: Int, val name: String, val age: Double)