@file:Suppress("KotlinJniMissingFunction")
package com.jetbrains.kmm.shared

import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.ReadBuffer
import java.nio.ByteBuffer

fun ReadBuffer.toByteBuffer(): ByteBuffer {
    val byteArray = data()
    return ByteBuffer.allocateDirect(limit).apply {
        for (i in 0 until limit)
            put(byteArray[i])
    }
}

// todo optimise this to prevent a copy
fun ByteBuffer.toReadBuffer(): ReadBuffer {
    val backingArray = array()
    return ArrayReadWriteBuffer(backingArray.sliceArray(arrayOffset() until arrayOffset() + limit())).apply {
        writePosition = limit()
    }
}

object JavaJni {
    private external fun sendMessage(byteBuffer: ByteBuffer): ByteBuffer
    fun sendMessage(message: ReadBuffer): ReadBuffer {
        val buffer: ByteBuffer = message.toByteBuffer()
        val result = sendMessage(buffer)
        // When JNI returns the result, there is an offset of 4 bytes
        // Why ?
        // I need to enforce little endian encoding globally in the framework
//        ByteOrder.LITTLE_ENDIAN
        val start = 4
        val end = 3
        return result.toReadBuffer()
    }

    external fun getByteBuffer(): ByteBuffer
    external fun echoByteBuffer(byteBuffer: ByteBuffer): ByteBuffer

    external fun execute(byteBuffer: ByteBuffer): ByteBuffer
    external fun parseJson(data: String): ByteBuffer

    fun execute(message: ReadBuffer): ReadBuffer {
        val result = execute(message.toByteBuffer())
        return result.toReadBuffer()
    }

    init {
        System.loadLibrary("kmmFlatInvoker")
    }
}







