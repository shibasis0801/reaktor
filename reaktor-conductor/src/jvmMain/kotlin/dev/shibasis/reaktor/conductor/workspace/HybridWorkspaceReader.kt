package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Read-only sight into one workspace, for the half of the seat that cannot open a file.
 *
 * The planner writes instructions and judges what came back, and until now it did both from prose:
 * a packet describing the task and the executor's own summary of what it did. That is the whole
 * quality gap against Codex or Claude Code, which read the code they are reasoning about. Every
 * defect this seat has shipped — text scaled by the square of the zoom, a status-bar parameter no
 * caller passed, two keyboard shortcuts that never fired — was plainly visible in the diff and
 * invisible in the summary.
 *
 * So this exists to let the planner look, and to let it look at nothing else. The root is supplied
 * by the caller from a task the planner already holds, every path is resolved through the real
 * filesystem and required to stay underneath it, and there is no write anywhere in this file. What
 * comes back is repository content: it is data the planner reads, never instruction it obeys.
 */
internal class HybridWorkspaceReader(private val root: File) {

    private val canonicalRoot = root.canonicalFile

    /**
     * Resolves a workspace-relative path, refusing anything that leaves the workspace.
     *
     * Canonicalisation happens before the check, so `..` segments and symlinks that point outside
     * are both caught by the same test rather than by a pattern that has to anticipate them.
     */
    private fun resolve(path: String): File {
        require(path.length <= 1024) { "Path is too long" }
        val candidate = File(canonicalRoot, path.removePrefix("/")).canonicalFile
        require(candidate == canonicalRoot || candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            "Path is outside the workspace"
        }
        return candidate
    }

    fun read(path: String, offset: Int, limit: Int): ReadResult {
        val file = resolve(path)
        require(file.isFile) { "Not a file: $path" }
        require(file.length() <= MAX_FILE_BYTES) {
            "File is ${file.length()} bytes; this surface reads files up to $MAX_FILE_BYTES"
        }
        // Binary files waste the planner's context and tell it nothing, so they are named rather
        // than dumped.
        val head = file.inputStream().use { it.readNBytes(8000) }
        require(head.none { byte -> byte == 0.toByte() }) { "File looks binary: $path" }
        val lines = file.readLines()
        val from = offset.coerceIn(0, maxOf(0, lines.size - 1))
        val slice = lines.drop(from).take(limit.coerceIn(1, MAX_LINES))
        return ReadResult(
            path = relative(file),
            totalLines = lines.size,
            firstLine = from + 1,
            text = slice.mapIndexed { index, line -> "${from + index + 1}\t$line" }.joinToString("\n"),
            truncated = from + slice.size < lines.size,
        )
    }

    fun list(path: String): List<String> {
        val directory = resolve(path)
        require(directory.isDirectory) { "Not a directory: $path" }
        return directory.listFiles().orEmpty()
            .filterNot { it.name == ".git" }
            .sortedBy { it.name }
            .take(MAX_ENTRIES)
            .map { if (it.isDirectory) relative(it) + "/" else relative(it) }
    }

    /**
     * Searches the workspace, preferring ripgrep and falling back to `git grep`.
     *
     * ripgrep honours `.gitignore` for free, which keeps build output and vendored trees — the bulk
     * of any real checkout — out of the planner's view without an exclusion list that would go
     * stale. But it is not guaranteed to exist, and it is not guaranteed to be on the PATH of the
     * process that happens to be hosting this workspace: the JVM launched from a build daemon has a
     * different PATH than the shell that installed it, which is exactly how this failed first time.
     * `git grep` is always there, because the workspace is a git checkout by construction.
     */
    fun search(query: String, glob: String?, limit: Int): SearchResult {
        require(query.isNotBlank() && query.length <= 500) { "Search needs a query of at most 500 characters" }
        val capped = limit.coerceIn(1, MAX_MATCHES)
        val ripgrep = ripgrep()
        val argv = if (ripgrep != null) buildList {
            add(ripgrep); add("--line-number"); add("--no-heading"); add("--color"); add("never")
            add("--max-columns"); add("400"); add("--max-count"); add("20"); add("--fixed-strings")
            glob?.takeIf { it.isNotBlank() }?.let { add("--glob"); add(it) }
            add("--"); add(query); add(".")
        } else buildList {
            add("git"); add("grep"); add("--line-number"); add("--fixed-strings")
            add("--untracked"); add("--no-color"); add("-e"); add(query)
            glob?.takeIf { it.isNotBlank() }?.let { add("--"); add(it) }
        }
        val process = ProcessBuilder(argv).directory(canonicalRoot).redirectErrorStream(false).start()
        val output = try {
            process.inputStream.bufferedReader().useLines { lines -> lines.take(capped + 1).toList() }
        } finally {
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        val matches = output.take(capped).map { it.removePrefix("./").removePrefix(canonicalRoot.path + File.separator) }
        return SearchResult(matches, truncated = output.size > capped, engine = if (ripgrep != null) "ripgrep" else "git grep")
    }

    /** ripgrep wherever it actually is, since the host process may not have inherited a useful PATH. */
    private fun ripgrep(): String? {
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).asSequence()
            .map { File(it, "rg") }
        return (onPath + sequenceOf(File("/opt/homebrew/bin/rg"), File("/usr/local/bin/rg"), File("/usr/bin/rg")))
            .firstOrNull { it.canExecute() }?.path
    }

    private fun relative(file: File) = file.path.removePrefix(canonicalRoot.path + File.separator)

    data class ReadResult(val path: String, val totalLines: Int, val firstLine: Int, val text: String, val truncated: Boolean)

    data class SearchResult(val matches: List<String>, val truncated: Boolean, val engine: String)

    companion object {
        const val MAX_FILE_BYTES = 2_000_000L
        const val MAX_LINES = 1200
        const val MAX_ENTRIES = 500
        const val MAX_MATCHES = 200
    }
}
