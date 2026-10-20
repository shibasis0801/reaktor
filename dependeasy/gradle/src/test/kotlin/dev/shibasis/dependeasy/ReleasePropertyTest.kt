package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class ReleasePropertyTest {
    @TempDir lateinit var directory: File

    private fun fixture() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val devToolsEnabled = dependeasy.releaseProperty(dev.shibasis.dependeasy.model.BuildOption.boolean("bestbuds.devTools"))
            dependeasy.kotlinObject("generateFlags", "fixture", "Flags") {
                boolean("devToolsEnabled", devToolsEnabled)
                text("fixture", rootProject.file("fixture.json"), devToolsEnabled)
            }
            tasks.register("release") { dependsOn("requireBestbudsDevToolsDisabledForRelease", "generateFlags") }
        """.trimIndent())
        directory.resolve("fixture.json").writeText("fixture-marker")
    }

    private fun runner(vararg arguments: String, configuration: String = "Debug") = GradleRunner.create()
        .withProjectDir(directory).withPluginClasspath()
        .withEnvironment(System.getenv() + ("CONFIGURATION" to configuration))
        .withArguments(*arguments, "exportBuildModel", "--configuration-cache", "--stacktrace")

    private fun requirement() = dev.shibasis.dependeasy.model.BuildModelReader.read(directory).nodes
        .single { it.id.name == "requireBestbudsDevToolsDisabledForRelease" }.kernel!!.spec
        .let { it as dev.shibasis.dependeasy.model.KernelSpec.WorkspaceNode }.requirements.single()

    private fun markers() = directory.resolve("build/generated/generateFlags/kotlin/fixture/Flags.kt")
        .readText().windowed("fixture-marker".length).count { it == "fixture-marker" }

    @Test fun `local development fixtures fail the release requirement before linking`() {
        fixture()
        directory.resolve("local.properties").writeText("bestbuds.devTools=true\n")
        val result = runner("release").buildAndFail()
        assertContains(result.output, "requires bestbuds.devTools=false")
        assertContains(result.output, "LocalProperties (local.properties)")
        assertEquals(TaskOutcome.FAILED, result.task(":requireBestbudsDevToolsDisabledForRelease")?.outcome)
    }

    @Test fun `command line overrides both property files and survives configuration cache reuse`() {
        fixture()
        directory.resolve("local.properties").writeText("bestbuds.devTools=true\n")
        directory.resolve("gradle.properties").writeText("bestbuds.devTools=true\n")
        val result = runner("release", "-Pbestbuds.devTools=false").build()
        assertEquals(dev.shibasis.dependeasy.model.OptionSource.CommandLine, requirement().option.source)
        assertEquals(0, markers())
        assertContains(runner("release", "-Pbestbuds.devTools=false").build().output, "Reusing configuration cache")
    }

    @Test fun `Gradle properties override local properties and default disables fixtures`() {
        fixture()
        directory.resolve("local.properties").writeText("bestbuds.devTools=true\n")
        directory.resolve("gradle.properties").writeText("bestbuds.devTools=false\n")
        runner("release").build()
        assertEquals(dev.shibasis.dependeasy.model.OptionSource.RootGradleProperties, requirement().option.source)
        assertEquals(0, markers())
        directory.resolve("local.properties").delete()
        directory.resolve("gradle.properties").delete()
        runner("release").build()
        assertEquals(dev.shibasis.dependeasy.model.OptionSource.Default, requirement().option.source)
        assertEquals(0, markers())
    }

    @Test fun `Xcode Release forces fixtures off even when explicitly requested`() {
        fixture()
        directory.resolve("local.properties").writeText("bestbuds.devTools=true\n")
        val result = runner("release", "-Pbestbuds.devTools=true", configuration = "Release").build()
        assertEquals(dev.shibasis.dependeasy.model.OptionSource.XcodeRelease, requirement().option.source)
        assertEquals(0, markers())
    }
}
