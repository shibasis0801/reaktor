package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import dev.shibasis.reaktor.tooling.gradleSourceRoots

/** Captures working files, not just HEAD. A budget miss prevents an acceptance claim. */
class SourceCandidates(private val root: File, private val artifacts: LocalAgentArtifacts) {
    fun capture(subjects: List<AgentGraphSubject> = emptyList()): AgentCandidate = capture(subjects, mutableSetOf())
    private fun capture(subjects: List<AgentGraphSubject>, visited: MutableSet<String>): AgentCandidate {
        visited += root.canonicalPath
        val notices = mutableListOf<String>()
        val base = git("rev-parse", "HEAD")?.trim()
        val files = git("ls-files", "-z", "--cached", "--others", "--exclude-standard")
        if (files == null) notices += "Git source inventory unavailable"
        val names = files.orEmpty().split('\u0000').filter { it.isNotEmpty() }.distinct().sorted()
        val hash = MessageDigest.getInstance("SHA-256")
        val nestedRoots = mutableListOf<File>()
        var remaining = 256_000_000L
        for (name in names) {
            hash.update(name.toByteArray()); hash.update(0)
            val file = File(root, name)
            if (!file.toPath().normalize().startsWith(root.toPath().normalize())) { notices += "Source outside workspace: $name"; continue }
            when {
                Files.isSymbolicLink(file.toPath()) -> hash.update(Files.readSymbolicLink(file.toPath()).toString().toByteArray())
                !file.exists() -> hash.update("deleted".toByteArray())
                !file.isFile -> if (File(file, ".git").exists()) nestedRoots += file else notices += "Non-file source: $name"
                file.length() > remaining -> notices += "Source capture budget exceeded at $name"
                else -> {
                    hash.update(if (file.canExecute()) 1 else 0)
                    val before = file.lastModified() to file.length()
                    file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            remaining -= count
                            if (remaining < 0) { notices += "Source grew beyond capture budget: $name"; break }
                            hash.update(buffer, 0, count)
                        }
                    }
                    if (before != file.lastModified() to file.length()) notices += "Source changed during capture: $name"
                }
            }
            hash.update(0)
        }
        val declared = gradleSourceRoots(root)
        if (declared.unresolved) notices += "Dynamic included-build roots require an evaluated build graph"
        val dependencies = (declared.roots + nestedRoots).distinctBy { it.canonicalPath }.filter { it.canonicalPath !in visited }
        val captured = dependencies.take((16 - visited.size).coerceAtLeast(0)).mapNotNull { dependency ->
            if (!dependency.isDirectory) { notices += "Missing included source root: ${dependency.path}"; null }
            else SourceCandidates(dependency, artifacts).capture(emptyList(), visited).also {
                hash.update(it.id.toByteArray())
                if (!it.complete) notices += it.notices.map { notice -> "${dependency.name}: $notice" }
            }
        }
        if (dependencies.size > captured.size) notices += "Included source coverage is incomplete"
        val sourceDigest = hash.digest().joinToString("") { "%02x".format(it) }
        val untracked = (git("ls-files", "-z", "--others", "--exclude-standard") ?: "").split('\u0000').filter { it.isNotEmpty() }
        val changed = (git("diff", "--name-only", "-z", base ?: "--cached") ?: "").split('\u0000') + untracked
        val diff = git("diff", "--no-ext-diff", "--no-textconv", "--no-color", base ?: "--cached")
        val additions = StringBuilder()
        var diffBytes = diff?.toByteArray()?.size ?: 0
        for (name in untracked.take(200)) {
            val addition = git("diff", "--no-index", "--no-ext-diff", "--no-textconv", "--no-color", "--", "/dev/null", name)
            if (addition == null) { notices += "New-file diff unavailable: $name"; continue }
            diffBytes += addition.toByteArray().size
            if (diffBytes > 16_000_000) { notices += "New-file diff budget exceeded"; break }
            additions.append(addition)
        }
        if (untracked.size > 200) notices += "New-file diff budget exceeded"
        if (diff == null) notices += "Diff unavailable or over 16 MB"
        if (base == null) notices += "No base commit; initial source has no committed baseline"
        val combinedDiff = diff?.let { local -> local + additions + captured.joinToString("") { child ->
            "\n# Included source root: ${child.workspaceRoot}\n" + child.diff?.let(artifacts::text).orEmpty()
        } }
        val diffArtifact = combinedDiff?.let {
            if (it.toByteArray().size > 16_000_000) { notices += "Combined diff exceeds 16 MB"; null } else artifacts.put(it, "source-diff")
        }
        val allChanged = changed.filter { it.isNotBlank() } + captured.flatMap { child ->
            val prefix = File(child.workspaceRoot).relativeTo(root).path
            child.changedFiles.map { "$prefix/$it" }
        }
        return AgentCandidate(digest(root.canonicalPath + "\n" + base + "\n" + sourceDigest), root.canonicalPath,
            base, sourceDigest, System.currentTimeMillis(), allChanged.distinct(),
            diffArtifact, notices.isEmpty(), notices.distinct().take(100), subjects,
            sourceRoots = (listOf(root.canonicalPath) + captured.flatMap { it.sourceRoots }).distinct())
    }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", "--no-optional-locks", "-C", root.canonicalPath) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val reader = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(16_000_001) } }
        try {
            val bytes = reader.get(30, TimeUnit.SECONDS)
            if (bytes.size > 16_000_000) { process.destroyForcibly(); return@runCatching null }
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() !in if ("--no-index" in args) setOf(0, 1) else setOf(0)) null else bytes.decodeToString()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }.getOrNull()
}
