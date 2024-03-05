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
    actual inline fun ParseJson(pointer: FlexPointer, data: String) = Flex_ParseJson(pointer, data)

    actual inline fun Destroy(pointer: FlexPointer) = Flex_Destroy(pointer)

    actual inline fun Finish(pointer: FlexPointer) = Flex_Finish(pointer)

    // todo Null Check
    actual inline fun GetBuffer(pointer: FlexPointer): ByteArray {
        val flexBuffer = Flex_GetBuffer(pointer)
        val byteArray = flexBuffer.useContents {
            buffer?.readBytes(size.toInt())
        }
        return requireNotNull(byteArray)
    }

    actual inline fun Null(pointer: FlexPointer, key: String?) = Flex_Null(pointer, key)

    actual inline fun Int(pointer: FlexPointer, key: String?, value: Long) = Flex_Int(pointer, key, value)

    actual inline fun Float(pointer: FlexPointer, key: String?, value: Float) = Flex_Float(pointer, key, value)

    actual inline fun Double(pointer: FlexPointer, key: String?, value: Double) = Flex_Double(pointer, key, value)

    actual inline fun Bool(pointer: FlexPointer, key: String?, value: Boolean) = Flex_Bool(pointer, key, value)

    actual inline fun String(pointer: FlexPointer, key: String?, value: String) = Flex_String(pointer, key, value)

    actual inline fun Blob(pointer: FlexPointer, key: String?, value: ByteArray) = Flex_Blob(pointer, key, value.toUByteArray().toCValues(), value.size.toULong())

    actual inline fun StartMap(pointer: FlexPointer, key: String?) = Flex_StartMap(pointer, key)

    actual inline fun EndMap(pointer: FlexPointer, mapStart: ULong) = Flex_EndMap(pointer, mapStart)

    actual inline fun StartVector(pointer: FlexPointer, key: String?) = Flex_StartVector(pointer, key)

    actual inline fun EndVector(pointer: FlexPointer, vectorStart: ULong) = Flex_EndVector(pointer, vectorStart)
}