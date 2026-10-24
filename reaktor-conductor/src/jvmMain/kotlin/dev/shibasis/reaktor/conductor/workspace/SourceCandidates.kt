package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import dev.shibasis.reaktor.conductor.SourceManifest
import dev.shibasis.reaktor.tooling.gradleSourceRoots

/** Captures working files, not just HEAD. A budget miss prevents an acceptance claim. */
class SourceCandidates(private val root: File, private val artifacts: LocalAgentArtifacts) {
    private var materializedRoots: List<File>? = null
    internal constructor(root: File, artifacts: LocalAgentArtifacts, roots: List<File>) : this(root, artifacts) {
        require(roots.size in 1..16 && roots.any { it.canonicalFile == root.canonicalFile })
        materializedRoots = roots.map(File::getCanonicalFile).distinct()
    }
    fun capture(subjects: List<AgentGraphSubject> = emptyList()): AgentCandidate = capture(subjects, mutableSetOf())
    private fun capture(subjects: List<AgentGraphSubject>, visited: MutableSet<String>): AgentCandidate {
        visited += root.canonicalPath
        val notices = mutableListOf<String>()
        val base = git("rev-parse", "HEAD")?.trim()
        val files = git(*(listOf("ls-files", "-z", "--cached", "--others", "--exclude-standard", "--") + CandidateSourceInputs.paths).toTypedArray())
        if (files == null) notices += "Git source inventory unavailable"
        val names = files.orEmpty().split('\u0000').filter { it.isNotEmpty() }.distinct().sorted()
        val stages = git(*(listOf("ls-files", "--stage", "-z", "--") + CandidateSourceInputs.paths).toTypedArray())
        if (stages == null) notices += "Git source modes unavailable"
        val gitlinks = stages.orEmpty()
            .split('\u0000').filter { it.startsWith("160000 ") && '\t' in it }
            .associate { it.substringAfter('\t') to it.substringBefore('\t').split(' ')[1] }
        val hash = MessageDigest.getInstance("SHA-256")
        var remaining = 512_000_000L
        var bulky = 0
        for (name in names) {
            hash.update(name.toByteArray()); hash.update(0)
            val file = File(root, name)
            if (!file.toPath().normalize().startsWith(root.toPath().normalize())) { notices += "Source outside workspace: $name"; continue }
            when {
                Files.isSymbolicLink(file.toPath()) -> hash.update(Files.readSymbolicLink(file.toPath()).toString().toByteArray())
                name in gitlinks -> {
                    val initialized = File(file, ".git").exists()
                    val revision = if (initialized) git("-C", file.canonicalPath, "rev-parse", "HEAD")?.trim() else gitlinks[name]
                    if (revision == null) notices += "Submodule revision unavailable: $name" else hash.update(revision.toByteArray())
                    if (initialized) {
                        val dirty = git("-C", file.canonicalPath, "status", "--porcelain=v1", "--untracked-files=all")
                        if (dirty == null || dirty.isNotBlank()) notices += "Submodule source is not clean: $name; commit its source before isolation"
                    }
                }
                !file.exists() -> hash.update("deleted".toByteArray())
                !file.isFile -> notices += if (File(file, ".git").exists()) "Unregistered nested repository source: $name" else "Non-file source: $name"
                // An APK, a vendored framework or a checked-in archive is not source, and reading one
                // costs the snapshot a hundred megabytes of budget that the code it exists to cover
                // then cannot have. Fingerprinting it by size keeps it in the revision — a replaced
                // binary almost always changes length — for the price of one stat call. Size rather
                // than mtime on purpose: rebuilding an identical artifact moves mtime, and a
                // revision that flips on that would abort live tasks for no change at all.
                file.length() > CONTENT_LIMIT -> { hash.update("size:${file.length()}".toByteArray()); bulky++ }
                file.length() > remaining -> notices += "Source capture budget exceeded at $name"
                else -> {
                    hash.update(if (file.canExecute()) 1 else 0)
                    val before = file.lastModified() to file.length()
                    try { file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            remaining -= count
                            if (remaining < 0) { notices += "Source grew beyond capture budget: $name"; break }
                            hash.update(buffer, 0, count)
                        }
                    } } catch (_: java.io.IOException) { notices += "Source changed during capture: $name" }
                    if (before != file.lastModified() to file.length()) notices += "Source changed during capture: $name"
                }
            }
            hash.update(0)
        }
        val declared = if (materializedRoots == null) gradleSourceRoots(root) else null
        if (declared?.unresolved == true) notices += "Dynamic included-build roots require an evaluated build graph"
        val outside = (materializedRoots ?: requireNotNull(declared).roots).map(File::getCanonicalFile).distinct()
            .filter { it.canonicalPath !in visited && !it.toPath().startsWith(root.canonicalFile.toPath()) }
        val dependencies = outside.filter { dependency -> outside.none { it != dependency && dependency.toPath().startsWith(it.toPath()) } }
        val captured = dependencies.take((16 - visited.size).coerceAtLeast(0)).mapNotNull { dependency ->
            if (!dependency.isDirectory) { notices += "Missing included source root: ${dependency.path}"; null }
            else (materializedRoots?.let { SourceCandidates(dependency, artifacts, it) } ?: SourceCandidates(dependency, artifacts)).capture(emptyList(), visited).also {
                hash.update(it.id.toByteArray())
                if (!it.complete) notices += it.notices.map { notice -> "${dependency.name}: $notice" }
            }
        }
        if (dependencies.size > captured.size) notices += "Included source coverage is incomplete"
        val sourceDigest = hash.digest().joinToString("") { "%02x".format(it) }
        val untracked = (git(*(listOf("ls-files", "-z", "--others", "--exclude-standard", "--") + CandidateSourceInputs.paths).toTypedArray()) ?: "").split('\u0000').filter { it.isNotEmpty() }
        val changed = (git(*(listOf("diff", "--name-only", "-z", base ?: "--cached", "--") + CandidateSourceInputs.paths).toTypedArray()) ?: "").split('\u0000') + untracked
        val diff = git(*(listOf("diff", "--no-ext-diff", "--no-textconv", "--no-color", base ?: "--cached", "--") + CandidateSourceInputs.paths).toTypedArray())
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
            val prefix = File(child.workspaceRoot).relativeTo(root.canonicalFile).path
            child.changedFiles.map { "$prefix/$it" }
        }
        return AgentCandidate(digest(root.canonicalPath + "\n" + base + "\n" + sourceDigest), root.canonicalPath,
            base, sourceDigest, System.currentTimeMillis(), allChanged.distinct(),
            diffArtifact, notices.isEmpty(), notices.distinct().take(100), subjects,
            sourceRoots = (listOf(root.canonicalPath) + captured.flatMap { it.sourceRoots }).distinct(),
            // Deliberately not a notice: a repository with one committed PDF would otherwise be
            // permanently incomplete, and incomplete is what blocks an acceptance claim. This is a
            // narrower statement — the bytes of these files are outside the revision — and it
            // belongs in what the packet says the snapshot cannot see, not in what voids it.
            fingerprintedBySize = bulky + captured.sumOf { it.fingerprintedBySize })
    }
    /**
     * The diff for [paths] alone: what one turn did, rather than how the checkout differs from HEAD.
     *
     * [capture] answers the second question, which is the right one for a candidate and useless for
     * review — in a repository carrying other uncommitted work it buries a twenty-line change under
     * deleted images and unrelated config. A reviewer who cannot cheaply see what a task changed
     * ends up trusting the executor's description of it, which is the one thing this seat exists to
     * avoid.
     *
     * Still measured against HEAD, so a file the turn edited that the operator had *also* edited
     * before it started shows both changes. Only a task-local overlay fixes that; this fixes the
     * much larger half, which is the hundred files the turn never touched at all.
     */
    fun diffOf(paths: List<String>): ScopedDiff {
        val notices = mutableListOf<String>()
        val wanted = paths.filter { it.isNotBlank() }.distinct().sorted()
        if (wanted.isEmpty()) return ScopedDiff("", emptyList())
        val bounded = wanted.take(SCOPE_LIMIT)
        if (wanted.size > bounded.size) notices += "Diff scoped to the first ${bounded.size} of ${wanted.size} changed paths"
        val untracked = (git(*(listOf("ls-files", "-z", "--others", "--exclude-standard", "--") + CandidateSourceInputs.paths).toTypedArray()) ?: "")
            .split(NUL).filter { it.isNotEmpty() }.toSet()
        val base = git("rev-parse", "HEAD")?.trim() ?: "--cached"
        val text = StringBuilder()
        // Chunked because a path list is argv, and a turn that touches hundreds of files would
        // otherwise run into the platform's argument limit and produce nothing at all.
        bounded.filter { it !in untracked }.chunked(ARGV_CHUNK).forEach { chunk ->
            val part = git(*(listOf("diff", "--no-ext-diff", "--no-textconv", "--no-color", base, "--") + chunk + CandidateSourceInputs.exclusions).toTypedArray())
            if (part == null) notices += "Diff unavailable for ${chunk.size} tracked ${if (chunk.size == 1) "path" else "paths"}"
            else text.append(part)
        }
        bounded.filter { it in untracked }.forEach { name ->
            val addition = git("diff", "--no-index", "--no-ext-diff", "--no-textconv", "--no-color", "--", "/dev/null", name)
            if (addition == null) notices += "New-file diff unavailable: $name" else text.append(addition)
        }
        return ScopedDiff(text.toString(), notices)
    }

    /**
     * A fingerprint of every file git currently considers dirty, for telling one turn's work apart.
     *
     * [capture] answers "how does this tree differ from HEAD", which is the right question for a
     * candidate and the wrong one for a report: in a checkout carrying other uncommitted work it
     * names a hundred files the agent never touched, and the planner is told the agent changed them
     * all. Comparing two of these instead answers "what did *this* turn do".
     *
     * Only the dirty set is fingerprinted, because a file the agent edits necessarily becomes dirty:
     * a clean file it touches enters the set, a dirty file it edits changes digest, one it reverts
     * leaves. Fingerprinting the whole tree would also be correct, and far too slow to run twice a
     * cycle on a real repository.
     */
    fun dirtyManifest(): SourceManifest {
        val status = git(*(listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + CandidateSourceInputs.paths).toTypedArray())
            ?: return SourceManifest(emptyMap(), available = false)
        // Porcelain v1 with -z emits "XY path" records separated by NUL; a rename or copy adds a
        // second record holding the original path, which is not itself a changed file.
        val records = status.split(NUL).filter { it.length > 3 }
        val paths = mutableListOf<String>()
        var index = 0
        while (index < records.size) {
            val record = records[index]
            paths += record.substring(3)
            if (record[0] == 'R' || record[0] == 'C') index++
            index++
        }
        val distinct = paths.distinct().sorted()
        val entries = distinct.take(MANIFEST_LIMIT).associateWith { name ->
            val file = File(root, name)
            when {
                Files.isSymbolicLink(file.toPath()) ->
                    "link:" + runCatching { Files.readSymbolicLink(file.toPath()).toString() }.getOrDefault("?")
                !file.exists() -> "absent"
                !file.isFile -> "directory"
                // Too big to hash twice a cycle; size and mtime still move when it is edited.
                file.length() > MANIFEST_FILE_LIMIT -> "large:" + file.length() + ":" + file.lastModified()
                else -> contentDigest(file.readBytes())
            }
        }
        return SourceManifest(entries, available = true, truncated = distinct.size > MANIFEST_LIMIT)
    }

    private fun contentDigest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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

/** A diff narrowed to one turn's paths, honest about what narrowing it cost. */
data class ScopedDiff(val text: String, val notices: List<String>)

internal object CandidateSourceInputs {
    private val renders = listOf("png", "jpg", "jpeg", "webp", "xml", "json", "html", "txt", "properties", "zip", "mp4", "pdf")
    val exclusions = listOf(".claude/worktrees", ".claude/worktrees/**", ".reaktor/libexec", ".reaktor/libexec/**").map { ":(top,exclude,glob)$it" } +
        listOf("agent", "reports").flatMap { root -> renders.map { extension -> ":(top,exclude,glob)$root/*[0-9][0-9][0-9][0-9]*/**/*.$extension" } }
    val paths = listOf(".") + exclusions
}

private const val MANIFEST_LIMIT = 5000
/** Above this a file is fingerprinted by size. Source files are far below it; binaries are not. */
private const val CONTENT_LIMIT = 1_000_000L
private const val SCOPE_LIMIT = 400
private const val ARGV_CHUNK = 100
private const val MANIFEST_FILE_LIMIT = 8_000_000L
private const val NUL = '\u0000'
