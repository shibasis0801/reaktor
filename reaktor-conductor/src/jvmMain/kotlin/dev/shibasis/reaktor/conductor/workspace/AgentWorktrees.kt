package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Owned, detached worktrees include the dirty source baseline using a private Git index. */
class AgentWorktrees(private val root: File, private val directory: Path, private val artifacts: LocalAgentArtifacts) {
    init { privateDirectory(directory) }
    private fun file(id: String): Path { require(id.matches(Regex("[a-f0-9]{64}"))); return directory.resolve("$id.json") }
    fun read(id: String): AgentWorktree = ConductorJson.decodeFromString(AgentWorktree.serializer(), Files.readString(file(id)))
    private fun save(value: AgentWorktree) = atomicWrite(file(value.id), ConductorJson.encodeToString(AgentWorktree.serializer(), value))
    @Synchronized fun prepare(runId: String, participant: String): AgentWorktree {
        val id = digest("$runId:$participant")
        if (Files.exists(file(id))) return read(id)
        val source = SourceCandidates(root, artifacts).capture()
        require(source.baseCommit != null) { "Worktree isolation needs a Git baseline" }
        require(source.complete) { "Worktree baseline coverage is incomplete: ${source.notices.joinToString()}" }
        require(source.sourceRoots.size <= 16)
        val sources = source.sourceRoots.map { File(it).canonicalFile }
        require(sources.all { it.parentFile == root.canonicalFile.parentFile }) { "Isolation currently requires sibling source repositories; inspect the source-root graph" }
        require(sources.map { it.name }.distinct().size == sources.size)
        val location = directory.resolve(id).also(::privateDirectory)
        val roots = sources.map { sourceRoot ->
            val tree = snapshot(sourceRoot)
            val head = git(sourceRoot, listOf("rev-parse", "HEAD")).trim()
            val commit = git(sourceRoot, listOf("-c", "user.name=Reaktor", "-c", "user.email=agents@localhost", "commit-tree", tree, "-p", head, "-m", "Reaktor isolated source baseline")).trim()
            val path = location.resolve(sourceRoot.name)
            git(sourceRoot, listOf("worktree", "add", "--detach", path.toString(), commit))
            AgentWorktreeRoot(sourceRoot.path, path.toString(), commit)
        }
        require(SourceCandidates(root, artifacts).capture().sourceDigest == source.sourceDigest) { "Source moved during isolation; retry with a new request" }
        return AgentWorktree(id, runId, participant, source.sourceDigest, roots).also(::save)
    }
    fun list(runId: String): List<AgentWorktree> = Files.list(directory).use { files -> files
        .filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }.toList()
        .map { read(it.fileName.toString().removeSuffix(".json")) }.filter { it.runId == runId } }

    @Synchronized fun review(id: String): AgentWorktreeReview {
        val worktree = read(id)
        val patches = patches(worktree)
        val revision = SourceCandidates(root, artifacts).capture().sourceDigest
        val conflicts = mutableListOf<String>()
        if (revision != worktree.sourceRevision) conflicts += "Source changed since this worktree was created. Rebase/review the worktree before applying."
        patches.forEach { (entry, patch) -> if (patch.isNotBlank()) runCatching { apply(File(entry.source), patch, check = true) }
            .onFailure { conflicts += "${File(entry.source).name}: ${it.message.orEmpty().take(1000)}" } }
        val combined = patches.entries.joinToString("\n") { (entry, patch) -> "# ${entry.source}\n$patch" }
        require(combined.toByteArray().size <= 16_000_000) { "Worktree review exceeds 16 MB" }
        val changed = worktree.roots.flatMap { entry ->
            git(File(entry.path), listOf("diff", "--name-only", "-z", entry.baseline, snapshot(File(entry.path)))).split('\u0000')
                .filter(String::isNotBlank).map { "${File(entry.source).name}/$it" }
        }
        return AgentWorktreeReview(worktree, digest(combined), revision, changed, combined.take(60000), conflicts)
    }

    @Synchronized fun apply(id: String, expectedPatch: String, expectedSource: String): AgentWorktreeReview {
        val prior = read(id)
        if (prior.appliedPatch == expectedPatch && prior.applyState == "applied") return review(id)
        require(prior.applyState == null) { "A previous apply needs reconciliation; inspect the source and retained worktree" }
        val review = review(id)
        require(review.patchDigest == expectedPatch && review.sourceRevision == expectedSource) { "Review is stale; inspect the current patch and source" }
        require(review.conflicts.isEmpty()) { review.conflicts.joinToString("\n") }
        val patches = patches(prior)
        require(digest(patches.entries.joinToString("\n") { (entry, patch) -> "# ${entry.source}\n$patch" }) == expectedPatch) { "Worktree changed during review" }
        save(prior.copy(applyState = "applying", appliedPatch = expectedPatch))
        try {
            patches.forEach { (entry, patch) -> if (patch.isNotBlank()) apply(File(entry.source), patch, check = false) }
            save(prior.copy(applyState = "applied", appliedPatch = expectedPatch))
        } catch (failure: Exception) {
            save(prior.copy(applyState = "needs-reconciliation", appliedPatch = expectedPatch))
            throw IllegalStateException("Apply may have changed some roots; inspect before retrying. ${failure.message}", failure)
        }
        return review(id)
    }

    private fun patches(worktree: AgentWorktree): Map<AgentWorktreeRoot, String> = worktree.roots.associateWith { entry ->
        require(File(entry.path).canonicalFile.toPath().startsWith(directory.toRealPath()))
        git(File(entry.path), listOf("diff", "--binary", "--no-ext-diff", "--no-textconv", entry.baseline, snapshot(File(entry.path))))
    }
    private fun snapshot(directory: File): String {
        val index = Files.createTempFile("reaktor-index-", ".tmp")
        Files.delete(index)
        return try {
            val env = mapOf("GIT_INDEX_FILE" to index.toString())
            git(directory, listOf("read-tree", "HEAD"), env)
            git(directory, listOf("add", "-A", "--", "."), env)
            git(directory, listOf("write-tree"), env).trim()
        } finally { Files.deleteIfExists(index) }
    }
    private fun apply(root: File, patch: String, check: Boolean) {
        val file = Files.createTempFile("reaktor-apply-", ".patch")
        try { Files.writeString(file, patch); git(root, listOf("apply", "--binary", "--whitespace=nowarn") + (if (check) listOf("--check") else emptyList()) + listOf("--", file.toString())) }
        finally { Files.deleteIfExists(file) }
    }
    private fun git(root: File, args: List<String>, environment: Map<String, String> = emptyMap()): String {
        val output = Files.createTempFile("reaktor-git-", ".log")
        try {
            val process = ProcessBuilder(listOf("git", "-C", root.path) + args).apply { environment().putAll(environment) }
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Git timed out") }
            require(Files.size(output) <= 16_000_000) { "Git output exceeds 16 MB" }
            val text = Files.readString(output)
            check(process.exitValue() == 0) { text.take(2000) }
            return text
        } finally { Files.deleteIfExists(output) }
    }
}
