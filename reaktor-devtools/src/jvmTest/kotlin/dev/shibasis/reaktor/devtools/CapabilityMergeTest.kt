package dev.shibasis.reaktor.devtools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One capability, one entry — and an entry that does not overstate itself.
 *
 * `logs` is both a stream and a control, and declaring it twice made the control unreachable
 * through [AgentDescriptor.capability], which answers with the first match. Merging fixed that and
 * introduced a second way to be wrong: a read-only build could advertise the merged entry at
 * [Fidelity.Interactive] — "accepts commands that change the running system" — while turning every
 * one of those commands away.
 */
class CapabilityMergeTest {

    private fun agent(writable: Boolean) = DevToolsAgent(
        applicationId = "ai.bestbuds.merge",
        displayName = "Merge",
        revision = AgentRevision("test", if (writable) "debug" else "release", "run", "digest"),
        policy = AgentPolicy(writable = writable),
    ).apply {
        register(OverrideStore().handler())
        register(logLevelHandler())
    }

    @Test
    fun `a name that is both a stream and a control is one reachable entry`() {
        val logs = agent(writable = true).describe().capabilities.filter { it.name == AgentCapability.Logs }
        assertEquals(1, logs.size, "one name, one entry: $logs")
        assertTrue(logs.single().available)
        assertEquals(Fidelity.Interactive, logs.single().fidelity, "the control half must survive")
        assertEquals(CommandSafety.Read, logs.single().safety, "reaching logs is still a read")
    }

    @Test
    fun `a read-only build does not advertise a rung it will refuse`() {
        val capabilities = agent(writable = false).describe().capabilities
        val logs = assertNotNull(capabilities.firstOrNull { it.name == AgentCapability.Logs })
        // The stream is still served, so the capability is reachable...
        assertTrue(logs.available, "a read-only build still streams logs")
        // ...but the refused control must not raise the rung.
        assertEquals(Fidelity.Stream, logs.fidelity, "only a served facet may raise the rung")

        // A name whose every facet is a refused command stays refused, with the reason.
        val overrides = assertNotNull(capabilities.firstOrNull { it.name == AgentCapability.Overrides })
        assertTrue(!overrides.available)
        assertEquals("This build serves reads only", overrides.unavailableReason)
    }

    @Test
    fun `no capability is declared twice whatever is registered`() {
        listOf(true, false).forEach { writable ->
            val duplicates = agent(writable).describe().capabilities
                .groupBy { it.name }
                .filterValues { it.size > 1 }
            assertTrue(duplicates.isEmpty(), "writable=$writable produced duplicates: ${duplicates.keys}")
        }
    }
}
