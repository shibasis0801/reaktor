package com.jetbrains.kmm.shared

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.getRoot
import com.jetbrains.kmm.shared.message.JavaMessageSender
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class JavaMessageSenderTests {
    @Test
    fun testFlexBufEcho() {
        val flexbuf = FlexBuffersBuilder().apply {
            put(42)
        }.finish()
        val echoed = JavaMessageSender.sendMessage(flexbuf)
        val root = getRoot(echoed).toInt()
        assertTrue(root == 42)
    }
}