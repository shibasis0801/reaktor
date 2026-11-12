package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.worklog.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentBenchmarksTest {
    private val agent = BoardActor(BoardActorKind.Agent, "codex", "codex:fixture")
    private val person = BoardActor(BoardActorKind.Person, "Fixture owner")

    private fun fixture(body: (AgentWorkspace, File, File) -> Unit) {
        val folder = Files.createTempDirectory("agent-benchmark").toFile()
        val root = File(folder, "workspace").also { it.mkdirs() }
        val data = File(folder, "state")
        try {
            File(root, "Source.kt").writeText("fun source() = 1\n")
            git(root, "init", "-q")
            git(root, "add", ".")
            git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "baseline")
            AgentWorkspace(root, data.toPath(), mapOf(RuntimeKind.Codex to EchoRuntime(RuntimeKind.Codex) { "fixture" },
                RuntimeKind.ClaudeCode to EchoRuntime(RuntimeKind.ClaudeCode) { "fixture" },
                RuntimeKind.Gemini to EchoRuntime(RuntimeKind.Gemini) { "fixture" }), discover = { ProviderCapability(it) },
                kernelRead = { name, args ->
                    assertEquals("kernel", name)
                    assertEquals("taskgraph.score", args["operation"]?.jsonPrimitive?.content)
                    val graph = args.getValue("arguments").jsonObject.getValue("graph").jsonPrimitive.content
                    buildJsonObject {
                        put("type", "completed")
                        put("result", AgentWorkspaceJson.encodeToJsonElement(GraphWorkScore.serializer(),
                            GraphWorkScore(graph, WorkLog(emptyList()).score(), emptyMap(), emptyList(), null, emptyList())))
                    }
                }).use { workspace -> body(workspace, root, data) }
        } finally { folder.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun scenario(root: File, id: String = "sample"): AgentBenchScenario {
        val draft = TaskGraphDraft("Fixture", tasks = listOf(
            TaskGraphOp.Add("Write", "writer", scope = listOf("Source.kt"), seat = TaskSeat(RuntimeKind.Codex, "fixture-model", access = TaskAccess.Write)),
            TaskGraphOp.Add("Check", "check", kind = TaskKind.Check, check = "unit", dependsOn = listOf(TaskDependency("writer")))))
        return AgentBenchScenario(id, "Fixture $id", mapOf("." to git(root, "rev-parse", "HEAD")), draft,
            listOf(AgentBenchDefect("offset", "correctness", "Source.kt", 1)))
    }

    private fun write(root: File, scenario: AgentBenchScenario) {
        File(root, "tests/agentBench/${scenario.id}/scenario.json").also {
            it.parentFile.mkdirs(); it.writeText(AgentWorkspaceJson.encodeToString(AgentBenchScenario.serializer(), scenario))
        }
    }

    @Test fun twoScenariosTwoConfigurationsAndThreeRepeatsPinBaselinesWithoutStartingWork() = fixture { workspace, root, _ ->
        listOf("first", "second").forEach { write(root, scenario(root, it)) }
        val head = git(root, "rev-parse", "HEAD")
        val entries = workspace.benchmarks.catalog().scenarios
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.scenario.defects.isEmpty() })
        val all = entries.flatMap { entry ->
            val slots = TaskGraphTemplates.slots(entry.scenario.draft)
            workspace.benchmarks.propose(entry.scenario.id, entry.revision,
                listOf(AgentBenchConfiguration("codex", slots), AgentBenchConfiguration("claude", slots.mapValues { (_, seat) -> seat.copy(agent = RuntimeKind.ClaudeCode) })),
                3, agent)
        }
        assertEquals(12, all.size)
        assertEquals(12, all.map { it.graph.id }.distinct().size)
        all.forEach { view ->
            assertEquals(TaskGraphStatus.Proposed, view.graph.status)
            assertEquals(mapOf(root.canonicalPath to head), view.graph.baseCommits)
            assertTrue(view.tasks.all { it.graph?.let { plan -> plan.attempt == 0 && plan.runId == null && plan.worktreeId == null } == true })
            assertTrue(requireNotNull(view.graph.benchmark).scenario.defects.isEmpty())
            assertFailsWith<IllegalArgumentException> { workspace.board.start(view.graph.id, agent, view.graph.revision) }
            val refusal = assertFailsWith<IllegalArgumentException> {
                runBlocking { workspace.landTaskCandidate(view.graph.id, view.tasks.first().id, "candidate", view.graph.revision, person) }
            }
            assertTrue(refusal.message.orEmpty().contains("Benchmark candidates cannot land"))
        }
        assertEquals(head, git(root, "rev-parse", "HEAD"))
        assertTrue(workspace.list().isEmpty())
    }

    @Test fun aSlotLadderIsPinnedWhenProposedAndOnlyItsRungsDispatch() = fixture { workspace, root, _ ->
        write(root, scenario(root))
        val entry = workspace.benchmarks.catalog().scenarios.single()
        val slots = TaskGraphTemplates.slots(entry.scenario.draft)
        fun laddered(rung: TaskSeat) = listOf(AgentBenchConfiguration("laddered", slots.mapValues { (_, seat) -> seat.copy(escalate = listOf(rung)) }))
        assertFailsWith<IllegalArgumentException> { workspace.benchmarks.propose("sample", entry.revision, laddered(TaskSeat(RuntimeKind.Codex)), 1, agent) }
        val task = workspace.benchmarks.propose("sample", entry.revision, laddered(TaskSeat(RuntimeKind.Codex, "strong-model")), 1, agent).single().tasks.first()
        val plan = requireNotNull(task.graph)
        val seat = requireNotNull(plan.seat)
        assertEquals(listOf(TaskSeat(RuntimeKind.Codex, "strong-model", access = TaskAccess.Write)), seat.escalate)
        val rung = requireNotNull(seat.escalated(TaskAccess.Write))
        assertEquals("strong-model", workspace.benchmarks.resolve(task.copy(graph = plan.copy(seat = rung)))?.seat?.model)
        assertFailsWith<IllegalArgumentException> { workspace.benchmarks.resolve(task.copy(graph = plan.copy(seat = rung.copy(model = "other-model")))) }
    }

    @Test fun pinnedInstructionsSurviveProfileEditsAndChangedSeatsAreRefused() = fixture { workspace, root, _ ->
        val profile = workspace.profiles.save(AgentProfile("benchmark-writer", "Writer", RuntimeKind.Codex,
            model = "fixture-model", instructions = "Original instructions", access = TaskAccess.Write), person)
        val scenario = scenario(root).let { it.copy(draft = it.draft.copy(tasks = it.draft.tasks.map { task ->
            if (task.key == "writer") task.copy(seat = TaskSeat(profile = profile.id)) else task
        })) }
        write(root, scenario)
        val entry = workspace.benchmarks.catalog().scenarios.single()
        val graph = workspace.benchmarks.propose("sample", entry.revision,
            listOf(AgentBenchConfiguration("pinned", TaskGraphTemplates.slots(entry.scenario.draft))), 1, agent).single()
        workspace.profiles.save(profile.copy(instructions = "Changed instructions"), person, profile.revision)
        val task = graph.tasks.first()
        val resolved = requireNotNull(workspace.benchmarks.resolve(task))
        assertEquals("Original instructions", resolved.instructions)
        assertEquals(profile.revision, resolved.profileRevision)
        assertFailsWith<IllegalArgumentException> { workspace.benchmarks.resolve(task.copy(graph = task.graph!!.copy(seat = resolved.seat.copy(model = "changed-model")))) }
    }

    @Test fun staleScenarioRevisionsMissingBaselinesAndEscapedManifestsAreRefused() = fixture { workspace, root, _ ->
        val original = scenario(root)
        write(root, original)
        val entry = workspace.benchmarks.catalog().scenarios.single()
        val config = listOf(AgentBenchConfiguration("one", TaskGraphTemplates.slots(original.draft)))
        write(root, original.copy(title = "Changed"))
        assertFailsWith<IllegalArgumentException> { workspace.benchmarks.propose("sample", entry.revision, config, 1, agent) }
        write(root, original.copy(revisions = mapOf("." to "0".repeat(40))))
        val missing = workspace.benchmarks.entry("sample")
        assertFailsWith<IllegalArgumentException> { workspace.benchmarks.propose("sample", missing.revision, config, 1, agent) }
        assertFailsWith<IllegalArgumentException> { workspace.benchmarks.entry("../escape") }
        val outside = File(root.parentFile, "outside.json").also { it.writeText(AgentWorkspaceJson.encodeToString(AgentBenchScenario.serializer(), original)) }
        val path = File(root, "tests/agentBench/sample/scenario.json").toPath()
        Files.delete(path); Files.createSymbolicLink(path, outside.toPath())
        assertFailsWith<IllegalArgumentException> { workspace.benchmarks.entry("sample") }
        assertTrue(workspace.board.graphs().isEmpty())
    }

    @Test fun openMeasurementsRetainUnknownCostAndSealedScenarioAfterTheSourceManifestChanges() = fixture { workspace, root, data ->
        val original = scenario(root)
        write(root, original)
        val entry = workspace.benchmarks.catalog().scenarios.single()
        val graph = workspace.benchmarks.propose("sample", entry.revision,
            listOf(AgentBenchConfiguration("one", TaskGraphTemplates.slots(original.draft))), 1, agent).single()
        write(root, original.copy(defects = emptyList()))
        val result = runBlocking { workspace.benchmarks.measure(graph.graph.id) }
        assertNull(result.checksPassed)
        assertNull(result.score.total.costUsd)
        assertNull(result.score.total.wallMillis)
        assertTrue(result.comparable)
        assertTrue(result.notes.any { "still open" in it })
        assertTrue(File(data, "benchmarks/${graph.graph.id}.json").isFile)
        assertEquals(result, workspace.benchmarks.catalog().runs.single().result)
        val task = graph.tasks.first()
        workspace.board.advance(graph.graph.id, task.id) { value, _ -> value.copy(intent = "Changed definition") }
        assertNull(workspace.benchmarks.catalog().runs.single().result)
        assertFalse(runBlocking { workspace.benchmarks.measure(graph.graph.id) }.comparable)
    }

    @Test fun changedOwnerBuildRefusesDispatchAndComparableMeasurements() = fixture { workspace, root, _ ->
        val original = scenario(root)
        write(root, original)
        val entry = workspace.benchmarks.entry("sample")
        val graph = workspace.benchmarks.propose("sample", entry.revision,
            listOf(AgentBenchConfiguration("one", TaskGraphTemplates.slots(original.draft))), 1, agent).single()
        val stale = workspace.board.propose(original.draft, agent,
            requireNotNull(graph.graph.benchmark).copy(ownerBuild = "previous-build"), graph.graph.baseCommits)
        val refusal = assertFailsWith<IllegalArgumentException> { workspace.benchmarks.resolve(stale.tasks.first()) }
        assertTrue(refusal.message.orEmpty().contains("owner build changed"))
        val measured = runBlocking { workspace.benchmarks.measure(stale.graph.id) }
        assertFalse(measured.comparable)
        assertTrue(measured.notes.any { "Owner build changed" in it })
        assertTrue(workspace.list().isEmpty())
    }

    @Test fun lensMatrixCountsConfirmedDefectsOnceAndLeavesUnmatchedFindingsForAdjudication() {
        val lens = ResolvedTaskSeat(TaskSeat(RuntimeKind.ClaudeCode, "fixture", profile = "correctness"), 7, "Review", ToolPolicy(), emptyList())
        val seat = ResolvedTaskSeat(TaskSeat(RuntimeKind.Codex, "fixture"), null, "", ToolPolicy(), emptyList(), lenses = listOf(lens))
        val scenario = AgentBenchScenario("sample", "Fixture", emptyMap(), TaskGraphDraft("Fixture", tasks = emptyList()), listOf(
            AgentBenchDefect("one", "correctness", "Source.kt", 1, 2),
            AgentBenchDefect("two", "security", "Source.kt", 8)))
        val binding = AgentBenchBinding(scenario, "revision", "one", mapOf("review" to seat), 1, 3, "build", null)
        fun finding(id: String, line: Int, confirmed: Boolean) = AgentFinding(id, "candidate", "run", "panel", "Finding", "Detail", "high",
            sourcePath = "Source.kt", line = line, lenses = listOf("correctness"),
            confirmation = if (confirmed) AgentFindingConfirmation("verify", "peer", "Confirmed") else null)
        val result = AgentBenchmarks.lensScores(binding, listOf(finding("a", 1, true), finding("duplicate", 2, true),
            finding("unmatched", 20, true), finding("unconfirmed", 8, false)))
        assertEquals(1, result.single { it.kind == "correctness" }.confirmed)
        assertEquals(1, result.single { it.kind == "security" }.missed)
        assertEquals(1, result.single { it.kind == "unclassified" }.unmatchedConfirmed)
        assertEquals(1, result.single { it.kind == "unclassified" }.unconfirmed)
        assertTrue(result.all { it.revision == 7L && it.provider == RuntimeKind.ClaudeCode })
    }

    @Test fun geminiSeatsTriVendorPanelsAndPlantedDefectCountsPinCleanly() = fixture { workspace, root, _ ->
        val reviewDraft = TaskGraphDraft(
            title = "Tri-vendor fixture",
            envelope = TaskEnvelope(tokens = 90_000L, repairRounds = 1),
            tasks = listOf(
                TaskGraphOp.Add(
                    title = "Write",
                    key = "writer",
                    scope = listOf("Source.kt"),
                    seat = TaskSeat(
                        agent = RuntimeKind.Gemini,
                        model = "gemini-4-argon-high",
                        effort = "high",
                        access = TaskAccess.Write,
                        escalate = listOf(TaskSeat(RuntimeKind.Gemini, "gemini-4-carbon-high", "high", TaskAccess.Write)),
                    ),
                ),
                TaskGraphOp.Add(
                    title = "Review",
                    key = "review",
                    scope = listOf("Source.kt"),
                    contract = WorkflowContract.Decision,
                    dependsOn = listOf(TaskDependency("writer")),
                    seat = TaskSeat(
                        agent = RuntimeKind.Gemini,
                        model = "gemini-4-carbon-high",
                        effort = "high",
                        access = TaskAccess.Read,
                        lenses = listOf("codex-correctness", "claude-correctness", "gemini-correctness"),
                    ),
                ),
            ),
        )
        val manifest = AgentBenchScenario(
            id = "tri-vendor",
            title = "Tri-vendor Gemini trial",
            revisions = mapOf("." to git(root, "rev-parse", "HEAD")),
            draft = reviewDraft,
            defects = listOf(
                AgentBenchDefect("d1", "correctness", "Source.kt", 1),
                AgentBenchDefect("d2", "security", "Source.kt", 1),
            ),
            track = BenchTrack.BlindReview,
            quotaTier = BenchQuotaTier.Micro,
            summary = "Checks Gemini writer, ladder, and tri-vendor lens panel.",
        )
        write(root, manifest)
        val catalogEntry = workspace.benchmarks.catalog().scenarios.single()
        assertEquals(2, catalogEntry.plantedDefectCount)
        assertTrue(catalogEntry.scenario.defects.isEmpty())
        val presets = ReaktorPlusBench.presetsFor(catalogEntry.scenario)
        assertTrue(presets.size >= 3)
        val estimate = ReaktorPlusBench.estimateQuota(catalogEntry, presets.first(), 1)
        assertEquals(BenchQuotaImpact.Low, estimate.impact)
        assertEquals(90_000L, estimate.envelopeCapPerRun)

        val proposed = workspace.benchmarks.propose(
            "tri-vendor",
            catalogEntry.revision,
            listOf(AgentBenchConfiguration("gemini-tri", TaskGraphTemplates.slots(reviewDraft), 80_000L)),
            1,
            agent,
        ).single()
        assertEquals(TaskGraphStatus.Proposed, proposed.graph.status)
        val writerTask = proposed.tasks.first { it.title == "Write" }
        val writerGraph = requireNotNull(writerTask.graph)
        val rung = requireNotNull(writerGraph.seat.escalated(TaskAccess.Write))
        assertEquals("gemini-4-carbon-high", workspace.benchmarks.resolve(writerTask.copy(graph = writerGraph.copy(seat = rung)))?.seat?.model)

        assertFailsWith<IllegalArgumentException> {
            workspace.benchmarks.propose(
                "tri-vendor",
                catalogEntry.revision,
                listOf(
                    AgentBenchConfiguration(
                        "gateway-rejected",
                        mapOf(
                            "writer" to TaskSeat(RuntimeKind.Gateway, "kimi", access = TaskAccess.Write),
                            "review" to TaskSeat(RuntimeKind.Codex, "gpt-6.1-sol", access = TaskAccess.Read),
                        ),
                    )
                ),
                1,
                agent,
            )
        }
    }

    @Test fun reaktorPlusStandingsAggregateQuotaEfficiencyAndFilterNonComparableRuns() {
        val baseDraft = TaskGraphDraft(
            title = "Bench",
            envelope = TaskEnvelope(tokens = 150_000L),
            tasks = listOf(
                TaskGraphOp.Add("Write", "writer", seat = TaskSeat(RuntimeKind.Codex, "gpt-6.1-sol", "medium", TaskAccess.Write)),
                TaskGraphOp.Add("Review", "review", seat = TaskSeat(RuntimeKind.Gemini, "gemini-4-carbon-high", "high", TaskAccess.Read), dependsOn = listOf(TaskDependency("writer"))),
            ),
        )
        val scenario = AgentBenchScenario("s1", "Scenario 1", mapOf("." to "a".repeat(40)), baseDraft, quotaTier = BenchQuotaTier.Standard)
        val entry = AgentBenchScenarioEntry(scenario, "rev1", plantedDefectCount = 1)
        val seats = mapOf(
            "writer" to ResolvedTaskSeat(TaskSeat(RuntimeKind.Codex, "gpt-6.1-sol", "medium", TaskAccess.Write)),
            "review" to ResolvedTaskSeat(TaskSeat(RuntimeKind.Gemini, "gemini-4-carbon-high", "high", TaskAccess.Read)),
        )
        fun makeRun(graph: String, config: String, accepted: Int, inputTok: Long, cachedTok: Long, outTok: Long, estCost: Double, comparable: Boolean): AgentBenchRun {
            val binding = AgentBenchBinding(scenario, "rev1", config, seats, 1, 1, "build-1", 120_000L)
            val total = WorkScore(
                startedAt = 1_000L,
                endedAt = 11_000L,
                wallMillis = 10_000L,
                workingMillis = 9_000L,
                personWaitMillis = 0L,
                unknownWaits = 0,
                turns = 2,
                personPromptsAfterFirst = 0,
                toolCalls = 6,
                failedTools = 0,
                builds = 0,
                failedBuilds = 0,
                tests = 1,
                failedTests = 0,
                files = listOf("Source.kt"),
                added = 5,
                removed = 1,
                inputTokens = inputTok,
                outputTokens = outTok,
                reasoningTokens = 1_000L,
                unknownUsageTurns = 0,
                costUsd = null,
                unknownCostTurns = 2,
                outcome = if (accepted > 0) WorkOutcome.Accepted else WorkOutcome.Open,
                partial = false,
                cachedInputTokens = cachedTok,
                freshInputTokens = inputTok - cachedTok,
                peakContextTokens = 40_000L,
                contextWindowTokens = 200_000L,
                peakContextPercent = 20.0,
                estimatedCostUsd = estCost,
            )
            val score = GraphWorkScore(graph, total, emptyMap(), listOf("T1"), 9_000L, emptyList())
            val lenses = listOf(AgentBenchLensScore("gemini-correctness", 1L, RuntimeKind.Gemini, "correctness", 2, 2, 0, 0, 0))
            val result = AgentBenchResult(graph, 1L, 12_000L, binding, score, true, accepted, 1, 0, lenses, comparable, emptyList())
            return AgentBenchRun(graph, TaskGraphStatus.Finished, binding, result)
        }
        val catalog = AgentBenchCatalog(
            scenarios = listOf(entry),
            runs = listOf(
                makeRun("G1", "tiered-fast", accepted = 1, inputTok = 80_000L, cachedTok = 60_000L, outTok = 5_000L, estCost = 0.18, comparable = true),
                makeRun("G2", "frontier-solo", accepted = 1, inputTok = 190_000L, cachedTok = 95_000L, outTok = 10_000L, estCost = 0.95, comparable = true),
                makeRun("G3", "tiered-fast", accepted = 1, inputTok = 999_000L, cachedTok = 0L, outTok = 1_000L, estCost = 9.0, comparable = false),
            ),
        )
        val comparableStandings = ReaktorPlusBench.standings(catalog, scenarioId = "s1", comparableOnly = true)
        assertEquals(listOf("tiered-fast", "frontier-solo"), comparableStandings.map { it.configuration })
        val winner = comparableStandings.first()
        assertEquals(1, winner.comparableRuns)
        assertEquals(1, winner.nonComparableRuns)
        assertEquals(85_000L, winner.tokensPerAccepted)
        assertEquals(0.75, winner.meanCachedShare!!, 0.001)
        assertEquals(1.0, winner.defectRecall!!, 0.001)
        assertTrue(winner.usesEstimatedCost)
    }

    private fun git(root: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { output }
        return output
    }
}
