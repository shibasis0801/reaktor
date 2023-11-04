package com.jetbrains.kmm.shared.message

import com.google.flatbuffers.kotlin.ReadBuffer

interface MessageReceiver {
    fun onMessage(message: ReadBuffer): ReadBuffer
}
