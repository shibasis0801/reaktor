package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import java.nio.file.Files
import kotlin.test.*

class AgentActivityStoreTest {
    @Test fun cursorsAndUnresolvedActionsSurviveRestartWithoutCrossAgentCollisions() {
        val data = Files.createTempDirectory("activity-store")
        val id = "a".repeat(64)
        try {
            val store = AgentActivityStore(data)
            store.append(id, "codex", 1, AgentActivityItem("one", ActivityKind.Tool, "write", ActivityStatus.Started))
            store.append(id, "claude", 1, AgentActivityItem("one", ActivityKind.Tool, "read", ActivityStatus.Completed))
            assertEquals("codex", store.unresolved(id, 1).single().agent)
            Files.writeString(data.resolve(id).resolve(".agent-torn.json"), "{")
            val reopened = AgentActivityStore(data)
            assertEquals(2, reopened.page(id).records.size)
            val last = reopened.append(id, "codex", 1, AgentActivityItem("one", ActivityKind.Tool, "write", ActivityStatus.Completed, output = "ok"))
            assertEquals(3, last.sequence)
            assertEquals("ok", reopened.page(id, 2).records.single().item.output)
            assertTrue(reopened.unresolved(id, 1).isEmpty())
            assertFailsWith<IllegalArgumentException> { reopened.page("../outside") }
        } finally { data.toFile().deleteRecursively() }
    }

    @Test fun outputAndPagesRemainBounded() {
        val data = Files.createTempDirectory("activity-bounds")
        val id = "b".repeat(64)
        try {
            val store = AgentActivityStore(data)
            repeat(50) { store.append(id, "codex", 1, AgentActivityItem("$it", ActivityKind.Tool, "output", output = "界".repeat(30000))) }
            val page = store.page(id, 0, 100)
            assertTrue(page.hasMore)
            assertTrue(page.records.all { it.item.detailTruncated })
            assertTrue(ConductorJson.encodeToString(AgentActivityPage.serializer(), page).toByteArray().size < 600000)
        } finally { data.toFile().deleteRecursively() }
    }
}
