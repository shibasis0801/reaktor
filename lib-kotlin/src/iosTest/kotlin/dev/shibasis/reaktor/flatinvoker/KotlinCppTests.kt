package dev.shibasis.reaktor.flatinvoker

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadWriteBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.flatinvoker.KotlinCpp
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
}