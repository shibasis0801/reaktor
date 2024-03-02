package dev.shibasis.flatinvoker.core

import dev.shibasis.reaktor.native.getName
import dev.shibasis.reaktor.native.getNameCpp
import dev.shibasis.reaktor.native.reaktorTest
import dev.shibasis.reaktor.native.sendByteArray
import dev.shibasis.reaktor.native.ExampleClass
import dev.shibasis.reaktor.native.Flex_Create
import dev.shibasis.reaktor.native.Flex_EndVector
import dev.shibasis.reaktor.native.Flex_Finish
import dev.shibasis.reaktor.native.Flex_GetBuffer
import dev.shibasis.reaktor.native.Flex_Int
import dev.shibasis.reaktor.native.Flex_StartVector
import dev.shibasis.reaktor.native.Flex_String
import dev.shibasis.reaktor.native.RCTBridge
import dev.shibasis.reaktor.native.RCTBridgeMethodProtocol
import dev.shibasis.reaktor.native.RCTBridgeModuleProtocol
import dev.shibasis.reaktor.native.RCTCxxBridge
import dev.shibasis.reaktor.native.RCTFunctionType
import dev.shibasis.reaktor.native.RCTRegisterModule
import io.ktor.utils.io.core.Closeable
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.placeTo
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.useContents
import platform.darwin.NSObject

object KotlinCpp {
    fun cppFlexBufferThroughC(): ByteArray? {
        val flexBuffer = Flex_Create()
        val start = Flex_StartVector(flexBuffer, null)
        Flex_Int(flexBuffer, null,42);
        Flex_String(flexBuffer, null,"Shibasis Patnaik")
        Flex_EndVector(flexBuffer, start)

        Flex_Finish(flexBuffer)
        val data = Flex_GetBuffer(flexBuffer)
        val byteArray = data.useContents {
            buffer?.readBytes(size.toInt())
        }

        return byteArray
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
