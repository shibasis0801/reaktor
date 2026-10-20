package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class TypedOutputsTest {
    @TempDir lateinit var directory: File

    private fun run(vararg arguments: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments(*arguments, "--configuration-cache", "--console=plain", "--stacktrace")

    private fun fixture() {
        directory.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "fixture"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include(":library")
            includeBuild("other")
        """.trimIndent())
        directory.resolve("library").mkdir()
        directory.resolve("library/build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val result = layout.buildDirectory.file("result.txt")
            val producer = tasks.register("producer") {
                val destination = result
                outputs.file(destination)
                doLast { destination.get().asFile.apply { parentFile.mkdirs(); writeText("verified") } }
            }
            publishOutput(OutputKind.VerificationResults, result, Verification.TypeScript, producer = producer)
            publishOutput(OutputKind.AppleFramework, result, Apple.IosArm64, Build.Debug, producer = producer)
        """.trimIndent())
        directory.resolve("other").mkdir()
        directory.resolve("other/settings.gradle.kts").writeText("rootProject.name = \"other\"")
        directory.resolve("other/build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            group = "fixture"
            dependeasy.workspace { val checks by verify(role = Workspace.Verify) }
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val unowned = tasks.register<dev.shibasis.dependeasy.web.PackageCommand>("unowned") {
                workingDirectory.set(layout.projectDirectory)
                executable.set("unused")
                toolVersion.set("fixture")
                sourceFiles.from(rootProject.file("../unpublished-input.txt"))
            }
            dependeasy.workspace {
                val inspectInputs by artifacts { include(node(unowned)) }
                val other = BuildHandle("other", "fixture")
                val framework = include(other)
                val checks by verify {
                    include(verification(projects.library, Verification.TypeScript))
                    include(framework.verify)
                }
                val apple by artifacts { outputs(appleFramework(projects.library, Apple.IosArm64, Build.Debug)) }
                val appleIncluded by artifacts { include(apple) }
            }
        """.trimIndent())
    }

    @Test fun `outputs and root roles carry producers without executing them during export`() {
        fixture()
        val export = run("exportBuildModel").build()
        assertNull(export.task(":library:producer"))
        assertNull(export.task(":other:checks"))
        val model = BuildModelReader.read(directory)
        val output = model.nodes.single { it.id == NodeId("fixture", ":library", "outputVerificationResultsTypeScript") }
        assertEquals(listOf(":library:producer"), (output.invocation as Invocation.Gradle).tasks)
        assertEquals("library/build/result.txt", output.outputs.single().paths.single())
        assertTrue(model.edges.any { it is Edge.Consumes && it.to == output.id })
        assertTrue(model.omissions.none { it.reason == "foreign task path" })
        val outside = model.omissions.filter { it.reason == "Input outside owning build has no resolved output variant" }
        assertEquals(listOf(Omission(NodeId("fixture", ":", "unowned"),
            "Input outside owning build has no resolved output variant", "../unpublished-input.txt")), outside)
        listOf("apple", "appleIncluded").forEach { name ->
            val node = model.nodes.single { it.id == NodeId("fixture", ":", name) }
            assertEquals(HostPlatform.Macos, node.host.platform)
            assertEquals("apple", node.artifactFamily)
        }
        assertContains(run("exportBuildModel").build().output, "Reusing configuration cache")
        val execution = run("checks").build()
        assertNotNull(execution.task(":library:producer"))
        assertNotNull(execution.task(":other:checks"))
        assertEquals("verified", directory.resolve("library/build/result.txt").readText())
        assertContains(run("checks").build().output, "Reusing configuration cache")
    }

    @Test fun `project rename fails at the typed use with its source location`() {
        fixture()
        directory.resolve("settings.gradle.kts").writeText(directory.resolve("settings.gradle.kts").readText().replace(":library", ":renamed"))
        directory.resolve("library").renameTo(directory.resolve("renamed"))
        val failure = run("exportBuildModel").buildAndFail().output
        assertContains(failure, "Unresolved reference")
        assertContains(failure, "library")
        assertContains(failure, "build.gradle.kts")
        assertTrue(Regex("""build\.gradle\.kts(?::\d+(?::\d+)?|' line: \d+)""").containsMatchIn(failure), failure)
    }
}
