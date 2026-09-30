package dev.shibasis.reaktor.conductor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolTest {
    private fun spec(id: String) = AgentSpec(
        id = AgentId(id),
        name = id.replaceFirstChar(Char::uppercase),
        runtime = RuntimeKind.Echo,
        instructions = "instructions for $id",
    )

    private val architect = spec("architect")
    private val skeptic = spec("skeptic")
    private val reviewer = spec("reviewer")

    private val thread = ThreadDocument(
        id = ThreadId("t"),
        title = "test",
        participants = listOf(architect, skeptic, reviewer),
    )

    /** Records the prompt each agent was handed, so tests can assert on what it could see. */
    private class Recorder {
        val prompts = mutableMapOf<AgentId, MutableList<String>>()

        fun runtime() = EchoRuntime { request ->
            prompts.getOrPut(request.agent.id) { mutableListOf() } += request.prompt
            "answer from ${request.agent.id.value}"
        }
    }

    private fun conductor(recorder: Recorder) = Conductor(
        runtimes = mapOf(RuntimeKind.Echo to recorder.runtime()),
    )

    @Test
    fun askRunsExactlyOneAgent() = runTest {
        val recorder = Recorder()
        val result = conductor(recorder).run(
            thread = thread,
            prompt = "question",
            protocol = Protocol.Ask(skeptic.id),
            workingDirectory = ".",
        )

        assertEquals(setOf(skeptic.id), recorder.prompts.keys)
        assertEquals(EventKind.Prompt, result.added.first().kind)
        assertEquals(EventKind.Proposal, result.added.last().kind)
    }

    /**
     * The property a council exists for. Round one runs every agent in parallel against one
     * snapshot, so no participant can have seen another's answer, and the compiler is not given
     * peers to include in the first place.
     */
    @Test
    fun councilRoundOneIsBlind() = runTest {
        val recorder = Recorder()
        conductor(recorder).run(
            thread = thread,
            prompt = "question",
            protocol = Protocol.Council(listOf(architect.id, skeptic.id)),
            workingDirectory = ".",
        )

        val firstPrompt = recorder.prompts.getValue(architect.id).first()
        assertFalse(firstPrompt.contains("answer from skeptic"))
        assertFalse(firstPrompt.contains("What the other participants said"))
    }

    @Test
    fun councilCritiqueRoundSeesPeerProposalsAndSynthesisSeesBoth() = runTest {
        val recorder = Recorder()
        val result = conductor(recorder).run(
            thread = thread,
            prompt = "question",
            protocol = Protocol.Council(
                agents = listOf(architect.id, skeptic.id),
                synthesizer = reviewer.id,
            ),
            workingDirectory = ".",
        )

        // In a critique round an agent sees its own proposal (as history) and the peer's (as a
        // peer), and each appears exactly once because the compiler deduplicates them.
        val critiquePrompt = recorder.prompts.getValue(architect.id)[1]
        assertTrue(critiquePrompt.contains("answer from architect"))
        assertEquals(1, critiquePrompt.windowed("answer from skeptic".length).count { it == "answer from skeptic" })
        val peerSection = critiquePrompt.substringAfter("What the other participants said")
        assertTrue(peerSection.contains("answer from skeptic"))
        assertFalse(peerSection.contains("answer from architect"))

        val synthesisPrompt = recorder.prompts.getValue(reviewer.id).single()
        assertTrue(synthesisPrompt.contains("answer from architect"))
        assertTrue(synthesisPrompt.contains("answer from skeptic"))
        assertTrue(synthesisPrompt.contains("Remaining disagreement"))

        assertEquals(
            listOf(EventKind.Prompt, EventKind.Proposal, EventKind.Proposal, EventKind.Critique, EventKind.Critique, EventKind.Synthesis),
            result.added.map { it.kind },
        )
        assertEquals(EventKind.Synthesis, result.answer?.kind)
    }

    /** The DAG shape: proposals share the prompt as parent, synthesis joins the critiques. */
    @Test
    fun councilProducesTheExpectedCausalShape() = runTest {
        val result = conductor(Recorder()).run(
            thread = thread,
            prompt = "question",
            protocol = Protocol.Council(listOf(architect.id, skeptic.id), synthesizer = reviewer.id),
            workingDirectory = ".",
        )

        val prompt = result.added.first()
        val proposals = result.added.filter { it.kind == EventKind.Proposal }
        val critiques = result.added.filter { it.kind == EventKind.Critique }
        val synthesis = result.added.single { it.kind == EventKind.Synthesis }

        assertTrue(proposals.all { it.parents == listOf(prompt.id) })
        assertTrue(critiques.all { it.parents == proposals.map { proposal -> proposal.id } })
        assertEquals(critiques.map { it.id }, synthesis.parents)
        assertEquals(2, synthesis.parents.size)
    }

    @Test
    fun pipelineRunsStagesInOrderAndEachStageSeesTheOneBefore() = runTest {
        val recorder = Recorder()
        val result = conductor(recorder).run(
            thread = thread,
            prompt = "build the thing",
            protocol = Protocol.Pipeline(
                listOf(
                    Stage(architect.id, "design it", EventKind.Proposal, seesPeers = false),
                    Stage(skeptic.id, "attack it", EventKind.Critique),
                    Stage(architect.id, "fix it", EventKind.Revision),
                ),
            ),
            workingDirectory = ".",
        )

        assertEquals(
            listOf(EventKind.Prompt, EventKind.Proposal, EventKind.Critique, EventKind.Revision),
            result.added.map { it.kind },
        )
        assertFalse(recorder.prompts.getValue(architect.id)[0].contains("What the other participants said"))
        assertTrue(recorder.prompts.getValue(skeptic.id).single().contains("answer from architect"))
        assertTrue(recorder.prompts.getValue(architect.id)[1].contains("answer from skeptic"))
    }

    /** Results are ordered by roster, not by whichever harness finished first. */
    @Test
    fun parallelRoundsCommitInDeterministicOrder() = runTest {
        suspend fun ids(): List<String> = conductor(Recorder()).run(
            thread = thread,
            prompt = "question",
            protocol = Protocol.All(listOf(architect.id, skeptic.id, reviewer.id)),
            workingDirectory = ".",
        ).added.mapNotNull { (it.author as? Author.Agent)?.id?.value }

        assertEquals(listOf("architect", "skeptic", "reviewer"), ids())
        assertEquals(ids(), ids())
    }

    /** A missing runtime degrades one agent's turn to a recorded failure; the round still lands. */
    @Test
    fun anAgentWithNoRuntimeFailsWithoutBreakingTheRound() = runTest {
        val offline = Conductor(runtimes = emptyMap())
        val result = offline.run(
            thread = thread,
            prompt = "question",
            protocol = Protocol.All(listOf(architect.id, skeptic.id)),
            workingDirectory = ".",
        )

        assertEquals(2, result.added.count { it.kind == EventKind.Failure })
        assertTrue(result.thread.events.containsAll(result.added))
    }

    @Test
    fun everyTurnIsAppendedToTheCanonicalThread() = runTest {
        val result = conductor(Recorder()).run(
            thread = thread,
            prompt = "question",
            protocol = Protocol.Council(listOf(architect.id, skeptic.id), synthesizer = reviewer.id),
            workingDirectory = ".",
        )

        assertEquals(result.added.size, result.thread.events.size)
        assertEquals(result.thread, decodeThread(result.thread.encode()))
    }
}
