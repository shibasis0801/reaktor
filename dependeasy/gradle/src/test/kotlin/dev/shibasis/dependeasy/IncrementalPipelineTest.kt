package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class IncrementalPipelineTest {
    @TempDir lateinit var directory: File

    @Test fun `artifact edges rebuild on changes and reuse configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"".replace("\\", ""))
        directory.resolve("source.txt").writeText("first")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.dag.artifact
            import dev.shibasis.dependeasy.process.CommandTask
            import dev.shibasis.dependeasy.plugins.DependeasyExtension
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val copy = tasks.register<CommandTask>("copySource") {
                workingDirectory.set(layout.projectDirectory)
                executable.set("/bin/cp")
                toolVersion.set("system")
                arguments.set(listOf("source.txt", "result.txt"))
                sourceFiles.from("source.txt")
                outputFiles.from("result.txt")
            }
            val compile = tasks.register<CommandTask>("compileArtifact") {
                workingDirectory.set(layout.projectDirectory)
                executable.set("/bin/cp")
                toolVersion.set("system")
                arguments.set(listOf("result.txt", "compiled.txt"))
                outputFiles.from("compiled.txt")
            }
            val generated = copy.artifact { layout.projectDirectory.file("result.txt").let { file -> providers.provider { file } } }
            extensions.configure<DependeasyExtension> {
                dag("copy") { target("copyBuild", node(compile).consumes(generated)) }
            }
        """.trimIndent())
        fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
            .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace").build()
        assertNull(run("help").task(":copySource"))
        assertFalse(directory.resolve("result.txt").exists())
        assertEquals(TaskOutcome.SUCCESS, run("copyBuild").task(":copySource")?.outcome)
        val repeat = run("copyBuild")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":copySource")?.outcome)
        assertContains(repeat.output, "Reusing configuration cache")
        directory.resolve("source.txt").writeText("second")
        assertEquals(TaskOutcome.SUCCESS, run("copyBuild").task(":copySource")?.outcome)
        assertEquals("second", directory.resolve("compiled.txt").readText())
        directory.resolve("compiled.txt").delete()
        val missing = run("copyBuild")
        assertEquals(TaskOutcome.UP_TO_DATE, missing.task(":copySource")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, missing.task(":compileArtifact")?.outcome)
        assertEquals("second", directory.resolve("compiled.txt").readText())
    }
}
