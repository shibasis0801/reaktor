//@file:Suppress("KotlinJniMissingFunction")
package dev.shibasis.reaktor.core

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

