package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.*

class SourceIdentityTest {
    @TempDir lateinit var directory: File
    @TempDir lateinit var relocationRoot: File

    private fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments("identity", "--configuration-cache", "--stacktrace").build()

    @Test fun `identity is incremental and deterministic across checkout locations`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"identityFixture\"")
        directory.resolve("source").mkdir()
        val source = directory.resolve("source/main.kt").apply { writeText("fun value() = 1\n") }
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.sourceIdentity("identity", "identity.properties") {
                sources("workspace", file("source"), fileTree("source"))
                revisions.put("revision", "fixture:revision=1")
            }
        """.trimIndent())
        val output = directory.resolve("build/generated/identity/identity.properties")
        assertEquals(TaskOutcome.SUCCESS, run().task(":identity")?.outcome)
        val original = output.readText()
        val properties = Properties().apply { output.inputStream().use(::load) }
        assertEquals("fixture:revision=1", properties.getProperty("revision"))
        assertFalse(properties.containsKey("builtAt"))
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":identity")?.outcome)
        output.delete()
        run()
        assertEquals(original, output.readText())
        source.writeText("fun value() = 2\n")
        assertEquals(TaskOutcome.SUCCESS, run().task(":identity")?.outcome)
        assertNotEquals(original, output.readText())
        val relocated = relocationRoot.resolve("checkout")
        try {
            relocated.mkdirs()
            listOf("settings.gradle.kts", "build.gradle.kts", "source").forEach {
                directory.resolve(it).copyRecursively(relocated.resolve(it))
            }
            GradleRunner.create().withProjectDir(relocated).withPluginClasspath().withArguments("identity").build()
            assertEquals(output.readText(), relocated.resolve("build/generated/identity/identity.properties").readText())
        } finally { relocated.deleteTreeSafely(within = relocationRoot) }
    }
}
