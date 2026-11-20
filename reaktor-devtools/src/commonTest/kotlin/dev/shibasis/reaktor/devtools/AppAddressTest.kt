package dev.shibasis.reaktor.devtools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppAddressTest {
    @Test
    fun eachAppHasItsOwnStableAddressAwayFromTheSharedOnes() {
        val ports = mapOf("ai.gymbuddy.app" to 47_961, "ai.hearth.app" to 47_904, "ai.coffer.app" to 47_897, "ai.lector.app" to 47_913, "ai.bestbuds.app" to 47_902)
        ports.forEach { (app, port) -> assertEquals(port, DevToolsProtocol.loopbackPort(app), app) }
        assertTrue(ports.values.all { it in DevToolsProtocol.AppPortFirst until DevToolsProtocol.AppPortFirst + DevToolsProtocol.AppPortCount })
        assertTrue(DevToolsProtocol.DefaultLoopbackPort !in ports.values && DevToolsProtocol.BrowserPort !in ports.values)
        assertEquals("reaktor-devtools.ai.coffer.app", DevToolsProtocol.androidSocketName("ai.coffer.app"))
    }
}
