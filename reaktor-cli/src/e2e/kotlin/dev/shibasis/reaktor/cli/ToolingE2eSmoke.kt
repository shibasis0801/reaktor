package dev.shibasis.reaktor.cli

import dev.shibasis.reaktor.tooling.JvmProjectDiscovery
import dev.shibasis.reaktor.tooling.AdHocProcessPlan
import dev.shibasis.reaktor.tooling.OutputChannel
import dev.shibasis.reaktor.tooling.RunEvent
import dev.shibasis.reaktor.tooling.RunStatus
import dev.shibasis.reaktor.tooling.SafetyApproval
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import dev.shibasis.reaktor.tooling.TaskId
import dev.shibasis.reaktor.tooling.TaskInvocation
import dev.shibasis.reaktor.tooling.platformExecutable
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.clikt.core.UsageError
import java.nio.file.Files
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Executable E2E smoke; intentionally uses no unit-test framework or mocks. */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--ambient-target-value") {
        println("ambient-target=${System.getenv("TARGET_ENV").orEmpty()}")
        return
    }
    if (args.firstOrNull() == "--ambient-target-plan") {
        runAmbientTargetPlanChild()
        return
    }
    runBlocking {
    val fixture = Files.createTempDirectory("reaktor-tooling-e2e-").toFile()
    try {
        val packageJson = buildJsonObject {
            put("name", "tooling-e2e-fixture")
            put("reaktor", buildJsonObject {
                put("name", "tooling-e2e-fixture")
                put("cloud", buildJsonObject {
                    put("dagger", ".")
                    put("pulumi", ".")
                })
                put("targets", buildJsonObject {
                    put("web", buildJsonObject {
                        put("runtime", "js-web")
                        put("deploy", "smoke:deploy-approval")
                    })
                })
            })
            put("workspaces", buildJsonArray { add("targets/e2e-worker") })
            put("scripts", buildJsonObject {
                put(
                    "smoke:success",
                    "node -e \"console.log('smoke-out'); console.error('smoke-err'); console.log('API_TOKEN=fixture-value')\"",
                )
                put("smoke:failure", "node -e \"console.error('expected-failure'); process.exit(7)\"")
                put(
                    "smoke:timeout",
                    "node -e \"const c=require('child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'}); console.log('timeout-child-pid='+c.pid); setInterval(()=>{},1000)\"",
                )
                put(
                    "smoke:cancel",
                    "node -e \"const c=require('child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'}); console.log('child-pid='+c.pid); setInterval(()=>{},1000)\"",
                )
                put(
                    "smoke:noise",
                    "node -e \"for(let i=0;i<384;i++) console.log(String(i).padStart(4,'0')+':'+'x'.repeat(40000))\"",
                )
                put(
                    "smoke:unconsumed",
                    "node -e \"for(let i=0;i<100000;i++) console.log('unconsumed-'+i); setInterval(()=>{},1000)\"",
                )
                put(
                    "smoke:args",
                    "node -e \"console.log(process.argv.slice(1).join('|'))\"",
                )
                put(
                    "smoke:deploy-approval",
                    "node -e \"require('fs').writeFileSync('approval-sentinel','executed')\"",
                )
                put("fastlane:android:play:internal", "FASTLANE_SKIP_UPDATE_CHECK=1 ./fastlane/run.sh android play_internal")
                put("fastlane:ios:testflight", "FASTLANE_SKIP_UPDATE_CHECK=1 ./fastlane/run.sh ios testflight_internal")
                put("fastlane:ios:setup-signing", "FASTLANE_SKIP_UPDATE_CHECK=1 ./fastlane/run.sh ios setup_signing")
                put("smoke:local-worker", "wrangler dev")
                put(
                    "maestro:android:prod",
                    "npm run maestro:android:build && MAESTRO_APP_ID=ai.bestbuds.app ../reaktor/tools/maestro/run-android-flow.sh tests/bestbudsAndroid -e APP_ENV=prod",
                )
                put(
                    "fastlane:android:maestro:release",
                    "FASTLANE_SKIP_UPDATE_CHECK=1 ./fastlane/run.sh android maestro_release",
                )
                put("fastlane:android:maestro", "FASTLANE_SKIP_UPDATE_CHECK=1 ./fastlane/run.sh android maestro")
                put("fastlane:ios:maestro", "FASTLANE_SKIP_UPDATE_CHECK=1 ./fastlane/run.sh ios maestro")
                put("fastlane:ios:maestro:release", "FASTLANE_SKIP_UPDATE_CHECK=1 ./fastlane/run.sh ios maestro_release")
                put(
                    "karate:configServer:public",
                    "../reaktor/tools/karate/run-karate.sh --configdir tests/support/karate tests/configServer/karate",
                )
                put(
                    "karate:configServer:public:dev",
                    "node scripts/run-with-env-files.mjs tests/support/karate/dev.env -- npm run karate:configServer:public",
                )
                put("smoke:delegated", "npm run deploy --workspace=e2e-worker-package")
                put("smoke:cd-delegated", "cd targets/e2e-worker && ./deploy.sh")
                put("smoke:sourced-helper", "./scripts/with-helper.sh")
                put(
                    "karate:nested:dev",
                    "node scripts/run-with-env-files.mjs tests/support/karate/dev.env -- npm run karate:configServer:public",
                )
                put("smoke:k3s-status", "./cloud/k3s/status.sh")
            })
        }
        fixture.resolve("package.json").writeText(packageJson.toString())
        fixture.resolve("settings.gradle.kts").writeText("rootProject.name = \"tooling-e2e\"\ninclude(\":e2eModule\")\n")
        fixture.resolve("gradlew").apply {
            writeText("#!/bin/sh\nprintf 'gradle-task=%s\\n' \"${'$'}*\"\n")
            check(setExecutable(true)) { "could not make E2E Gradle wrapper executable" }
        }
        val delegatedPackage = fixture.resolve("targets/e2e-worker/package.json").apply {
            parentFile.mkdirs()
            writeText("""{"name":"e2e-worker-package","scripts":{"deploy":"wrangler deploy"}}""")
        }
        val delegatedShell = fixture.resolve("targets/e2e-worker/deploy.sh").apply {
            writeText("#!/bin/sh\nprintf 'delegated-shell\\n'\n")
            check(setExecutable(true))
        }
        val workerSource = fixture.resolve("targets/e2e-worker/src/index.ts").apply {
            parentFile.mkdirs()
            writeText("export default { fetch() { return new Response('ok') } }\n")
        }
        fixture.resolve("targets/e2e-worker/wrangler.json").writeText(
            """{"name":"e2e-worker","main":"src/index.ts"}""",
        )
        fixture.resolve("fastlane/run.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexit 0\n")
            check(setExecutable(true))
        }
        val fastfile = fixture.resolve("fastlane/Fastfile").apply {
            writeText("lane :maestro do\n  sh('tools/maestro/run-android-flow.sh tests/bestbudsAndroid')\nend\n")
        }
        fixture.resolve("targets/appDarwin/Gemfile").apply {
            parentFile.mkdirs()
            writeText("source 'https://rubygems.org'\ngem 'fastlane'\n")
        }
        fixture.resolve("tests/bestbudsAndroid/base.yaml").apply {
            parentFile.mkdirs()
            writeText("appId: ai.bestbuds.fixture\n---\n- launchApp\n")
        }
        fixture.resolve("tools/maestro/common.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
        }
        fixture.resolve("tools/maestro/run-android-flow.sh").apply {
            writeText("#!/bin/sh\nsource \"${'$'}{SCRIPT_DIR}/common.sh\"\n")
        }
        val sourcedHelper = fixture.resolve("scripts/helper.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
        }
        fixture.resolve("scripts/with-helper.sh").apply {
            writeText("#!/bin/sh\nSCRIPT_DIR=${'$'}(cd \"${'$'}(dirname \"${'$'}0\")\" && pwd)\nsource \"${'$'}SCRIPT_DIR/helper.sh\"\n")
            check(setExecutable(true))
        }
        fixture.resolve("tests/configServer/karate/smoke.feature").apply {
            parentFile.mkdirs()
            writeText("Feature: nested wrapper closure\n")
        }
        fixture.resolve("tests/support/karate/dev.env").apply {
            parentFile.mkdirs()
            writeText("TARGET_ENV=dev\n")
        }
        fixture.resolve("tools/karate/run-karate.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
        }
        val k3sKubeconfig = fixture.resolve("cloud/k3s/kubeconfig").apply {
            parentFile.mkdirs()
            writeText("apiVersion: v1\nclusters: []\n")
        }
        fixture.resolve("cloud/k3s/status.sh").apply {
            writeText("#!/bin/sh\nKUBECONFIG=${'$'}SCRIPT_DIR/kubeconfig\n")
            check(setExecutable(true))
        }
        val moduleBuild = fixture.resolve("e2eModule/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("plugins { base }\n")
        }
        verifyCliCloudFailureAndNestedDoctor(fixture)

        ProcessRunner(Terminal()).let { runner ->
            check(!runner.requiresApproval(listOf("java", "-version"))) {
                "doctor's Java version probe must remain a read-only operation"
            }
            check(!runner.requiresApproval(listOf("node", "--version"))) {
                "doctor's Node version probe must remain a read-only operation"
            }
            check(!runner.requiresApproval(listOf(fixture.resolve("reaktor").absolutePath, "install"))) {
                "self update's local CLI reinstall must remain executable without synthetic approval"
            }
            check(!runner.requiresApproval(listOf(fixture.resolve("gradlew").absolutePath, "installDist"))) {
                "self update's local Gradle rebuild must remain executable without synthetic approval"
            }
            check(!runner.requiresApproval(listOf("C:\\reaktor\\gradlew.bat", "installDist"))) {
                "Windows self update's Gradle rebuild must remain locally executable"
            }
            check(!runner.requiresApproval(listOf("C:\\reaktor\\build\\install\\reaktor\\bin\\reaktor.bat", "install"))) {
                "Windows self update's reinstall must remain locally executable"
            }
            check(!runner.requiresApproval(listOf("java", "-jar", fixture.resolve("targets/reaktorDesktop/engine.jar").absolutePath))) {
                "local Desktop engine launcher must remain executable without synthetic approval"
            }
            check(!runner.requiresApproval(listOf("java", "-jar", "C:\\bestbuds\\targets\\reaktorDesktop\\engine.jar"))) {
                "Windows Desktop engine launcher must remain executable without synthetic approval"
            }
            val secret = "pulumi-e2e-secret-sentinel"
            listOf(
                listOf("pulumi", "config", "set", "dbPassword", secret, "--secret", "--stack", "prod"),
                listOf("pulumi", "config", "set", "--secret", "dbPassword", secret, "--stack", "prod"),
                listOf("pulumi", "config", "--stack", "prod", "set", "--path", "--secret", "dbPassword", secret),
                listOf("pulumi", "config", "set", "--secret", "--type", "string", "dbPassword", secret, "--stack", "prod"),
                listOf("pulumi", "config", "set", "--secret", "dbPassword", "--", "-$secret", "--stack", "prod"),
                listOf("dagger", "call", "deploy", "--token=$secret"),
                listOf("dagger", "call", "deploy", "--token", "-$secret"),
            ).forEach { argv ->
                val rendered = runner.redactedCommand(argv)
                check(secret !in rendered && "[REDACTED]" in rendered) {
                    "sensitive CLI argv leaked in rendered plan: $rendered"
                }
            }
            val pickerSecret = "deploy-picker-secret-sentinel"
            val pickerOptions = checkNotNull(ReaktorProject.discover(fixture)).deployPickerOptions(
                listOf("--token", pickerSecret),
                runner::redactedCommand,
            )
            check(pickerOptions.none { option ->
                pickerSecret in option.detail || option.keywords.any { pickerSecret in it }
            }) { "deploy picker leaked sensitive arguments" }
        }
        check(runCatching { requireExplicitPulumiStack(listOf("--cwd", "observability", "up")) }.exceptionOrNull() is UsageError) {
            "Pulumi global options must not hide an ambient-stack mutation"
        }
        check(runCatching { requireExplicitPulumiStack(listOf("preview")) }.exceptionOrNull() is UsageError) {
            "Pulumi preview must bind an explicit stack"
        }
        check(runCatching { requireExplicitPulumiStack(listOf("stack", "rm")) }.exceptionOrNull() is UsageError) {
            "Pulumi stack mutation must not target the ambient stack"
        }
        check(runCatching { requireExplicitPulumiStack(listOf("watch")) }.exceptionOrNull() is UsageError) {
            "Pulumi watch must bind an explicit stack"
        }
        listOf("logs", "console", "about").forEach { command ->
            check(runCatching { requireExplicitPulumiStack(listOf(command)) }.exceptionOrNull() is UsageError) {
                "Pulumi $command must bind an explicit stack"
            }
        }
        requireExplicitPulumiStack(listOf("--cwd", "observability", "up", "--stack", "bestbuds-production"))
        requireExplicitPulumiStack(listOf("config", "set", "key", "value", "--stack=bestbuds-production"))
        requireExplicitPulumiStack(listOf("stack", "ls"))
        check(platformExecutable("npm", "Windows 11") == "npm.cmd") {
            "Windows npm tasks must use the executable shim that ProcessBuilder can launch"
        }
        check(platformExecutable("npm", "Linux") == "npm")
        check(checkNotNull(ReaktorProject.discover(fixture)).gradleWrapper("Windows 11").name == "gradlew.bat") {
            "Windows CLI commands must resolve the Gradle batch wrapper"
        }
        fixture.resolve("docs/docusaurus.config.js").apply { parentFile.mkdirs(); writeText("export default {}") }
        val docsCommand = checkNotNull(checkNotNull(ReaktorProject.discover(fixture)).docsCommand(listOf("build", ";touch", "owned")))
        check(docsCommand.command.none { it == "sh" || it == "-c" }) { "docs command still depends on a Unix shell" }
        check(docsCommand.command.takeLast(2) == listOf(";touch", "owned")) {
            "docs arguments were not preserved as literal argv"
        }

        val discovered = checkNotNull(JvmProjectDiscovery().discoverWorkspace(fixture)) {
            "fixture workspace was not discovered"
        }
        val discoveredIds = discovered.catalog.tasks.map { it.id.value }.toSet()
        check(
            discoveredIds.containsAll(
                setOf(
                    "npm/smoke:success",
                    "npm/smoke:failure",
                    "npm/smoke:timeout",
                    "npm/smoke:cancel",
                    "npm/smoke:noise",
                    "npm/smoke:args",
                    "npm/smoke:deploy-approval",
                ),
            ),
        ) {
            "expected fixture tasks, got $discoveredIds"
        }
        val gradleTaskId = discovered.catalog.tasks.first { it.provider == "gradle" }.id
        val preparedGradle = discovered.prepare(TaskInvocation(gradleTaskId))
        check(File(preparedGradle.request.argv.first()).canonicalFile == fixture.resolve("gradlew").canonicalFile) {
            "discovered Gradle task did not retain its executable wrapper"
        }
        listOf(
            "npm/fastlane:android:play:internal",
            "npm/fastlane:ios:testflight",
            "npm/fastlane:ios:setup-signing",
        ).forEach { id ->
            val task = discovered.catalog.tasks.single { it.id.value == id }
            check(task.safety.requiresApproval) { "$id was downgraded by FASTLANE_SKIP_UPDATE_CHECK" }
        }
        check(
            discovered.catalog.tasks.single { it.id.value == "npm/maestro:android:prod" }
                .safety.classification == SafetyClass.UnknownRemoteEffect,
        ) { "production Maestro task was not gated as a device/production effect" }
        listOf(
            "npm/fastlane:android:maestro",
            "npm/fastlane:android:maestro:release",
            "npm/fastlane:ios:maestro",
            "npm/fastlane:ios:maestro:release",
        ).forEach { id ->
            check(discovered.catalog.tasks.single { it.id.value == id }.safety.classification == SafetyClass.DeviceWrite) {
                "$id was hidden by a generic release/local classification"
            }
        }
        check(
            discovered.catalog.tasks.single { it.id.value == "npm/karate:configServer:public" }
                .safety.classification == SafetyClass.UnknownRemoteEffect,
        ) { "default live Karate task was not gated as a remote effect" }
        check(
            discovered.catalog.tasks.single { it.id.value == "npm/karate:configServer:public:dev" }
                .safety.classification == SafetyClass.NonProductionWrite,
        ) { "development Karate task was not gated as a non-production write" }
        val remoteArgumentPlan = discovered.prepare(
            TaskInvocation(TaskId("npm/smoke:local-worker"), arguments = listOf("--remote")),
        ).plan
        check(remoteArgumentPlan.safety.classification == SafetyClass.UnknownRemoteEffect) {
            "caller-controlled --remote argument did not raise the discovered task safety"
        }
        val destructiveArgumentPlan = discovered.prepare(
            TaskInvocation(TaskId("npm/smoke:local-worker"), arguments = listOf("--delete")),
        ).plan
        check(destructiveArgumentPlan.safety.classification == SafetyClass.Destructive) {
            "caller-controlled destructive argument did not raise the discovered task safety"
        }
        val productionArgumentPlan = discovered.prepare(
            TaskInvocation(TaskId("npm/smoke:local-worker"), arguments = listOf("-e", "APP_ENV=prod")),
        ).plan
        check(productionArgumentPlan.safety.classification == SafetyClass.UnknownRemoteEffect) {
            "caller-controlled production selector did not raise discovered task safety"
        }
        listOf(
            listOf("--env", "prod"),
            listOf("--environment", "production"),
            listOf("APP_ENV", "prod"),
            listOf("TARGET_ENV", "prod"),
            listOf("--config", "wrangler.production.json"),
        ).forEach { selector ->
            val plan = discovered.prepare(
                TaskInvocation(TaskId("npm/smoke:local-worker"), arguments = selector),
            ).plan
            check(plan.safety.classification == SafetyClass.UnknownRemoteEffect) {
                "separate-token production selector $selector did not raise discovered task safety"
            }
        }
        val devTargetPlan = AdHocProcessPlan.create(
            argv = listOf("node", "--version"),
            workingDirectory = fixture,
            taskId = TaskId("environment-target-e2e"),
            safety = dev.shibasis.reaktor.tooling.SafetyPolicy(SafetyClass.ReadOnly),
            environment = mapOf("TARGET_ENV" to "dev"),
        )
        val productionTargetPlan = AdHocProcessPlan.create(
            argv = listOf("node", "--version"),
            workingDirectory = fixture,
            taskId = TaskId("environment-target-e2e"),
            safety = dev.shibasis.reaktor.tooling.SafetyPolicy(SafetyClass.ReadOnly),
            environment = mapOf("TARGET_ENV" to "prod"),
        )
        check(devTargetPlan.plan.fingerprint != productionTargetPlan.plan.fingerprint) {
            "external target environment was not bound into the exact executable plan"
        }
        verifyInheritedAmbientTargetEnvironment()
        check(
            runCatching {
                discovered.prepare(TaskInvocation(TaskId("npm/fastlane:android:play:internal")))
            }.exceptionOrNull()?.message?.contains("artifact/source closure") == true,
        ) {
            "production Fastlane distribution remained executable without a sealed app artifact/source closure"
        }

        val fastfileBeforeDrift = fastfile.readText()
        assertDelayedDefinitionDrift(
            approvedExecution(discovered, "npm/fastlane:android:maestro"),
            mutate = { fastfile.appendText("# changed lane after approval\n") },
            restore = { fastfile.writeText(fastfileBeforeDrift) },
            label = "Fastfile",
        )
        val addedMaestroFlow = fixture.resolve("tests/bestbudsAndroid/added-after-approval.yaml")
        assertDelayedDefinitionDrift(
            approvedExecution(discovered, "npm/maestro:android:prod"),
            mutate = { addedMaestroFlow.writeText("appId: changed.membership\n") },
            restore = { addedMaestroFlow.delete() },
            label = "Maestro flow membership",
        )
        val workerSourceBeforeDrift = workerSource.readText()
        assertDelayedDefinitionDrift(
            approvedExecution(discovered, "npm/smoke:delegated"),
            mutate = { workerSource.appendText("// changed worker source after approval\n") },
            restore = { workerSource.writeText(workerSourceBeforeDrift) },
            label = "worker source/config",
        )
        val sourcedHelperBeforeDrift = sourcedHelper.readText()
        assertDelayedDefinitionDrift(
            approvedExecution(discovered, "npm/smoke:sourced-helper"),
            mutate = { sourcedHelper.appendText("# changed sourced helper after approval\n") },
            restore = { sourcedHelper.writeText(sourcedHelperBeforeDrift) },
            label = "sourced shell helper",
        )
        val nestedRemote = discovered.prepare(TaskInvocation(TaskId("npm/karate:nested:dev")))
        check(nestedRemote.request.definitionSeal?.directories?.any {
            it.directory.canonicalPath == fixture.resolve("tests/configServer/karate").canonicalPath
        } == true) {
            "literal -- npm run boundary did not recursively bind the nested remote harness flow directory"
        }
        val kubeconfigBeforeDrift = k3sKubeconfig.readText()
        assertDelayedDefinitionDrift(
            approvedExecution(discovered, "npm/smoke:k3s-status"),
            mutate = { k3sKubeconfig.appendText("# changed live client credential\n") },
            restore = { k3sKubeconfig.writeText(kubeconfigBeforeDrift) },
            label = "k3s kubeconfig",
        )
        val moduleBuildBeforeDrift = moduleBuild.readText()
        assertDelayedDefinitionDrift(
            approvedExecution(discovered, gradleTaskId.value),
            mutate = { moduleBuild.appendText("// changed build logic after approval\n") },
            restore = { moduleBuild.writeText(moduleBuildBeforeDrift) },
            label = "Gradle module build logic",
        )
        discovered.prepare(TaskInvocation(TaskId("npm/smoke:delegated")))
        delegatedPackage.writeText("""{"name":"e2e-worker-package","scripts":{"deploy":"wrangler deploy --dry-run"}}""")
        check(runCatching {
            discovered.prepare(TaskInvocation(TaskId("npm/smoke:delegated")))
        }.exceptionOrNull()?.message?.contains("definition changed", ignoreCase = true) == true) {
            "transitive workspace script drift was not rejected before execution"
        }
        val rediscovered = checkNotNull(JvmProjectDiscovery().discoverWorkspace(fixture))
        rediscovered.prepare(TaskInvocation(TaskId("npm/smoke:cd-delegated")))
        delegatedShell.appendText("# definition drift\n")
        check(runCatching {
            rediscovered.prepare(TaskInvocation(TaskId("npm/smoke:cd-delegated")))
        }.exceptionOrNull()?.message?.contains("definition changed", ignoreCase = true) == true) {
            "cd-relative downstream script drift was not rejected before execution"
        }

        SupervisedProcessExecutor().use { executor ->
            val project = checkNotNull(ReaktorProject.discover(fixture))
            val cliInvocation = checkNotNull(
                toolingInvocationFor(
                    command = ProjectCommand(
                        label = "npm run smoke:args -- first second",
                        command = listOf("npm", "run", "smoke:args", "--", "first", "second"),
                        cwd = fixture,
                    ),
                    tooling = discovered,
                    project = project,
                ),
            )
            check(cliInvocation.arguments == listOf("first", "second")) {
                "CLI/shared-tooling mapping dropped argv: ${cliInvocation.arguments}"
            }
            val argumentRun = execute(discovered.prepare(cliInvocation), executor)
            check(argumentRun.result.status == RunStatus.Succeeded)
            check(argumentRun.output(OutputChannel.Stdout).any { it == "first|second" }) {
                "mapped CLI arguments did not reach the executable npm task"
            }
            argumentRun.assertTerminalEvent()
            val windowsCliInvocation = checkNotNull(
                toolingInvocationFor(
                    command = ProjectCommand(
                        label = "npm.cmd run smoke:args -- windows",
                        command = listOf("npm.cmd", "run", "smoke:args", "--", "windows"),
                        cwd = fixture,
                    ),
                    tooling = discovered,
                    project = project,
                ),
            )
            check(windowsCliInvocation.arguments == listOf("windows")) {
                "Windows npm.cmd invocation did not map to shared tooling"
            }

            val success = execute(discovered, executor, "npm/smoke:success")
            check(success.result.status == RunStatus.Succeeded)
            check(success.output(OutputChannel.Stdout).any { it == "smoke-out" })
            check(success.output(OutputChannel.Stderr).any { it == "smoke-err" })
            check(success.output(OutputChannel.Stdout).any { it == "API_TOKEN=[REDACTED]" }) {
                "secret-looking output was not redacted"
            }
            success.assertTerminalEvent()

            val unconsumed = discovered.prepare(TaskInvocation(TaskId("npm/smoke:unconsumed")))
            val unconsumedHandle = executor.start(unconsumed.request)
            delay(250)
            withTimeout(5_000) { unconsumedHandle.cancel() }
            val unconsumedResult = withTimeout(5_000) { unconsumedHandle.await() }
            check(unconsumedResult.status == RunStatus.Cancelled) {
                "no-consumer noisy process did not cancel cleanly: ${unconsumedResult.status}"
            }

            val immediateCancelScheduler = Executors.newSingleThreadExecutor()
            val immediateCancelDispatcher = immediateCancelScheduler.asCoroutineDispatcher()
            val immediateCancelScope = CoroutineScope(SupervisorJob() + immediateCancelDispatcher)
            val immediateCancelBlockerEntered = CountDownLatch(1)
            val releaseImmediateCancelBlocker = CountDownLatch(1)
            val immediateCancelExecutor = SupervisedProcessExecutor(scope = immediateCancelScope)
            val immediateCancelSentinel = fixture.resolve("immediate-cancel-sentinel")
            try {
                immediateCancelScope.launch {
                    immediateCancelBlockerEntered.countDown()
                    releaseImmediateCancelBlocker.await()
                }
                check(immediateCancelBlockerEntered.await(5, TimeUnit.SECONDS)) {
                    "could not gate the immediate-cancel executor"
                }
                val immediateRequest = AdHocProcessPlan.create(
                    argv = listOf(
                        "node",
                        "-e",
                        "require('fs').writeFileSync('immediate-cancel-sentinel','started')",
                    ),
                    workingDirectory = fixture,
                    taskId = TaskId("immediate-cancel-e2e"),
                    safety = dev.shibasis.reaktor.tooling.SafetyPolicy(SafetyClass.LocalArtifactWrite),
                )
                val immediateHandle = immediateCancelExecutor.start(immediateRequest)
                // No event collector is attached before this cancellation. The LAZY execution
                // coroutine is still queued behind the blocker and must nevertheless finalize.
                val immediatelyCancelled = withTimeout(5_000) { immediateHandle.cancel() }
                check(immediatelyCancelled.status == RunStatus.Cancelled) {
                    "immediate pre-entry cancellation did not return Cancelled: $immediatelyCancelled"
                }
                check(withTimeout(5_000) { immediateHandle.await() } == immediatelyCancelled) {
                    "immediate cancel and await returned different terminal results"
                }
                val immediateEvents = withTimeout(5_000) { immediateHandle.events.toList() }
                check(
                    immediateEvents.singleOrNull() is RunEvent.Completed &&
                        (immediateEvents.single() as RunEvent.Completed).run.status == RunStatus.Cancelled,
                ) {
                    "immediate cancellation did not close with one terminal event: $immediateEvents"
                }
                check(!immediateCancelSentinel.exists()) {
                    "immediately cancelled process still reached ProcessBuilder.start"
                }
            } finally {
                releaseImmediateCancelBlocker.countDown()
                immediateCancelExecutor.close()
                immediateCancelDispatcher.close()
                immediateCancelScheduler.shutdownNow()
            }

            val failure = execute(discovered, executor, "npm/smoke:failure")
            check(failure.result.status == RunStatus.Failed)
            check(failure.result.exitCode == 7)
            check(failure.output(OutputChannel.Stderr).any { it == "expected-failure" })
            failure.assertTerminalEvent()

            val timeoutBase = discovered.prepare(TaskInvocation(TaskId("npm/smoke:timeout")))
            val timeoutPrepared = AdHocProcessPlan.create(
                argv = timeoutBase.request.argv,
                workingDirectory = timeoutBase.request.workingDirectory,
                taskId = timeoutBase.plan.taskId,
                safety = timeoutBase.plan.safety,
                invocation = timeoutBase.plan.invocation,
                environment = timeoutBase.request.environment,
                sensitiveEnvironmentKeys = timeoutBase.request.sensitiveEnvironmentKeys,
                redactions = timeoutBase.request.redactions,
                timeoutMillis = 250,
                workspaceId = timeoutBase.plan.workspaceId,
                fingerprintContext = timeoutBase.plan.fingerprintContext,
            )
            val timeoutHandle = executor.start(timeoutPrepared)
            val timeoutEvents = withTimeout(10_000) { timeoutHandle.events.toList() }
            val timedOut = withTimeout(10_000) { timeoutHandle.await() }
            check(timedOut.status == RunStatus.TimedOut)
            check(timedOut.failure?.code == "process-timeout")
            val timeoutChildPid = timeoutEvents.filterIsInstance<RunEvent.Output>()
                .firstOrNull { it.text.startsWith("timeout-child-pid=") }
                ?.text
                ?.substringAfter('=')
                ?.toLongOrNull()
            checkNotNull(timeoutChildPid) { "timeout process did not report its descendant PID" }
            SmokeResult(timedOut, timeoutEvents).assertTerminalEvent()
            check(ProcessHandle.of(timeoutChildPid).map(ProcessHandle::isAlive).orElse(false).not()) {
                "descendant process $timeoutChildPid survived timeout"
            }

            val approvalSentinel = fixture.resolve("approval-sentinel")
            val approvalPreview = discovered.prepare(
                TaskInvocation(
                    taskId = TaskId("npm/smoke:deploy-approval"),
                ),
            )
            val approvalPrepared = discovered.prepare(
                TaskInvocation(
                    taskId = TaskId("npm/smoke:deploy-approval"),
                    approval = SafetyApproval(
                        approvedBy = "fixture-independent-reviewer",
                        approvedAtEpochMillis = System.currentTimeMillis(),
                        planFingerprint = approvalPreview.plan.fingerprint,
                    ),
                ),
            )
            check(approvalPrepared.plan.safety.requiresApproval)
            check(approvalPrepared.plan.invocation.approval?.planFingerprint == approvalPrepared.plan.fingerprint) {
                "reviewed approval fingerprint was not preserved in the executable plan"
            }
            val wrongFingerprintPlan = approvalPrepared.plan.copy(
                invocation = approvalPrepared.plan.invocation.copy(
                    approval = approvalPrepared.plan.invocation.approval?.copy(
                        planFingerprint = "different-plan-fingerprint",
                    ),
                ),
            )
            val approvalRejection = runCatching {
                executor.start(approvalPrepared.request.copy(plan = wrongFingerprintPlan))
            }.exceptionOrNull()
            check(approvalRejection is IllegalArgumentException) {
                "executor accepted an approval for a different plan: $approvalRejection"
            }
            check(!approvalSentinel.exists()) { "fingerprint-rejected command was still executed" }

            val tamperSentinel = fixture.resolve("tamper-sentinel")
            val requestTamperRejection = runCatching {
                executor.start(
                    approvalPrepared.request.copy(
                        argv = listOf(
                            "node",
                            "-e",
                            "require('fs').writeFileSync('tamper-sentinel','executed')",
                        ),
                    ),
                )
            }.exceptionOrNull()
            check(requestTamperRejection is IllegalArgumentException) {
                "executor accepted argv that no longer matched the approved plan: $requestTamperRejection"
            }
            check(!tamperSentinel.exists()) { "request-tampered command was still executed" }

            val definitionBeforeDrift = fixture.resolve("package.json").readText()
            fixture.resolve("package.json").appendText("\n")
            val delayedDefinitionRejection = runCatching {
                executor.start(approvalPrepared.request)
            }.exceptionOrNull()
            check(delayedDefinitionRejection is IllegalArgumentException) {
                "executor accepted a task whose definition changed after approval: $delayedDefinitionRejection"
            }
            check(!approvalSentinel.exists()) { "definition-drifted command was still executed" }
            fixture.resolve("package.json").writeText(definitionBeforeDrift)

            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val gate = CountDownLatch(1)
            val blockerStarted = CountDownLatch(1)
            val gatedScope = CoroutineScope(SupervisorJob() + dispatcher)
            gatedScope.launch {
                blockerStarted.countDown()
                gate.await()
            }
            check(blockerStarted.await(5, TimeUnit.SECONDS)) { "could not gate the process executor" }
            val gatedExecutor = SupervisedProcessExecutor(scope = gatedScope)
            val gatedHandle = gatedExecutor.start(approvalPrepared.request)
            fixture.resolve("package.json").appendText("\n")
            gate.countDown()
            val gatedResult = withTimeout(5_000) { gatedHandle.await() }
            check(gatedResult.status == RunStatus.Failed && gatedResult.failure?.message?.contains("definition changed") == true) {
                "definition changed after admission but before process launch was not rejected: $gatedResult"
            }
            check(!approvalSentinel.exists()) { "post-admission definition drift still launched the command" }
            fixture.resolve("package.json").writeText(definitionBeforeDrift)
            gatedExecutor.close()
            dispatcher.close()

            val approved = execute(approvalPrepared, executor)
            check(approved.result.status == RunStatus.Succeeded)
            approved.assertTerminalEvent()
            check(approvalSentinel.readText() == "executed") { "approved command did not execute" }

            val noisyPrepared = discovered.prepare(TaskInvocation(TaskId("npm/smoke:noise")))
            val noisyHandle = executor.start(noisyPrepared.request)
            // Let the producer fill the bounded event channel before attaching the consumer. This
            // exercises backpressure as well as per-line truncation without retaining raw output.
            delay(200)
            val noisyEvents = withTimeout(20_000) { noisyHandle.events.toList() }
            val noisyRun = withTimeout(10_000) { noisyHandle.await() }
            val noisy = SmokeResult(noisyRun, noisyEvents)
            check(noisyRun.status == RunStatus.Succeeded)
            val noisyOutput = noisy.output(OutputChannel.Stdout)
            check(noisyOutput.all { it.length <= 16_384 }) { "an output event exceeded the bounded line size" }
            val noisyPayload = noisyOutput.filter { line ->
                line.length >= 5 && line.take(4).all(Char::isDigit) && line[4] == ':'
            }
            check(noisyPayload.size == 384) { "expected 384 noisy payload lines, got ${noisyPayload.size}" }
            check(noisyPayload.all { it.length == 16_384 }) { "long output was not consistently truncated" }
            noisy.assertTerminalEvent()

            val prepared = discovered.prepare(TaskInvocation(TaskId("npm/smoke:cancel")))
            val handle = executor.start(prepared.request)
            val events = mutableListOf<RunEvent>()
            val collecting = async { handle.events.toList(events) }
            val childPid = withTimeout(10_000) {
                while (true) {
                    events.filterIsInstance<RunEvent.Output>()
                        .firstOrNull { it.text.startsWith("child-pid=") }
                        ?.text
                        ?.substringAfter('=')
                        ?.toLongOrNull()
                        ?.let { return@withTimeout it }
                    delay(20)
                }
                error("unreachable")
            }
            val cancelled = handle.cancel()
            collecting.await()
            check(cancelled.status == RunStatus.Cancelled)
            SmokeResult(cancelled, events).assertTerminalEvent()
            check(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false).not()) {
                "descendant process $childPid survived cancellation"
            }
        }

        fixture.resolve("package.json").appendText("\n")
        val driftRejection = runCatching {
            discovered.prepare(TaskInvocation(TaskId("npm/smoke:success")))
        }.exceptionOrNull()
        check(driftRejection is IllegalStateException) {
            "catalog/source-definition drift was not rejected: $driftRejection"
        }
        check(driftRejection.message?.contains("Task definition changed") == true) {
            "catalog drift rejection was not actionable: ${driftRejection.message}"
        }

        println("tooling E2E smoke passed")
    } finally {
        check(fixture.deleteRecursively()) { "could not remove fixture ${fixture.absolutePath}" }
    }
    }
}

