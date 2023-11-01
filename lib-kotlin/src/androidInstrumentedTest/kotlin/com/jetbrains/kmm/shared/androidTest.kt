package com.jetbrains.kmm.shared

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.getRoot
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {
    @Test
    fun testExample() {
        assertTrue("Check Android is mentioned", Greeting().greeting().contains("Android"))
    }

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

        val ref = getRoot(result)
        val x = ref.type
        val sum = ref.toInt();

        val answer = result[0].toInt()
        assertTrue("Sum is 100", answer == 100)
    }
}
