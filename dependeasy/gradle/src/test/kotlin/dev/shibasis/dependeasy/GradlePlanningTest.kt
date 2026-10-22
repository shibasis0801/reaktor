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
        directory.resolve("migrations").mkdirs()
        directory.resolve("migrations/0001_fixture.sql").writeText("SELECT 1;")
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
                val schema by migrate(dev.shibasis.dependeasy.model.DatabaseMigrations.Postgres(
                    directory = "migrations",
                    connections = mapOf(dev.shibasis.dependeasy.model.Environment("dev") to "FIXTURE_DATABASE_URL"),
                    sources = emptyList()))
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
        val schema = BuildModelReader.read(directory).nodes.single { it.id.name == "schema" }
        assertEquals(NodeKind.Deploy, schema.kind)
        assertEquals(Effect.Deploy, schema.effect)
        assertEquals("MigrationKernel", schema.kernel?.className?.substringAfterLast('.'))
        assertIs<KernelSpec.DatabaseMigration>(schema.kernel?.spec)
        assertEquals(listOf(Environment("dev")), schema.environments)
        assertTrue(BuildModelReader.read(directory).declarations.any {
            it.option?.name == GradleProtocol.EnvironmentOption && it.option?.value == "dev" && it.option?.scope == ":" })
        assertTrue(schema.inputs.flatMap { it.declarations }.any {
            it.kind == FileInputKind.Tree && it.identity == "migrations" && "*.sql" in it.includes })
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
        val unreviewed = runner("schema").buildAndFail()
        assertContains(unreviewed.output, "Database migrations require a reviewed build plan")
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
        directory.resolve("state.json").writeText("""{"version":"$initial","message":"baseline","writes":0,"secrets":["RETIRED","UNRELATED"]}""")
        val secretCanary = "fixture-private-secret-canary"
        directory.resolve("config").mkdirs()
        directory.resolve("config/worker.json").writeText("""{"CLIENT_SECRET":"$secretCanary","unrelated":{"nested":true}}""")
        directory.resolve("wrangler.json").writeText("""{"name":"fixture-dev","main":"entry.ts","compatibility_date":"2026-10-01","d1_databases":[{"binding":"DB","database_id":"fixture-db","migrations_dir":"migrations"}]}""")
        directory.resolve("migrations").mkdirs()
        directory.resolve("migrations/0001_fixture.sql").delete()
        directory.resolve("migrations/0001_init.sql").writeText("CREATE TABLE item (id INTEGER);")
        directory.resolve("tool").apply {
            writeText("""
                #!/usr/bin/env python3
                import json,sys,pathlib,sqlite3
                args=sys.argv[1:]
                state=pathlib.Path('state.json'); value=json.loads(state.read_text())
                if args[2]=='d1':
                    db=sqlite3.connect('fixture.db'); db.row_factory=sqlite3.Row
                    if args[3]=='execute':
                        rows=[dict(row) for row in db.execute(args[args.index('--command')+1])]
                        print(json.dumps([{'success':True,'results':rows}]))
                    else:
                        assert args[3:5]==['migrations','apply']
                        db.executescript("CREATE TABLE IF NOT EXISTS d1_migrations (id INTEGER PRIMARY KEY, name TEXT UNIQUE);")
                        for file in sorted(pathlib.Path('migrations').glob('*.sql')):
                            if not db.execute('SELECT 1 FROM d1_migrations WHERE name=?',[file.name]).fetchall():
                                db.executescript(file.read_text())
                                db.execute('INSERT INTO d1_migrations(name) VALUES (?)',[file.name]);db.commit()
                    db.close()
                elif args[2]=='secret':
                    if args[3]=='list': print(json.dumps([{'name':name} for name in value['secrets']]))
                    else:
                        assert args[3]=='bulk'
                        payload=json.load(sys.stdin)
                        assert payload=={'CLIENT_SECRET':'$secretCanary','RETIRED':None}
                        value['secrets']=sorted((set(value['secrets']) | {name for name,text in payload.items() if text is not None}) - {name for name,text in payload.items() if text is None})
                        value['writes']+=1
                        state.write_text(json.dumps(value))
                        print('$secretCanary')
                elif args[2]=='deployments': print(json.dumps([{'versions':[{'version_id':value['version'],'percentage':100}]}]))
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
            val deployment = WorkerDeployment(Environment("dev"), ServiceRef("fixture-dev"), secrets = listOf("CLIENT_SECRET"), retiredSecrets = listOf("RETIRED"))
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
            val secrets = tasks.register<dev.shibasis.dependeasy.cloudflare.WorkerSecrets>("fixtureSecrets") {
                workingDirectory.set(layout.projectDirectory)
                executable.set(layout.projectDirectory.file("tool").asFile.absolutePath)
                toolVersion.set("fixture")
                configuration.set("wrangler.json")
                declaration.set(BuildModelReader.json.encodeToString(FleetWorker.serializer(), fleetWorker))
                environmentName.set("dev")
                sourcePaths.set(listOf("config/worker.json"))
            }
            val migrations = tasks.register<dev.shibasis.dependeasy.cloudflare.D1Migration>("fixtureMigrations") {
                workingDirectory.set(layout.projectDirectory)
                executable.set(layout.projectDirectory.file("tool").asFile.absolutePath)
                toolVersion.set("fixture")
                configuration.set("wrangler.json")
                declaration.set(BuildModelReader.json.encodeToString(FleetWorker.serializer(), fleetWorker))
                environmentName.set("dev")
                environments.set(listOf("dev"))
                binding.set("DB")
                sourceFiles.from(fileTree("migrations") { include("*.sql") })
            }
            extensions.getByType<dev.shibasis.dependeasy.plugins.DependeasyExtension>().workspace {
                val release by verify { include(node(deploy), node(secrets), node(migrations)) }
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
        bridge(GradleProtocol.PlanTask, option)
        val rollbackPlan = BuildModelReader.readPlan(directory.resolve("${GradleProtocol.PlanDirectory}/$digest.json"))
        val (rollbackDigest, _) = contract(request.copy(expectedContextDigest = rollbackPlan.contextDigest, deployments = rollbackPlan.deployments))
        bridge("deployFixture", "-P${GradleProtocol.DeployActionOption}=rollback",
            "-P${GradleProtocol.DeployRequestOption}=${directory.resolve("${GradleProtocol.RequestDirectory}/$rollbackDigest.json").absolutePath}")
        val rollback = WorkerDeployKernel.receipts(directory, node.id).single { it.action == "rollback" }
        assertEquals(initial, rollback.remoteVersion)
        assertEquals(deployed, rollback.rollbackTarget)
        assertEquals(2, BuildModelReader.json.parseToJsonElement(directory.resolve("state.json").readText()).jsonObject.getValue("writes").jsonPrimitive.int)
        val secrets = model.nodes.single { it.id.name == "fixtureSecrets" }
        val secretsRequest = request.copy(node = secrets.id, declaredAt = secrets.declaredAt)
        val (secretsDigest, secretsOption) = contract(secretsRequest)
        val plannedSecrets = bridge(GradleProtocol.PlanTask, secretsOption)
        val secretsPlan = BuildModelReader.readPlan(directory.resolve("${GradleProtocol.PlanDirectory}/$secretsDigest.json"))
        assertEquals(listOf("CLIENT_SECRET"), secretsPlan.secrets.single().missing)
        assertEquals(listOf("RETIRED", "UNRELATED"), secretsPlan.secrets.single().extra)
        assertEquals(listOf("RETIRED"), secretsPlan.secrets.single().spec.retiredNames)
        assertFalse(secretCanary in plannedSecrets.output)
        assertFalse(secretCanary in BuildModelReader.json.encodeToString(GradlePlan.serializer(), secretsPlan))
        assertEquals(2, BuildModelReader.json.parseToJsonElement(directory.resolve("state.json").readText()).jsonObject.getValue("writes").jsonPrimitive.int)
        val rejected = GradleRunner.create().withProjectDir(directory)
            .withArguments("fixtureSecrets", environmentArgument, "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(rejected.output, "Worker secrets require a reviewed build plan")
        val (_, secretsExecution) = contract(secretsRequest.copy(expectedContextDigest = secretsPlan.contextDigest, secrets = secretsPlan.secrets))
        val appliedSecrets = bridge(GradleProtocol.RunTask, secretsExecution)
        assertFalse(secretCanary in appliedSecrets.output)
        assertEquals(3, BuildModelReader.json.parseToJsonElement(directory.resolve("state.json").readText()).jsonObject.getValue("writes").jsonPrimitive.int)
        val secretReceipt = WorkerDeployKernel.receipts(directory, secrets.id).single { it.action == "secrets-apply" }
        assertEquals(ReceiptStatus.Succeeded, secretReceipt.result)
        assertEquals(listOf("CLIENT_SECRET", "UNRELATED"), BuildModelReader.json.parseToJsonElement(directory.resolve("state.json").readText())
            .jsonObject.getValue("secrets").jsonArray.map { it.jsonPrimitive.content })
        val secretDetails = BuildModelReader.nodeDetails(directory, secrets.id.toString(), limit = 10)
        assertEquals(secrets.id.name, secretDetails.getValue("secretsPlan").jsonObject.getValue("spec").jsonObject.getValue("deployment").jsonObject.getValue("node").jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(secretDetails.getValue("deploymentReceipts").jsonArray.any { it.jsonObject["action"]?.jsonPrimitive?.content == "secrets-apply" })
        assertFalse(secretCanary in secretDetails.toString())
        val migration = model.nodes.single { it.id.name == "fixtureMigrations" }
        val migrationRequest = request.copy(node = migration.id, declaredAt = migration.declaredAt)
        val (migrationDigest, migrationOption) = contract(migrationRequest)
        val unreviewed = GradleRunner.create().withProjectDir(directory)
            .withArguments("fixtureMigrations", environmentArgument, "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(unreviewed.output, "D1 migrations require a reviewed build plan")
        bridge(GradleProtocol.PlanTask, migrationOption)
        val migrationPlan = BuildModelReader.readPlan(directory.resolve("${GradleProtocol.PlanDirectory}/$migrationDigest.json"))
        assertEquals(listOf("0001"), migrationPlan.migrations.single().pending)
        assertTrue(migrationPlan.migrations.single().applied.isEmpty())
        assertIs<DatabaseMigrations.D1>(migrationPlan.migrations.single().spec.database)
        val (_, migrationExecution) = contract(migrationRequest.copy(expectedContextDigest = migrationPlan.contextDigest, migrations = migrationPlan.migrations))
        bridge(GradleProtocol.RunTask, migrationExecution)
        val migrationReceipt = WorkerDeployKernel.receipts(directory, migration.id).single { it.action == "migrations-apply" }
        assertEquals(ReceiptStatus.Succeeded, migrationReceipt.result)
        assertEquals("0001_init.sql", migrationReceipt.remoteVersion)
        assertEquals("ledger-verified", migrationReceipt.phase)
        assertTrue(BuildModelReader.nodeDetails(directory, migration.id.toString()).containsKey("migrationPlan"))
    }

    @Test fun `ClickHouse binding plans read only and applies the exact sealed SQL`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"clickhouseFixture\"")
        directory.resolve("sql").mkdirs()
        directory.resolve("sql/setup.sql").writeText("CREATE DATABASE IF NOT EXISTS {database:Identifier};")
        directory.resolve("access").writeText("fixture access")
        directory.resolve("kubectl").apply {
            writeText("""
                #!/usr/bin/env python3
                import sys,json,pathlib
                args=sys.argv[1:]; root=pathlib.Path(__file__).parent
                assert pathlib.Path(args[1]).resolve()==(root/'access').resolve()
                assert args[0]=='--kubeconfig' and args[2]=='--request-timeout=20s'
                if args[3:]==['get','namespace','reaktor','-o','jsonpath={.metadata.uid}']:
                    print('fixture-namespace'); sys.exit(0)
                assert args[3:8]==['exec','-i','-n','reaktor','clickhouse-0']
                assert args[8:10]==['--','clickhouse-client']
                state=root/'state.json'; writes=int(state.read_text()) if state.exists() else 0
                sql=sys.stdin.read()
                if '--readonly=1' in args:
                    assert sql.startswith('SELECT ')
                    rows=[{'name':'fixture'}] if writes and 'system.databases' in sql else []
                    print(json.dumps({'data':rows}))
                else:
                    assert sql=='CREATE DATABASE IF NOT EXISTS fixture;'
                    state.write_text(str(writes+1))
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
            buildscript { dependencies { classpath(files($files)) } }
            apply<dev.shibasis.dependeasy.plugins.PipelinePlugin>()
            extensions.getByType<dev.shibasis.dependeasy.plugins.DependeasyExtension>().workspace {
                val sql by migrate(DatabaseMigrations.ClickHouse("sql", listOf("setup.sql"),
                    mapOf(Environment("dev") to "fixture"), "reaktor", "clickhouse-0", ServiceRef("clickhouse")),
                    kubeconfig = BuildOption.string("fixture.kubeconfig", "access"))
            }
        """.trimIndent())
        fun git(vararg args: String) {
            assertEquals(0, ProcessBuilder("git", *args).directory(directory).redirectErrorStream(true).start().let {
                it.inputStream.readBytes(); it.waitFor()
            })
        }
        git("init", "--quiet")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "--allow-empty", "--quiet", "-m", "fixture")
        fun bridge(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
            .withTestKitDir(directory.resolve("test-kit"))
            .withEnvironment(System.getenv() + ("PATH" to directory.absolutePath + File.pathSeparator + System.getenv("PATH")))
            .withArguments(*tasks, "--configuration-cache", "--stacktrace").build()
        bridge("exportBuildModel")
        val model = BuildModelReader.read(directory)
        val node = model.nodes.single { it.id.name == "sql" }
        assertEquals("ClickHouseMigrationKernel", node.kernel?.className?.substringAfterLast('.'))
        val spec = assertIs<KernelSpec.DatabaseMigration>(node.kernel?.spec)
        assertIs<DatabaseMigrations.ClickHouse>(spec.migration.database)
        assertEquals("access", spec.migration.kubeconfig)
        assertEquals(ToolRef("kubectl", "host-provided (unversioned)"), spec.tool)
        assertEquals(listOf(ServiceRef("clickhouse")), node.bindings)
        assertTrue(node.inputs.flatMap { it.declarations }.any { it.kind == FileInputKind.File && it.identity == "sql/setup.sql" })
        fun contract(request: GradleRequest): Pair<String, String> {
            val text = BuildModelReader.json.encodeToString(GradleRequest.serializer(), request) + "\n"
            val digest = BuildModelReader.digest(text)
            val file = directory.resolve("${GradleProtocol.RequestDirectory}/$digest.json").apply { parentFile.mkdirs(); writeText(text) }
            return digest to "-P${GradleProtocol.RequestOption}=${file.absolutePath}"
        }
        val request = GradleRequest(node = node.id, declaredAt = node.declaredAt,
            modelDigest = BuildModelReader.digest(BuildModelReader.encode(model)), arguments = listOf("--configuration-cache"))
        val (digest, option) = contract(request)
        bridge(GradleProtocol.PlanTask, option)
        val plan = BuildModelReader.readPlan(directory.resolve("${GradleProtocol.PlanDirectory}/$digest.json"))
        assertEquals(listOf("setup"), plan.migrations.single().pending)
        assertFalse(directory.resolve("state.json").exists())
        val repeat = bridge(GradleProtocol.PlanTask, option)
        assertContains(repeat.output, "Reusing configuration cache")
        val repeated = BuildModelReader.readPlan(directory.resolve("${GradleProtocol.PlanDirectory}/$digest.json"))
        assertEquals(plan.copy(configuredMillis = 0), repeated.copy(configuredMillis = 0))
        val (_, execution) = contract(request.copy(expectedContextDigest = plan.contextDigest, migrations = plan.migrations))
        bridge(GradleProtocol.RunTask, execution)
        assertEquals("1", directory.resolve("state.json").readText())
        val receipt = WorkerDeployKernel.receipts(directory, node.id).single { it.action == "migrations-apply" }
        assertEquals(ReceiptStatus.Succeeded, receipt.result)
        assertEquals("sql-applied", receipt.phase)
        assertEquals(ServiceRef("clickhouse"), receipt.service)
        assertTrue(BuildModelReader.nodeDetails(directory, node.id.toString()).containsKey("migrationPlan"))
    }

}