private fun runAmbientTargetPlanChild() = runBlocking {
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val request = AdHocProcessPlan.create(
        argv = listOf(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "dev.shibasis.reaktor.cli.ToolingE2eSmokeKt",
            "--ambient-target-value",
        ),
        workingDirectory = File(System.getProperty("java.io.tmpdir")),
        taskId = TaskId("ambient-target-child-e2e"),
        safety = dev.shibasis.reaktor.tooling.SafetyPolicy(SafetyClass.ReadOnly),
        // Deliberately no explicit environment override: this exercises the inherited JVM snapshot.
    )
    println("ambient-fingerprint=${request.plan.fingerprint}")
    SupervisedProcessExecutor().use { executor ->
        val result = execute(
            dev.shibasis.reaktor.tooling.PreparedProcessExecution(request.plan, request),
            executor,
        )
        check(result.result.status == RunStatus.Succeeded)
        val observed = result.output(OutputChannel.Stdout).single { it.startsWith("ambient-target=") }
        println("ambient-executed=${observed.substringAfter('=')}")
    }
}

private fun verifyInheritedAmbientTargetEnvironment() {
    fun launch(target: String): Pair<String, String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "dev.shibasis.reaktor.cli.ToolingE2eSmokeKt",
            "--ambient-target-plan",
        ).redirectErrorStream(true).apply {
            environment()["TARGET_ENV"] = target
        }.start().also { it.outputStream.close() }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "ambient target child failed for $target: $output" }
        val fingerprint = output.lineSequence().single { it.startsWith("ambient-fingerprint=") }.substringAfter('=')
        val executed = output.lineSequence().single { it.startsWith("ambient-executed=") }.substringAfter('=')
        return fingerprint to executed
    }
    val development = launch("dev")
    val production = launch("prod")
    check(development.first != production.first) {
        "inherited ambient TARGET_ENV was not bound into the executable fingerprint"
    }
    check(development.second == "dev" && production.second == "prod") {
        "executor did not forward the snapshotted inherited TARGET_ENV: $development / $production"
    }
}

