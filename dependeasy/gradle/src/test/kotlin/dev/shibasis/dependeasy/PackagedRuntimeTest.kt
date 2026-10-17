package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.desktop.sharedClassArchive
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class PackagedRuntimeTest {
    @TempDir lateinit var directory: File

    @Test fun `archive identities follow jar content and access flags without retaining stale flags`() {
        val jar = directory.resolve("runtime.jar").apply { writeText("first") }
        val arguments = listOf("-Xms768m", "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED")
        fun identity(flags: List<String>) = sharedClassArchive(flags, "/cache", "app", listOf(jar))
            .single { it.startsWith("-XX:SharedArchiveFile=") }
        val first = sharedClassArchive(arguments, "/cache", "app", listOf(jar))
        assertEquals(identity(arguments), identity(arguments.reversed()))
        assertNotEquals(identity(arguments), identity(arguments + "--add-opens=java.base/java.io=ALL-UNNAMED"))
        assertEquals(first, sharedClassArchive(first, "/cache", "app", listOf(jar)))
        val before = identity(arguments)
        jar.writeText("second")
        assertNotEquals(before, identity(arguments))
        assertEquals(1, first.count { it.startsWith("-XX:SharedArchiveFile=") })
        assertContains(first, "-Xms768m")
    }

    @Test fun `packaged launchers remain executable and reuse configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"runtimeFixture\"")
        directory.resolve("jdk/lib/module").apply { parentFile.mkdirs(); writeText("fixture") }
        directory.resolve("jdk/bin/java").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nprintf '%s' \"\$*\" > \"\${0%/bin/java}/class-sharing-arguments\"\n")
            setExecutable(true)
        }
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.desktop.retainJavaLauncher
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val sdk = layout.projectDirectory.dir("jdk").asFile.absolutePath
            tasks.register<Sync>("image") {
                from("jdk/lib")
                into(layout.buildDirectory.dir("runtime"))
                retainJavaLauncher(providers.provider { sdk }, layout.buildDirectory.dir("runtime").let {
                    objects.directoryProperty().value(it)
                }, providers.gradleProperty("sharing").map(String::toBoolean).orElse(true))
            }
        """.trimIndent())
        fun run(vararg extra: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("image", "--configuration-cache", "--stacktrace", *extra).build()
        run()
        val runtime = directory.resolve("build/runtime")
        assertTrue(runtime.resolve("bin/java").canExecute())
        assertEquals("-Xshare:dump", runtime.resolve("class-sharing-arguments").readText())
        assertContains(run().output, "Reusing configuration cache")
        run("-Psharing=false")
        assertTrue(runtime.resolve("bin/java").canExecute())
        assertFalse(runtime.resolve("class-sharing-arguments").exists())
    }
}
