@file:Suppress("KotlinJniMissingFunction")
package com.jetbrains.kmm.shared.message

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import com.jetbrains.kmm.shared.toByteBuffer
import com.jetbrains.kmm.shared.toReadBuffer
import java.io.Serializable
import java.nio.ByteBuffer


// We are going to rely on flatbuf's codegen for the actual project
// This is just for using during development
interface Marshallable {
    fun marshall(): ReadBuffer
}

interface Unmarshallable<T> {
    fun unmarshall(buffer: ReadBuffer): T
}

data class Point(
    val x: Int,
    val y: Int,
    val z: Int
): Marshallable {
    override fun marshall(): ReadBuffer {
        return FlexBuffersBuilder().apply {
            val start = startMap()
//            set("x", x)
//            set("y", y)
//            set("z", z)
            endMap(start)
        }.finish()
    }

    companion object: Unmarshallable<Point> {
        override fun unmarshall(buffer: ReadBuffer): Point {
            val root = getRoot(buffer).toMap()
            return Point(
                x = root["x"].toInt(),
                y = root["y"].toInt(),
                z = root["z"].toInt()
            )
        }
    }
}


object JavaMessageSender: MessageSender {
    private external fun sendMessage(message: ByteBuffer): ByteBuffer
    override fun sendMessage(message: ReadBuffer): ReadBuffer {
        val result = sendMessage(message.toByteBuffer())
        return result.toReadBuffer()
    }

    init {
        System.loadLibrary("kmmFlatInvoker")
    }
}


