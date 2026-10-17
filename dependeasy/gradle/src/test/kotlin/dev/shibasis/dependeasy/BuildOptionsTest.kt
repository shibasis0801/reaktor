package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BuildOptionsTest {
    @TempDir lateinit var directory: File

    private fun fixture() {
        directory.resolve("settings.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.configuration.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.settings") }
            rootProject.name = "fixture"
            val feature = BuildOption.boolean("fixture.feature")
            val selected = resolve(feature).get()
            println("settings option: ${'$'}{selected.value} ${'$'}{selected.evidence.source}")
            val environment = resolve(BuildOption.string("fixture.environment", environment = "FIXTURE_ENV")).get()
            println("settings environment: [${'$'}{environment.value}] ${'$'}{environment.evidence.source}")
            if (option(feature).get()) include(":enabled")
        """.trimIndent())
        directory.resolve("enabled").mkdirs()
        directory.resolve("enabled/build.gradle.kts").writeText("")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.configuration.option
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val feature = BuildOption.boolean("fixture.feature")
            check((findProject(":enabled") != null) == option(feature).get())
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
        assertContains(run("exportBuildModel", "-Pfixture.feature=true").build().output, "settings option: true CommandLine")
        assertEquals(OptionSource.CommandLine, requirement().option.source)
        assertEquals("true", requirement().option.value)
        assertEquals("true", requirement().expected)
        val check = run("checks", "-Pfixture.feature=true").build()
        assertNotNull(check.task(":checks"))
        assertContains(run("checks", "-Pfixture.feature=true").build().output, "Reusing configuration cache")
    }

    @Test fun `local default and typed requirements are enforced`() {
        fixture()
        assertContains(run("exportBuildModel").build().output, "settings option: false Default")
        assertEquals(OptionSource.Default, requirement().option.source)
        assertEquals("false", requirement().option.value)
        val rejected = run("checks").buildAndFail()
        assertNull(rejected.task(":producer"))
        val failure = rejected.output
        assertContains(failure, "requires fixture.feature=true")
        assertContains(failure, "Default")
        directory.resolve("local.properties").writeText("fixture.feature=true\n")
        assertContains(run("exportBuildModel").build().output, "settings option: true LocalProperties")
        assertEquals(OptionSource.LocalProperties, requirement().option.source)
        run("checks").build()
    }

    @Test fun `system property overrides root gradle properties and rejects invalid booleans`() {
        fixture()
        directory.resolve("gradle.properties").writeText("fixture.feature=false\n")
        assertContains(run("exportBuildModel", "-Dorg.gradle.project.fixture.feature=true").build().output, "settings option: true SystemProperty")
        assertEquals(OptionSource.SystemProperty, requirement().option.source)
        assertContains(run("exportBuildModel", "-Pfixture.feature=perhaps").buildAndFail().output, "perhaps")
        val environment = System.getenv() + ("FIXTURE_ENV" to "")
        assertContains(run("exportBuildModel").withEnvironment(environment).build().output,
            "settings environment: [] Environment")
        val changed = environment + ("FIXTURE_ENV" to "present")
        val first = run("exportBuildModel").withEnvironment(changed).build()
        assertContains(first.output, "settings environment: [present] Environment")
        assertFalse(first.output.contains("Reusing configuration cache"))
        assertContains(run("exportBuildModel").withEnvironment(changed).build().output, "Reusing configuration cache")
    }
}
