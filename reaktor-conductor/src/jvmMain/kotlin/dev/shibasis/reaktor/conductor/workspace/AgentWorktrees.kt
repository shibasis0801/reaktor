package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.ArtifactRef
import kotlinx.serialization.json.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Owned, detached worktrees include the dirty source baseline using a private Git index. */
class AgentWorktrees(private val root: File, private val directory: Path, private val artifacts: LocalAgentArtifacts) {
    init { privateDirectory(directory) }
    private fun file(id: String): Path { require(id.matches(Regex("[a-f0-9]{64}"))); return directory.resolve("$id.json") }
    fun read(id: String): AgentWorktree = ConductorJson.decodeFromString(AgentWorktree.serializer(), Files.readString(file(id)))
    @Synchronized internal fun attach(id: String, runId: String): AgentWorktree {
        require(runId.isNotBlank() && runId.length <= 200)
        val value = read(id)
        if (runId == value.runId || runId in value.attemptRuns) return value
        require(value.attemptRuns.size < 100) { "Worktree attempt reference budget exhausted" }
        return value.copy(attemptRuns = value.attemptRuns + runId).also { save(it); register(it) }
    }
    internal fun capture(id: String, subjects: List<AgentGraphSubject> = emptyList()): AgentCandidate {
        val value = read(id)
        val location = directory.resolve(id).toRealPath()
        val roots = value.roots.map { entry ->
            File(entry.path).canonicalFile.also { path ->
                require(path.toPath().parent == location && File(path, ".git").isFile) { "Worktree source is no longer owned" }
            }
        }
        val workingRoot = File(value.roots.single { it.source == root.canonicalPath }.path)
        return SourceCandidates(workingRoot, artifacts, roots).capture(subjects)
    }
    private fun save(value: AgentWorktree) = atomicWrite(file(value.id), ConductorJson.encodeToString(AgentWorktree.serializer(), value))
    private fun registration(id: String): Path? {
        val state = root.canonicalFile.parentFile.toPath().resolve(".remote-dev")
        return if (Files.isRegularFile(state.resolve("config.json")))
            state.resolve("worktrees").resolve("${digest(root.canonicalPath + ":" + id)}.json") else null
    }
    private fun register(value: AgentWorktree) {
        val target = registration(value.id) ?: return
        val config = ConductorJson.parseToJsonElement(Files.readString(target.parent.parent.resolve("config.json"))).jsonObject
        val projects = config.getValue("projects").jsonArray.map { it.jsonPrimitive.content }.toSet()
        require(value.roots.all { File(it.source).canonicalFile.parentFile == root.canonicalFile.parentFile && File(it.source).name in projects }) {
            "Worktree sources must belong to the configured build workspace"
        }
        privateDirectory(target.parent)
        atomicWrite(target, ConductorJson.encodeToString(AgentWorktree.serializer(), value))
    }
    @Synchronized fun remove(id: String) {
        val value = read(id)
        value.roots.forEach { entry ->
            val path = File(entry.path).canonicalFile.toPath()
            require(path.parent == directory.resolve(id).toRealPath())
            if (Files.exists(path)) git(File(entry.source), listOf("worktree", "remove") +
                (if (value.applyState in listOf("applied", "abandoned")) listOf("--force") else emptyList()) + path.toString())
        }
        registration(id)?.let(Files::deleteIfExists)
        directory.resolve(id).toFile().deleteTreeSafely(within = directory.toFile())
        Files.delete(file(id))
    }
    @Synchronized internal fun retainSnapshot(id: String): ArtifactRef {
        val value = read(id)
        val before = capture(id)
        require(before.complete) { "Snapshot coverage is incomplete; keep the checkout" }
        val ref = "refs/reaktor/abandoned/$id/${before.id}"
        val commits = value.roots.associateWith { entry ->
            val path = File(entry.path)
            val tree = snapshot(path)
            git(path, listOf("-c", "user.name=Reaktor", "-c", "user.email=agents@localhost",
                "commit-tree", tree, "-p", entry.baseline, "-m", "Reaktor abandoned work snapshot")).trim()
        }
        val after = capture(id)
        require(after.complete && before.id == after.id) { "Source changed during snapshot; keep the checkout" }
        commits.forEach { (entry, commit) ->
            git(File(entry.source), listOf("update-ref", ref, commit))
            require(git(File(entry.source), listOf("rev-parse", ref)).trim() == commit) { "Snapshot reference was not retained" }
        }
        return artifacts.put(buildJsonObject {
            put("worktree", id); put("candidate", before.id)
            put("restore", "For each root: git -C <source> worktree add --detach <new-path> <ref>")
            putJsonArray("roots") { commits.forEach { (entry, commit) -> add(buildJsonObject {
                put("source", entry.source); put("ref", ref); put("commit", commit); put("baseline", entry.baseline)
            }) } }
        }.toString(), "worktree-snapshot")
    }

