package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortWatchTest {
    private data class Session(val userId: String, val accessToken: String, val refreshToken: String)

    private class Opaque

    @Test
    fun valuesWithSecretsAreMasked() {
        val value = watchedValue(Session("u-1", "eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl", "rkr_abcDEF-123"))!!
        assertTrue(value.startsWith("Session(userId=u-1"))
        assertFalse("eyJhbGciOi" in value)
        assertFalse("rkr_abcDEF-123" in value)
    }

    @Test
    fun nothingWorthShowingIsDropped() {
        assertNull(watchedValue(null))
        assertNull(watchedValue(Unit))
        assertNull(watchedValue(Opaque()))
    }

    @Test
    fun collectionsAreSummarisedNotDumped() {
        val value = watchedValue((1..1000).toList())!!
        assertTrue(value.endsWith("(1000) [1, 2, 3, …]"), value)
        assertEquals("Map(1) {a=1}", watchedValue(mapOf("a" to 1)))
        assertEquals(600, watchedValue("x".repeat(5000))!!.length)
    }

    @Test
    fun watchesAreAddressedByNodeAndPort() {
        val watches = PortWatches()
        assertFalse(watches.watching("n1", "port"))
        watches.add("n1", "port")
        assertTrue(watches.watching("n1", "port"))
        assertFalse(watches.watching("n2", "port"))
        watches.remove("n1", "port")
        assertFalse(watches.watching("n1", "port"))
    }

    @Test
    fun theAgentOffersWatchingOnlyWhereValuesMayBeCaptured() = runBlocking {
        val closed = DevToolsAgent("test.app", "Test", policy = AgentPolicy(captureValues = false))
        val refused = closed.execute(AgentCommand("1", AgentCapability.PortWatch, "add", mapOf("node" to "n", "port" to "p")))
        assertFalse(refused.accepted)
        val open = DevToolsAgent("test.app", "Test", policy = AgentPolicy(captureValues = true))
        val accepted = open.execute(AgentCommand("2", AgentCapability.PortWatch, "add", mapOf("node" to "n", "port" to "p")))
        assertTrue(accepted.accepted)
        assertTrue(open.watches.watching("n", "p"))
    }
}
