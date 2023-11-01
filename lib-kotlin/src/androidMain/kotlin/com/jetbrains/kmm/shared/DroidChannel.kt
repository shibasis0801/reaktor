@file:Suppress("KotlinJniMissingFunction")
package com.jetbrains.kmm.shared

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import java.nio.ByteBuffer

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

object JavaMessageSender: MessageSender {
    private external fun sendMessage(byteBuffer: ByteBuffer): ByteBuffer
    override fun sendMessage(message: ReadBuffer): ReadBuffer {
        val result = sendMessage(message.toByteBuffer())
        // When JNI returns the result, there is an offset of 4 bytes
        // Why ?
        // Instead of meddling with bytebuffers should I pass direct byte arrays
        // I need to enforce little endian encoding globally in the framework
        return ArrayReadBuffer(result.array(), result.arrayOffset())
    }
    init {
        System.loadLibrary("kmmFlatInvoker")
    }
}
