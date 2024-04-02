package dev.shibasis.flatinvoker.ffi

import dev.shibasis.flatinvoker.core.EncodingComplexCase
import dev.shibasis.flatinvoker.core.EncodingSimpleCase
import dev.shibasis.flatinvoker.core.serialization.decodeToCallTrace
import dev.shibasis.flatinvoker.core.serialization.encodeToFlexBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class FlexDecoderTests {
    @Test
    fun testFlexDecoderSimple() {
        val simpleCase = EncodingSimpleCase()
//        val flexBuffer = encodeToFlexBuffer(simpleCase)
//        val decoded = decodeFromFlexBuffer<EncodingSimpleCase>(flexBuffer)
        decodeToCallTrace<EncodingSimpleCase>()
        assertTrue(false)
    }
}