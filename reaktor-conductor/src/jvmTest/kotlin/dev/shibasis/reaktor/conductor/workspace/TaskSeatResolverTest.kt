package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class TaskSeatResolverTest {
    @Test fun budgetDefaultsOverridesAndUnsupportedNativeCapsAreExplicit() {
        val defaults = AgentBudget(maxTokens = 1000)
        val profile = AgentProfile("budgeted", "Budgeted", RuntimeKind.Codex, budget = defaults)
        val resolver = TaskSeatResolver({ profile })
        assertEquals(defaults, resolver.resolve(TaskSeat(profile = profile.id)).seat.budget)
        val override = AgentBudget(maxTokens = 200)
        assertEquals(override, resolver.resolve(TaskSeat(profile = profile.id, budget = override)).seat.budget)
        assertFailsWith<IllegalArgumentException> { resolver.resolve(TaskSeat(RuntimeKind.Codex, budget = AgentBudget(maxCostUsd = 1.0))) }
        assertEquals(1.0, resolver.resolve(TaskSeat(RuntimeKind.ClaudeCode, budget = AgentBudget(maxCostUsd = 1.0))).seat.budget?.maxCostUsd)
        assertFailsWith<IllegalArgumentException> { resolver.resolve(TaskSeat(lead = true, budget = defaults)) }
        assertFailsWith<IllegalArgumentException> { AgentBudget(maxTokens = 0) }
        assertFailsWith<IllegalArgumentException> { AgentBudget(maxCostUsd = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { AgentBudget(maxCostUsd = -1.0) }
        assertFailsWith<IllegalArgumentException> { AgentBudget(timeoutMillis = 0) }
    }

    @Test fun budgetSnapshotsReplaceUpdatesKeepSubsetsAndAccumulateSeparateTurns() {
        val limits = AgentBudget(maxTokens = 100)
        val first = AgentUsage(inputTokens = 70, outputTokens = 10, cachedInputTokens = 60, reasoningOutputTokens = 9)
        val warned = AgentBudgetProgress().observe("codex/one", first, limits, emptyMap())
        assertEquals(80L, warned.tokens)
        assertNotNull(warned.warning)
        assertNull(warned.exceeded)
        val repeated = warned.observe("codex/one", first, limits, emptyMap())
        assertEquals(80L, repeated.tokens)
        val held = repeated.observe("codex/two", AgentUsage(inputTokens = 20), limits, emptyMap())
        assertEquals(100L, held.tokens)
        assertNotNull(held.exceeded)
        val durable = AgentWorkspaceJson.decodeFromString(AgentBudgetProgress.serializer(),
            AgentWorkspaceJson.encodeToString(AgentBudgetProgress.serializer(), held))
        assertEquals(held, durable)
        assertNull(AgentBudgetProgress().tokens)
        assertNull(AgentBudgetProgress().costUsd)
        val usd = AgentBudget(maxCostUsd = 1.0)
        val near = AgentBudgetProgress().observe("claude/one", AgentUsage(costUsd = 0.8), usd, emptyMap())
        assertNotNull(near.warning)
        assertNull(near.exceeded)
        assertNotNull(near.observe("claude/one", AgentUsage(costUsd = 1.0), usd, emptyMap()).exceeded)
        assertEquals(Long.MAX_VALUE, AgentBudgetProgress(usage = mapOf("x" to AgentUsage(inputTokens = Long.MAX_VALUE, outputTokens = 1))).tokens)
    }

    @Test fun eachPanelLensHasItsOwnCapWithoutChargingAnotherLens() {
        val limits = mapOf("lens-0" to AgentBudget(maxTokens = 50), "lens-1" to AgentBudget(maxTokens = 100))
        val first = AgentBudgetProgress().observe("lens-0/first", AgentUsage(inputTokens = 20), null, limits)
        val second = first.observe("lens-1/second", AgentUsage(inputTokens = 60), null, limits)
        assertNull(second.warning)
        val exceeded = second.observe("lens-0/first", AgentUsage(inputTokens = 50), null, limits)
        assertTrue(exceeded.exceeded.orEmpty().startsWith("lens-0:"))
    }

    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val capability = ProviderCapability(RuntimeKind.Codex, defaultModel = "model-a",
        effort = EffortSupport(listOf(NativeEffort("low"), NativeEffort("high")), source = "fixture"),
        models = listOf(ModelChoice("model-a", "A", EffortSupport(listOf(NativeEffort("low")), source = "model fixture"))))
    private val profile = AgentProfile("careful", "Careful reviewer", RuntimeKind.Codex, "model-a", "low", "Try to disprove the fix.",
        TaskAccess.Write, ToolPolicy(allow = listOf("Read"), deny = listOf("Network")), revision = 3)

    @Test fun profileDefaultsAndTaskOverridesResolveExplicitly() {
        val resolver = TaskSeatResolver({ profile }) { capability }
        val inherited = resolver.resolve(TaskSeat(profile = profile.id))
        assertEquals(TaskSeat(RuntimeKind.Codex, "model-a", "low", TaskAccess.Write, profile = profile.id), inherited.seat)
        assertEquals(3L, inherited.profileRevision)
        assertEquals(profile.name, inherited.name)
        assertEquals(profile.instructions, inherited.instructions)
        assertTrue(inherited.tools.allowWrites)
        assertEquals(profile.tools.deny, inherited.tools.deny)
        val overridden = resolver.resolve(TaskSeat(RuntimeKind.ClaudeCode, "claude-model", "high", TaskAccess.Read, profile = profile.id))
        assertEquals(RuntimeKind.ClaudeCode, overridden.seat.agent)
        assertEquals("claude-model", overridden.seat.model)
        assertEquals("high", overridden.seat.effort)
        assertFalse(overridden.tools.allowWrites)
        assertEquals(4, overridden.notes.count { "overrides" in it })
        assertFailsWith<IllegalArgumentException> { resolver.resolve(TaskSeat(agent = RuntimeKind.ClaudeCode, profile = profile.id)) }
    }

    @Test fun modelSpecificEffortsRefuseInsteadOfUsingTheGlobalSet() {
        val resolver = TaskSeatResolver({ profile }) { capability }
        val failure = assertFailsWith<UnsupportedEffortException> { resolver.resolve(TaskSeat(profile = profile.id, effort = "high")) }
        assertEquals(listOf(NativeEffort("low")), failure.resolution.supported)
        assertTrue("Available: low" in failure.message.orEmpty())
    }

    @Test fun aPlainSeatUsesConfiguredDefaultsAndUnknownEffortsStayExplicit() {
        val plain = TaskSeatResolver(capability = { capability }).resolve(TaskSeat(agent = RuntimeKind.Codex))
        assertEquals("model-a", plain.seat.model)
        assertEquals(TaskAccess.Read, plain.seat.access)
        assertNull(plain.seat.effort)
        assertNull(plain.profileRevision)
        val unknown = TaskSeatResolver().resolve(TaskSeat(RuntimeKind.Gemini, effort = "experimental"))
        assertEquals("experimental", unknown.seat.effort)
        assertTrue(unknown.notes.any { "does not enumerate" in it })
        val legacy = ConductorJson.decodeFromString(TaskSeat.serializer(), "{\"agent\":\"Codex\",\"access\":\"Read\"}")
        assertEquals(TaskAccess.Read, legacy.access)
    }

    @Test fun builtInsAreImmutableAndCopiesAreRevisionedAndDurable() {
        val directory = Files.createTempDirectory("agent-profiles")
        try {
            val store = AgentProfileStore(directory) { 42L }
            assertEquals(Rosters.default.map { it.id.value }.toSet() + ReviewLenses.profiles.map { it.id }, store.list().map { it.id }.toSet())
            val builtIn = store.read("reviewer")
            assertTrue(builtIn.builtIn)
            assertFailsWith<IllegalArgumentException> { store.save(builtIn.copy(instructions = "changed"), person, 1) }
            val saved = store.save(builtIn.copy(id = "my-reviewer"), person)
            assertFalse(saved.builtIn)
            assertEquals(1L, saved.revision)
            assertEquals(person, saved.by)
            assertEquals(42L, saved.updatedAt)
            val next = store.save(saved.copy(instructions = "A new perspective"), person, saved.revision)
            assertEquals(2L, next.revision)
            assertFailsWith<IllegalArgumentException> { store.save(saved, person, saved.revision) }
            assertEquals(next, AgentProfileStore(directory).read(saved.id))
            assertFailsWith<IllegalArgumentException> { store.save(saved.copy(id = "../outside"), person) }
            assertFailsWith<IllegalArgumentException> { store.save(saved.copy(id = "too-long", instructions = "x".repeat(20_001)), person) }
        } finally { directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun discoveryAndGraphChecksUseTheWorkspaceResolver() {
        val root = Files.createTempDirectory("seat-root").toFile()
        val directory = Files.createTempDirectory("seat-data")
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Codex
            override fun run(request: AgentRequest) = emptyFlow<AgentEvent>()
        }
        try {
            AgentWorkspace(root, directory, mapOf(runtime.kind to runtime), discover = { capability }).use { workspace ->
                workspace.profiles.save(profile, person)
                val seat = TaskSeat(profile = profile.id)
                val discovery = agentTaskGraphTools(workspace).single { it.name == "agent_taskgraph_seats" }
                    .execute(buildJsonObject { put("seat", AgentWorkspaceJson.encodeToJsonElement(TaskSeat.serializer(), seat)) }).jsonObject
                val resolved = AgentWorkspaceJson.decodeFromJsonElement(ResolvedTaskSeat.serializer(), discovery.getValue("resolved"))
                assertEquals(workspace.resolveSeat(seat), resolved)
                val proposed = workspace.board.propose(TaskGraphDraft("Review", tasks = listOf(TaskGraphOp.Add("Review it", seat = seat, scope = listOf(":engine")))), person)
                assertEquals(listOf(RuntimeKind.Codex), proposed.graph.envelope.agents)
                assertEquals(listOf(":engine"), proposed.graph.envelope.writes)
                assertEquals(seat, proposed.tasks.single().graph?.seat)
                val before = workspace.board.view("G1")
                val invalid = TaskGraphPatch(listOf(TaskGraphOp.Seat("T1", seat.copy(effort = "high"))), "Try another effort")
                assertFailsWith<UnsupportedEffortException> { workspace.board.outside("G1", invalid, TaskGraphCaller(person)) }
                assertEquals(before, workspace.board.view("G1"))
                assertFailsWith<UnsupportedEffortException> { workspace.submit(AgentSubmission("invalid", RuntimeKind.Codex, "Review", model = "model-a", effort = NativeEffort("high"))) }
            }
        } finally {
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
