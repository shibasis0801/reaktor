package dev.shibasis.reaktor.flatinvoker.message

import com.google.flatbuffers.kotlin.ReadBuffer

interface MessageReceiver {
    fun onMessage(message: ReadBuffer): ReadBuffer
}
