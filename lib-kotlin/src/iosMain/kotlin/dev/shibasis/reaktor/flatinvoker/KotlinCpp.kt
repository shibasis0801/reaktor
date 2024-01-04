package dev.shibasis.reaktor.flatinvoker

import com.google.flatbuffers.kotlin.value
import dev.shibasis.reaktor.native.getName
import dev.shibasis.reaktor.native.getNameCpp
import dev.shibasis.reaktor.native.reaktorTest
import dev.shibasis.reaktor.native.sendByteArray
import dev.shibasis.reaktor.native.ExampleClass
import dev.shibasis.reaktor.native.Flex_delete
import dev.shibasis.reaktor.native.Flex_getBuffer
import dev.shibasis.reaktor.native.Flex_getBuffer1
import dev.shibasis.reaktor.native.Flex_getInt
import dev.shibasis.reaktor.native.Flex_new
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CValue
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents

object KotlinCpp {
    fun cppFlexBufferThroughC(): ByteArray? {
        val flexBuffer = Flex_new()
//        val flexArray = Flex_getBuffer(flexBuffer)
        val data = Flex_getInt(flexBuffer)

        val array1 = Flex_getBuffer1(flexBuffer).useContents {
            buffer?.readBytes(size.toInt())
        }

//        val byteArray = flexArray.useContents {
//            buffer?.readBytes(size.toInt())
//        }
        return array1
    }

    fun t(): String? {
        val x = ExampleClass()
        return x.getHelloWorld()
    }

    fun getIntFromC(): Int {
        return reaktorTest()
    }
    fun getNameFromC(): String? {
        return getName()?.toKString()
    }

    fun getNameFromCpp(): String? {
        return getNameCpp()?.toKString()
    }

    fun testSendBytesArray(byteArray: ByteArray): Boolean {
        val result = sendByteArray(byteArray.toUByteArray().toCValues(), byteArray.size)
        return result == 1
    }
}
