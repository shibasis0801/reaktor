package dev.shibasis.dependeasy.settings

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/** Source preparation is a task, pinned to a commit; existing worktrees are never reset. */
@DisableCachingByDefault(because = "Git checkout metadata is host-specific")
abstract class GitSourceTask : DefaultTask() {
    @get:Input abstract val repository: Property<String>
    @get:Input abstract val revision: Property<String>
    @get:OutputDirectory abstract val checkout: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction fun prepare() {
        val directory = checkout.get().asFile
        val commit = revision.get()
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "Native sources require a full commit SHA" }
        if (!directory.resolve(".git").exists()) {
            if (directory.exists()) {
                if (directory.list()?.isNotEmpty() != false) throw GradleException(
                    "Native source $directory is not a Git checkout; preserve its contents before preparing pinned sources"
                )
                // Gradle creates @OutputDirectory before the action. Remove only that empty directory.
                Files.delete(directory.toPath())
            }
            directory.parentFile.mkdirs()
            val temporary = Files.createTempDirectory(directory.parentFile.toPath(), ".${directory.name}-").toFile()
            try {
                git("init", temporary.absolutePath)
                git("-C", temporary.absolutePath, "remote", "add", "origin", repository.get())
                git("-C", temporary.absolutePath, "fetch", "--depth=1", "origin", commit)
                git("-C", temporary.absolutePath, "checkout", "--detach", "FETCH_HEAD")
                Files.move(temporary.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.deleteTreeSafely(within = directory.parentFile)
            }
        }
        val actual = git("-C", directory.absolutePath, "rev-parse", "HEAD").trim()
        val changes = git("-C", directory.absolutePath, "status", "--porcelain", "--untracked-files=no").trim()
        if (changes.isNotEmpty()) throw GradleException("Native source $directory has local edits; preserve them before building a pinned artifact")
        if (actual != commit) throw GradleException(
            "Native source $directory is at $actual, expected $commit. Preserve local work before replacing the checkout."
        )
    }

    private fun git(vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            executable = "git"
            args(*arguments)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8)
    }
}
