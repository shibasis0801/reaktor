package dev.shibasis.dependeasy

import kotlinx.serialization.encodeToString
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
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"sourcesFixture\"; enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\"); include(\"workers\")")
        directory.resolve("workers").mkdirs()
        directory.resolve("targets/worker").mkdirs()
        directory.resolve("targets/worker/wrangler.json").writeText("""{"name":"fixture-worker","main":"index.ts"}""")
        directory.resolve("targets/worker/wrangler.dev.json").writeText("""{"name":"fixture-worker-dev","main":"index.ts","routes":[{"pattern":"dev.example.test","custom_domain":true}]}""")
        directory.resolve("workers/build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.workers
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            workers(delivery = Delivery(listOf(Environment("dev"), Environment("prod")), Environment("prod"), Delivery.Cloudflare("fixture-account", emptyList(), true))) {
                worker(WorkerRef("worker", "targets/worker"), listOf(
                    WorkerDeployment(Environment("dev"), ServiceRef("fixture-worker-dev")),
                    WorkerDeployment(Environment("prod"), ServiceRef("fixture-worker"), publicEndpoint = "https://prod.example.test")))
            }
        """.trimIndent())
        directory.resolve("gradle.properties").writeText("android.builtInKotlin=false\nandroid.newDsl=false")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.handles.*
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
                val workerEndpoints by generated(output(projects.workers, OutputKind.GeneratedContract, Contract.WorkerFleet))
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
        directory.resolve("src/commonMain/kotlin/Consumer.kt").writeText("package fixture; fun server(): String = TypedFields.server + WorkerEndpoints.ProdWorker + WorkerEndpoints.DevWorker")
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
        val endpoints = directory.resolve("build/generated/workerEndpoints/kotlin/fixture/WorkerEndpoints.kt")
        assertContains(endpoints.readText(), "const val ProdWorker: String = \"https://prod.example.test\"")
        assertContains(endpoints.readText(), "const val DevWorker: String = \"https://dev.example.test\"")
        val modelWithEndpoints = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val endpointNode = modelWithEndpoints.nodes.single { it.id.name == "workerEndpoints" }
        val endpointSpec = assertIs<dev.shibasis.dependeasy.model.KernelSpec.GeneratedObject>(endpointNode.kernel?.spec)
        assertEquals(listOf("build/dependeasy/contracts/cloudflare/fleet.json"), endpointSpec.workerFleet.paths)
        assertTrue(modelWithEndpoints.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes && it.from == endpointNode.id && it.to.name == "outputGeneratedContractWorkerFleet" })
        assertTrue(first.tasks.indexOfFirst { it.path == ":workers:generateWorkerFleet" } < first.tasks.indexOfFirst { it.path == ":workerEndpoints" })
        val sourceBytes = endpoints.readText()

        val repeat = run()
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":generateFlags")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":generateTypedFields")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":workerEndpoints")?.outcome)
        assertEquals(sourceBytes, endpoints.readText())
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
        assertContains(requirement.output, "requires enabled=false")
        val xcodeRelease = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withEnvironment(System.getenv() + ("CONFIGURATION" to "Release"))
            .withArguments("generateTypedFields", "requireEnabledDisabledForRelease", "exportBuildModel", "-Penabled=true", "--configuration-cache").build()
        val releaseGuard = dev.shibasis.dependeasy.model.BuildModelReader.read(directory).nodes
            .single { it.id.name == "requireEnabledDisabledForRelease" }
        val guardSpec = releaseGuard.kernel!!.spec as dev.shibasis.dependeasy.model.KernelSpec.WorkspaceNode
        assertEquals(dev.shibasis.dependeasy.model.OptionSource.XcodeRelease, guardSpec.requirements.single().option.source)
        assertEquals("dev.shibasis.dependeasy.model.VerificationKernel", releaseGuard.kernel!!.className)
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
            .replace("    require(Enabled, false)\n", "")
            .replace("    val generateTypedFields by generated(TypedFields)",
                "    require(Enabled, false)\n    val generateTypedFields by generated(TypedFields)"))
        directory.resolve("src/commonMain/kotlin/Consumer.kt").writeText("package fixture; fun server(): String = fixture.renamed.TypedFields.server")
        val stale = directory.resolve("build/generated/generateTypedFields/kotlin/obsolete/Stale.kt")
        stale.parentFile.mkdirs(); stale.writeText("package obsolete; object Stale")
        assertEquals(TaskOutcome.SUCCESS, run(false).task(":generateTypedFields")?.outcome)
        assertFalse(typed.exists())
        assertFalse(stale.exists())
        assertTrue(directory.resolve("build/generated/generateTypedFields/kotlin/fixture/renamed/TypedFields.kt").isFile)

        val contract = directory.resolve("build/dependeasy/contracts/cloudflare/fleet.json")
        val directSpec = endpointSpec.copy(workerFleet = dev.shibasis.dependeasy.model.FileSet(listOf(contract.absolutePath)))
        val directOutput = directory.resolve("direct-endpoints")
        fun generateDirect() = dev.shibasis.dependeasy.model.KotlinObjectKernel.generate(directSpec,
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), directOutput)
        generateDirect()
        val directSource = directOutput.resolve("fixture/WorkerEndpoints.kt")
        assertEquals(sourceBytes, directSource.readText())
        val valid = contract.readText()
        val fleet = dev.shibasis.dependeasy.model.BuildModelReader.json.decodeFromString<dev.shibasis.dependeasy.model.WorkerFleetContract>(valid)
        fun writeFleet(value: dev.shibasis.dependeasy.model.WorkerFleetContract) {
            contract.writeText(dev.shibasis.dependeasy.model.BuildModelReader.json.encodeToString(value))
        }
        val worker = fleet.workers.single()
        writeFleet(fleet.copy(workers = listOf(worker, worker)))
        assertFailsWith<IllegalArgumentException> { generateDirect() }
        assertEquals(sourceBytes, directSource.readText())
        writeFleet(fleet.copy(workers = listOf(worker.copy(name = "invalid-name"))))
        assertFailsWith<IllegalArgumentException> { generateDirect() }
        writeFleet(fleet.copy(workers = listOf(worker.copy(deployments = worker.deployments.map { it.copy(publicEndpoint = "https://user:private@example.test") }))))
        assertFailsWith<IllegalArgumentException> { generateDirect() }
        val ambiguous = kotlinx.serialization.json.Json.parseToJsonElement("""{"routes":[{"pattern":"one.test","custom_domain":true},{"pattern":"two.test","custom_domain":true}]}""") as kotlinx.serialization.json.JsonObject
        writeFleet(fleet.copy(workers = listOf(worker.copy(settings = worker.settings + ("dev" to ambiguous)))))
        assertFailsWith<IllegalArgumentException> { generateDirect() }
        writeFleet(fleet.copy(workers = listOf(worker.copy(settings = emptyMap(), deployments = worker.deployments.map { it.copy(publicEndpoint = null) }))))
        generateDirect()
        assertFalse(directSource.readText().contains("const val"))
        contract.writeText(valid)

        assertFalse(directory.resolve("build/classes/kotlin/jvm/main/fixture/TypedFields.class").exists())
    }
}
