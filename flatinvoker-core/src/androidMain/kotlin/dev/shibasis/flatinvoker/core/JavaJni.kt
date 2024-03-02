@file:Suppress("KotlinJniMissingFunction")
package dev.shibasis.flatinvoker.core

import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.ReadBuffer
import dalvik.annotation.optimization.CriticalNative
import dalvik.annotation.optimization.FastNative
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
    // FastNative will have small benefits, CriticalNative will have greater benefits
    // https://nickb.website/blog/speeding-up-your-android-apps-native-calls
    // 1000 iterations without = 58 seconds, with = 55 seconds

    @FastNative
    external fun parseJson(data: String): ByteBuffer

    fun execute(message: ReadBuffer): ReadBuffer {
        val result = execute(message.toByteBuffer())
        return result.toReadBuffer()
    }

    init {
        System.loadLibrary("FlatInvokerCore")
    }
}







