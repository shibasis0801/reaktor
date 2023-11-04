package com.jetbrains.kmm.shared.message

import com.google.flatbuffers.kotlin.ReadBuffer

object JavaMessageSender: MessageSender {

    override fun sendMessage(message: ReadBuffer): ReadBuffer {
        return message
    }
}