    @Synchronized internal fun abandon(id: String, snapshot: ArtifactRef) {
        val manifest = ConductorJson.parseToJsonElement(artifacts.text(snapshot)).jsonObject
        require(snapshot.kind == "worktree-snapshot" && manifest.getValue("worktree").jsonPrimitive.content == id)
        val value = read(id)
        val roots = manifest.getValue("roots").jsonArray
        require(roots.size == value.roots.size && roots.map { it.jsonObject.getValue("source").jsonPrimitive.content }.toSet() == value.roots.map { it.source }.toSet()) { "Snapshot must retain every owned root" }
        roots.forEach { item ->
            val entry = item.jsonObject
            val source = entry.getValue("source").jsonPrimitive.content
            require(value.roots.any { it.source == source })
            require(git(File(source), listOf("rev-parse", entry.getValue("ref").jsonPrimitive.content)).trim() ==
                entry.getValue("commit").jsonPrimitive.content) { "Retained snapshot changed; keep the checkout" }
        }
        val current = capture(id)
        require(current.complete && current.id == manifest.getValue("candidate").jsonPrimitive.content) { "Source changed after snapshot; keep the checkout" }
        save(value.copy(applyState = "abandoned"))
        remove(id)
    }

    fun prepare(runId: String, participant: String): AgentWorktree =
        prepare(runId, participant) { SourceCandidates(root, artifacts).capture() }

