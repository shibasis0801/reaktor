package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.serialization.json.*
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

    @Test fun `worker bridge plans without writes and executes only its sealed bundle`() {
        fixture()
        val initial = "11111111-1111-1111-1111-111111111111"
        val deployed = "22222222-2222-2222-2222-222222222222"
        directory.resolve("state.json").writeText("""{"version":"$initial","message":"baseline","writes":0}""")
        directory.resolve("wrangler.json").writeText("""{"name":"fixture-dev","main":"entry.ts","compatibility_date":"2026-10-01"}""")
        directory.resolve("tool").apply {
            writeText("""
                #!/usr/bin/env python3
                import json,sys,pathlib
                args=sys.argv[1:]
                state=pathlib.Path('state.json'); value=json.loads(state.read_text())
                if args[2]=='deployments': print(json.dumps([{'versions':[{'version_id':value['version'],'percentage':100}]}]))
                elif args[2]=='versions': print(json.dumps({'id':value['version'],'annotations':{'workers/message':value['message']},'resources':{'script_runtime':{'compatibility_date':'2026-10-01','compatibility_flags':[]},'bindings':[]}}))
                elif '--dry-run' in args:
                    bundle=pathlib.Path(args[args.index('--outdir')+1]); bundle.mkdir(parents=True,exist_ok=True)
                    (bundle/'entry.js').write_text('export default {}')
                else:
                    assert args[2] in ['deploy','rollback']
                    if args[2]=='deploy': assert '--no-bundle' in args and pathlib.Path(args[3]).read_text()=='export default {}'
                    value['version']='$deployed' if args[2]=='deploy' else args[3]
                    value['message']=args[args.index('--message')+1]; value['writes']+=1
                    state.write_text(json.dumps(value))
            """.trimIndent())
            setExecutable(true)
        }
        val metadata = java.util.Properties().apply {
            GradlePlanningTest::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use(::load)
        }
        val files = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
            .joinToString(",") { BuildModelReader.json.encodeToString(kotlinx.serialization.serializer<String>(), it) }
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.model.*
            import dev.shibasis.dependeasy.web.WranglerDeploy
            import dev.shibasis.dependeasy.configuration.option
            import dev.shibasis.dependeasy.handles.*
            buildscript { dependencies { classpath(files($files)) } }
            apply<dev.shibasis.dependeasy.plugins.PipelinePlugin>()
            option(BuildOption.string(GradleProtocol.EnvironmentOption, "dev")).get()
            val deployment = WorkerDeployment(Environment("dev"), ServiceRef("fixture-dev"))
            val fleetWorker = FleetWorker(WorkerRef("fixture", "."), NodeId("receiptFixture", ":", "deployFixture"), listOf(deployment))
            val deploy = tasks.register<WranglerDeploy>("deployFixture") {
                workingDirectory.set(layout.projectDirectory)
                executable.set(layout.projectDirectory.file("tool").asFile.absolutePath)
                toolVersion.set("fixture")
                configuration.set("wrangler.json")
                declaration.set(BuildModelReader.json.encodeToString(FleetWorker.serializer(), fleetWorker))
                environmentName.set("dev")
                bindings.set(emptyList())
            }
            extensions.getByType<dev.shibasis.dependeasy.plugins.DependeasyExtension>().workspace {
                val release by verify { include(node(deploy)) }
            }
        """.trimIndent())
        fun git(vararg args: String) { assertEquals(0, ProcessBuilder("git", *args).directory(directory).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() }) }
        git("init", "--quiet")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "--allow-empty", "--quiet", "-m", "fixture")
        val environmentArgument = "-P${GradleProtocol.EnvironmentOption}=dev"
        fun bridge(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
            .withArguments(*tasks, environmentArgument, "--configuration-cache", "--stacktrace").build()
        bridge("exportBuildModel")
        val model = BuildModelReader.read(directory)
        val node = model.nodes.single { it.id.name == "deployFixture" }
        val spec = assertIs<KernelSpec.WranglerDeploy>(node.kernel?.spec)
        assertEquals("dev.shibasis.dependeasy.model.WorkerDeployKernel", node.kernel?.className)
        fun contract(request: GradleRequest): Pair<String,String> {
            val text = BuildModelReader.json.encodeToString(GradleRequest.serializer(), request) + "\n"
            val digest = BuildModelReader.digest(text)
            val file = directory.resolve("${GradleProtocol.RequestDirectory}/$digest.json").apply { parentFile.mkdirs(); writeText(text) }
            return digest to "-P${GradleProtocol.RequestOption}=${file.absolutePath}"
        }
        val request = GradleRequest(node = node.id, declaredAt = node.declaredAt,
            modelDigest = BuildModelReader.digest(BuildModelReader.encode(model)), arguments = listOf("--configuration-cache", environmentArgument))
        for (arguments in listOf(
            listOf("-P${GradleProtocol.EnvironmentOption}=prod"),
            listOf(environmentArgument, environmentArgument),
            listOf(environmentArgument, "-P${GradleProtocol.RequestOption}=other.json"),
            listOf(environmentArgument, "--project-dir=.."),
        )) {
            val (rejected, _) = contract(request.copy(arguments = arguments))
            assertFailsWith<IllegalArgumentException> {
                GradleInvocationKernel.validate(directory, directory.resolve("${GradleProtocol.RequestDirectory}/$rejected.json"), model)
            }
        }
        val (digest, option) = contract(request)
        bridge(GradleProtocol.PlanTask, option)
        val plan = BuildModelReader.readPlan(directory.resolve("${GradleProtocol.PlanDirectory}/$digest.json"))
        assertEquals(setOf(node.id), plan.tasks.map { it.id }.toSet())
        assertFalse(node.id in plan.preparationTasks)
        assertEquals(node.id, plan.deployments.single().spec.node)
        assertEquals(initial, plan.deployments.single().previousVersion)
        assertEquals(0, BuildModelReader.json.parseToJsonElement(directory.resolve("state.json").readText()).jsonObject.getValue("writes").jsonPrimitive.int)
        bridge(GradleProtocol.PlanTask, option)
        val refreshed = BuildModelReader.readPlan(directory.resolve("${GradleProtocol.PlanDirectory}/$digest.json"))
        assertNotEquals(plan.deployments.single().bundle, refreshed.deployments.single().bundle)
        val (_, execution) = contract(request.copy(expectedContextDigest = refreshed.contextDigest, deployments = refreshed.deployments))
        bridge(GradleProtocol.RunTask, execution)
        val receipt = WorkerDeployKernel.receipts(directory, node.id).single { it.action == "apply" }
        assertEquals(deployed, receipt.remoteVersion)
        assertEquals(initial, receipt.rollbackTarget)
        assertEquals(1, BuildModelReader.json.parseToJsonElement(directory.resolve("state.json").readText()).jsonObject.getValue("writes").jsonPrimitive.int)
        assertEquals(receipt.run, BuildModelReader.json.parseToJsonElement(directory.resolve("state.json").readText()).jsonObject.getValue("message").jsonPrimitive.content)
        val details = BuildModelReader.nodeDetails(directory, node.id.toString())
        assertTrue(details.getValue("deploymentReceipts").jsonArray.any { it.jsonObject["action"]?.jsonPrimitive?.content == "apply" })
        assertEquals(spec.deployment.service, receipt.service)
    }

}
