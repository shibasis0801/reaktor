package com.jetbrains.kmm.shared

import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.getRoot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class GreetingTest {
    @Test
    fun testCpp() {
        assertTrue("Able to access JNI", Platform().data == 42)
    }

    @Test
    fun testJavaCppFlexBufferIntegerSum() {
        val array = FlexBuffersBuilder().apply {
            val start = startVector()
            put(45)
            put(55)
            endVector(start)
        }.finish()

        val result = JavaMessageSender.sendMessage(array)
        val test = FlexBuffersBuilder().apply {
            put(100)
        }.finish()

        val data = test.data()
        val buf = ArrayReadWriteBuffer(data)

        val ref = getRoot(result).toVector()
        val sum = ref[2].toInt();

        val answer = result[0].toInt()
        assertTrue("Sum is 100", sum == 100)
    }

    @Test
    fun testByteBufferTransfer() {
        val buffer = JavaMessageSender.getByteBuffer()
        println("ByteBuffer offset -> ${buffer.arrayOffset()}")
        assertTrue(buffer.arrayOffset() == 0)
    }

    @Test
    fun testEchoByteBuffer() {
        val buffer = ByteBuffer.allocateDirect(1);
        val newBuffer = ByteBuffer.allocateDirect(1);
        newBuffer.put(9);
        val result = JavaMessageSender.echoByteBuffer(buffer)
        assertTrue(true)
    }

    @Test
    fun testExecute() {
        val command = FlexBuffersBuilder().apply {
            val start = startMap()
            this["className"] = "Sum"
            this["functionName"] = "add"
            endMap(start)
        }.finish()
        val result = JavaMessageSender.execute(command)
        val sum = getRoot(result).toVector()
        val className = sum[0].toString()
        val functionName = sum[1].toString()
        assertTrue(className == "Sum")
        assertTrue(functionName == "add")
    }
}
