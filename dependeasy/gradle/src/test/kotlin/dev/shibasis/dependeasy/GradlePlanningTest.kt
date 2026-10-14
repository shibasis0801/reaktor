package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class GradlePlanningTest {
    @TempDir lateinit var directory: File
    private fun fixture() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"receiptFixture\"")
        directory.resolve("source.txt").writeText("first")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val copied = tasks.register<Copy>("copied") {
                from(layout.projectDirectory.file("source.txt"))
                into(layout.buildDirectory.dir("copied"))
            }
            tasks.register("unmodeled") { doLast { logger.lifecycle("external phase") } }
            val privateToken = providers.gradleProperty("fixtureToken")
            val failed = tasks.register("failed") { doLast { throw GradleException("fixtureToken=" + privateToken.get()) } }
            dependeasy.workspace {
                verify("checks") { includes(node(copied)) }
                verify("failureChecks") { includes(node(failed)) }
            }
        """.trimIndent())
    }
    private fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace")
    private fun receipts() = directory.resolve(GradleProtocol.ReceiptDirectory).listFiles().orEmpty()
        .filter { it.extension == "json" }.map(BuildModelReader::readReceipt)

    @Test fun `ordinary runs record typed outcomes and reuse configuration cache`() {
        fixture()
        runner("exportBuildModel", "checks").build()
        val first = receipts().single()
        assertContains(first.requestedNodes, NodeId("receiptFixture", ":", "checks"))
        assertEquals(TaskOutcome.Executed, first.tasks.single { it.task.name == "copied" }.outcome)
        assertNotNull(first.modelDigest)
        val repeated = runner("exportBuildModel", "checks").build()
        assertContains(repeated.output, "Reusing configuration cache")
        val second = receipts().single { it.run != first.run }
        assertEquals(TaskOutcome.UpToDate, second.tasks.single { it.task.name == "copied" }.outcome)
        assertTrue(second.tasks.all { it.durationMillis >= 0 })
        directory.resolve("build/copied/source.txt").delete()
        runner("exportBuildModel", "checks").build()
        assertEquals(TaskOutcome.Executed, receipts().single { it.run !in setOf(first.run, second.run) }
            .tasks.single { it.task.name == "copied" }.outcome)
    }

    @Test fun `external phase preserves observed identity without inventing a declaration`() {
        fixture()
        runner("exportBuildModel", "unmodeled").build()
        val receipt = receipts().single()
        assertEquals(listOf(NodeId("receiptFixture", ":", "unmodeled")), receipt.requestedNodes)
        assertContains(receipt.modelOmission.orEmpty(), "no exported build declaration")
        assertFalse(BuildModelReader.read(directory).nodes.any { it.id.name == "unmodeled" })
    }

    @Test fun `configuration failures produce redacted receipts before tasks start`() {
        fixture()
        runner("exportBuildModel").build()
        val existing = receipts().map { it.run }.toSet()
        val token = "synthetic-configuration-token"
        directory.resolve("build.gradle.kts").appendText("\nthrow GradleException(\"fixtureToken=\" + providers.gradleProperty(\"fixtureToken\").get())\n")
        runner("checks", "-PfixtureToken=$token").buildAndFail()
        val receipt = receipts().single { it.run !in existing }
        assertEquals(ReceiptStatus.Failed, receipt.status)
        assertTrue(receipt.tasks.isEmpty())
        assertFalse(token in receipt.failures.joinToString())
        assertContains(receipt.failures.joinToString(), "[REDACTED]")
    }

    @Test fun `failed task messages are redacted in owned receipts`() {
        fixture()
        val token = "synthetic-private-fixture-token"
        runner("exportBuildModel", "failureChecks", "-PfixtureToken=$token").buildAndFail()
        val receipt = receipts().single()
        assertEquals(ReceiptStatus.Failed, receipt.status)
        val failures = receipt.tasks.single { it.task.name == "failed" }.failures.joinToString("\n")
        assertFalse(token in failures)
        assertContains(failures, "[REDACTED]")
    }
    @Test fun `bridge plans Gradles selected dependencies and links only the new run`() {
        fixture()
        val metadata = java.util.Properties().apply {
            GradlePlanningTest::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use(::load)
        }
        val files = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
            .joinToString(",") { BuildModelReader.json.encodeToString(kotlinx.serialization.serializer<String>(), it) }
        val script = directory.resolve("build.gradle.kts")
        script.writeText(script.readText().replace("plugins { id(\"dev.shibasis.dependeasy.pipeline\") }",
            "buildscript { dependencies { classpath(files($files)) } }\napply<dev.shibasis.dependeasy.plugins.PipelinePlugin>()")
            .replace("dependeasy.workspace", "extensions.getByType<dev.shibasis.dependeasy.plugins.DependeasyExtension>().workspace"))

        var operation = "planning"
        var launcher = "APP_ICON_100"
        fun bridgeRunner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
            .withArguments(*tasks, "--configuration-cache", "--stacktrace")
            .withEnvironment(System.getenv() + (GradleProtocol.OperationRunVariable to operation) + (launcher to "launcher"))
        bridgeRunner("exportBuildModel").build()
        val model = BuildModelReader.read(directory)
        val node = model.nodes.single { it.id.name == "checks" }
        val request = GradleRequest(node = node.id, declaredAt = node.declaredAt,
            modelDigest = BuildModelReader.digest(BuildModelReader.encode(model)), arguments = listOf("--configuration-cache"))
        val text = BuildModelReader.json.encodeToString(GradleRequest.serializer(), request) + "\n"
        val digest = BuildModelReader.digest(text)
        val contract = directory.resolve("${GradleProtocol.RequestDirectory}/$digest.json")
        contract.parentFile.mkdirs(); contract.writeText(text)
        val option = "-P${GradleProtocol.RequestOption}=${contract.absolutePath}"
        bridgeRunner(GradleProtocol.PlanTask, option).build()
        val planFile = directory.resolve("${GradleProtocol.PlanDirectory}/$digest.json")
        val plan = BuildModelReader.readPlan(planFile)
        val copied = NodeId("receiptFixture", ":", "copied")
        assertEquals(setOf(copied, node.id), plan.tasks.map { it.id }.toSet())
        assertEquals(listOf(copied), plan.tasks.single { it.id == node.id }.dependencies)
        assertFalse(directory.resolve("build/copied/source.txt").exists())
        assertTrue(receipts().any { it.dryRun && it.requestedNodes == listOf(node.id) })
        val firstPlan = planFile.readText()
        launcher = "APP_ICON_200"
        bridgeRunner(GradleProtocol.PlanTask, option).build()
        assertEquals(firstPlan, planFile.readText())
        val runRequest = request.copy(expectedContextDigest = plan.contextDigest)
        val runText = BuildModelReader.json.encodeToString(GradleRequest.serializer(), runRequest) + "\n"
        val runDigest = BuildModelReader.digest(runText)
        val runContract = contract.parentFile.resolve("$runDigest.json").apply { writeText(runText) }
        val runOption = "-P${GradleProtocol.RequestOption}=${runContract.absolutePath}"
        operation = "bridge-first"
        bridgeRunner(GradleProtocol.RunTask, runOption).build()
        val resultFile = directory.resolve("${GradleProtocol.ResultDirectory}/$runDigest.json")
        val first = BuildModelReader.readRunResult(resultFile)
        assertEquals(1, first.receipts.size)
        assertEquals(TaskOutcome.Executed, BuildModelReader.readReceipt(directory.resolve(first.receipts.single()))
            .tasks.single { it.task == copied }.outcome)
        assertEquals(operation, first.operationRunId)
        operation = "bridge-second"
        val repeated = bridgeRunner(GradleProtocol.RunTask, runOption).build()
        assertContains(repeated.output, "Reusing configuration cache")
        val second = BuildModelReader.readRunResult(resultFile)
        assertEquals(operation, second.operationRunId)
        assertEquals(operation, BuildModelReader.readReceipt(directory.resolve(second.receipts.single())).operationRunId)
        assertEquals(1, second.receipts.size)
        assertNotEquals(first.receipts, second.receipts)
        assertEquals(TaskOutcome.UpToDate, BuildModelReader.readReceipt(directory.resolve(second.receipts.single()))
            .tasks.single { it.task == copied }.outcome)
    }

}
