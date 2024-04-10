package dev.shibasis.flatinvoker.ffi

import dev.shibasis.flatinvoker.core.EncodingSimpleCase
import dev.shibasis.flatinvoker.core.serialization.printDecoderCallTrace
import kotlin.test.Test
import kotlin.test.assertTrue

class FlexDecoderTests {
    @Test
    fun testFlexDecoderSimple() {
        val simpleCase = EncodingSimpleCase()
//        val flexBuffer = encodeToFlexBuffer(simpleCase)
//        val decoded = decodeFromFlexBuffer<EncodingSimpleCase>(flexBuffer)
        printDecoderCallTrace<EncodingSimpleCase>()
        assertTrue(false)
    }
}