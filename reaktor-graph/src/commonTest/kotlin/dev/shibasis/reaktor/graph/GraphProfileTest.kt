package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.GraphProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GraphProfileTest {
    @Test fun selectionOnlyConstructsTheChosenImplementation() {
        val calls = mutableListOf<String>()
        val mock = GraphProfile("mock")
        val mixed = mock.selecting(mapOf("chat" to "remote"))
        assertEquals("mock", mock.mode("chat"))
        assertEquals("remote", mixed.create<String>("chat") { calls += it; it })
        assertEquals("mock", mixed.mode("people"))
        assertEquals(listOf("remote"), calls)
    }

    @Test fun typedReplacementKeepsOtherTypesAndActivationsIndependent() {
        val initial = GraphProfile("mock")
        val api = listOf("fake")
        val replaced = initial.replacing<List<String>>("chat", api)
        assertSame(api, replaced.create<List<String>>("chat") { error("Recipe must not run") })
        assertEquals("remote", replaced.create<String>("chat") { "remote" })
        assertEquals(listOf("original"), initial.create<List<String>>("chat") { listOf("original") })
    }
}
