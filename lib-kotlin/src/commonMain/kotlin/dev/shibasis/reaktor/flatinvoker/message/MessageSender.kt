package dev.shibasis.reaktor.flatinvoker.message

import com.google.flatbuffers.kotlin.ReadBuffer

interface MessageSender {
    fun sendMessage(message: ReadBuffer): ReadBuffer
}