/** Exercises the installed command boundary: cloud failures must fail the CLI, and doctor must use the discovered root. */
private fun verifyCliCloudFailureAndNestedDoctor(fixture: File) {
    val bin = fixture.resolve("e2e-bin").apply { mkdirs() }
    fun executable(name: String, body: String) = bin.resolve(name).apply {
        writeText("#!/bin/sh\n$body\n")
        check(setExecutable(true)) { "could not make E2E $name executable" }
    }
    executable("dagger", "printf 'dagger-failed\\n' >&2; exit 23")
    executable("pulumi", "printf '%s\\n' \"${'$'}*\"; exit 0")
    listOf("node", "npm", "java").forEach { name ->
        executable(name, "printf '$name-e2e-version\\n'; exit 0")
    }
    executable(
        "npx",
        "[ \"${'$'}1\" = --no-install ] || { printf 'unsafe-npx-args:%s\\n' \"${'$'}*\" >&2; exit 18; }; printf 'npx-e2e-version\\n'; exit 0",
    )
    fixture.resolve("gradlew").apply {
        writeText("#!/bin/sh\npwd > doctor-cwd.log\nprintf 'gradle-e2e-version\\n'\n")
        check(setExecutable(true)) { "could not make E2E gradlew executable" }
    }
    val nested = fixture.resolve("targets/appWeb").apply { mkdirs() }

    fun cli(vararg args: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            listOf(java, "-cp", System.getProperty("java.class.path"), "dev.shibasis.reaktor.cli.MainKt") + args,
        )
            .directory(nested)
            .redirectErrorStream(true)
            .apply {
                val existing = environment()["PATH"].orEmpty()
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + existing
            }
            .start()
            .also { it.outputStream.close() }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }

    val (cloudCode, cloudOutput) = cli("cloud", "dagger", "functions")
    check(cloudCode != 0 && "exited with 23" in cloudOutput) {
        "failed cloud command was reported as CLI success: exit=$cloudCode output=$cloudOutput"
    }
    val secret = "deploy-output-secret-sentinel"
    val (_, deployDryRunOutput) = cli("deploy", "web", "--dry-run", "--", "--token", secret)
    check(secret !in deployDryRunOutput && "[REDACTED]" in deployDryRunOutput) {
        "deploy dry-run leaked sensitive arguments: $deployDryRunOutput"
    }
    val (_, deployApprovalOutput) = cli("deploy", "web", "--", "--token", secret)
    check(secret !in deployApprovalOutput && "[REDACTED]" in deployApprovalOutput) {
        "deploy approval/table output leaked sensitive arguments: $deployApprovalOutput"
    }
    val pulumiSecret = "pulumi-boundary-secret-sentinel"
    val (_, pulumiOutput) = cli(
        "cloud", "pulumi", "--", "config", "set", "--secret", "--type", "string",
        "dbPassword", pulumiSecret, "--stack", "prod",
    )
    check(pulumiSecret !in pulumiOutput && "[REDACTED]" in pulumiOutput) {
        "installed CLI leaked a Pulumi secret: $pulumiOutput"
    }
    val (doctorCode, doctorOutput) = cli("doctor")
    check(doctorCode == 0) { "doctor failed from a project descendant: $doctorOutput" }
    check(fixture.resolve("doctor-cwd.log").readText().trim() == fixture.canonicalPath) {
        "doctor did not execute the Gradle wrapper from the discovered project root"
    }
    executable(
        "npx",
        "[ \"${'$'}1\" = --no-install ] || { printf 'unsafe-npx-args:%s\\n' \"${'$'}*\" >&2; exit 18; }; printf 'wrangler-unavailable\\n' >&2; exit 19",
    )
    val (unhealthyDoctorCode, unhealthyDoctorOutput) = cli("doctor")
    check(unhealthyDoctorCode != 0 && "wrangler-unavailable" in unhealthyDoctorOutput) {
        "doctor reported an unhealthy required probe as success: exit=$unhealthyDoctorCode output=$unhealthyDoctorOutput"
    }
}

