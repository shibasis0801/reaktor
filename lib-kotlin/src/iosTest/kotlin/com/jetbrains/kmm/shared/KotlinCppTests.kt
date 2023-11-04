package com.jetbrains.kmm.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class KotlinCppTests {
    @Test
    fun testInt() {
        assertTrue(KotlinCpp.getIntFromC() == 42, "Able to call C++")
    }

    @Test
    fun testString() {
        assertTrue(KotlinCpp.getNameFromC() == "shibasis", "Able to pass a C string")
    }

    @Test
    fun testCppString() {
        println(KotlinCpp.getNameFromCpp())
        assertTrue(KotlinCpp.getNameFromCpp() == "shibasisCpp", "Able to pass a C++ string")
    }

    @Test
    fun testCppBytes() {
        assertTrue(KotlinCpp.testSendBytesArray(byteArrayOf(1)), "Able to pass a C++ bytes")
    }
}