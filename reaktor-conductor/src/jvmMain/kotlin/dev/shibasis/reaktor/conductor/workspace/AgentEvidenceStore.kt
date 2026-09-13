package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class AgentEvidenceStore(private val root: File, private val directory: Path) {
    val artifacts = LocalAgentArtifacts(directory.resolve("artifacts"))
    private val candidates = SourceCandidates(root, artifacts)
    init { privateDirectory(directory.resolve("tasks")) }

    @Synchronized fun get(taskId: String): AgentTaskEvidence {
        val path = path(taskId)
        return if (Files.exists(path)) ConductorJson.decodeFromString(AgentTaskEvidence.serializer(), Files.readString(path))
        else AgentTaskEvidence(taskId)
    }
    @Synchronized fun capture(taskId: String, subjects: List<AgentGraphSubject> = emptyList()): AgentCandidate {
        val candidate = candidates.capture(subjects)
        val task = get(taskId)
        save(task.copy(candidates = (task.candidates.filterNot { it.id == candidate.id } + candidate).takeLast(100)))
        return candidate
    }
    @Synchronized fun requireChecks(taskId: String, checks: List<String>): AgentTaskEvidence {
        require(checks.size <= 30 && checks.all { it.isNotBlank() && it.length <= 200 })
        return save(get(taskId).copy(requiredChecks = checks.distinct(), acceptedCandidate = null))
    }
    @Synchronized fun finding(taskId: String, finding: AgentFinding): AgentTaskEvidence {
        val task = get(taskId)
        val candidate = task.candidates.single { it.id == finding.candidateId }
        require(finding.id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        require(finding.severity in listOf("blocker", "high", "medium", "low"))
        require(finding.title.isNotBlank() && finding.title.length <= 300 && finding.detail.length <= 8000)
        require(finding.resolvedByCandidate == null && finding.resolution == null)
        finding.sourcePath?.let { path ->
            require(!File(path).isAbsolute && candidate.sourceRoots.any { File(root, path).canonicalFile.toPath().startsWith(File(it).toPath()) })
            require(path in candidate.changedFiles || candidate.subjects.any { it.sourcePath == path }) { "Finding source is outside candidate evidence" }
        }
        finding.subject?.let { require(it in candidate.subjects) { "Unknown graph subject" } }
        require(finding.line == null || finding.line > 0)
        val existing = task.findings.firstOrNull { it.id == finding.id }
        require(existing == null || existing == finding) { "Finding id already has different content" }
        return if (existing != null) task else save(task.copy(findings = task.findings + finding, acceptedCandidate = null))
    }
    @Synchronized fun resolve(taskId: String, findingId: String, candidateId: String, reason: String): AgentTaskEvidence {
        val task = get(taskId)
        require(task.candidates.any { it.id == candidateId })
        require(reason.isNotBlank() && reason.length <= 4000)
        require(task.findings.any { it.id == findingId })
        return save(task.copy(findings = task.findings.map {
            if (it.id == findingId) it.copy(resolvedByCandidate = candidateId, resolution = reason) else it
        }, acceptedCandidate = null))
    }
    @Synchronized fun check(taskId: String, check: AgentCandidateCheck): AgentTaskEvidence {
        val task = get(taskId)
        require(task.candidates.any { it.id == check.candidateId })
        require(check.result.revision == check.candidateId) { "Check does not identify this candidate" }
        return save(task.copy(checks = task.checks.filterNot { it.id == check.id && it.candidateId == check.candidateId } + check, acceptedCandidate = null))
    }
    @Synchronized fun accept(taskId: String, candidateId: String): AgentTaskEvidence {
        val task = get(taskId)
        val candidate = task.candidates.single { it.id == candidateId }
        val now = candidates.capture(candidate.subjects)
        require(candidate.complete && now.complete && now.id == candidate.id) { "Source changed or candidate coverage is incomplete" }
        require(task.requiredChecks.isNotEmpty()) { "Declare acceptance checks first" }
        require(task.requiredChecks.all { id -> task.checks.any {
            it.id == id && it.candidateId == candidateId && it.result.outcome == CheckOutcome.Passed && it.source == "kernel"
        } }) { "Required kernel checks have not passed for this candidate" }
        require(task.findings.none { it.resolvedByCandidate != candidateId && it.severity in listOf("blocker", "high") }) { "Blocking findings need resolution evidence on this candidate" }
        return save(task.copy(acceptedCandidate = candidateId))
    }
    fun graph(taskId: String): AgentEvidenceGraph {
        val task = get(taskId)
        val edges = buildList {
            task.candidates.forEach { c ->
                add(AgentEvidenceEdge(taskId, "produces", c.id))
                c.subjects.forEach { add(AgentEvidenceEdge(c.id, "changes", it.ref)) }
            }
            task.findings.forEach { f ->
                add(AgentEvidenceEdge(f.producerRunId, "produces", f.id))
                add(AgentEvidenceEdge(f.id, "reviews", f.candidateId))
                f.resolvedByCandidate?.let { add(AgentEvidenceEdge(it, "resolves", f.id)) }
            }
            task.checks.forEach { add(AgentEvidenceEdge(it.id, "verifies", it.candidateId)) }
            task.acceptedCandidate?.let { add(AgentEvidenceEdge(taskId, "accepts", it)) }
        }
        return AgentEvidenceGraph(task, edges)
    }
    fun artifact(taskId: String, id: String, offset: Long = 0, limit: Int = 24000): AgentArtifactPage {
        val task = get(taskId)
        val refs = task.candidates.mapNotNull { it.diff } + task.checks.mapNotNull { it.result.log }
        return artifacts.read(refs.firstOrNull { it.id == id } ?: error("Artifact is not attached to this task"), offset, limit)
    }
    private fun path(id: String): Path {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "Invalid task id" }
        return directory.resolve("tasks/$id.json")
    }
    private fun save(task: AgentTaskEvidence): AgentTaskEvidence {
        atomicWrite(path(task.taskId), ConductorJson.encodeToString(AgentTaskEvidence.serializer(), task))
        return task
    }
}
