package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNull

class SettingsToolchainTest {
    @TempDir lateinit var directory: File

    @Test fun `settings supplies Kotlin defaults without starting backend tools`() {
        directory.resolve("settings.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.settings") }
            rootProject.name = "settingsFixture"
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("org.jetbrains.kotlin.jvm") }
            tasks.named("help") {
                inputs.property("kotlinVersion", kotlin.coreLibrariesVersion)
                doLast { logger.lifecycle("selected Kotlin: " + inputs.properties["kotlinVersion"]) }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertContains(first.output, "selected Kotlin: ${ToolchainVersions.Kotlin}")
        assertNull(first.task(":nodeSetup"))
        assertNull(first.task(":prepareNativeSources"))
        assertContains(run().output, "Reusing configuration cache")
    }

    @Test fun `each Kotlin JS project executes the shared verified Node`() {
        directory.resolve("settings.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.settings") }
            rootProject.name = "nodeFixture"
            include(":child")
        """.trimIndent())
        directory.resolve("package.json").writeText("""{"name":"node-fixture","private":true}""")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
        """.trimIndent())
        directory.resolve("child").mkdirs()
        directory.resolve("child/build.gradle.kts").writeText("""
            import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin { js { nodejs() } }
            val tools = extensions.getByType<NodeJsEnvSpec>()
            tasks.register<Exec>("qualifiedNode") {
                dependsOn(with(tools) { project.nodeJsSetupTaskProvider })
                executable = tools.executable.get()
                args("--version")
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(":child:qualifiedNode", "--configuration-cache", "--stacktrace").build()
        assertContains(run().output, "v${ToolchainVersions.Node}")
        assertContains(run().output, "Reusing configuration cache")
    }
}
