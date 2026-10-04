package dev.shibasis.reaktor.tooling.git

import java.io.File

data class WorkspaceRevision(val branch: String, val shortSha: String?)

fun readWorkspaceRevision(root: File): WorkspaceRevision? {
    val gitDir = gitDirectoryOf(root) ?: return null
    val head = runCatching { File(gitDir, "HEAD").readText().trim() }.getOrNull() ?: return null

    // Detached HEAD holds the sha itself; otherwise it points at the ref for the current branch.
    val ref = head.removePrefix("ref:").trim().takeIf { head.startsWith("ref:") }
        ?: return WorkspaceRevision(branch = "detached", shortSha = head.take(SHORT_SHA))

    val branch = ref.removePrefix("refs/heads/")
    val common = commonGitDirectory(gitDir)
    val sha = runCatching { File(gitDir, ref).readText().trim() }.getOrNull()
        ?: runCatching { File(common, ref).readText().trim() }.getOrNull()
        ?: packedRefSha(common, ref)
    return WorkspaceRevision(branch = branch, shortSha = sha?.take(SHORT_SHA))
}

fun readWorkspaceRemotes(root: File): List<String> {
    val gitDir = gitDirectoryOf(root) ?: return emptyList()
    return runCatching {
        var remote = false
        File(commonGitDirectory(gitDir), "config").readLines().mapNotNull { line ->
            val entry = line.trim()
            if (entry.startsWith("[")) remote = entry.startsWith("[remote ")
            if (remote && entry.startsWith("url") && entry.substringAfter("url").trimStart().startsWith("="))
                entry.substringAfter('=').trim().removeSurrounding("\"").takeIf(String::isNotBlank)
            else null
        }
    }.getOrDefault(emptyList())
}

private fun commonGitDirectory(gitDir: File): File = runCatching {
    val pointer = File(gitDir, "commondir").takeIf(File::isFile)?.readText()?.trim()
        ?: return@runCatching gitDir
    File(pointer).let { if (it.isAbsolute) it else File(gitDir, pointer) }
}.getOrDefault(gitDir)

/** Walks up from the workspace to the checkout, so a module directory still reports its branch. */
private fun gitDirectoryOf(root: File): File? {
    var current: File? = root.absoluteFile
    while (current != null) {
        val candidate = File(current, ".git")
        when {
            candidate.isDirectory -> return candidate
            // A worktree or submodule records its real git directory in a `gitdir:` pointer file.
            candidate.isFile -> {
                val pointer = runCatching { candidate.readText().trim() }.getOrNull()
                    ?.removePrefix("gitdir:")?.trim()
                    ?: return null
                val resolved = File(pointer).let { if (it.isAbsolute) it else File(current, pointer) }
                return resolved.takeIf { it.isDirectory }
            }
        }
        current = current.parentFile
    }
    return null
}

/** A branch that has not moved since it was packed has no loose ref file. */
private fun packedRefSha(gitDir: File, ref: String): String? = runCatching {
    File(gitDir, "packed-refs").useLines { lines ->
        lines.mapNotNull { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 2 && parts[1] == ref) parts[0] else null
        }.firstOrNull()
    }
}.getOrNull()

private const val SHORT_SHA = 7
