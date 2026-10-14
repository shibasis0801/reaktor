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

    @Test fun `SDK generation and prefab extraction clear only children of owned outputs`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"boundaryFixture\"")
        directory.resolve("preserved.txt").writeText("source")
        val generator = directory.resolve("generator").apply {
            writeText("#!/bin/sh\nwhile [ \"$#\" -gt 0 ]; do if [ \"$1\" = --out ]; then shift; mkdir -p \"$1/sdk\"; printf generated > \"$1/sdk/value\"; break; fi; shift; done\n")
            setExecutable(true)
        }
        ZipOutputStream(directory.resolve("input.aar").outputStream()).use {
            it.putNextEntry(ZipEntry("prefab/modules/sample/include/source.h")); it.write("header".toByteArray()); it.closeEntry()
        }
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.codegen.PulumiSdkTask
            import dev.shibasis.dependeasy.tasks.ExtractPrefabTask
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<PulumiSdkTask>("generateSdk") {
                providerName.set("fixture"); providerVersion.set("1"); server.set("example.invalid")
                executable.set(layout.projectDirectory.file("generator").asFile.absolutePath); toolVersion.set("fixture")
                outputDirectory.set(layout.buildDirectory.dir("sdk"))
            }
            tasks.register<ExtractPrefabTask>("extractPrefab") {
                archiveFiles.from("input.aar"); moduleName.set("sample"); outputDirectory.set(layout.buildDirectory.dir("prefab"))
            }
        """.trimIndent())
        for (name in listOf("sdk", "prefab")) {
            directory.resolve("build/$name/obsolete").apply { parentFile.mkdirs(); writeText("old") }
        }
        val first = runner("generateSdk", "extractPrefab").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateSdk")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":extractPrefab")?.outcome)
        assertEquals("source", directory.resolve("preserved.txt").readText())
        assertEquals("header", directory.resolve("build/prefab/include/source.h").readText())
        assertEquals("generated", directory.resolve("build/sdk/sdk/value").readText())
        for (name in listOf("sdk", "prefab")) assertFalse(directory.resolve("build/$name/obsolete").exists())
        val repeated = runner("generateSdk", "extractPrefab").build()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":generateSdk")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":extractPrefab")?.outcome)
        val protected = directory.resolve("protected").apply { mkdirs(); resolve("keep.txt").writeText("preserve") }
        for (name in listOf("sdk", "prefab")) {
            val output = directory.resolve("build/$name")
            output.walkBottomUp().forEach { it.delete() }
            Files.createSymbolicLink(output.toPath(), protected.toPath())
            val failed = runner(if (name == "sdk") "generateSdk" else "extractPrefab", "--rerun-tasks").buildAndFail()
            assertContains(failed.output, "Refusing to clear through symbolic link")
            assertEquals("source", directory.resolve("preserved.txt").readText())
            assertEquals("preserve", protected.resolve("keep.txt").readText())
            Files.delete(output.toPath())
        }
    }
}
