package dev.shibasis.reaktor.conductor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThreadTest {
    private val agent = AgentSpec(
        id = AgentId("architect"),
        name = "Architect",
        runtime = RuntimeKind.Echo,
        instructions = "design",
    )

    private fun thread() = ThreadDocument(
        id = ThreadId("t1"),
        title = "test",
        participants = listOf(agent),
    )

    private fun event(id: String, parents: List<String> = emptyList(), kind: EventKind = EventKind.Prompt) =
        ThreadEvent(
            id = EventId(id),
            author = Author.Human(),
            kind = kind,
            text = id,
            parents = parents.map(::EventId),
        )

    @Test
    fun appendRejectsUnknownParent() {
        assertFailsWith<ThreadIntegrityException> {
            thread().append(event("b", parents = listOf("a")))
        }
    }

    @Test
    fun appendRejectsDuplicateId() {
        val one = thread().append(event("a"))
        assertFailsWith<ThreadIntegrityException> { one.append(event("a")) }
    }

    @Test
    fun appendRejectsSelfParent() {
        assertFailsWith<ThreadIntegrityException> {
            thread().append(event("a", parents = listOf("a")))
        }
    }

    @Test
    fun appendRejectsAgentOutsideTheThread() {
        assertFailsWith<ThreadIntegrityException> {
            thread().append(
                ThreadEvent(
                    id = EventId("a"),
                    author = Author.Agent(AgentId("stranger")),
                    kind = EventKind.Proposal,
                    text = "hello",
                ),
            )
        }
    }

    /**
     * The structural guarantee the design rests on: because every parent must already exist, a
     * cycle cannot be constructed at all, so no cycle check is needed anywhere downstream.
     */
    @Test
    fun cyclesAreUnrepresentable() {
        val document = thread().append(event("a")).append(event("b", parents = listOf("a")))
        assertFailsWith<ThreadIntegrityException> {
            document.append(event("c", parents = listOf("d")))
        }
        assertTrue(document.events.all { child -> child.parents.all { document.event(it) != null } })
    }

    @Test
    fun headsAreEventsNothingPointsAt() {
        val document = thread()
            .append(event("a"))
            .append(event("b", parents = listOf("a")))
            .append(event("c", parents = listOf("a")))
        assertEquals(listOf("b", "c"), document.heads().map { it.id.value })
    }

    @Test
    fun ancestorsAreTransitiveAndInDocumentOrder() {
        val document = thread()
            .append(event("a"))
            .append(event("b", parents = listOf("a")))
            .append(event("c", parents = listOf("a")))
            .append(event("d", parents = listOf("b", "c")))
        assertEquals(listOf("a", "b", "c"), document.ancestorsOf(EventId("d")).map { it.id.value })
    }

    @Test
    fun roundTripsThroughJson() {
        val document = thread()
            .append(event("a"))
            .append(event("b", parents = listOf("a"), kind = EventKind.Proposal))
        assertEquals(document, decodeThread(document.encode()))
    }
}