private suspend fun execute(
    discovered: dev.shibasis.reaktor.tooling.DiscoveredJvmWorkspace,
    executor: SupervisedProcessExecutor,
    taskId: String,
): SmokeResult {
    val prepared = discovered.prepare(TaskInvocation(TaskId(taskId)))
    return execute(prepared, executor)
}

private suspend fun execute(
    prepared: dev.shibasis.reaktor.tooling.PreparedProcessExecution,
    executor: SupervisedProcessExecutor,
): SmokeResult {
    val handle = executor.start(prepared.request)
    val events = handle.events.toList()
    return SmokeResult(handle.await(), events)
}

private fun approvedExecution(
    discovered: dev.shibasis.reaktor.tooling.DiscoveredJvmWorkspace,
    taskId: String,
): dev.shibasis.reaktor.tooling.PreparedProcessExecution {
    val preview = discovered.prepare(TaskInvocation(TaskId(taskId)))
    if (!preview.plan.safety.requiresApproval) return preview
    return discovered.prepare(
        TaskInvocation(
            taskId = TaskId(taskId),
            approval = SafetyApproval(
                approvedBy = "definition-closure-e2e",
                approvedAtEpochMillis = System.currentTimeMillis(),
                planFingerprint = preview.plan.fingerprint,
            ),
        ),
    )
}

