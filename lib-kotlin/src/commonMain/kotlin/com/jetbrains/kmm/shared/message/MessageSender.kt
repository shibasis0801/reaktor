package com.jetbrains.kmm.shared.message

import com.google.flatbuffers.kotlin.ReadBuffer

interface MessageSender {
    fun sendMessage(message: ReadBuffer): ReadBuffer
}


