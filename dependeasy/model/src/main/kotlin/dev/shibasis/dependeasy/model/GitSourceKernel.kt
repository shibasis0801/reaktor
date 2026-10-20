package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object GitSourceKernel {
    fun prepare(spec: KernelSpec.GitSource, execute: (List<String>) -> String) {
        val directory = File(spec.checkout)
        val commit = spec.revision
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "Native sources require a full commit SHA" }
        if (!directory.resolve(".git").exists()) {
            if (directory.exists()) {
                if (directory.list()?.isNotEmpty() != false) throw IllegalStateException(
                    "Native source $directory is not a Git checkout; preserve its contents before preparing pinned sources"
                )
                Files.delete(directory.toPath())
            }
            directory.parentFile.mkdirs()
            val temporary = Files.createTempDirectory(directory.parentFile.toPath(), ".${directory.name}-").toFile()
            try {
                execute(Git.initialize(temporary.path))
                execute(Git.origin(temporary.path, spec.repository))
                execute(Git.fetch(temporary.path, commit))
                execute(Git.checkout(temporary.path))
                Files.move(temporary.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.deleteTreeSafely(within = directory.parentFile)
            }
        }
        val actual = execute(Git.revision(directory.path)).trim()
        val changes = execute(Git.changes(directory.path)).trim()
        if (changes.isNotEmpty()) throw IllegalStateException("Native source $directory has local edits; preserve them before building a pinned artifact")
        if (actual != commit) throw IllegalStateException(
            "Native source $directory is at $actual, expected $commit. Preserve local work before replacing the checkout."
        )
    }

}
