package dev.shibasis.reaktor.conductor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in roster and the four-stage design pipeline are samples. What has to work is an
 * operator writing their own agents and their own topology, and an agent designing one itself.
 */
class CustomTopologyTest {
    private fun spec(id: String, runtime: RuntimeKind = RuntimeKind.Echo) = AgentSpec(
        id = AgentId(id),
        name = id,
        runtime = runtime,
        instructions = "instructions for $id",
    )

    private val planner = spec("planner")

    private fun thread(vararg agents: AgentSpec) = ThreadDocument(
        id = ThreadId("t"),
        title = "t",
        participants = agents.toList(),
    )

    private fun conductor(reply: (AgentRequest) -> String = { "answer from ${it.agent.id.value}" }) =
        Conductor(runtimes = mapOf(RuntimeKind.Echo to EchoRuntime(reply = reply)))

    /** A roster and a topology the operator wrote, with names the framework has never seen. */
    @Test
    fun anOperatorDefinedTeamAndTopologyRunsAsWritten() = runTest {
        val roster = listOf(spec("security"), spec("dba"), spec("sre"))
        val plan = Protocol.Pipeline(
            listOf(
                Stage(AgentId("security"), "threat model it", EventKind.Proposal, seesPeers = false),
                Stage(AgentId("dba"), "check the schema implications", EventKind.Critique),
                Stage(AgentId("sre"), "check the rollout", EventKind.Critique),
            ),
        )

        val result = conductor().run(
            thread = thread(*roster.toTypedArray()),
            prompt = "ship the migration",
            protocol = plan,
            workingDirectory = ".",
        )

        assertEquals(
            listOf("security", "dba", "sre"),
            result.added.mapNotNull { (it.author as? Author.Agent)?.id?.value },
        )
        assertEquals(
            listOf(EventKind.Prompt, EventKind.Proposal, EventKind.Critique, EventKind.Critique),
            result.added.map { it.kind },
        )
    }

    /** A topology is data, so it survives being written to a file and read back. */
    @Test
    fun aTopologyRoundTripsThroughJson() {
        val plan: Protocol = Protocol.Pipeline(
            listOf(
                Stage(AgentId("a"), "do", EventKind.Proposal),
                Stage(AgentId("b"), "check", EventKind.Critique, seesPeers = false),
            ),
        )
        val encoded = ConductorJson.encodeToString(Protocol.serializer(), plan)
        assertTrue(encoded.contains("\"type\":\"pipeline\""))
        assertEquals(plan, ConductorJson.decodeFromString(Protocol.serializer(), encoded))

        val council: Protocol = Protocol.Council(listOf(AgentId("a")), synthesizer = AgentId("b"))
        assertEquals(
            council,
            ConductorJson.decodeFromString(
                Protocol.serializer(),
                ConductorJson.encodeToString(Protocol.serializer(), council),
            ),
        )
    }

    /** The planner designs the team, and agents it invented become real participants. */
    @Test
    fun aPlannerCanDesignTheTeamAndTheTopology() = runTest {
        val plan = """
            Here is my plan.
            {"rationale":"needs a perf specialist",
             "agents":[{"id":"perf","name":"Perf","runtime":"Echo","instructions":"measure it"}],
             "protocol":{"type":"pipeline","stages":[
               {"agent":"perf","instruction":"profile the hot path","kind":"Proposal"},
               {"agent":"planner","instruction":"summarise","kind":"Synthesis"}]}}
        """.trimIndent()

        val result = conductor { request ->
            if (request.agent.id.value == "planner" && request.prompt.contains("Design the team")) {
                plan
            } else {
                "answer from ${request.agent.id.value}"
            }
        }.run(
            thread = thread(planner),
            prompt = "make it fast",
            protocol = Protocol.Planned(planner.id),
            workingDirectory = ".",
        )

        assertTrue(result.thread.participants.any { it.id.value == "perf" })
        assertEquals(
            listOf(EventKind.Prompt, EventKind.Note, EventKind.Proposal, EventKind.Synthesis),
            result.added.map { it.kind },
        )
        assertEquals(EventKind.Synthesis, result.answer?.kind)
    }

    @Test
    fun aPlannerThatReturnsNothingUsableFailsLoudly() = runTest {
        val failure = runCatching {
            conductor { "no json here" }.run(
                thread = thread(planner),
                prompt = "x",
                protocol = Protocol.Planned(planner.id),
                workingDirectory = ".",
            )
        }.exceptionOrNull()
        assertTrue(failure is ThreadIntegrityException)
    }

    @Test
    fun jsonIsExtractedFromProseAndNestedBraces() {
        assertEquals("""{"a":{"b":1}}""", extractJsonObject("""prose {"a":{"b":1}} more"""))
        assertEquals("""{"a":"}"}""", extractJsonObject("""x {"a":"}"} y"""))
        assertEquals(null, extractJsonObject("no object"))
    }
}
