package dev.shibasis.reaktor.flatinvoker

import dev.shibasis.reaktor.flatinvoker.Platform
import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {

    @Test
    fun testExample() {
        assertTrue(Greeting().greeting().contains("iOS"), "Check iOS is mentioned")
        assertTrue(Platform().data == 12, "Able to call C++")
        println(Platform().data)
    }
}