package dev.shibasis.reaktor.flatinvoker

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.flatinvoker.message.JavaMessageSender
import org.junit.Assert.assertTrue
import org.junit.Test

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