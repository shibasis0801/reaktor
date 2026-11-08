package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class GeneratedOutputBoundaryTest {
    @TempDir lateinit var directory: File

    private fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace")

    @Test fun `Prefab extraction clears only children of its owned output`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"boundaryFixture\"")
        directory.resolve("preserved.txt").writeText("source")
        ZipOutputStream(directory.resolve("input.aar").outputStream()).use {
            it.putNextEntry(ZipEntry("prefab/modules/sample/include/source.h")); it.write("header".toByteArray()); it.closeEntry()
        }
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.tasks.ExtractPrefabTask
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<ExtractPrefabTask>("extractPrefab") {
                archiveFiles.from("input.aar"); moduleName.set("sample"); outputDirectory.set(layout.buildDirectory.dir("prefab"))
            }
        """.trimIndent())
        directory.resolve("build/prefab/obsolete").apply { parentFile.mkdirs(); writeText("old") }
        val first = runner("extractPrefab").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":extractPrefab")?.outcome)
        assertEquals("source", directory.resolve("preserved.txt").readText())
        assertEquals("header", directory.resolve("build/prefab/include/source.h").readText())
        assertFalse(directory.resolve("build/prefab/obsolete").exists())
        val repeated = runner("extractPrefab").build()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":extractPrefab")?.outcome)
        val protected = directory.resolve("protected").apply { mkdirs(); resolve("keep.txt").writeText("preserve") }
        val output = directory.resolve("build/prefab")
        output.walkBottomUp().forEach { it.delete() }
        Files.createSymbolicLink(output.toPath(), protected.toPath())
        val failed = runner("extractPrefab", "--rerun-tasks").buildAndFail()
        assertContains(failed.output, "Refusing to clear through symbolic link")
        assertEquals("source", directory.resolve("preserved.txt").readText())
        assertEquals("preserve", protected.resolve("keep.txt").readText())
        Files.delete(output.toPath())
    }
}
