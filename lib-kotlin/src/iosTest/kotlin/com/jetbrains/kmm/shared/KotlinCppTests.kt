package com.jetbrains.kmm.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class KotlinCppTests {
    @Test
    fun testNSString() {
        assertTrue(KotlinCpp.t() == "Hello, World!", "Able to call Objective - C++")
    }

    @Test
    fun testInt() {
        assertTrue(KotlinCpp.getIntFromC() > 100, "Able to call C++")
    }

    @Test
    fun testString() {
        println(KotlinCpp.getNameFromC())
        assertTrue(KotlinCpp.getNameFromC() == "shibasis", "Able to pass a C string")
    }

    @Test
    fun testCppString() {
        println(KotlinCpp.getNameFromCpp())
        assertTrue(KotlinCpp.getNameFromCpp() == "shibasisCpp", "Able to pass a C++ string")
    }

    @Test
    fun testCppBytes() {
        assertTrue(KotlinCpp.testSendBytesArray(byteArrayOf(1, 2, 3)), "Able to pass a C++ bytes")
    }
}