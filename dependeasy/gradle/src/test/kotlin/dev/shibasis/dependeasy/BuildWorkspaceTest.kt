package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BuildWorkspaceTest {
    @TempDir lateinit var directory: File

    @Test fun `export is deterministic source validated and separate from execution`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register("producer") { doLast { error("Export must not execute the producer") } }
            dependeasy.workspace {
                verify("checks", ":producer")
                target("desktop") { tasks(":checks"); executionHost = dev.shibasis.dependeasy.workspace.BuildHost.Local }
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
        assertEquals(4, check.declaredAt?.line)
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
        val dependency = Node(NodeId("library", ":", "compile"), NodeKind.Target, "compile", Effect.Build, HostRequirement(),
            SourceSite("library", "build.gradle.kts", 1), null, Invocation.Gradle(listOf(":compile")))
        library.resolve("authored-model.json").writeText(BuildModelReader.encode(BuildModel(producer = Producer(version = "fixture"),
            builds = listOf(BuildIdentity("library", ".")), nodes = listOf(dependency))))
        library.resolve("build.gradle.kts").writeText("""
            tasks.register("compile") { doLast { error("Export cannot execute compilation") } }
            tasks.register<Copy>("exportBuildModel") {
                from("authored-model.json")
                into(layout.buildDirectory.dir("dependeasy"))
                rename { "model.json" }
            }
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.workspace {
                includedBuild("library")
                verify("checks") { included("library", ":compile") }
            }
        """.trimIndent())
        val run = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--configuration-cache").build()
        assertNull(run.task(":library:compile"))
        val model = BuildModelReader.read(directory)
        assertEquals(setOf("fixture", "library"), model.builds.map { it.name }.toSet())
        assertContains(model.nodes.map { it.id }, dependency.id)
        assertEquals(NodeId("library", ":", "compile"), model.edges.single().to)
    }
}
