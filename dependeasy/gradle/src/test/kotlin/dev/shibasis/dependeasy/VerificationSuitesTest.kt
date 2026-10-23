package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class VerificationSuitesTest {
    @TempDir lateinit var root: File
    private fun write(path: String, text: String) = root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    @Test fun `suite kernels execute pinned argv and exclude environment values from the model`() {
        write("settings.gradle.kts", "rootProject.name = \"verificationFixture\"")
        write("suite/input.feature", "Feature: fixture")
        write("suite/input.js", "export default function() {}")
        write("config/karate-config.js", "function fn() { return {}; }")
        write("environment.env", "PRIVATE_FIXTURE=never-export-this-marker\n")
        write("jar", "fixture")
        write("tool", "#!/bin/sh\nprintf '%s\n' \"\$@\" >> arguments\nprintf '%s' \"\$PRIVATE_FIXTURE\" > private-proof\n").setExecutable(true)
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.verification.*
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val karate = tasks.register<KarateSuite>("karate") {
                workingDirectory.set(layout.projectDirectory); executable.set(file("tool").absolutePath); toolVersion.set("2.0.0")
                jar.set(layout.projectDirectory.file("jar")); suites.set(listOf("suite/input.feature")); tags.set(listOf("@smoke"))
                arguments.set(listOf("--configdir", "config")); sourceFiles.from(file("suite/input.feature")); environmentFiles.from(file("environment.env"))
                outputDirectories.from(layout.buildDirectory.dir("reports/karate"))
            }
            val k6 = tasks.register<K6Suite>("k6") {
                workingDirectory.set(layout.projectDirectory); executable.set(file("tool").absolutePath); toolVersion.set("2.1.0")
                script.set(layout.projectDirectory.file("suite/input.js")); environmentFiles.from(file("environment.env"))
                sourceFiles.from(file("suite/input.js")); outputFiles.from(layout.buildDirectory.file("reports/k6/summary.json"))
            }
            dependeasy.workspace { val suites by verify { include(node(karate), node(k6)) } }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments("suites", "exportBuildModel", "--configuration-cache").build()
        run()
        assertEquals("never-export-this-marker", root.resolve("private-proof").readText())
        val model = BuildModelReader.read(root)
        assertTrue(model.nodes.any { it.kernel?.spec is KernelSpec.KarateSuite })
        assertTrue(model.nodes.any { it.kernel?.spec is KernelSpec.K6Suite })
        assertFalse(root.resolve(BuildModelReader.RelativePath).readText().contains("never-export-this-marker"))
        val argv = root.resolve("arguments").readText()
        assertContains(argv, "--summary-export")
        assertContains(argv, "@smoke")
        assertContains(run().output, "Reusing configuration cache")
    }
    @Test fun `signature replacement requires opt in and local host propagates to its target`() {
        write("settings.gradle.kts", "rootProject.name = \"physicalFixture\"")
        write("flow.yaml", "appId: fixture.app\n---\n- launchApp\n")
        write("unused.yaml", "appId: fixture.app\n---\n- launchApp\n")
        write("app.apk", "fixture")
        write("runner", """
            #!/usr/bin/env python3
            import sys,json
            from pathlib import Path
            if 'devices' in sys.argv:
                print(json.dumps([dict(platform='android',kind='device',id='fixture-phone',ready=True)]))
            else:
                Path('flow-ran').write_text('passed')
        """.trimIndent()).setExecutable(true)
        write("adb", """
            #!/usr/bin/env python3
            import sys
            from pathlib import Path
            if 'uninstall' in sys.argv:
                Path('uninstalled').write_text('explicit')
            elif not Path('uninstalled').exists():
                print('INSTALL_FAILED_UPDATE_INCOMPATIBLE')
                sys.exit(1)
        """.trimIndent()).setExecutable(true)
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.verification.DeviceFlow
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val flow = tasks.register<DeviceFlow>("flow") {
                workingDirectory.set(layout.projectDirectory); platform.set(DevicePlatform.Android); applicationId.set("fixture.app")
                appOutput.set("physicalFixture:app:outputAndroidApkDebug"); appFile.set(layout.projectDirectory.file("app.apk"))
                runner.set(layout.projectDirectory.file("runner")); runnerVersion.set("1.1.27"); adbExecutable.set(layout.projectDirectory.file("adb"))
                declaredFlowPaths.set(listOf(file("flow.yaml").absolutePath, file("unused.yaml").absolutePath))
                reportDirectory.set(layout.buildDirectory.dir("reports/flow")); contractFile.set(layout.buildDirectory.file("dependeasy/device/flow.json"))
                installApplication.set(true)
                uninstallOnSignatureMismatch.set(providers.gradleProperty("allowUninstall").map(String::toBooleanStrict).orElse(false))
            }
            dependeasy.workspace { val phoneCheck by verify { include(node(flow, OutputKind.VerificationResults)) } }
        """.trimIndent())
        fun runner(vararg args: String) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")
        runner("exportBuildModel").build()
        val hosts = BuildModelReader.json.decodeFromString<RemoteHostsContract>(root.resolve("build/dependeasy/remote/hosts.json").readText())
        assertEquals(ExecutionHost.Local, hosts.taskHosts.getValue(":phoneCheck").execution)
        val denied = runner("phoneCheck").buildAndFail()
        assertContains(denied.output, "signature replacement requires")
        assertFalse(root.resolve("uninstalled").exists())
        assertFalse(root.resolve("flow-ran").exists())
        fun selected() = runner("flow", "--platform", "android", "--flow", "flow", "--artifacts", "always", "-PallowUninstall=true").build()
        selected()
        assertEquals("explicit", root.resolve("uninstalled").readText())
        assertEquals("passed", root.resolve("flow-ran").readText())
        val contract = BuildModelReader.json.decodeFromString<DeviceFlowContract>(root.resolve("build/dependeasy/device/flow.json").readText())
        assertEquals("android", contract.platform)
        assertEquals("always", contract.artifacts)
        assertEquals(listOf(root.resolve("flow.yaml").canonicalPath), contract.flows)
        assertContains(selected().output, "Reusing configuration cache")
        val invalid = runner("flow", "--flow", "undeclared", "-PallowUninstall=true").buildAndFail()
        assertContains(invalid.output, "Select exactly one declared flow: undeclared")
    }

    @Test fun `device task options select platform owned inputs before execution`() {
        write("settings.gradle.kts", """rootProject.name = "deviceOptions"; enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS"); include("app", "apple", "phones")""")
        write("package.json", """{"name":"device-options","private":true}""")
        write("app/build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { java }
            val apk = tasks.register("apk") { outputs.dir(layout.buildDirectory.dir("apk")) }
            publishOutput(OutputKind.AndroidApk, layout.buildDirectory.dir("apk"), Build.Debug, producer = apk)
        """.trimIndent())
        write("apple/release.input.json", BuildModelReader.json.encodeToString(ReleasePlan.serializer(),
            ReleasePlan(product = "fixture", versionName = "1", buildNumber = 1, bundleId = "fixture.app",
                team = "fixture", artifacts = mapOf("iosDebugApp" to "ios/app.bin"), commands = emptyMap())))
        write("apple/build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { java }
            val release = tasks.register<Copy>("release") {
                from(layout.projectDirectory.file("release.input.json"))
                into(layout.buildDirectory)
                rename { "release.json" }
            }
            publishOutput(OutputKind.GeneratedContract, layout.buildDirectory.file("release.json"), Contract.AppleRelease, producer = release)
        """.trimIndent())
        write("android/login.yaml", "appId: fixture.android\n---\n- launchApp\n")
        write("ios/app.bin", "fixture app output")
        write("ios/login.yaml", "appId: fixture.ios\n---\n- launchApp\n")
        write("ios/unused.yaml", "appId: fixture.ios\n---\n- launchApp\n")
        write("prepare.py", "from pathlib import Path\nPath('prepared').write_text('ready')\n")
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.workspace {
                val prepared by prepare(DeviceHook.PythonDevice("prepare.py"))
                val phoneChecks by verify(projects.phones)
                val pr by verify()
                delivery(after = pr) {
                    val appRelease by release(output(projects.apple, OutputKind.GeneratedContract, Contract.AppleRelease), LaneHandle("fixture", "internal"))
                }
                packageScripts()
            }
        """.trimIndent())
        write("phones/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.deviceTests
            import dev.shibasis.dependeasy.verification.DeviceFlow
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.device-tests") }
            deviceTests(mapOf(DevicePlatform.Android to androidApk(projects.app, Build.Debug),
                DevicePlatform.Ios to output(projects.apple, OutputKind.GeneratedContract, Contract.AppleRelease)), "fixture.app") {
                val flows by suite(DeviceSuite(mapOf(
                    DevicePlatform.Android to DeviceFlowSelection("android", files = listOf("login.yaml")),
                    DevicePlatform.Ios to DeviceFlowSelection("ios", exclude = listOf("unused.yaml"))),
                    environmentAllowList = setOf("FIXTURE_MESSAGE"),
                    optionalEnvironmentFiles = listOf("device.local.env")))
                alias("test:phones", flows, DeviceOptions(DevicePlatform.Ios, "login", ArtifactPolicy.Always))
            }
            tasks.register<Copy>("inspectIosApplication") {
                from(tasks.named<DeviceFlow>("flows").get().applicationOutputs)
                into(layout.buildDirectory.dir("inspected"))
            }
        """.trimIndent())
        fun runner(flow: String, vararg extra: String) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(listOf(":phones:flows", "--platform", "ios") +
                (if (flow.isBlank()) emptyList() else listOf("--flow", flow)) +
                listOf("--artifacts", "always", "-x", ":phones:flows", "exportBuildModel", "generatePackageScripts", "--configuration-cache", "--stacktrace") + extra)
        fun run(flow: String = "login") = runner(flow).build()
        fun spec(): KernelSpec.DeviceFlow = assertIs(BuildModelReader.read(root).nodes.single { it.id.project == ":phones" && it.id.name == "flows" }.kernel?.spec)
        GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("generatePackageScripts", "--configuration-cache", "--stacktrace").build()
        val generatedManifest = root.resolve("package.json").readText()
        val first = run()
        assertEquals(generatedManifest, root.resolve("package.json").readText())
        assertNull(first.task(":phones:flows"))
        assertNull(first.task(":app:apk"))
        assertNull(first.task(":apple:release"))
        assertEquals("ios", spec().platform)
        assertEquals("deviceOptions:apple:outputGeneratedContractAppleRelease", spec().appOutput)
        assertEquals(listOf("ios/login.yaml"), spec().flows)
        assertEquals("always", spec().artifacts)
        assertEquals(listOf("FIXTURE_MESSAGE"), spec().environmentAllowList)
        assertFalse(Regex("bestbuds|manna", RegexOption.IGNORE_CASE).containsMatchIn(BuildModelReader.json.encodeToString(spec())))
        val model = BuildModelReader.read(root)
        assertTrue(model.edges.any { it is Edge.After && it.from == NodeId("deviceOptions", ":", "appRelease") && it.to == NodeId("deviceOptions", ":", "pr") })
        val options = model.nodes.single { it.id == NodeId("deviceOptions", ":phones", "flows") }.options.associateBy { it.name }
        assertEquals(setOf("platform", "flow", "artifacts"), options.keys)
        assertEquals(setOf("android", "ios"), options.getValue("platform").allowedValues.map(String::lowercase).toSet())
        assertTrue(options.values.all { it.type == OptionType.String && !it.multiple && it.description.isNotBlank() })
        assertTrue(options.getValue("flow").allowedValues.isEmpty())
        assertTrue(options.getValue("artifacts").allowedValues.any { it.equals("always", ignoreCase = true) })
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == NodeId("deviceOptions", ":phones", "flows") && it.to == NodeId("deviceOptions", ":apple", "outputGeneratedContractAppleRelease") })
        assertTrue(model.edges.any { it is Edge.After && it.from == NodeId("deviceOptions", ":phones", "flows") && it.to == NodeId("deviceOptions", ":", "maestroRunnerSetup") })
        assertEquals(ExecutionHost.Local, model.requiredHost(NodeId("deviceOptions", ":", "phoneChecks")).execution)
        assertFalse(root.resolve("phones/build/dependeasy/device/flows.json").exists())
        assertContains(root.resolve("package.json").readText(), ":phones:flows --platform ios --flow login --artifacts Always")
        assertContains(run().output, "Reusing configuration cache")
        run("")
        assertContains(run("").output, "Reusing configuration cache")
        write("ios/extra.yaml", "appId: fixture.ios\n---\n- launchApp\n")
        run("")
        assertEquals(listOf("ios/extra.yaml", "ios/login.yaml"), spec().flows)
        write("device.local.env", "PRIVATE_FIXTURE_TOKEN=never-in-public-model\n")
        run("")
        assertContains(spec().environmentKeys, "PRIVATE_FIXTURE_TOKEN")
        assertFalse(root.resolve("build/dependeasy/model.json").readText().contains("never-in-public-model"))
        root.resolve("device.local.env").delete()
        run("")
        assertFalse("PRIVATE_FIXTURE_TOKEN" in spec().environmentKeys)
        assertContains(runner("undeclared").buildAndFail().output, "Select exactly one declared flow: undeclared")
        fun installing() = runner("login", "-Pdependeasy.installApplication=true", ":phones:inspectIosApplication").build()
        val installed = installing()
        assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, installed.task(":apple:release")?.outcome)
        assertNull(installed.task(":pr"))
        assertNull(installed.task(":app:apk"))
        assertEquals("fixture app output", root.resolve("phones/build/inspected/app.bin").readText())
        assertContains(installing().output, "Reusing configuration cache")
        fun prepare() = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("prepared", "exportBuildModel", "--configuration-cache", "--stacktrace").build()
        prepare()
        assertEquals("ready", root.resolve("prepared").readText())
        assertContains(root.resolve("build/dependeasy/device/prepared-preparation.txt").readText(), "result=success")
        val prepared = BuildModelReader.read(root).nodes.single { it.id.name == "prepared" }
        assertIs<KernelSpec.Process>(prepared.kernel?.spec)
        assertEquals(HostRequirement(HostPlatform.Macos, ExecutionHost.Local), prepared.host)
        assertContains(prepare().output, "Reusing configuration cache")
    }


    @Test fun `worker contract membership tracks optional suites with cache reuse`() {
        write("settings.gradle.kts", """rootProject.name = "workerOptions"; include("workers")""")
        write("package.json", """{"name":"worker-options","private":true}""")
        write("build.gradle.kts", """plugins { id("dev.shibasis.dependeasy.workspace") }""")
        write("workers/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.workers
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            val prod = Environment("prod")
            val dev = Environment("dev")
            workers(Delivery(listOf(dev, prod), prod, Delivery.Cloudflare("fixture-account", emptyList(), true)), contracts = WorkerContractTests("config")) {
                worker(WorkerRef("fixture", "worker"), listOf(WorkerDeployment(dev, ServiceRef("fixture-worker-dev")), WorkerDeployment(prod, ServiceRef("fixture-worker"))))
            }
        """.trimIndent())
        fun run(vararg options: String) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("exportBuildModel", *options, "--configuration-cache", "--stacktrace").build()
        fun suites() = BuildModelReader.read(root).nodes.filter { it.id.project == ":workers" }
            .mapNotNull { node -> (node.kernel?.spec as? KernelSpec.KarateSuite)?.let { node.id.name to it.tags } }.toMap()
        run()
        assertTrue(suites().isEmpty())
        assertContains(run().output, "Reusing configuration cache")
        write("tests/worker/karate/input.feature", "Feature: fixture")
        assertFalse(run().output.contains("Reusing configuration cache"))
        val base = mapOf("karateWorkerAuth" to emptyList<String>(), "karateWorkerSmoke" to listOf("@smoke"))
        assertEquals(base, suites())
        assertContains(run().output, "Reusing configuration cache")
        run("-Pdependeasy.environment=dev")
        assertEquals(base, suites())
        assertContains(run("-Pdependeasy.environment=dev").output, "Reusing configuration cache")
        run()
        write("tests/worker/karate/behaviour.feature", "Feature: fixture")
        write("tests/worker/karate/public.feature", "Feature: fixture")
        assertFalse(run().output.contains("Reusing configuration cache"))
        assertEquals(base + mapOf("karateWorkerBehaviour" to listOf("@behaviour"), "karateWorkerPublic" to emptyList()), suites())
        assertContains(run().output, "Reusing configuration cache")
        assertTrue(root.resolve("tests/worker/karate/behaviour.feature").delete())
        assertTrue(root.resolve("tests/worker/karate/public.feature").delete())
        assertFalse(run().output.contains("Reusing configuration cache"))
        assertEquals(base, suites())
        assertTrue(root.resolve("tests/worker/karate/input.feature").delete())
        assertTrue(root.resolve("tests/worker/karate").delete())
        assertFalse(run().output.contains("Reusing configuration cache"))
        assertTrue(suites().isEmpty())
        assertContains(run().output, "Reusing configuration cache")
    }

    @Test fun `pinned Karate and k6 execute real HTTP suites`() {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/fixture") { exchange ->
            requests.incrementAndGet()
            val body = """{"value":"passed"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/fixture"
            write("settings.gradle.kts", "rootProject.name = \"pinnedVerificationFixture\"; enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\"); include(\"load\", \"workers\")")
            write("package.json", """{"name":"pinned-verification-fixture","private":true}""")
            write("suite/input.feature", """
                Feature: local service qualification
                Scenario: fetch declared service
                  Given url fixtureBaseUrl
                  When method get
                  Then status 200
                  And match response.value == 'passed'
                  And match java.lang.System.getenv('FIXTURE_DECLARED') == 'declared'
            """.trimIndent())
            write("suite/input.js", """
                import http from 'k6/http';
                import { check } from 'k6';
                export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
                export default function() {
                  const response = http.get('$url');
                  check(response, {
                    'declared service': value => value.status === 200 && value.json('value') === 'passed',
                    'required environment wins': () => __ENV.FIXTURE_PRIORITY === 'required',
                    'declared environment reaches runner': () => __ENV.FIXTURE_DECLARED === 'declared'
                  });
                }
            """.trimIndent())
            write("config/karate-config.js", """
                function fn() {
                  var fleet = karate.read('file:build/dependeasy/contracts/cloudflare/fleet.json');
                  return { fixtureBaseUrl: fleet.workers[0].deployments[0].endpoint };
                }
            """.trimIndent())
            write("config/required.env", "FIXTURE_PRIORITY=required\n")
            write("worker/wrangler.json", """{"name":"fixture-worker","main":"src/index.ts"}""")
            write("tests/worker/karate/input.feature", root.resolve("suite/input.feature").readText())
            write("workers/build.gradle.kts", """
                import dev.shibasis.dependeasy.components.workers
                import dev.shibasis.dependeasy.model.*
                plugins { id("dev.shibasis.dependeasy.worker-fleet") }
                val prod = Environment("prod")
                workers(Delivery(listOf(prod), prod, Delivery.Cloudflare("fixture-account", emptyList(), true)), contracts = WorkerContractTests("config",
                    environment = mapOf(prod to mapOf("FIXTURE_DECLARED" to "declared")))) {
                    worker(WorkerRef("fixture", "worker"), listOf(WorkerDeployment(prod, ServiceRef("fixture-worker"), endpoint = "$url")))
                }
            """.trimIndent())
            write("load/build.gradle.kts", """
                import dev.shibasis.dependeasy.components.loadTests
                import dev.shibasis.dependeasy.handles.*
                import dev.shibasis.dependeasy.model.*
                plugins { id("dev.shibasis.dependeasy.load-tests") }
                enum class Suites(val spec: LoadSuite) {
                    realKarate(LoadSuite.Karate("suite", "config",
                        environment = mapOf("FIXTURE_DECLARED" to "declared"),
                        environmentFiles = listOf("config/required.env"),
                        optionalEnvironmentFiles = listOf("config/optional.env"),
                        aliases = listOf(PackageScript("karate:fixture")))),
                    realK6(LoadSuite.K6("suite/input.js",
                        environment = mapOf("FIXTURE_DECLARED" to "declared"),
                        environmentFiles = listOf("config/required.env"),
                        optionalEnvironmentFiles = listOf("config/optional.env"),
                        aliases = listOf(PackageScript("k6:fixture"))));
                }
                loadTests(Suites.entries.associateWith { it.spec },
                    output(projects.workers, OutputKind.GeneratedContract, Contract.WorkerFleet))
            """.trimIndent())
            write("build.gradle.kts", """
                plugins { id("dev.shibasis.dependeasy.workspace") }
                workspace {
                    val qualification by verify(projects.load)
                    packageScripts()
                }
            """.trimIndent())
            fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
                .withArguments(*(tasks.toList().ifEmpty { listOf("qualification", "exportBuildModel", "generatePackageScripts") }).toTypedArray(),
                    "--configuration-cache", "--stacktrace").build()
            val exported = run("exportBuildModel", "generatePackageScripts")
            assertEquals(0, requests.get())
            assertNull(exported.task(":karateSetup"))
            assertNull(exported.task(":k6Setup"))
            val declared = BuildModelReader.read(root)
            assertContains(BuildModelKernel.routing(declared, "pinnedVerificationFixture").environment, "FIXTURE_DECLARED")
            val installerEdges = listOf("realKarate" to "karateSetup", "realK6" to "k6Setup").map { (suite, tool) ->
                val source = NodeId("pinnedVerificationFixture", ":load", suite)
                val target = NodeId("pinnedVerificationFixture", ":", tool)
                assertEquals(listOf(":$tool"), assertNotNull(declared.nodes.single { it.id == target }.invocation as? Invocation.Gradle).tasks)
                assertFalse(declared.nodes.any { it.id.project == ":load" && it.id.name == tool })
                Edge.After(source, target).also { assertContains(declared.edges, it) }
            }
            assertContains(declared.edges, Edge.Consumes(NodeId("pinnedVerificationFixture", ":load", "realKarate"),
                NodeId("pinnedVerificationFixture", ":workers", "outputGeneratedContractWorkerFleet"), "GeneratedContract:WorkerFleet"))
            val generatedContract = root.resolve("build/dependeasy/contracts/cloudflare/fleet.json")
            assertFalse(generatedContract.exists())
            run()
            assertTrue(generatedContract.isFile)
            assertEquals(2, requests.get())
            assertTrue(root.resolve("load/build/reports/karate/realKarate").walkTopDown().any { it.isFile })
            val summary = BuildModelReader.json.parseToJsonElement(root.resolve("load/build/reports/k6/realK6.json").readText())
            assertContains(summary.toString(), "checks")
            val scripts = BuildModelReader.json.parseToJsonElement(root.resolve("package.json").readText()).toString()
            assertContains(scripts, "./gradlew :load:realKarate")
            assertContains(scripts, "./gradlew :load:realK6")
            assertContains(run().output, "Reusing configuration cache")
            val repeated = BuildModelReader.read(root)
            installerEdges.forEach { assertContains(repeated.edges, it) }
            assertEquals(4, requests.get())
            write("config/optional.env", "PRIVATE_OPTIONAL=never-export-this-value\nFIXTURE_PRIORITY=optional\n")
            run()
            val model = BuildModelReader.read(root)
            val specs = model.nodes.filter { it.id.project == ":load" }.mapNotNull { it.kernel?.spec }
            assertContains(specs.filterIsInstance<KernelSpec.KarateSuite>().single().execution.environmentKeys, "PRIVATE_OPTIONAL")
            assertContains(specs.filterIsInstance<KernelSpec.K6Suite>().single().execution.environmentKeys, "PRIVATE_OPTIONAL")
            assertFalse(root.resolve(BuildModelReader.RelativePath).readText().contains("never-export-this-value"))
            assertContains(run().output, "Reusing configuration cache")
            assertTrue(root.resolve("config/optional.env").delete())
            run()
            val withoutOptional = BuildModelReader.read(root).nodes.mapNotNull { it.kernel?.spec }.filterIsInstance<KernelSpec.K6Suite>().single()
            assertFalse("PRIVATE_OPTIONAL" in withoutOptional.execution.environmentKeys)
            val workerSuite = model.nodes.single { it.id.project == ":workers" && it.id.name == "karateWorkerAuth" }
            assertContains(assertIs<KernelSpec.KarateSuite>(workerSuite.kernel?.spec).execution.environmentKeys, "FIXTURE_DECLARED")
            val workerProducer = NodeId("pinnedVerificationFixture", ":workers", "generateWorkerFleet")
            assertContains(model.edges, Edge.Consumes(workerSuite.id, workerProducer, "generateWorkerFleet"))
            assertContains(model.edges, Edge.After(workerSuite.id, workerProducer))
            assertTrue(generatedContract.delete())
            val beforeWorker = requests.get()
            run(":workers:karateWorkerAuth")
            assertTrue(generatedContract.isFile)
            assertEquals(beforeWorker + 1, requests.get())
        } finally { server.stop(0) }
    }

}
