package dev.shibasis.flatinvoker.ffi

import org.junit.Test
import kotlin.test.assertEquals

class KotlinToCppTest {
    @Test
    fun test() {
        val result = Tester.test()
        assertEquals(3, result)
    }
}