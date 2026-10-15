package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BuildOptionsTest {
    @TempDir lateinit var directory: File

    private fun fixture() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val feature = BuildOption.boolean("fixture.feature")
            val producer = tasks.register<dev.shibasis.dependeasy.workspace.WorkspaceTargetTask>("producer")
            dependeasy.workspace { val checks by verify { include(node(producer)); require(feature, true) } }
        """.trimIndent())
    }
    private fun run(vararg arguments: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments(*arguments, "--configuration-cache", "--console=plain", "--stacktrace")
    private fun requirement() = BuildModelReader.read(directory).nodes.single { it.id.name == "checks" }.requirements.single()

    @Test fun `command line overrides local properties and records the source`() {
        fixture()
        directory.resolve("local.properties").writeText("fixture.feature=false\n")
        run("exportBuildModel", "-Pfixture.feature=true").build()
        assertEquals(OptionSource.CommandLine, requirement().option.source)
        assertEquals("true", requirement().option.value)
        assertEquals("true", requirement().expected)
        val check = run("checks", "-Pfixture.feature=true").build()
        assertNotNull(check.task(":checks"))
        assertContains(run("checks", "-Pfixture.feature=true").build().output, "Reusing configuration cache")
    }

    @Test fun `local default and typed requirements are enforced`() {
        fixture()
        run("exportBuildModel").build()
        assertEquals(OptionSource.Default, requirement().option.source)
        assertEquals("false", requirement().option.value)
        val rejected = run("checks").buildAndFail()
        assertNull(rejected.task(":producer"))
        val failure = rejected.output
        assertContains(failure, "requires fixture.feature=true")
        assertContains(failure, "Default")
        directory.resolve("local.properties").writeText("fixture.feature=true\n")
        run("exportBuildModel").build()
        assertEquals(OptionSource.LocalProperties, requirement().option.source)
        run("checks").build()
    }

    @Test fun `system property overrides root gradle properties and rejects invalid booleans`() {
        fixture()
        directory.resolve("gradle.properties").writeText("fixture.feature=false\n")
        run("exportBuildModel", "-Dorg.gradle.project.fixture.feature=true").build()
        assertEquals(OptionSource.SystemProperty, requirement().option.source)
        assertContains(run("exportBuildModel", "-Pfixture.feature=perhaps").buildAndFail().output, "perhaps")
    }
}
