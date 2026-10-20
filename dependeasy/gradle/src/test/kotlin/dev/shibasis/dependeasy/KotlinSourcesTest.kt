package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class KotlinSourcesTest {
    @TempDir lateinit var directory: File

    private fun run(enabled: Boolean = true) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withEnvironment(System.getenv().filterKeys { it != "CONFIGURATION" })
        .withArguments("generateFlags", "compileKotlinJvm", "exportBuildModel", "-Penabled=$enabled", "--configuration-cache", "--stacktrace").build()

    @Test fun `generated sources track optional text and flags without capturing the project`() {
        assertEquals(0, ProcessBuilder("git", "init").directory(directory).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() })
        assertEquals(0, ProcessBuilder("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--allow-empty", "-m", "fixture")
            .directory(directory).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() })
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"sourcesFixture\"")
        directory.resolve("gradle.properties").writeText("android.builtInKotlin=false\nandroid.newDsl=false")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.library") }
            repositories { mavenCentral() }
            val Enabled = BuildOption.boolean("enabled")
            val TypedFields by GeneratedObject("fixture") {
                val server by string(BuildOption.string("server", "fixture"))
                val enabled by flag(Enabled)
                val sourceRevision by revision()
                val optional by text("fixture.txt", enabled = Enabled)
            }
            library(namespace = "fixture", targets = Targets.Jvm) {
                val generateTypedFields by generated(TypedFields)
                require(Enabled, false)
            }
            val fixtureEnabled = dependeasy.option(dev.shibasis.dependeasy.model.BuildOption.boolean("enabled"))
            dependeasy.kotlinObject("generateFlags", "fixture", "Flags") {
                boolean("enabled", fixtureEnabled)
                string("label", provider { "fixture" })
                text("optional", file("fixture.txt"), fixtureEnabled)
            }
        """.trimIndent())
        directory.resolve("src/commonMain/kotlin").mkdirs()
        directory.resolve("src/commonMain/kotlin/Consumer.kt").writeText("package fixture; fun server(): String = TypedFields.server")
        val output = directory.resolve("build/generated/generateFlags/kotlin/fixture/Flags.kt")
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateFlags")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateTypedFields")?.outcome)
        assertTrue(directory.resolve("build/classes/kotlin/jvm/main/fixture/ConsumerKt.class").isFile)
        val typed = directory.resolve("build/generated/generateTypedFields/kotlin/fixture/TypedFields.kt")
        assertContains(typed.readText(), "val server: String = \"fixture\"")
        assertContains(output.readText(), "const val enabled: Boolean = true")
        assertContains(typed.readText(), "const val enabled: Boolean = true")
        assertContains(typed.readText(), "const val sourceRevision: String = ")
        val repeat = run()
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":generateFlags")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":generateTypedFields")?.outcome)
        directory.resolve("fixture.txt").writeText("token with \"quotes\", ${'$'}dollars and\nnewlines")
        assertEquals(TaskOutcome.SUCCESS, run().task(":generateFlags")?.outcome)
        assertContains(output.readText(), "\\${'$'}dollars")
        assertContains(output.readText(), "\\nnewlines")
        assertContains(typed.readText(), "\\nnewlines")
        val model = directory.resolve("build/dependeasy/model.json").readText()
        assertContains(model, "fixture.txt")
        assertContains(model, "requireEnabledDisabledForRelease")
        assertFalse(model.contains("token with"))
        val requirement = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withEnvironment(System.getenv().filterKeys { it != "CONFIGURATION" })
            .withArguments("requireEnabledDisabledForRelease", "-Penabled=true", "--configuration-cache").buildAndFail()
        assertContains(requirement.output, "enabled must be false for release")
        val xcodeRelease = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withEnvironment(System.getenv() + ("CONFIGURATION" to "Release"))
            .withArguments("generateTypedFields", "requireEnabledDisabledForRelease", "-Penabled=true", "--configuration-cache").build()
        assertContains(xcodeRelease.output, "Xcode CONFIGURATION=Release")
        assertContains(typed.readText(), "const val enabled: Boolean = false")
        assertFalse(typed.readText().contains("dollars"))
        run(false)
        assertFalse(output.readText().contains("dollars"))
        assertFalse(typed.readText().contains("dollars"))
        assertContains(typed.readText(), "const val enabled: Boolean = false")
        directory.resolve("fixture.txt").delete()
        assertEquals(TaskOutcome.UP_TO_DATE, run(false).task(":generateFlags")?.outcome)
        val script = directory.resolve("build.gradle.kts")
        script.writeText(script.readText().replace("GeneratedObject(\"fixture\")", "GeneratedObject(\"fixture.renamed\")")
            .replace("val generateTypedFields by generated(TypedFields)\n    require(Enabled, false)",
                "require(Enabled, false)\n    val generateTypedFields by generated(TypedFields)"))
        directory.resolve("src/commonMain/kotlin/Consumer.kt").writeText("package fixture; fun server(): String = fixture.renamed.TypedFields.server")
        val stale = directory.resolve("build/generated/generateTypedFields/kotlin/obsolete/Stale.kt")
        stale.parentFile.mkdirs(); stale.writeText("package obsolete; object Stale")
        assertEquals(TaskOutcome.SUCCESS, run(false).task(":generateTypedFields")?.outcome)
        assertFalse(typed.exists())
        assertFalse(stale.exists())
        assertTrue(directory.resolve("build/generated/generateTypedFields/kotlin/fixture/renamed/TypedFields.kt").isFile)
        assertFalse(directory.resolve("build/classes/kotlin/jvm/main/fixture/TypedFields.class").exists())
    }
}
