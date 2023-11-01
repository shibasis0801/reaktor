package com.jetbrains.kmm.shared

import com.google.flatbuffers.kotlin.ReadBuffer

interface MessageSender {
    fun sendMessage(message: ReadBuffer): ReadBuffer
}


interface MessageReceiver {
    fun onMessage(message: ReadBuffer): ReadBuffer
}
