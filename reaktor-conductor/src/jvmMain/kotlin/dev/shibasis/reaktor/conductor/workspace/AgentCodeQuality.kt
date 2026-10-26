package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object AgentCodeQuality {
    fun measure(records: List<Pair<String, AgentLanding>>, modules: (List<String>) -> List<String>, now: Long): AgentChurnReport {
        val notices = mutableListOf("Churn follows added text lines through first-parent Git history, including file renames. It measures later edits, not their cause or code quality. Earlier landings without a retained receipt are not inferred.")
        val files = records.flatMap { (task, landing) -> landing.commits.flatMap { (source, commit) ->
            val root = File(source)
            runCatching {
                require(commit.matches(Regex("[a-f0-9]{40,64}")))
                val paths = git(root, "diff-tree", "--root", "--no-commit-id", "--name-only", "-r", "-z", "--no-renames", commit)
                    .split('\u0000').filter(String::isNotBlank)
                require(paths.size <= 200) { "Landing exceeds the 200-file measurement bound" }
                val history = git(root, "log", "--first-parent", "--format=%H %ct", "-5001", "HEAD").lineSequence()
                    .filter(String::isNotBlank).map { line -> line.substringBefore(' ') to line.substringAfter(' ').toLong() * 1000 }.toList()
                val index = history.indexOfFirst { it.first == commit }
                paths.map { path ->
                    val added = runCatching { addedLines(git(root, "show", "--format=", "--no-ext-diff", "--no-textconv", "--unified=0", "--no-renames", commit, "--", path)) }
                    val windows = listOf(14, 30).map { days ->
                        val until = landing.at + days * 86_400_000L
                        val mature = now >= until
                        if (!mature) AgentChurnWindow(days, added.getOrNull()?.size, null, false, "Window has not elapsed")
                        else runCatching {
                            val lines = added.getOrThrow()
                            require(index >= 0) { "Landing is unavailable on HEAD's retained first-parent history" }
                            val descendants = history.take(index + 1)
                            require(descendants.last().second <= until) { "Landing commit date is later than the window" }
                            val tip = descendants.firstOrNull { it.second <= until }?.first ?: error("Window endpoint unavailable")
                            val survived = if (tip == commit || lines.isEmpty()) lines else {
                                val blame = git(root, "blame", "--reverse", "--first-parent", "-M1", "--line-porcelain", "$commit..$tip", "--", path)
                                val observed = Regex("(?m)^([a-f0-9]{40,64}) [0-9]+ ([0-9]+)(?: [0-9]+)?$").findAll(blame)
                                    .associate { it.groupValues[2].toInt() to it.groupValues[1] }
                                require(lines.all { it in observed }) { "Git did not account for every landed line" }
                                lines.filterTo(mutableSetOf()) { observed[it] == tip }
                            }
                            AgentChurnWindow(days, lines.size, lines.size - survived.size, true)
                        }.getOrElse { AgentChurnWindow(days, added.getOrNull()?.size, null, true, it.message ?: "History unavailable") }
                    }
                    AgentChurnFile(task, landing.candidate, landing.seat?.seat?.profile, landing.seat?.profileRevision,
                        landing.seat?.seat?.agent?.name, source, commit, path, modules(listOf(File(root, path).canonicalPath)), windows)
                }
            }.getOrElse {
                notices += "$task / ${root.name} / ${commit.take(12)}: ${it.message ?: "History unavailable"}"
                listOf(AgentChurnFile(task, landing.candidate, landing.seat?.seat?.profile, landing.seat?.profileRevision,
                    landing.seat?.seat?.agent?.name, source, commit, "(history unavailable)", emptyList(),
                    listOf(14, 30).map { days -> AgentChurnWindow(days, null, null, now >= landing.at + days * 86_400_000L, "History unavailable") }))
            }
        } }
        return AgentChurnReport(now, files, notices)
    }

    private fun addedLines(diff: String): Set<Int> {
        require("GIT binary patch" !in diff && !Regex("(?m)^Binary files ").containsMatchIn(diff)) { "Binary content is not line measurable" }
        val result = mutableSetOf<Int>()
        var line: Int? = null
        diff.lineSequence().forEach { text ->
            val hunk = Regex("^@@ -[0-9]+(?:,[0-9]+)? \\+([0-9]+)(?:,[0-9]+)? @@").find(text)
            if (hunk != null) line = hunk.groupValues[1].toInt()
            else if (line != null) when {
                text.startsWith('+') -> { result += requireNotNull(line); line = requireNotNull(line) + 1 }
                text.startsWith(' ') -> line = requireNotNull(line) + 1
            }
        }
        return result
    }

    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "--no-optional-locks", "-C", root.canonicalPath) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val reader = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(8_000_001) } }
        try {
            val bytes = reader.get(15, TimeUnit.SECONDS)
            require(bytes.size <= 8_000_000) { "Git output exceeds measurement bound" }
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "Git history query failed" }
            return bytes.decodeToString()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
