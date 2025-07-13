package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.core.EncodingSimpleCase
import dev.shibasis.reaktor.core.serialization.printDecoderCallTrace
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