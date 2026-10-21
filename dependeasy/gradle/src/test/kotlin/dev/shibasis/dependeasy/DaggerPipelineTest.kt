package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.dagger.GenerateDaggerTask
import dev.shibasis.dependeasy.dagger.VerifyDaggerTask
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DaggerPipelineTest {
    @TempDir lateinit var root: File

    @Test fun `Reaktor catalog accepts component outputs and still rejects missing or uncatalogued libraries`() {
        val libraries = Reaktor.modules.map { it.path }
        fun settings(paths: List<String>) {
            paths.forEach { root.resolve(it.removePrefix(":")).mkdirs() }
            root.resolve("settings.gradle.kts").writeText(
                "rootProject.name = \"reaktor\"\ninclude(" + paths.joinToString { "\"$it\"" } + ")")
        }
        settings(libraries + ":pipeline")
        root.resolve("build.gradle.kts").writeText("plugins { id(\"dev.shibasis.dependeasy.workspace\") }")
        root.resolve("pipeline/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("""
                import dev.shibasis.dependeasy.components.daggerPipeline
                plugins { id("dev.shibasis.dependeasy.pipeline") }
                daggerPipeline()
            """.trimIndent())
        }
        fun runner() = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("exportBuildModel", "--configuration-cache", "--stacktrace")
        runner().build()
        assertContains(runner().build().output, "Reusing configuration cache")
        settings(libraries + listOf(":pipeline", ":uncatalogued"))
        assertContains(runner().buildAndFail().output, "uncatalogued=[:uncatalogued]")
        settings(libraries - Reaktor.Core.path + ":pipeline")
        assertContains(runner().buildAndFail().output, "missing=[:reaktor-core]")
    }

    @Test fun `component keeps root pipeline identity and publishes its verification`() {
        fun write(path: String, text: String) = root.resolve(path).apply {
            parentFile.mkdirs(); writeText(text.trimIndent())
        }
        write("settings.gradle.kts", """
            rootProject.name = "productFixture"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include("pipeline")
        """)
        write("build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.workspace") }
            workspace {
                val portable by verify {}
                val ci by verify(projects.pipeline)
            }
        """)
        write("pipeline/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.daggerPipeline
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            daggerPipeline(options = listOf(BuildOption.boolean("fixture.devTools").argument(false)))
        """)
        fun runner(vararg args: String) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")
        fun run() = runner(":pipeline:generateDagger", "ci").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":pipeline:verifyDagger")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":ci")?.outcome)
        val actual = root.resolve("dagger/pipeline.json")
        val expected = root.resolve("pipeline/build/dependeasy/dagger-reference/pipeline.json")
        assertContentEquals(expected.readBytes(), actual.readBytes())
        val contract = BuildModelReader.json.decodeFromString<DaggerPipelineContract>(actual.readText())
        assertEquals("productFixture", contract.build)
        assertEquals("false", contract.environment["ORG_GRADLE_PROJECT_fixture.devTools"])
        assertTrue(contract.targets.any { it.id == NodeId("productFixture", ":", "portable") })
        assertFalse(root.resolve("pipeline/dagger/pipeline.json").exists())
        val model = BuildModelReader.read(root)
        assertTrue(model.edges.any { it.from == NodeId("productFixture", ":", "ci") && it.to.project == ":pipeline" })
        assertContains(run().output, "Reusing configuration cache")
        assertContentEquals(expected.readBytes(), actual.readBytes())
        write("dependeasy/dagger/src/index.ts", "export const fixture = true")
        val appeared = run()
        assertFalse(appeared.output.contains("Reusing configuration cache"))
        val sharedPackage = root.resolve("dependeasy/dagger/package.json")
        assertTrue(sharedPackage.isFile)
        assertContains(run().output, "Reusing configuration cache")
        assertTrue(root.resolve("dependeasy/dagger/src/index.ts").delete())
        assertTrue(sharedPackage.delete())
        assertFalse(run().output.contains("Reusing configuration cache"))
        assertFalse(sharedPackage.exists())
        assertContains(run().output, "Reusing configuration cache")
        actual.appendText("stale")
        assertContains(runner("ci").buildAndFail().output, "./gradlew :pipeline:generateDagger")
    }

    @Test fun `pipeline is data only and prunes local dependency closure`() {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        fun node(name: String, host: HostRequirement = HostRequirement()) = Node(NodeId("fixture", ":", name), NodeKind.Target, name,
            Effect.Verify, host, null, null, Invocation.Gradle(listOf(":$name")))
        val local = node("local", HostRequirement(execution = ExecutionHost.Local))
        val parent = node("parent")
        val pr = node("pr")
        val android = node("android").copy(outputs = listOf(FileSet(listOf("app.aab"), OutputKind.AndroidBundle.name)))
        val androidParent = node("androidParent")
        val task = project.tasks.create("generate", GenerateDaggerTask::class.java).apply {
            buildName.set("fixture"); engineVersion.set("0.21.8"); browserImage.set("fixture-browser")
            gradleProperties.set(mapOf("fixture.option" to "false"))
            executionEnvironment.set(mapOf("DEPE_CONTAINER_PLATFORM" to "linux/arm64"))
            toolchainContract.set(BuildModelReader.json.encodeToString(DaggerToolchainContract(javaImage = "fixture-jdk", gradleUrl = "fixture", gradleSha256 = "0".repeat(64), gradleArchive = "fixture",
                androidSdk = AndroidSdkContract(directory = "fixture", commandLineTools = "fixture", downloadUrl = "fixture", sha256 = "0".repeat(64), packages = emptyList()))))
            declarations.set(listOf(local, parent, pr, android, androidParent).map(BuildModelReader::encode))
            edges.set(listOf(Edge.Includes(parent.id, local.id), Edge.Includes(androidParent.id, android.id))
                .map { BuildModelReader.json.encodeToString<Edge>(it) })
            outputFile.set(root.resolve("dagger/pipeline.json"))
        }
        task.generate()
        val pipeline = BuildModelReader.json.decodeFromString<DaggerPipelineContract>(task.outputFile.get().asFile.readText())
        assertEquals(listOf(android.id, androidParent.id, pr.id), pipeline.targets.map { it.id })
        assertEquals("false", pipeline.environment["ORG_GRADLE_PROJECT_fixture.option"])
        assertEquals("linux/arm64", pipeline.environment["DEPE_CONTAINER_PLATFORM"])
        assertEquals(mapOf(android.id.toString() to "linux/amd64", androidParent.id.toString() to "linux/amd64"),
            BuildModelReader.json.decodeFromString<Map<String, String>>(pipeline.environment.getValue("DEPE_CONTAINER_PLATFORM_OVERRIDES")))
        assertEquals(setOf("pipeline.json"), root.resolve("dagger").list()!!.toSet())
        val verify = project.tasks.create("verify", VerifyDaggerTask::class.java).apply {
            expected.set(task.outputFile); actual.set(root.resolve("pipeline-copy.json"))
        }
        task.outputFile.get().asFile.copyTo(verify.actual.get().asFile)
        verify.check()
        verify.actual.get().asFile.appendText("stale")
        assertFailsWith<IllegalArgumentException> { verify.check() }
    }
}
