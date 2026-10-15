package dev.shibasis.reaktor.conductor

import kotlin.test.*

class ContextPacketTest {
    @Test fun oversizedEntryDoesNotHideLaterSmallEvidence() {
        val small = ContextEntry("small", "source", "Useful", "evidence", "query")
        val packet = ContextPacket(workspaceId = "workspace", principalId = "agent", source = "code", revision = "revision",
            entries = listOf(small.copy(ref = "large", text = "x".repeat(5000)), small, small))
        val bounded = assertNotNull(packet.bounded(600))
        assertEquals(listOf(small), bounded.entries)
        assertEquals(2, bounded.omittedEntries)
        assertEquals(packet.revision, bounded.revision)
        assertTrue(bounded.partial)
        assertTrue(ConductorJson.encodeToString(ContextPacket.serializer(), bounded).length <= 600)
    }

    @Test fun impossibleEnvelopeIsOmittedAndSmallPacketsRemainComplete() {
        val packet = ContextPacket(workspaceId = "workspace", principalId = "agent", source = "code")
        assertNull(packet.bounded(0))
        assertEquals(packet, packet.bounded(2000))
        assertFailsWith<IllegalArgumentException> { packet.bounded(-1) }
    }
}
