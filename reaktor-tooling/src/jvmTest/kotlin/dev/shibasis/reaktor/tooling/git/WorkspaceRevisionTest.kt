package dev.shibasis.reaktor.tooling.git

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceRevisionTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    @Test fun moduleDirectoriesReadTheCheckoutRevisionAndRemoteSections() {
        val root = Files.createTempDirectory("git-checkout").toFile()
        try {
            val git = File(root, ".git").apply { mkdirs() }
            metadata(git)
            val module = File(root, "modules/app").apply { mkdirs() }
            assertEquals(WorkspaceRevision("topic", "0123456"), readWorkspaceRevision(module))
            assertEquals(listOf("git@github.com:example/project.git"), readWorkspaceRemotes(module))
        } finally { root.deleteRecursively() }
    }

    @Test fun linkedWorktreesReadTheirBranchFromSharedLooseRefs() {
        val root = Files.createTempDirectory("git-linked").toFile()
        try {
            val (worktree, _) = linked(root)
            assertEquals(WorkspaceRevision("topic", "0123456"), readWorkspaceRevision(worktree))
            assertEquals(listOf("git@github.com:example/project.git"), readWorkspaceRemotes(worktree))
        } finally { root.deleteRecursively() }
    }

    @Test fun linkedWorktreesReadSharedPackedRefsAndDetachedHeads() {
        val root = Files.createTempDirectory("git-packed").toFile()
        try {
            val (worktree, common) = linked(root)
            File(common, "refs/heads/topic").delete()
            File(common, "packed-refs").writeText("$sha refs/heads/topic\n")
            assertEquals(WorkspaceRevision("topic", "0123456"), readWorkspaceRevision(worktree))
            File(common, "worktrees/linked/HEAD").writeText("$sha\n")
            assertEquals(WorkspaceRevision("detached", "0123456"), readWorkspaceRevision(worktree))
        } finally { root.deleteRecursively() }
    }

    private fun linked(root: File): Pair<File, File> {
        val common = File(root, "shared.git").apply { mkdirs() }
        metadata(common)
        val git = File(common, "worktrees/linked").apply { mkdirs() }
        File(git, "commondir").writeText("../..\n")
        File(git, "HEAD").writeText("ref: refs/heads/topic\n")
        val worktree = File(root, "checkout").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: ../shared.git/worktrees/linked\n")
        return worktree to common
    }

    private fun metadata(git: File) {
        File(git, "refs/heads").mkdirs()
        File(git, "HEAD").writeText("ref: refs/heads/topic\n")
        File(git, "refs/heads/topic").writeText("$sha\n")
        File(git, "config").writeText("""
            [remote "origin"]
                url = "git@github.com:example/project.git"
            [submodule "dependency"]
                url = https://example.test/dependency.git
        """.trimIndent())
    }
}
