package dev.shibasis.reaktor.conductor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextCompilerTest {
    private val compiler = DefaultContextCompiler()

    private val architect = AgentSpec(
        id = AgentId("architect"),
        name = "Architect",
        runtime = RuntimeKind.Echo,
        instructions = "design cleanly",
    )

    private val skeptic = AgentSpec(
        id = AgentId("skeptic"),
        name = "Skeptic",
        runtime = RuntimeKind.Echo,
        instructions = "attack it",
    )

    private val peerProposal = ThreadEvent(
        id = EventId("p1"),
        author = Author.Agent(skeptic.id),
        kind = EventKind.Proposal,
        text = "SECRET-PEER-CONTENT",
    )

    private val thread = ThreadDocument(
        id = ThreadId("t"),
        title = "t",
        participants = listOf(architect, skeptic),
    )

    private fun compile(visibility: Visibility) = compiler.compile(
        CompileRequest(
            thread = thread,
            agent = architect,
            task = "decide the storage layer",
            visibility = visibility,
            peers = listOf(peerProposal),
        ),
    )

    /**
     * The blind round is the reason a council is worth paying for, so it is enforced rather than
     * requested: peer material is dropped by the compiler even when the caller passes it in.
     */
    @Test
    fun blindVisibilityDropsPeersEvenWhenSupplied() {
        val prompt = compile(Visibility.Blind)
        assertFalse(prompt.contains("SECRET-PEER-CONTENT"))
        assertTrue(prompt.contains("decide the storage layer"))
        assertTrue(prompt.contains("design cleanly"))
    }

    @Test
    fun sharedVisibilityIncludesPeers() {
        assertTrue(compile(Visibility.Shared).contains("SECRET-PEER-CONTENT"))
    }

    @Test
    fun anAgentNeverSeesItsOwnProposalAsAPeer() {
        val own = peerProposal.copy(id = EventId("p2"), author = Author.Agent(architect.id))
        val prompt = compiler.compile(
            CompileRequest(
                thread = thread,
                agent = architect,
                task = "critique",
                visibility = Visibility.Shared,
                peers = listOf(own),
            ),
        )
        assertFalse(prompt.contains("What the other participants said"))
    }

    @Test
    fun peerKindsFilterWhatCrossesTheBoundary() {
        val note = peerProposal.copy(id = EventId("p3"), kind = EventKind.Note)
        val prompt = compiler.compile(
            CompileRequest(
                thread = thread,
                agent = architect,
                task = "t",
                visibility = Visibility.Shared,
                peers = listOf(note),
            ),
        )
        assertFalse(prompt.contains("SECRET-PEER-CONTENT"))
    }
}
