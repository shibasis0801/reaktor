package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.appserver.stopNativeProcess
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * What Antigravity will let this workspace's executor run, read before a turn rather than after.
 *
 * A headless turn cannot answer a permission prompt, so an ungranted command does not pause — it is
 * refused, the turn ends, and the refusal names only the action *kind*. Discovering that costs a
 * full cycle each time, which on a repository where one cycle is a Gradle build is the most
 * expensive way possible to learn that a rule is missing. Asking first costs one cheap subprocess.
 *
 * This reports; it never edits. The grants are the operator's security policy, and a coding agent
 * silently widening them is precisely the thing nobody should be able to do by accident.
 */
object AntigravityGrants {

    /** One `command(...)` rule, as Antigravity prints it. */
    data class Grant(val scope: String, val command: String)

    /**
     * Reads the allow list via the CLI's own answer, so it reflects every scope it merges rather
     * than whichever settings file this code happens to know about.
     */
    fun allowed(binary: String = "agy", timeoutSeconds: Long = 30): List<Grant>? = runCatching {
        val output = Files.createTempFile("antigravity-grants-", ".txt")
        var process: Process? = null
        try {
            process = ProcessBuilder(listOf(binary, "--print=/permissions", "--output-format", "text"))
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS) || process.exitValue() != 0 || Files.size(output) > 1048576)
                return@runCatching null
            Files.readString(output).lineSequence().mapNotNull { line ->
                // scope <tab> allow <tab> command(git status)
                val parts = line.split('\t')
                if (parts.size < 3 || parts[1].trim() != "allow") return@mapNotNull null
                val rule = parts[2].trim()
                if (!rule.startsWith("command(") || !rule.endsWith(")")) return@mapNotNull null
                Grant(parts[0].trim(), rule.removePrefix("command(").removeSuffix(")"))
            }.toList()
        } finally {
            process?.takeIf { it.isAlive }?.let(::stopNativeProcess)
            Files.deleteIfExists(output)
        }
    }.getOrNull()

    /**
     * Which of [needed] no rule covers.
     *
     * Rules match by prefix on the command line — `command(./gradlew)` covers every Gradle
     * invocation — so a need is met when any grant is a prefix of it at a word boundary. Returns
     * null when the allow list could not be read at all, which is not the same as nothing missing.
     */
    fun missing(needed: List<String>, binary: String = "agy"): List<String>? {
        return missing(needed, allowed(binary) ?: return null)
    }

    private fun missing(needed: List<String>, allowed: List<Grant>): List<String> {
        val grants = allowed.map { it.command }
        return needed.filterNot { need ->
            grants.any { grant -> need == grant || need.startsWith("$grant ") }
        }
    }

    /**
     * The commands a writing turn in a Gradle workspace normally reaches for.
     *
     * Deliberately read-only where git is concerned: `git add` is included because agents stage what
     * they write, and `checkout`, `reset`, `clean` and `stash` are not, because a workspace carrying
     * uncommitted work has more to lose from those than any turn could gain.
     */
    fun expectedFor(workspace: File, writes: Boolean): List<String> = buildList {
        add("git status"); add("git diff"); add("git log")
        if (writes) {
            add("git add")
            if (File(workspace, "gradlew").canExecute()) add("./gradlew")
        }
    }

    /** A line an operator can act on, or null when nothing is missing or nothing could be read. */
    fun advisory(workspace: File, writes: Boolean, binary: String = "agy"): String? {
        val absent = missing(expectedFor(workspace, writes), allowed(binary, timeoutSeconds = 2) ?: return null)
        if (absent.isEmpty()) return null
        return "Antigravity has no rule for: " + absent.joinToString(", ") + ". A headless turn cannot " +
            "answer a permission prompt, so these are refused rather than asked about. Add them to " +
            "~/.gemini/antigravity-cli/settings.json under permissions.allow as " +
            absent.joinToString(", ") { "command($it)" } + " — rules match by prefix."
    }
}
