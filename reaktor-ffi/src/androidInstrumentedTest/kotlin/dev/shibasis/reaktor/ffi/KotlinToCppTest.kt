package dev.shibasis.reaktor.ffi

import org.junit.Test
import kotlin.test.assertEquals

class KotlinToCppTest {
    @Test
    fun test() {
        val result = Tester.test()
        assertEquals(3, result)
    }

    @Test
    fun testHermes() {
        Tester.testHermes();
        assertEquals(1, 1);
    }
}