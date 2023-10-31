package com.jetbrains.kmm.shared

import com.jetbrains.kmm.shared.Platform
import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {

    @Test
    fun testInt() {
        assertTrue(Greeting().greeting().contains("iOS"), "Check iOS is mentioned")
        assertTrue(Platform().data != 0, "Able to call C++")
        println(Platform().data)
    }

    @Test
    fun testString() {
        assertTrue(FlatInvoker.getNameFromC() == "shibasis", "Able to pass a C string")
    }

    @Test
    fun testCppString() {
        println(FlatInvoker.getNameFromCpp())
        assertTrue(FlatInvoker.getNameFromCpp() == "shibasisCpp", "Able to pass a C++ string")
    }

    @Test
    fun testCppBytes() {
        assertTrue(FlatInvoker.testSendBytesArray(byteArrayOf(1)), "Able to pass a C++ bytes")
    }
}