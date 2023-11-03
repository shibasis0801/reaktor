@file:Suppress("KotlinJniMissingFunction")
package com.jetbrains.kmm.shared

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import java.nio.ByteBuffer
import java.nio.ByteOrder

object JavaMessageReceiver {
    fun onMessage(message: ByteArray) {

    }
}

fun ReadBuffer.toByteBuffer(): ByteBuffer {
    val byteArray = data()
    return ByteBuffer.allocateDirect(limit).apply {
        for (i in 0 until limit)
            put(byteArray[i])
    }
}

fun ByteBuffer.toReadBuffer(): ReadBuffer {
    val backingArray = array()
    return ArrayReadWriteBuffer(backingArray.sliceArray(arrayOffset() until arrayOffset() + limit())).apply {
        writePosition = limit()
    }
}

object JavaMessageSender: MessageSender {
    private external fun sendMessage(byteBuffer: ByteBuffer): ByteBuffer
    override fun sendMessage(message: ReadBuffer): ReadBuffer {
        val result = sendMessage(message.toByteBuffer())
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
    fun execute(message: ReadBuffer): ReadBuffer {
        val result = execute(message.toByteBuffer())
        return result.toReadBuffer()
    }

    init {
        System.loadLibrary("kmmFlatInvoker")
    }
}
