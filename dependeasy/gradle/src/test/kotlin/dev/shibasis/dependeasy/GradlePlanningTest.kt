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
            import dev.shibasis.dependeasy.configuration.option
            import dev.shibasis.dependeasy.model.BuildOption
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            option(BuildOption.boolean("fixture.feature")).get()
            option(BuildOption.string("fixture.secret", "fixture-sensitive-default", sensitive = true)).get()
            abstract class OptionFixture : DefaultTask() {
                @get:Input @get:org.gradle.api.tasks.options.Option(option = "active", description = "Enable fixture")
                abstract val active: org.gradle.api.provider.Property<Boolean>
                @get:Input @get:org.gradle.api.tasks.options.Option(option = "label", description = "Fixture labels")
                abstract val labels: org.gradle.api.provider.ListProperty<String>
                @get:OutputFile abstract val result: org.gradle.api.file.RegularFileProperty
                init { active.convention(true); labels.convention(emptyList()) }
                @TaskAction fun write() { result.get().asFile.writeText(active.get().toString() + "|" + labels.get().joinToString("|")) }
            }
            val selection = tasks.register<OptionFixture>("selection") { result.set(layout.buildDirectory.file("selected.txt")) }
            val copied = tasks.register<Copy>("copied") {
                from(layout.projectDirectory.file("source.txt"))
                into(layout.buildDirectory.dir("copied"))
            }
            tasks.register("unmodeled") { doLast { logger.lifecycle("external phase") } }
            val privateToken = providers.gradleProperty("fixtureToken")
            val failed = tasks.register("failed") { doLast { throw GradleException("fixtureToken=" + privateToken.get()) } }
            dependeasy.workspace {
                val checks by verify { include(node(copied)) }
                val failureChecks by verify { include(node(failed)) }
                val optionChecks by verify { include(node(selection)) }
            }
        """.trimIndent())
    }
    private fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace")
    private fun receipts() = directory.resolve(GradleProtocol.ReceiptDirectory).listFiles().orEmpty()
        .filter { it.extension == "json" }.map(BuildModelReader::readReceipt)

    @Test fun `ordinary runs record typed outcomes and reuse configuration cache`() {
        fixture()
        runner("exportBuildModel", "checks", "-Pfixture.feature=true").build()
        val first = receipts().single()
        assertContains(first.requestedNodes, NodeId("receiptFixture", ":", "checks"))
        assertEquals(TaskOutcome.Executed, first.tasks.single { it.task.name == "copied" }.outcome)
        assertNotNull(first.modelDigest)
        val effective = first.resolvedOptions.single { it.option?.name == "fixture.feature" }.option!!
        assertEquals("true", effective.value)
        assertEquals("false", effective.defaultValue)
        assertEquals(OptionSource.CommandLine, effective.source)
        assertEquals(BuildOption.boolean("fixture.feature").resolutionOrder, effective.resolutionOrder)
        assertEquals(":", effective.scope)
        val privateDefault = first.resolvedOptions.single { it.option?.name == "fixture.secret" }.option!!
        assertEquals("<redacted>", privateDefault.value)
        assertEquals("<redacted>", privateDefault.defaultValue)
        assertFalse(receipts().toString().contains("fixture-sensitive-default"))
        val repeated = runner("exportBuildModel", "checks", "-Pfixture.feature=true").build()
        assertContains(repeated.output, "Reusing configuration cache")
        val second = receipts().single { it.run != first.run }
        assertEquals(TaskOutcome.UpToDate, second.tasks.single { it.task.name == "copied" }.outcome)
        assertEquals(first.resolvedOptions, second.resolvedOptions)
        assertTrue(second.tasks.all { it.durationMillis >= 0 })
        directory.resolve("build/copied/source.txt").delete()
        runner("exportBuildModel", "checks", "-Pfixture.feature=true").build()
        assertEquals(TaskOutcome.Executed, receipts().single { it.run !in setOf(first.run, second.run) }
            .tasks.single { it.task.name == "copied" }.outcome)
        for (dryRun in listOf(true, false, true)) {
            val existing = receipts().map { it.run }.toSet()
            val arguments = listOf("exportBuildModel", "checks", "-Pfixture.feature=true") +
                if (dryRun) listOf("--dry-run") else emptyList()
            val result = runner(*arguments.toTypedArray()).build()
            assertContains(result.output, "Reusing configuration cache")
            val receipt = receipts().single { it.run !in existing }
            assertEquals(dryRun, receipt.dryRun)
            assertEquals(dryRun, receipt.tasks.isEmpty())
        }
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
        val selection = model.nodes.single { it.id.name == "selection" }
        assertEquals(OptionType.Boolean, selection.options.single { it.name == "active" }.type)
        assertTrue(selection.options.single { it.name == "label" }.multiple)
        val selectedRequest = request.copy(node = selection.id, declaredAt = selection.declaredAt,
            arguments = listOf("--no-active", "--label=one", "--label=two"))
        fun selectedContract(value: GradleRequest): Pair<String, String> {
            val encoded = BuildModelReader.json.encodeToString(GradleRequest.serializer(), value) + "\n"
            val id = BuildModelReader.digest(encoded)
            val file = contract.parentFile.resolve("$id.json").apply { writeText(encoded) }
            return id to "-P${GradleProtocol.RequestOption}=${file.absolutePath}"
        }
        val (selectionDigest, selectionOption) = selectedContract(selectedRequest)
        bridgeRunner(GradleProtocol.PlanTask, selectionOption).build()
        assertFalse(directory.resolve("build/selected.txt").exists())
        val selectedPlan = BuildModelReader.readPlan(directory.resolve("${GradleProtocol.PlanDirectory}/$selectionDigest.json"))
        assertEquals(listOf(":selection") + selectedRequest.arguments, selectedPlan.invocation)
        val (executionDigest, executionOption) = selectedContract(selectedRequest.copy(expectedContextDigest = selectedPlan.contextDigest))
        bridgeRunner(GradleProtocol.RunTask, executionOption).build()
        assertEquals("false|one|two", directory.resolve("build/selected.txt").readText())
        val selectedResult = BuildModelReader.readRunResult(directory.resolve("${GradleProtocol.ResultDirectory}/$executionDigest.json"))
        assertEquals(ReceiptStatus.Succeeded, BuildModelReader.readReceipt(directory.resolve(selectedResult.receipts.single())).status)
    }

}
