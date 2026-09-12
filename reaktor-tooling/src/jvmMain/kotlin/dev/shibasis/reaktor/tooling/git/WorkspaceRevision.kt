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
    val sha = runCatching { File(gitDir, ref).readText().trim() }.getOrNull()
        ?: packedRefSha(gitDir, ref)
    return WorkspaceRevision(branch = branch, shortSha = sha?.take(SHORT_SHA))
}

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
