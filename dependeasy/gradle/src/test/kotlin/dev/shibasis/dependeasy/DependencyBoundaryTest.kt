package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DependencyBoundaryTest {
    @TempDir lateinit var directory: File

    private fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--no-watch-fs", "--stacktrace")

    @Test fun `boundaries stay lazy and preserve resolved project policies across cache reuse`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"boundaryFixture\"\ninclude(\":renderer-jvm\")")
        val module = directory.resolve("renderer-jvm/build.gradle.kts").apply { parentFile.mkdirs() }
        module.writeText("plugins { java }\ngroup = \"headless\"")
        val build = directory.resolve("build.gradle.kts")
        build.writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.jvm") }
            repositories { mavenCentral() }
            library {
                dependencies { runtimeOnly(project(":renderer-jvm")) }
                val verifyBoundary by boundary(KernelSpec.DependencyBoundary(
                    ConfigurationRef.RuntimeClasspath, listOf("gui"), emptyList(), listOf("engine"),
                    "-jvm", "Headless fixture acquired a renderer"), Verification.KernelBoundary)
            }
        """.trimIndent())
        assertNull(runner("help").build().task(":verifyBoundary"))
        assertFalse(directory.resolve("renderer-jvm/build").exists())
        runner("exportBuildModel").build()
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val boundary = model.nodes.single { it.id.name == "verifyBoundary" }
        val output = model.nodes.single { it.id.name == "outputVerificationResultsKernelBoundary" }
        val spec = boundary.kernel?.spec as dev.shibasis.dependeasy.model.KernelSpec.DependencyBoundary
        assertEquals("runtimeClasspath", spec.configuration.name)
        assertEquals(spec, output.kernel?.spec)
        assertEquals(listOf(":verifyBoundary"), (output.invocation as dev.shibasis.dependeasy.model.Invocation.Gradle).tasks)
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Includes &&
            it.from.name == "componentChecks" && it.to == boundary.id })
        runner("componentChecks").build()
        assertEquals("verified\n", directory.resolve("build/dependeasy/checks/verifyBoundary.txt").readText())
        runner("check").build()
        assertContains(runner("check").build().output, "Reusing configuration cache")

        module.writeText("plugins { java }\ngroup = \"gui.renderer\"")
        assertContains(runner("check").buildAndFail().output, "gui.renderer:renderer-jvm")
        module.writeText("plugins { java }\ngroup = \"headless\"")
        build.writeText(build.readText().replace("listOf(\"engine\")", "listOf(\"renderer\")"))
        assertContains(runner("check").buildAndFail().output, "headless:renderer-jvm")
    }

    @Test fun `library boundary is a typed child of component verification`() {
        directory.resolve("gradle.properties").writeText("android.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true")
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"componentBoundary\"; enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\"); include(\":library\", \"renderer-jvm\")")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.workspace") }
            workspace { val pr by verify(projects.library) }
        """.trimIndent())
        val renderer = directory.resolve("renderer-jvm/build.gradle.kts").apply { parentFile.mkdirs() }
        renderer.writeText("plugins { java }; group = \"headless\"; java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }")
        val build = directory.resolve("library/build.gradle.kts").apply { parentFile.mkdirs() }
        build.writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            repositories { mavenCentral() }
            library(namespace = "dev.fixture.boundary", targets = Targets.Jvm, bytecode = 21) {
                dependencies { jvm { implementation(project(":renderer-jvm")) } }
                val verifyBoundary by boundary(groupPrefixes = listOf("gui"), modules = listOf("engine"),
                    reason = "Headless fixture acquired a renderer")
            }
        """.trimIndent())
        val export = runner("exportBuildModel").build()
        assertNull(export.task(":library:verifyBoundary"))
        assertNull(export.task(":library:compileKotlinJvm"))
        assertFalse(directory.resolve("renderer-jvm/build").exists())
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val boundary = model.nodes.single { it.id.project == ":library" && it.id.name == "verifyBoundary" }
        val spec = boundary.kernel?.spec as dev.shibasis.dependeasy.model.KernelSpec.DependencyBoundary
        assertEquals("jvmRuntimeClasspath", spec.configuration.name)
        assertEquals(listOf("gui"), spec.groupPrefixes)
        assertEquals(listOf("engine"), spec.modules)
        assertEquals(dev.shibasis.dependeasy.model.Effect.Verify, boundary.effect)
        assertEquals("library/build.gradle.kts", boundary.declaredAt?.file)
        assertEquals(7, boundary.declaredAt?.line)
        val checks = boundary.id.copy(name = "componentChecks")
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Includes && it.from == checks && it.to == boundary.id })
        assertContains(runner("exportBuildModel").build().output, "Reusing configuration cache")
        assertNotNull(runner("pr").build().task(":library:verifyBoundary"))
        assertEquals("verified\n", directory.resolve("library/build/dependeasy/checks/verifyBoundary.txt").readText())
        assertContains(runner("pr").build().output, "Reusing configuration cache")
        renderer.writeText(renderer.readText().replace("headless", "gui.renderer"))
        assertContains(runner("pr").buildAndFail().output, "gui.renderer:renderer-jvm")
        renderer.writeText(renderer.readText().replace("gui.renderer", "headless"))
        build.writeText(build.readText().replace("modules = listOf(\"engine\")", "modules = listOf(\"renderer\")"))
        assertContains(runner("pr").buildAndFail().output, "headless:renderer-jvm")
    }
}
