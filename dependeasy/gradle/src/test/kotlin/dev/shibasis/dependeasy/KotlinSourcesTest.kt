package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class KotlinSourcesTest {
    @TempDir lateinit var directory: File

    private fun run(enabled: Boolean = true) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments("generateFlags", "-Penabled=$enabled", "--configuration-cache", "--stacktrace").build()

    @Test fun `generated sources track optional text and flags without capturing the project`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"sourcesFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val fixtureEnabled = dependeasy.localProperty("enabled").map(String::toBoolean)
            dependeasy.kotlinObject("generateFlags", "fixture", "Flags") {
                boolean("enabled", fixtureEnabled)
                string("label", provider { "fixture" })
                text("optional", file("fixture.txt"), fixtureEnabled)
            }
        """.trimIndent())
        val output = directory.resolve("build/generated/generateFlags/kotlin/fixture/Flags.kt")
        assertEquals(TaskOutcome.SUCCESS, run().task(":generateFlags")?.outcome)
        assertContains(output.readText(), "const val enabled: Boolean = true")
        val repeat = run()
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":generateFlags")?.outcome)
        directory.resolve("fixture.txt").writeText("token with \"quotes\", ${'$'}dollars and\nnewlines")
        assertEquals(TaskOutcome.SUCCESS, run().task(":generateFlags")?.outcome)
        assertContains(output.readText(), "\\${'$'}dollars")
        assertContains(output.readText(), "\\nnewlines")
        run(false)
        assertFalse(output.readText().contains("dollars"))
        directory.resolve("fixture.txt").delete()
        assertEquals(TaskOutcome.UP_TO_DATE, run(false).task(":generateFlags")?.outcome)
    }
}
