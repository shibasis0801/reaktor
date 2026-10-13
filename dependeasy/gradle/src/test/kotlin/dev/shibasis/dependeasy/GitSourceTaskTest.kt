package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class GitSourceTaskTest {
    @TempDir lateinit var directory: File

    private fun git(directory: File, vararg arguments: String): String {
        val process = ProcessBuilder("git", "-C", directory.absolutePath, *arguments)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }

    @Test fun `fresh pinned checkout does not mistake its parent repository for source`() {
        val origin = directory.resolve("origin").apply { mkdirs() }
        git(origin, "init")
        origin.resolve("source.h").writeText("pinned\n")
        git(origin, "add", "source.h")
        git(origin, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture")
        val revision = git(origin, "rev-parse", "HEAD")
        val project = directory.resolve("project").apply { mkdirs() }
        git(project, "init")
        project.resolve("settings.gradle.kts").writeText("rootProject.name = \"sourceFixture\"")
        project.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.settings.GitSourceTask
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<GitSourceTask>("prepareSource") {
                repository.set("${origin.absolutePath}")
                revision.set("$revision")
                checkout.set(layout.projectDirectory.dir(".sources/library"))
            }
        """.trimIndent())
        fun runner() = GradleRunner.create().withProjectDir(project).withPluginClasspath()
            .withArguments("prepareSource", "--configuration-cache", "--stacktrace")
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":prepareSource")?.outcome)
        val checkout = project.resolve(".sources/library")
        assertEquals(revision, git(checkout, "rev-parse", "HEAD"))
        assertEquals(TaskOutcome.UP_TO_DATE, runner().build().task(":prepareSource")?.outcome)
        checkout.resolve("source.h").writeText("local work\n")
        assertContains(runner().buildAndFail().output, "has local edits")
        assertEquals("local work\n", checkout.resolve("source.h").readText())
    }
}