    @Synchronized internal fun prepare(runId: String, participant: String, capture: () -> AgentCandidate): AgentWorktree {
        val id = digest("$runId:$participant")
        if (Files.exists(file(id))) return read(id).also(::register)
        repeat(3) { attempt ->
            val source = capture()
            if (!source.complete && source.moving() && attempt < 2) return@repeat
            require(source.baseCommit != null) { "Worktree isolation needs a Git baseline" }
            require(source.complete) { "Worktree baseline coverage is incomplete: ${source.notices.joinToString()}" }
            require(source.sourceRoots.size <= 16)
            val sources = source.sourceRoots.map { File(it).canonicalFile }
            require(sources.all { it.parentFile == root.canonicalFile.parentFile }) { "Isolation currently requires sibling source repositories; inspect the source-root graph" }
            require(sources.map { it.name }.distinct().size == sources.size)
            val location = directory.resolve(id).also(::privateDirectory)
            val roots = mutableListOf<AgentWorktreeRoot>()
            try {
                sources.forEach { sourceRoot ->
                    val tree = snapshot(sourceRoot)
                    val head = git(sourceRoot, listOf("rev-parse", "HEAD")).trim()
                    val commit = git(sourceRoot, listOf("-c", "user.name=Reaktor", "-c", "user.email=agents@localhost", "commit-tree", tree, "-p", head, "-m", "Reaktor isolated source baseline")).trim()
                    val path = location.resolve(sourceRoot.name)
                    require(!Files.exists(path)) { "An unfinished worktree needs inspection: $path" }
                    roots += AgentWorktreeRoot(sourceRoot.path, path.toString(), commit)
                    git(sourceRoot, listOf("worktree", "add") + (if (sourceRoot.name == "bestbuds") listOf("--no-checkout") else emptyList()) + listOf("--detach", path.toString(), commit))
                    if (sourceRoot.name == "bestbuds") {
                        git(path.toFile(), listOf("sparse-checkout", "set", "--no-cone", "--", "/*", "!/agent/"))
                        git(path.toFile(), listOf("read-tree", "-mu", "HEAD"))
                    }
                }
                val current = capture()
                if (!current.complete || current.sourceDigest != source.sourceDigest) {
                    discard(roots, location)
                    require(current.complete || current.moving()) { "Worktree baseline coverage is incomplete: ${current.notices.joinToString()}" }
                    check(attempt < 2) { "Source moved during all three isolation attempts; retry after the source settles" }
                    return@repeat
                }
                return AgentWorktree(id, runId, participant, source.sourceDigest, roots).also { save(it); register(it) }
            } catch (failure: Exception) {
                runCatching {
                    discard(roots, location)
                    Files.deleteIfExists(file(id))
                    registration(id)?.let(Files::deleteIfExists)
                }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
        error("Source did not settle for worktree isolation")
    }

    private fun AgentCandidate.moving() = notices.isNotEmpty() && notices.all {
        "Source changed during capture:" in it || "Source grew beyond capture budget:" in it || it == "Included source coverage is incomplete"
    }

    private fun discard(roots: List<AgentWorktreeRoot>, location: Path) {
        roots.asReversed().forEach { entry ->
            val path = File(entry.path).toPath()
            require(path.parent == location && location.parent == directory)
            if (Files.exists(path)) {
                if (Files.exists(path.resolve(".git"))) git(File(entry.source), listOf("worktree", "remove", "--force", path.toString()))
                else path.toFile().deleteTreeSafely(within = location.toFile())
            }
        }
        Files.deleteIfExists(location)
    }
    fun list(runId: String): List<AgentWorktree> = Files.list(directory).use { files -> files
        .filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }.toList()
        .map { read(it.fileName.toString().removeSuffix(".json")) }.filter { it.runId == runId || runId in it.attemptRuns } }

    @Synchronized fun review(id: String): AgentWorktreeReview {
        val worktree = read(id)
        val patches = patches(worktree)
        val files = changed(worktree)
        val conflicts = mutableListOf<String>()
        val source = files.entries.joinToString("\n") { (entry, paths) ->
            val current = snapshot(File(entry.source))
            val moved = if (paths.isEmpty()) "" else git(File(entry.source),
                listOf("diff", "--binary", "--no-ext-diff", "--no-textconv", entry.baseline, current, "--") + paths)
            if (moved.isNotBlank()) conflicts += "${File(entry.source).name}: candidate files changed since the baseline: ${paths.joinToString()}"
            "${entry.source}:${entry.baseline}:$paths\n$moved"
        }
        patches.forEach { (entry, patch) -> if (patch.isNotBlank()) runCatching { apply(File(entry.source), patch, check = true) }
            .onFailure { conflicts += "${File(entry.source).name}: ${it.message.orEmpty().take(1000)}" } }
        val combined = patches.entries.joinToString("\n") { (entry, patch) -> "# ${entry.source}\n$patch" }
        require(combined.toByteArray().size <= 16_000_000) { "Worktree review exceeds 16 MB" }
        val changed = files.flatMap { (entry, paths) -> paths.map { "${File(entry.source).name}/$it" } }
        return AgentWorktreeReview(worktree, digest(combined), digest(source), changed, combined.take(60000), conflicts)
    }

    private fun changed(worktree: AgentWorktree): Map<AgentWorktreeRoot, List<String>> = worktree.roots.associateWith { entry ->
        git(File(entry.path), listOf("diff", "--no-renames", "--name-only", "-z", entry.baseline, snapshot(File(entry.path))))
            .split('\u0000').filter(String::isNotBlank)
    }

    internal fun files(id: String): List<File> = changed(read(id)).flatMap { (entry, paths) ->
        paths.map { File(entry.source, it).canonicalFile }
    }

    @Synchronized internal fun land(id: String, expectedPatch: String, expectedSource: String, message: String): Map<String, String> {
        val worktree = read(id)
        require(worktree.applyState == null) { "A previous apply needs reconciliation before landing" }
        val review = review(id)
        require(review.patchDigest == expectedPatch && review.sourceRevision == expectedSource) { "Review is stale" }
        require(review.conflicts.isEmpty()) { review.conflicts.joinToString("\n") }
        val patches = patches(worktree).filterValues(String::isNotBlank)
        require(patches.isNotEmpty()) { "The candidate has no changes to land" }
        require(digest(patches(worktree).entries.joinToString("\n") { (entry, patch) -> "# ${entry.source}\n$patch" }) == expectedPatch) { "Worktree changed during landing" }
        val files = changed(worktree)
        data class Prepared(val entry: AgentWorktreeRoot, val head: String, val branch: String, val commit: String,
            val index: Path, val replacement: Path)
        val locks = mutableListOf<Path>()
        val temporary = mutableListOf<Path>()
        var applied = false
        try {
            val prepared = patches.map { (entry, patch) ->
                val source = File(entry.source)
                val paths = files.getValue(entry)
                val index = Path.of(git(source, listOf("rev-parse", "--path-format=absolute", "--git-path", "index")).trim())
                require(!Files.isSymbolicLink(index)) { "Git index is a symbolic link" }
                val lock = Path.of("$index.lock")
                Files.createFile(lock)
                locks.add(lock)
                require(git(source, listOf("diff", "--cached", "--name-only", "-z", "--") + paths).isEmpty()) {
                    "Staged changes in candidate files: ${entry.source}: ${paths.joinToString()}"
                }
                val head = git(source, listOf("rev-parse", "HEAD")).trim()
                val branch = git(source, listOf("symbolic-ref", "HEAD")).trim()
                val selected = Files.createTempFile("reaktor-land-", ".index").also(temporary::add)
                Files.delete(selected)
                val env = mapOf("GIT_INDEX_FILE" to selected.toString())
                git(source, listOf("read-tree", head), env)
                runCatching { apply(source, patch, check = false, environment = env, cached = true) }.getOrElse {
                    error("Candidate patch depends on uncommitted source in ${entry.source}: ${paths.joinToString()}: ${it.message}")
                }
                val tree = git(source, listOf("write-tree"), env).trim()
                val commit = git(source, listOf("-c", "user.name=Reaktor", "-c", "user.email=agents@localhost",
                    "commit-tree", tree, "-p", head, "-m", message)).trim()
                val replacement = Files.createTempFile(index.parent, "reaktor-land-", ".index").also(temporary::add)
                if (Files.exists(index)) Files.copy(index, replacement, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                else {
                    Files.delete(replacement)
                    git(source, listOf("read-tree", head), mapOf("GIT_INDEX_FILE" to replacement.toString()))
                }
                val actual = mapOf("GIT_INDEX_FILE" to replacement.toString())
                paths.forEach { path ->
                    val item = git(source, listOf("ls-tree", "-z", commit, "--", path)).removeSuffix("\u0000")
                    if (item.isEmpty()) git(source, listOf("update-index", "--force-remove", "--", path), actual)
                    else {
                        val parts = item.substringBefore('\t').split(' ')
                        require(parts.size == 3 && parts[1] == "blob") { "Unsupported candidate index entry: $path" }
                        git(source, listOf("update-index", "--add", "--cacheinfo", parts[0], parts[2], path), actual)
                    }
                }
                Prepared(entry, head, branch, commit, index, replacement)
            }
            prepared.forEach {
                require(git(File(it.entry.source), listOf("rev-parse", "HEAD")).trim() == it.head &&
                    git(File(it.entry.source), listOf("symbolic-ref", "HEAD")).trim() == it.branch) { "HEAD moved before landing" }
            }
            apply(id, expectedPatch, expectedSource)
            applied = true
            prepared.forEach {
                val source = File(it.entry.source)
                require(git(source, listOf("symbolic-ref", "HEAD")).trim() == it.branch) { "Branch changed during landing" }
                git(source, listOf("update-ref", "-m", message, it.branch, it.commit, it.head))
                Files.move(it.replacement, it.index, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return prepared.associate { File(it.entry.source).name to it.commit }
        } catch (failure: Exception) {
            if (applied) save(read(id).copy(applyState = "needs-reconciliation"))
            throw failure
        } finally {
            temporary.forEach(Files::deleteIfExists)
            locks.asReversed().forEach(Files::deleteIfExists)
        }
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
        git(File(entry.path), listOf("diff", "--binary", "--no-renames", "--no-ext-diff", "--no-textconv", entry.baseline, snapshot(File(entry.path))))
    }
    private fun snapshot(directory: File): String {
        val index = Files.createTempFile("reaktor-index-", ".tmp")
        Files.delete(index)
        return try {
            val env = mapOf("GIT_INDEX_FILE" to index.toString())
            git(directory, listOf("read-tree", "HEAD"), env)
            git(directory, listOf("add", "-A", "--") + CandidateSourceInputs.paths(directory), env)
            git(directory, listOf("write-tree"), env).trim()
        } finally { Files.deleteIfExists(index) }
    }
    private fun apply(root: File, patch: String, check: Boolean, environment: Map<String, String> = emptyMap(), cached: Boolean = false) {
        val file = Files.createTempFile("reaktor-apply-", ".patch")
        try { Files.writeString(file, patch); git(root, listOf("apply", "--binary", "--whitespace=nowarn") +
            (if (check) listOf("--check") else emptyList()) + (if (cached) listOf("--cached") else emptyList()) + listOf("--", file.toString()), environment) }
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
