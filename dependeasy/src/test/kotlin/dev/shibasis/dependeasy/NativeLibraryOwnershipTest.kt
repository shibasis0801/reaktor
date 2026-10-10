package dev.shibasis.dependeasy

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class NativeLibraryOwnershipTest {
    @TempDir lateinit var directory: File

    @Test fun `native definitions follow current targets and archive changes without collecting orphan files`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"nativeOwnership\"")
        val sources = directory.resolve("cpp").apply { mkdirs() }
        sources.resolve("main.cpp").writeText("extern int support(); int sample() { return support(); }\n")
        val support = sources.resolve("support.cpp").apply { writeText("int support() { return 42; }\n") }
        fun declare(name: String) = sources.resolve("CMakeLists.txt").writeText("""
            cmake_minimum_required(VERSION 3.22)
            project(sample LANGUAGES CXX)
            add_library($name STATIC support.cpp)
            add_library(sample STATIC main.cpp)
            target_link_libraries(sample PUBLIC $name)
        """.trimIndent())
        declare("oldSupport")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.tasks.GenerateNativeDefTask
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                val compile = cmake("sample") { source = "cpp" }
                tasks.register<GenerateNativeDefTask>("definition") {
                    staticLibraryManifests.from(compile.flatMap { it.staticLibrariesManifest })
                    outputFile.set(layout.buildDirectory.file("sample.def"))
                    dependsOn(compile)
                }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("definition", "--configuration-cache", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, run().task(":definition")?.outcome)
        val definition = directory.resolve("build/sample.def")
        assertContains(definition.readText(), "liboldSupport.a")
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":definition")?.outcome)
        declare("currentSupport")
        assertEquals(TaskOutcome.SUCCESS, run().task(":definition")?.outcome)
        assertTrue(directory.resolve("build/dependeasy/native/sample/Release/liboldSupport.a").isFile)
        assertContains(definition.readText(), "libcurrentSupport.a")
        assertFalse("liboldSupport.a" in definition.readText())
        val before = definition.readText()
        support.writeText("int support() { return 43; }\n")
        assertEquals(TaskOutcome.SUCCESS, run().task(":definition")?.outcome)
        assertEquals(before, definition.readText())
    }
}
