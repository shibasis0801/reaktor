package dev.shibasis.reaktor.flatinvoker

import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {
    @Test
    fun testExample() {
        assertTrue("Check Android is mentioned", Greeting().greeting().contains("Android"))
    }
}