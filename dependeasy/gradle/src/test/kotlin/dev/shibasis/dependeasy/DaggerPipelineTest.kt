package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.dagger.GenerateDaggerTask
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
        fun run() = runner(":pipeline:generateDagger", "ci", "exportBuildModel").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":pipeline:generateDagger")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":ci")?.outcome)
        val actual = root.resolve("dagger/pipeline.json")
        val original = actual.readBytes()
        assertFalse(root.resolve("pipeline/build/dependeasy/dagger-reference").exists())
        val contract = BuildModelReader.json.decodeFromString<DaggerPipelineContract>(actual.readText())
        assertEquals("productFixture", contract.build)
        assertEquals("false", contract.environment["ORG_GRADLE_PROJECT_fixture.devTools"])
        assertEquals(2, contract.schema)
        assertFalse(actual.readText().contains("\"targets\""))
        assertFalse(root.resolve("pipeline/dagger/pipeline.json").exists())
        val model = BuildModelReader.read(root)
        assertTrue(model.edges.any { it.from == NodeId("productFixture", ":", "ci") && it.to.project == ":pipeline" })
        assertContains(run().output, "Reusing configuration cache")
        assertContentEquals(original, actual.readBytes())
        root.resolve("build.gradle.kts").appendText("\nworkspace { val additional by verify {} }\n")
        run()
        assertContentEquals(original, actual.readBytes())
        val resolved = runner(DaggerProtocol.PrepareTask, "-P${DaggerProtocol.NodeOption}=portable", "-P${GradleProtocol.EnvironmentOption}=dev").build()
        assertEquals(TaskOutcome.SUCCESS, resolved.task(":" + DaggerProtocol.PrepareTask)?.outcome)
        val invocation = BuildModelReader.json.decodeFromString<ContainerInvocation>(root.resolve(DaggerProtocol.InvocationFile).readText())
        assertEquals(NodeId("productFixture", ":", "portable"), invocation.node)
        assertContains(invocation.artifacts, "productFixture/build/dependeasy/receipts/**")
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
        runner("ci").build()
        assertContentEquals(original, actual.readBytes())
    }

    @Test fun `pipeline stays toolchain only and runtime resolves host environment secrets and outputs`() {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        fun node(name: String, host: HostRequirement = HostRequirement()) = Node(NodeId("fixture", ":", name), NodeKind.Target, name,
            Effect.Verify, host, null, null, Invocation.Gradle(listOf(":$name")))
        val local = node("local", HostRequirement(execution = ExecutionHost.Local))
        val parent = node("parent")
        val pr = node("pr")
        val android = node("android").copy(outputs = listOf(FileSet(listOf("app.aab"), OutputKind.AndroidBundle.name)))
        val androidParent = node("androidParent")
        val task = project.tasks.create("generate", GenerateDaggerTask::class.java).apply {
            buildName.set("fixture"); engineVersion.set("0.21.8")
            gradleProperties.set(mapOf("fixture.option" to "false"))
            executionEnvironment.set(mapOf("DEPE_CONTAINER_PLATFORM" to "linux/arm64"))
            toolchainContract.set(BuildModelReader.json.encodeToString(DaggerToolchainContract(javaImage = "fixture-jdk", browserImage = "fixture-browser", gradleUrl = "fixture", gradleSha256 = "0".repeat(64), gradleArchive = "fixture",
                androidSdk = AndroidSdkContract(directory = "fixture", cliVersion = "fixture", downloadUrl = "fixture", sha256 = "0".repeat(64), packages = emptyList()))))
            outputFile.set(root.resolve("dagger/pipeline.json"))
        }
        task.generate()
        val pipeline = BuildModelReader.json.decodeFromString<DaggerPipelineContract>(task.outputFile.get().asFile.readText())
        val model = BuildModel(producer = Producer(version = "fixture"), builds = listOf(BuildIdentity("included", "../included"), BuildIdentity("fixture", ".")),
            nodes = listOf(local, parent, pr, android, androidParent),
            edges = listOf(Edge.Includes(parent.id, local.id), Edge.Includes(androidParent.id, android.id)))
        fun resolve(node: Node, secrets: Set<String> = emptySet(), selected: BuildModel = model) =
            DaggerKernel.resolve(KernelSpec.ContainerNode(node.id, Environment("dev"), FileSet(emptyList()), FileSet(emptyList())), selected, secrets)
        assertFailsWith<IllegalArgumentException> { resolve(local) }
        assertFailsWith<IllegalArgumentException> { resolve(parent) }
        assertEquals("linux/amd64", resolve(pr).platform)
        assertEquals("linux/amd64", resolve(androidParent).platform)
        assertContains(resolve(android).artifacts, "fixture/app.aab")
        val privateInput = pr.copy(inputs = listOf(FileSet(listOf("config/private.env"))))
        assertFalse(resolve(privateInput, selected = model.copy(nodes = model.nodes.filter { it.id != pr.id } + privateInput))
            .artifacts.any { "private.env" in it })
        assertEquals(listOf("./gradlew", ":pr", "-Pdependeasy.environment=dev", "--console=plain"), resolve(pr).arguments)
        val secret = pr.copy(requiredSecrets = listOf("FIXTURE_TOKEN"), environments = listOf(Environment("dev")))
        val secretModel = model.copy(nodes = model.nodes.filter { it.id != pr.id } + secret)
        assertFailsWith<IllegalArgumentException> { resolve(secret, selected = secretModel) }
        assertEquals(listOf("FIXTURE_TOKEN"), resolve(secret, setOf("FIXTURE_TOKEN"), secretModel).secrets)
        assertFailsWith<IllegalArgumentException> { resolve(secret, setOf("FIXTURE_TOKEN"), secretModel.copy(nodes = secretModel.nodes.map { if (it.id == secret.id) it.copy(environments = listOf(Environment("prod"))) else it })) }
        assertEquals("fixture-browser", pipeline.toolchain.browserImage)
        assertEquals("false", pipeline.environment["ORG_GRADLE_PROJECT_fixture.option"])
        assertEquals(resolve(pr).platform, pipeline.environment["DEPE_CONTAINER_PLATFORM"])
        assertFalse("DEPE_CONTAINER_PLATFORM_OVERRIDES" in pipeline.environment)
        assertEquals(2, pipeline.toolchain.schemaVersion)
        assertEquals(768, pipeline.toolchain.memory.gradle.heapMiB)
        assertEquals("fixture-gradle", pipeline.toolchain.caches["/root/.gradle"])
        assertFalse("GRADLE_OPTS" in pipeline.environment)
        assertContains(pipeline.toolchain.files.getValue("/root/.gradle/gradle.properties"), "kotlin.daemon.jvmargs=-Xmx2560m")
        val wrapper = java.util.Properties().apply {
            load(pipeline.toolchain.files.getValue("/work/fixture/gradle/wrapper/gradle-wrapper.properties").reader())
        }
        assertEquals("file:fixture", wrapper.getProperty("distributionUrl"))
        assertEquals("0".repeat(64), wrapper.getProperty("distributionSha256Sum"))
        assertEquals("false", wrapper.getProperty("validateDistributionUrl"))
        assertEquals(setOf("pipeline.json"), root.resolve("dagger").list()!!.toSet())
    }
}
