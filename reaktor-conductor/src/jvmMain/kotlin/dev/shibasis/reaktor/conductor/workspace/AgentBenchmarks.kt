package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.worklog.GraphWorkScore
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AgentBenchmarks internal constructor(
    private val root: File,
    private val directory: Path,
    private val workspace: AgentWorkspace,
    private val scopeModules: (List<String>) -> List<String>,
) {
    private val scenarios = File(root, "tests/agentBench").canonicalFile
    init {
        require(scenarios.toPath().startsWith(root.canonicalFile.toPath())) { "Benchmark scenarios must stay inside this source root" }
        privateDirectory(directory)
    }

    fun churn(): AgentChurnReport = AgentCodeQuality.measure(
        workspace.board.tasks().flatMap { task -> workspace.evidence.get(task.id).landings.map { task.id to it } },
        scopeModules, System.currentTimeMillis())

    fun catalog(): AgentBenchCatalog {
        val entries = if (!scenarios.isDirectory) emptyList() else scenarios.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.matches(Id) }.sortedBy { it.name }.also { require(it.size <= 100) }
            .map { entry(it.name).let { entry -> entry.copy(scenario = entry.scenario.copy(defects = emptyList())) } }
        return AgentBenchCatalog(entries, workspace.board.graphs().mapNotNull { graph ->
            graph.benchmark?.let { AgentBenchRun(graph.id, graph.status, it, result(graph.id)?.takeIf { result -> result.revision == graph.revision }) }
        })
    }

    fun entry(id: String): AgentBenchScenarioEntry {
        require(id.matches(Id)) { "Invalid benchmark scenario id" }
        val file = File(scenarios, "$id/scenario.json").canonicalFile
        require(file.toPath().startsWith(scenarios.toPath()) && file.isFile && file.length() <= 262_144) { "Missing or oversized scenario" }
        val text = file.readText()
        val scenario = AgentWorkspaceJson.decodeFromString(AgentBenchScenario.serializer(), text)
        require(scenario.id == id && scenario.title.isNotBlank() && scenario.title.length <= 120)
        require(scenario.defects.size <= 100 && scenario.defects.map { it.id }.distinct().size == scenario.defects.size)
        scenario.defects.forEach { defect ->
            require(defect.id.matches(Id) && defect.kind.isNotBlank() && defect.kind != "unclassified" && defect.kind.length <= 80 &&
                defect.from > 0 && defect.through >= defect.from && defect.path.isNotBlank() && defect.path.length <= 4096 &&
                !File(defect.path).isAbsolute && Path.of(defect.path).normalize().none { it.toString() == ".." })
        }
        TaskGraphTemplates.ordered(scenario.draft)
        return AgentBenchScenarioEntry(scenario, digest(text))
    }

    fun propose(id: String, revision: String, configurations: List<AgentBenchConfiguration>, repeats: Int, by: BoardActor): List<TaskGraphView> =
        workspace.admitOperation { synchronized(workspace.board) {
            val entry = entry(id)
            require(entry.revision == revision) { "Scenario changed; refresh before proposing" }
            require(repeats in 1..10 && configurations.size in 1..6 && repeats * configurations.size <= 24)
            require(configurations.map { it.id }.distinct().size == configurations.size)
            val base = baselines(entry.scenario.revisions)
            val prepared = configurations.map { config ->
                require(config.id.matches(Id) && (config.estimatedTokens == null || config.estimatedTokens > 0))
                val slots = TaskGraphTemplates.slots(entry.scenario.draft)
                require(config.slots.keys == slots.keys) { "Bind every scenario profile slot exactly once" }
                val pinned = config.slots.mapValues { (_, seat) ->
                    workspace.resolveSeat(seat).also { value ->
                        require(!value.seat.lead && value.seat.agent in setOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode) &&
                            !value.seat.model.isNullOrBlank()) { "Benchmark seats require a native provider and explicit model" }
                    }
                }
                val draft = TaskGraphTemplates.bind(entry.scenario.draft, pinned.mapValues { it.value.seat })
                TaskGraphTemplates.validate(draft) { workspace.resolveSeat(it).seat }
                Triple(config, pinned, draft)
            }
            val build = ownerBuild()
            atomicWrite(directory.resolve("scenario-${entry.revision}.json"), AgentWorkspaceJson.encodeToString(AgentBenchScenario.serializer(), entry.scenario))
            prepared.flatMap { (config, pinned, draft) -> (1..repeats).map { repeat ->
                workspace.board.propose(draft.copy(title = ("${entry.scenario.title} · ${config.id} · $repeat/$repeats").take(200)),
                    by, AgentBenchBinding(entry.scenario.copy(defects = emptyList()), entry.revision, config.id, pinned, repeat, repeats, build, config.estimatedTokens),
                    base)
            } }
        } }

    internal fun resolve(task: BoardTask): ResolvedTaskSeat? {
        val plan = requireNotNull(task.graph)
        val seat = plan.seat ?: return null
        val view = workspace.board.view(plan.id)
        val benchmark = view.graph.benchmark ?: return workspace.resolveSeat(seat)
        return requireNotNull(benchmark.seats.values.firstOrNull { it.seat == seat }) { "Seat is outside the pinned benchmark configuration" }
    }

    suspend fun measure(graph: String): AgentBenchResult {
        val before = workspace.board.view(graph)
        val binding = requireNotNull(before.graph.benchmark) { "This graph is not a benchmark" }
        val scenario = AgentWorkspaceJson.decodeFromString(AgentBenchScenario.serializer(), Files.readString(directory.resolve("scenario-${binding.scenarioRevision}.json")))
        val answer = workspace.readKernel("kernel", buildJsonObject {
            put("operation", "taskgraph.score")
            putJsonObject("arguments") { put("graph", graph) }
        }).jsonObject
        check(answer["type"]?.jsonPrimitive?.content == "completed") { "Kernel scorecard is unavailable" }
        val score = AgentWorkspaceJson.decodeFromJsonElement(GraphWorkScore.serializer(), answer.getValue("result"))
        val view = workspace.board.view(graph)
        require(view.graph.revision == before.graph.revision) { "Graph changed while measuring; measure again" }
        val evidence = view.tasks.map { workspace.evidence.get(it.id) }
        val writers = view.tasks.filter { it.graph?.let { plan -> plan.kind == TaskKind.Merge || plan.seat?.access == TaskAccess.Write } == true }
        val terminal = view.graph.status in setOf(TaskGraphStatus.Finished, TaskGraphStatus.Failed, TaskGraphStatus.Cancelled)
        val checks = view.tasks.filter { it.graph?.kind == TaskKind.Check }
        val unchanged = definition(view) == binding.definition
        val value = AgentBenchResult(graph, view.graph.revision, System.currentTimeMillis(), binding, score,
            if (!terminal || checks.isEmpty()) null else checks.all { it.graph?.state == TaskState.Succeeded },
            writers.count { writer -> evidence.first { it.taskId == writer.id }.let { it.approverVerified && it.acceptedCandidate == writer.graph?.outcome?.candidateId && it.acceptedCandidate != null } },
            writers.count { it.graph?.outcome?.candidateId != null },
            view.tasks.sumOf { (it.graph?.attempt?.minus(1) ?: 0).coerceAtLeast(0) },
            lensScores(binding.copy(scenario = scenario), panelFindings(view, evidence.flatMap { it.findings })), unchanged,
            buildList {
                if (!unchanged) add("Definitions changed after proposal; this run is not comparable.")
                if (!terminal) add("Run is still open; measurements are partial.")
                if (checks.isEmpty()) add("No acceptance check is declared.")
                add("Acceptance counts only person-verified exact candidates; passing checks alone do not prove acceptance.")
                add("Lens matches use declared planted-defect file/line ranges. Unmatched confirmed findings require adjudication; they are not automatically false positives.")
                add("Timing, usage and cost retain the scorecard's unknown and partial fields. Repeats share the host's existing caches.")
            })
        atomicWrite(directory.resolve("$graph.json"), AgentWorkspaceJson.encodeToString(AgentBenchResult.serializer(), value))
        return value
    }

    private fun panelFindings(view: TaskGraphView, published: List<AgentFinding>): List<AgentFinding> {
        val observations = view.tasks.flatMap { task ->
            val plan = requireNotNull(task.graph)
            val run = plan.runId ?: return@flatMap emptyList()
            if (plan.state != TaskState.Succeeded || plan.contract != dev.shibasis.reaktor.conductor.WorkflowContract.Decision) return@flatMap emptyList()
            val submission = workspace.submission(run)
            val resolved = submission.resolvedSeat?.takeIf { it.lenses.isNotEmpty() } ?: return@flatMap emptyList()
            val target = requireNotNull(submission.task)
            TaskReviewPanel.observations(requireNotNull(target.candidate), run, resolved,
                requireNotNull(workspace.checkpoint(run)).completed.mapValues { it.value.text },
                workspace.evidence.get(target.evidenceTask).checks)
        }
        return (published + observations).distinctBy { it.id to it.candidateId }
    }

    private fun result(graph: String): AgentBenchResult? {
        val path = directory.resolve("$graph.json")
        return if (!Files.isRegularFile(path)) null else AgentWorkspaceJson.decodeFromString(AgentBenchResult.serializer(), Files.readString(path))
    }

    private fun baselines(revisions: Map<String, String>): Map<String, String> {
        require(revisions.size in 1..16)
        val base = revisions.map { (path, revision) ->
            val source = File(root, path).canonicalFile
            require(source.parentFile == root.canonicalFile.parentFile && revision.matches(Regex("[a-f0-9]{40,64}"))) { "Use exact revisions of sibling source roots" }
            val process = ProcessBuilder("git", "-C", source.path, "cat-file", "-e", "$revision^{commit}").redirectErrorStream(true).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Git revision lookup timed out") }
            require(process.exitValue() == 0) { "Benchmark revision is unavailable in ${source.name}" }
            source.path to revision
        }.toMap()
        require(base.size == revisions.size && root.canonicalPath in base)
        return base
    }

    private fun ownerBuild(): String {
        val source = File(AgentWorkspace::class.java.protectionDomain.codeSource.location.toURI())
        val file = if (source.isFile) source else File(source, "dev/shibasis/reaktor/conductor/workspace/AgentWorkspace.class")
        require(file.isFile && file.length() <= 67_108_864)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return (if (source.isFile) "conductor-jar:" else "development-class:") + hash.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val Id = Regex("[a-zA-Z0-9_-]{1,80}")

        internal fun definition(view: TaskGraphView): String = digest(AgentWorkspaceJson.encodeToString(TaskGraphDraft.serializer(),
            TaskGraphDraft("", view.graph.goal, view.tasks.sortedBy { it.id }.map { task ->
                val plan = requireNotNull(task.graph)
                TaskGraphOp.Add(task.title, task.id, task.intent, task.acceptance, task.scope, plan.kind, plan.seat,
                    plan.contract, plan.check, plan.dependsOn, plan.layout, task.parent)
            }, view.graph.envelope)))

        internal fun lensScores(binding: AgentBenchBinding, findings: List<AgentFinding>): List<AgentBenchLensScore> =
            binding.seats.values.flatMap { it.lenses }.distinctBy { it.seat.profile to it.profileRevision }.flatMap { lens ->
                val profile = requireNotNull(lens.seat.profile)
                val own = findings.filter { profile in it.lenses }
                fun matches(finding: AgentFinding, defect: AgentBenchDefect) =
                    finding.sourcePath == defect.path && finding.line?.let { it in defect.from..defect.through } == true
                val confirmed = own.filter { it.confirmation != null }
                binding.scenario.defects.groupBy { it.kind }.map { (kind, defects) ->
                    val found = defects.count { defect -> confirmed.any { matches(it, defect) } }
                    AgentBenchLensScore(profile, lens.profileRevision, lens.seat.agent, kind, defects.size, found, defects.size - found, 0, 0)
                } + listOf(AgentBenchLensScore(profile, lens.profileRevision, lens.seat.agent, "unclassified", 0, 0, 0,
                    confirmed.count { finding -> binding.scenario.defects.none { matches(finding, it) } }, own.count { it.confirmation == null }))
            }
    }
}
