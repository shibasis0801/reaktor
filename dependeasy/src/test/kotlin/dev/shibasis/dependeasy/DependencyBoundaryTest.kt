package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DependencyBoundaryTest {
    @TempDir lateinit var directory: File

    private fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace")

    @Test fun `boundaries stay lazy and preserve resolved project policies across cache reuse`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"boundaryFixture\"\ninclude(\":renderer-jvm\")")
        val module = directory.resolve("renderer-jvm/build.gradle.kts").apply { parentFile.mkdirs() }
        module.writeText("plugins { java }\ngroup = \"headless\"")
        val build = directory.resolve("build.gradle.kts")
        build.writeText("""
            plugins { java; id("dev.shibasis.dependeasy.pipeline") }
            dependencies { runtimeOnly(project(":renderer-jvm")) }
            dependeasy.dependencyBoundary("verifyBoundary", "runtimeClasspath") {
                forbidGroupPrefixes("gui")
                forbidModules("engine")
                reason.set("Headless fixture acquired a renderer")
            }
        """.trimIndent())
        assertNull(runner("help").build().task(":verifyBoundary"))
        assertFalse(directory.resolve("renderer-jvm/build").exists())
        runner("check").build()
        assertContains(runner("check").build().output, "Reusing configuration cache")

        module.writeText("plugins { java }\ngroup = \"gui.renderer\"")
        assertContains(runner("check").buildAndFail().output, "gui.renderer:renderer-jvm")
        module.writeText("plugins { java }\ngroup = \"headless\"")
        build.writeText(build.readText().replace("forbidModules(\"engine\")", "forbidModules(\"renderer\")"))
        assertContains(runner("check").buildAndFail().output, "headless:renderer-jvm")
    }
}
