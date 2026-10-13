package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BuildWorkspaceTest {
    @TempDir lateinit var directory: File

    @Test fun `export records owned output producers without running foreign compiler dependencies`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"; enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\"); include(\":client\", \"library\")")
        listOf("client", "library").forEach { name ->
            directory.resolve(name).mkdir()
            directory.resolve("$name/build.gradle.kts").writeText("plugins { java }")
        }
        directory.resolve("client/build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { java; id("dev.shibasis.dependeasy.pipeline") }
            dependencies { implementation(project(":library")) }
            val collected = layout.buildDirectory.file("classpath.txt")
            val collector = tasks.register("collectClasspath") {
                inputs.files(configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
                outputs.file(collected)
            }
            publishOutput(OutputKind.VerificationResults, collected, Verification.TypeScript, producer = collector)
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.workspace { verify("compilerChecks") { consumes(verification(projects.client, Verification.TypeScript)) } }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--parallel", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertNull(first.task(":client:compileJava"))
        assertNull(first.task(":library:compileJava"))
        assertNull(first.task(":client:collectClasspath"))
        val model = BuildModelReader.read(directory)
        val output = model.nodes.single { it.id == NodeId("fixture", ":client", "outputVerificationResultsTypeScript") }
        assertEquals(listOf(":client:collectClasspath"), (output.invocation as Invocation.Gradle).tasks)
        assertTrue(model.edges.any { it is Edge.Consumes && it.to == output.id })
        assertTrue(model.omissions.none { it.reason == "foreign task path" })
        assertContains(run().output, "Reusing configuration cache")
    }

    @Test fun `export is deterministic source validated and separate from execution`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val producer = tasks.register("producer") { doLast { error("Export must not execute the producer") } }
            dependeasy.workspace {
                val checks = verify("checks") { includes(node(producer)) }
                target("desktop") { includes(checks); host = dev.shibasis.dependeasy.model.HostRequirement(execution = dev.shibasis.dependeasy.model.ExecutionHost.Local) }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertNull(first.task(":producer"))
        val text = directory.resolve(BuildModelReader.RelativePath).readText()
        val model = BuildModelReader.read(directory)
        val check = model.nodes.single { it.id.name == "checks" }
        assertEquals(Effect.Verify, check.effect)
        assertEquals(5, check.declaredAt?.line)
        assertEquals("build.gradle.kts", check.declaredAt?.file)
        assertEquals("dev.shibasis.dependeasy.workspace.WorkspaceTargetTask", check.kernel?.className)
        assertEquals(ExecutionHost.Local, model.nodes.single { it.id.name == "desktop" }.host.execution)
        assertEquals(listOf(":checks"), (check.invocation as Invocation.Gradle).tasks)
        assertContains(run().output, "Reusing configuration cache")
        assertEquals(text, directory.resolve(BuildModelReader.RelativePath).readText())
        directory.resolve("build.gradle.kts").appendText("\n")
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(directory) }
    }

    @Test fun `included models preserve build ownership without a second task scheduler`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"; includeBuild(\"library\")")
        val library = directory.resolve("library").apply { mkdir() }
        library.resolve("settings.gradle.kts").writeText("rootProject.name = \"library\"")
        library.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            group = "fixture"
            val compile = tasks.register("compile") { doLast { error("Export cannot execute compilation") } }
            dependeasy.workspace { verify("checks", Workspace.Verify) { includes(node(compile)) } }
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.workspace {
                val library = BuildHandle("library", "fixture")
                includedBuild(library)
                verify("checks") { includes(library, Workspace.Verify) }
            }
        """.trimIndent())
        val run = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--configuration-cache").build()
        assertNull(run.task(":library:compile"))
        val model = BuildModelReader.read(directory)
        assertEquals(setOf("fixture", "library"), model.builds.map { it.name }.toSet())
        assertContains(model.nodes.map { it.id }, NodeId("library", ":", "compile"))
        assertTrue(model.edges.any { it.to == NodeId("library", ":", "outputWorkspaceCompletionVerify") })
    }
}
