package dev.shibasis.flatinvoker.core

import dev.shibasis.flatinvoker.native.Flex_Blob
import dev.shibasis.flatinvoker.native.Flex_Bool
import dev.shibasis.flatinvoker.native.Flex_Create
import dev.shibasis.flatinvoker.native.Flex_Destroy
import dev.shibasis.flatinvoker.native.Flex_Double
import dev.shibasis.flatinvoker.native.Flex_EndMap
import dev.shibasis.flatinvoker.native.Flex_EndVector
import dev.shibasis.flatinvoker.native.Flex_Finish
import dev.shibasis.flatinvoker.native.Flex_Float
import dev.shibasis.flatinvoker.native.Flex_GetBuffer
import dev.shibasis.flatinvoker.native.Flex_Int
import dev.shibasis.flatinvoker.native.Flex_Null
import dev.shibasis.flatinvoker.native.Flex_ParseJson
import dev.shibasis.flatinvoker.native.Flex_StartMap
import dev.shibasis.flatinvoker.native.Flex_StartVector
import dev.shibasis.flatinvoker.native.Flex_String
import kotlinx.cinterop.useContents
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCValues


actual object FlexBuffer {
    actual inline fun Create() = Flex_Create()
    actual inline fun ParseJson(pointer: Long, data: String) = Flex_ParseJson(pointer, data).run { 0L }

    actual inline fun Destroy(pointer: Long) = Flex_Destroy(pointer)

    actual inline fun Finish(pointer: Long): Long = Flex_Finish(pointer).run { 0 }

    // todo Null Check
    actual inline fun GetBuffer(pointer: Long): ByteArray {
        val flexBuffer = Flex_GetBuffer(pointer)
        val byteArray = flexBuffer.useContents {
            buffer?.readBytes(size.toInt())
        }
        return requireNotNull(byteArray)
    }

    actual inline fun Null(pointer: Long, key: String?) = Flex_Null(pointer, key)

    actual inline fun Int(pointer: Long, key: String?, value: Long) = Flex_Int(pointer, key, value)

    actual inline fun Float(pointer: Long, key: String?, value: Float) = Flex_Float(pointer, key, value)

    actual inline fun Double(pointer: Long, key: String?, value: Double) = Flex_Double(pointer, key, value)

    actual inline fun Bool(pointer: Long, key: String?, value: Boolean) = Flex_Bool(pointer, key, value)

    actual inline fun String(pointer: Long, key: String?, value: String) = Flex_String(pointer, key, value)

    actual inline fun Blob(pointer: Long, key: String?, value: ByteArray) = Flex_Blob(pointer, key, value.toUByteArray().toCValues(), value.size.toULong())

    actual inline fun StartMap(pointer: Long, key: String?) = Flex_StartMap(pointer, key).toLong()

    actual inline fun EndMap(pointer: Long, mapStart: Long) = Flex_EndMap(pointer, mapStart.toULong())

    actual inline fun StartVector(pointer: Long, key: String?) = Flex_StartVector(pointer, key).toLong()

    actual inline fun EndVector(pointer: Long, vectorStart: Long) = Flex_EndVector(pointer, vectorStart.toULong())
}