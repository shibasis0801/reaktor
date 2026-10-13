package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleWiringTest {
    @TempDir lateinit var directory: File

    @Test fun `JVM entry points share ordinary source sets and produce safely quoted commands`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"executionFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.module.arguments
            plugins { id("dev.shibasis.dependeasy.jvm") }
            dependeasy {
                jvm(bytecode = 21)
                val values = providers.gradleProperty("value").map { listOf(it) }
                jvmEntryPoint("probe", "fixture.ProbeKt", "probe", verify = true) { arguments(values) }
                jvmCommand("probeCommand", "fixture.ProbeKt", values, "probe")
            }
        """.trimIndent())
        directory.resolve("src/main/kotlin").mkdirs()
        directory.resolve("src/main/kotlin/Value.kt").writeText("package fixture\nfun prefix() = \"echo:\"")
        directory.resolve("src/probe/kotlin").mkdirs()
        directory.resolve("src/probe/kotlin/Probe.kt").writeText("package fixture\nfun main(args: Array<String>) { println(prefix() + args.single()) }")
        val value = "a space; \$HOME ' apostrophe"
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("probe", "probeCommand", "-Pvalue=$value", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertContains(first.output, "echo:$value")
        val command = first.output.lineSequence().single { it.startsWith("'") && "'fixture.ProbeKt'" in it }
        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
        assertEquals("echo:$value", process.inputStream.bufferedReader().readText().trim())
        assertEquals(0, process.waitFor())
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertContains(repeated.output, "echo:$value")
    }

    @Test fun `binary templates compile large Unicode assets and follow their producer`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"assetFixture\"")
        directory.resolve("payload.bin").writeText("नमस्ते 🌍 \u0000 \"\n".repeat(10_000) + "end")
        directory.resolve("template.kt").writeText("package fixture\nobject Encoded { val data = {{payload}} }")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.jvm") }
            val copyAsset = tasks.register<Sync>("copyAsset") { from("payload.bin"); into("assets") }
            dependeasy {
                jvm(bytecode = 21) { dependencies { testImplementation(kotlin("test-junit")) } }
                kotlinTemplate("Encoded") {
                    namespace = "fixture"
                    template = "template.kt"
                    from(copyAsset)
                    base64("payload", "assets/payload.bin")
                }
                testClasses()
            }
        """.trimIndent())
        directory.resolve("src/test/kotlin").mkdirs()
        directory.resolve("src/test/kotlin/AssetTest.kt").writeText("""
            package fixture
            import kotlin.test.*
            class AssetTest {
                @Test fun completeUnicodeAsset() {
                    val data = java.util.Base64.getDecoder().decode(Encoded.data).toString(Charsets.UTF_8)
                    assertTrue(data.startsWith("नमस्ते 🌍"))
                    assertTrue(data.endsWith("end"))
                    assertTrue(data.length > 100_000)
                    assertTrue('\u0000' in data)
                }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("test", "--configuration-cache", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, run().task(":test")?.outcome)
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":generateEncoded")?.outcome)
        directory.resolve("payload.bin").appendText("changed end")
        val changed = run()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":generateEncoded")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":test")?.outcome)
    }

    @Test fun `probe classpaths refresh when identical classes move`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"classpathFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.module.JvmClasspathTask
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<JvmClasspathTask>("exportClasspath") {
                classpath.from(layout.projectDirectory.dir(providers.gradleProperty("location").get()))
                destination.set(layout.buildDirectory.file("classpath.txt"))
            }
        """.trimIndent())
        listOf("first", "second").forEach { location ->
            directory.resolve(location).mkdirs()
            directory.resolve("$location/resource.txt").writeText("same classpath content")
        }
        fun run(location: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportClasspath", "-Plocation=$location", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, run("first").task(":exportClasspath")?.outcome)
        val repeated = run("first")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":exportClasspath")?.outcome)
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, run("second").task(":exportClasspath")?.outcome)
        assertEquals(directory.resolve("second").canonicalPath,
            File(directory.resolve("build/classpath.txt").readText()).canonicalPath)
    }

    @Test fun `module generators feed compilation and resources with explicit test discovery`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"moduleFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.jvm") }
            repositories { mavenCentral() }
            dependeasy {
                jvm(bytecode = 21)
                kotlinObject("generateFlags", "fixture", "Flags") {
                    boolean("enabled", provider { true })
                }
                sourceIdentity("generateIdentity", "fixture.properties") {
                    revision("revision", rootDir)
                    sources("fixture", rootDir, files("src/main/kotlin/Consumer.kt"))
                }
                testClasses()
            }
            dependencies { testImplementation(kotlin("test-junit")) }
        """.trimIndent())
        directory.resolve("src/main/kotlin").mkdirs()
        directory.resolve("src/main/kotlin/Consumer.kt").writeText("package fixture\nfun enabled() = Flags.enabled")
        directory.resolve("src/test/kotlin").mkdirs()
        directory.resolve("src/test/kotlin/WiringTest.kt").writeText("""
            package fixture
            import kotlin.test.*
            class Helper
            class WiringTest {
                @Test fun generatedInputsReachTheConsumer() {
                    assertTrue(enabled())
                    assertNotNull(javaClass.getResource("/fixture.properties"))
                }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("test", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateFlags")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateIdentity")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":test")?.outcome)
        assertNull(first.task(":nodeSetup"))
        assertContains(run().output, "Reusing configuration cache")
    }
}