private suspend fun assertDelayedDefinitionDrift(
    prepared: dev.shibasis.reaktor.tooling.PreparedProcessExecution,
    mutate: () -> Unit,
    restore: () -> Unit,
    label: String,
): Unit = coroutineScope {
    val scheduler = Executors.newSingleThreadExecutor()
    val dispatcher = scheduler.asCoroutineDispatcher()
    val executorScope = CoroutineScope(SupervisorJob() + dispatcher)
    val blockerEntered = CountDownLatch(1)
    val releaseProcessStart = CountDownLatch(1)
    val executor = SupervisedProcessExecutor(scope = executorScope)
    try {
        executorScope.launch {
            blockerEntered.countDown()
            releaseProcessStart.await()
        }
        check(blockerEntered.await(5, TimeUnit.SECONDS)) { "could not gate executor for $label drift" }
        val handle = executor.start(prepared.request)
        val eventCollection = async { handle.events.toList() }
        mutate()
        releaseProcessStart.countDown()
        val result = withTimeout(5_000) { handle.await() }
        val events = withTimeout(5_000) { eventCollection.await() }
        check(result.status == RunStatus.Failed && result.failure?.message?.contains("definition changed") == true) {
            "$label drift was not rejected at the process-start boundary: $result"
        }
        check(events.none { it is RunEvent.Started }) {
            "$label drift reached ProcessBuilder.start"
        }
    } finally {
        releaseProcessStart.countDown()
        restore()
        executor.close()
        dispatcher.close()
        scheduler.shutdownNow()
    }
}

private data class SmokeResult(
    val result: dev.shibasis.reaktor.tooling.TaskRun,
    val events: List<RunEvent>,
) {
    fun output(channel: OutputChannel): List<String> = events
        .filterIsInstance<RunEvent.Output>()
        .filter { it.channel == channel }
        .map { it.text }

    fun assertTerminalEvent() {
        val terminalEvents = events.filterIsInstance<RunEvent.Completed>()
        check(terminalEvents.size == 1) { "expected one terminal event, got ${terminalEvents.size}" }
        check(terminalEvents.single().run == result) { "terminal event and awaited result diverged" }
        check(events.last() is RunEvent.Completed) { "terminal event was not last" }
        check(events.map { it.sequence } == events.indices.map(Int::toLong)) { "event sequence was not contiguous" }
    }
}
