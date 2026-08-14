package dev.shibasis.reaktor.cloud

import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import dev.shibasis.reaktor.tooling.ToolingWorkspace
import dev.shibasis.reaktor.tooling.WorkspaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Executable process-boundary E2E; intentionally uses no unit-test framework or mocks. */
fun main() = runBlocking {
    val fixture = Files.createTempDirectory("reaktor-cloud-e2e-").toFile()
    try {
        val invocationLog = fixture.resolve("cloud-invocations.log")
        val pulumiProgram = fixture.resolve("pulumi-program").apply { mkdirs() }
        val executable = fixture.resolve("fake-cloud-tool.sh").apply {
            writeText(
                """#!/bin/sh
                    |printf '%s\n' "${'$'}*" >> '${invocationLog.absolutePath}'
                    |printf 'fake-tool-started\n'
                    |if [ -n "${'$'}CLOUDFLARE_API_TOKEN" ]; then printf 'secret=%s\n' "${'$'}CLOUDFLARE_API_TOKEN"; fi
                    |sleep 1
                    |printf 'fake-tool-finished\n'
                    |""".trimMargin(),
            )
            check(setExecutable(true)) { "could not make E2E tool executable" }
        }

        verifyRunnerCannotExecuteOrCancelTwice(fixture, executable.absolutePath, invocationLog)
        check(invocationLog.delete()) { "could not reset invocation log" }
        verifyDaggerApprovalAndLifecycle(fixture, executable.absolutePath, invocationLog)
        if (invocationLog.exists()) check(invocationLog.delete()) { "could not reset invocation log" }
        verifyPulumiApproval(pulumiProgram, executable.absolutePath, invocationLog)

        println("cloud tool E2E passed")
    } finally {
        check(fixture.deleteRecursively()) { "could not remove fixture ${fixture.absolutePath}" }
    }
}

private suspend fun verifyRunnerCannotExecuteOrCancelTwice(
    fixture: java.io.File,
    executable: String,
    invocationLog: java.io.File,
): Unit = coroutineScope {
    val events = ProcessToolRunner().run(
        command = listOf(executable, "direct"),
        workingDir = fixture,
        runId = "direct-single-claim",
        safety = SafetyClass.LocalArtifactWrite,
    )
    val owner = async { events.toList() }
    withTimeout(5_000) {
        while (!invocationLog.exists()) delay(10)
    }
    val duplicate = runCatching { events.toList() }
    check(duplicate.exceptionOrNull()?.message?.contains("already been collected") == true) {
        "duplicate ProcessToolRunner collection was not rejected"
    }
    val ownerEvents = owner.await()
    check(ownerEvents.completedCode() == 0) { "duplicate collector cancelled the owning process" }
    check(invocationLog.readLines().size == 1) { "direct command executed more than once" }
}

private suspend fun verifyDaggerApprovalAndLifecycle(
    fixture: java.io.File,
    executable: String,
    invocationLog: java.io.File,
): Unit = coroutineScope {
    val provider = DaggerToolProvider(
        repoRoot = fixture,
        executable = executable,
        reaktorRoot = fixture,
        environment = mapOf("CLOUDFLARE_API_TOKEN" to "e2e-secret-never-forwarded"),
    )
    val operations = provider.operations()
    val deployOperation = operations.single { it.id == "dagger.deployWorkers" }
    check(deployOperation.unavailableReason?.contains("source closure") == true) {
        "Dagger operation catalog advertised deployWorkers as executable"
    }
    check(operations.single { it.id == "dagger.jsChecks" }.unavailableReason == null) {
        "Dagger operation catalog marked the real jsChecks function unavailable"
    }
    val catalogTasks = provider.asCatalogProvider().tasks(
        ToolingWorkspace(WorkspaceId("cloud-e2e"), "cloud-e2e", fixture.absolutePath),
    )
    val catalogTask = catalogTasks.single { it.id.value == "dagger.deployWorkers" }
    check(catalogTask.unavailableReason == deployOperation.unavailableReason) {
        "unified cloud catalog dropped the deployWorkers availability contract"
    }
    check(catalogTask.attributes["unavailableReason"] == deployOperation.unavailableReason) {
        "unified cloud catalog dropped the backwards-compatible unavailability attribute"
    }
    check(catalogTask.inputs.single { it.name == "cloudflare-api-token" }.sensitive) {
        "unified cloud catalog dropped the deployment secret-input contract"
    }
    check(catalogTask.inputs.single { it.name == "target-env" }.allowedValues == listOf("dev", "prod")) {
        "unified cloud catalog dropped the deployment environment choices"
    }
    val command = CloudCommand(
        operationId = "dagger.deployWorkers",
        provider = "dagger",
        fn = "deployWorkers",
        args = mapOf(
            "cloudflare-api-token" to "CLOUDFLARE_API_TOKEN",
            "target-env" to "prod",
        ),
    )
    check(runCatching { provider.plan(command) }.exceptionOrNull()?.message?.contains("source closure") == true) {
        "Dagger deployWorkers remained executable without a bounded source seal"
    }
    check(runCatching { provider.run(command) }.exceptionOrNull()?.message?.contains("source closure") == true) {
        "Dagger deployWorkers remained runnable without a bounded source seal"
    }
    val jsCommand = CloudCommand(
        operationId = "dagger.jsChecks",
        provider = "dagger",
        fn = "jsChecks",
    )
    check(catalogTasks.single { it.id.value == jsCommand.operationId }.unavailableReason == null) {
        "unified cloud catalog did not advertise jsChecks as executable"
    }
    val jsPlan = provider.plan(jsCommand)
    check(jsPlan.displayCommand.contains("jsChecks")) {
        "Dagger jsChecks plan did not target the declared function"
    }
    val jsRun = provider.run(jsCommand)
    check(provider.events(jsRun.id).toList().completedCode() == 0) {
        "Dagger jsChecks did not execute through the supervised process boundary"
    }
    check(invocationLog.readLines().single().contains("call jsChecks")) {
        "Dagger jsChecks executed an unexpected process command"
    }
    check(provider.events(jsRun.id).toList().isEmpty()) { "completed Dagger jsChecks run was retained" }
}

private suspend fun verifyPulumiApproval(
    fixture: java.io.File,
    executable: String,
    invocationLog: java.io.File,
) {
    val provider = PulumiToolProvider(fixture, executable = executable)
    val command = CloudCommand(
        operationId = "pulumi.up",
        provider = "pulumi",
        fn = "up",
        stack = "e2e",
    )
    check(runCatching { provider.run(command.copy(operationId = "pulumi.preview")) }.isFailure) {
        "Pulumi accepted a command whose operation identity did not match its verb"
    }
    check(runCatching { provider.run(command.copy(stack = null)) }.isFailure) {
        "Pulumi accepted a command without an explicit reviewed stack"
    }

    val blocked = provider.run(command)
    check(provider.events(blocked.id).toList().completedCode() == 126) {
        "unapproved Pulumi mutation did not fail closed"
    }
    check(!invocationLog.exists()) { "blocked Pulumi mutation launched the executable" }

    val reviewedPlan = provider.plan(command)
    check(reviewedPlan.displayCommand.any { it == "e2e" }) { "Pulumi plan omitted its explicit stack" }
    val exactApproval = CloudExecutionApproval(
        approvedBy = "cloud-e2e-operator",
        planFingerprint = reviewedPlan.fingerprint,
        reason = "exercise exact approved Pulumi plan",
    )
    val pulumiDefinition = fixture.resolve("Pulumi.yaml").apply { writeText("name: cloud-e2e\nruntime: java\n") }
    val driftPlan = provider.plan(command)
    val driftApproval = exactApproval.copy(planFingerprint = driftPlan.fingerprint)
    pulumiDefinition.appendText("description: changed after review\n")
    check(runCatching { provider.run(command, driftApproval) }.isFailure) {
        "Pulumi accepted approval after its program definition changed"
    }
    pulumiDefinition.delete()
    check(runCatching { provider.run(command.copy(stack = "different-stack"), exactApproval) }.isFailure) {
        "Pulumi accepted an approval after the reviewed stack changed"
    }
    val delayedPlan = provider.plan(command)
    val delayedApproval = exactApproval.copy(planFingerprint = delayedPlan.fingerprint)
    val delayed = provider.run(command, delayedApproval)
    fixture.resolve("Pulumi.yaml").writeText("name: drifted-before-collection\nruntime: java\n")
    check(provider.events(delayed.id).toList().completedCode() == 127) {
        "Pulumi executed changed source after run registration and before event collection"
    }
    fixture.resolve("Pulumi.yaml").delete()
    val approved = provider.run(command, exactApproval)
    val approvedEvents = provider.events(approved.id).toList()
    check(approvedEvents.completedCode() == 0) { "approved Pulumi mutation failed" }
    check(approvedEvents.filterIsInstance<CloudEvent.Log>().any { it.line == "fake-tool-finished" }) {
        "approved Pulumi output was not streamed"
    }
    check(invocationLog.readLines().single().contains("up --stack e2e --non-interactive --yes")) {
        "Pulumi argv did not match the approved command"
    }
    check(provider.events(approved.id).toList().isEmpty()) { "completed Pulumi run was retained" }

    coroutineScope {
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
            check(blockerEntered.await(5, TimeUnit.SECONDS)) { "could not gate the cloud process executor" }
            val gatedProvider = PulumiToolProvider(
                fixture,
                runner = ProcessToolRunner(executor),
                executable = executable,
            )
            val gatedPlan = gatedProvider.plan(command)
            val gatedApproval = CloudExecutionApproval(
                approvedBy = "cloud-e2e-operator",
                planFingerprint = gatedPlan.fingerprint,
            )
            val gatedRun = gatedProvider.run(command, gatedApproval)
            val collection = async { gatedProvider.events(gatedRun.id).toList() }
            delay(100)
            val invocationCount = invocationLog.readLines().size
            fixture.resolve("src/new-program-member.kt").apply {
                parentFile.mkdirs()
                writeText("// added after executor admission\n")
            }
            releaseProcessStart.countDown()
            check(collection.await().completedCode() == 1) {
                "Pulumi directory membership drift did not fail at the immediate process-start boundary"
            }
            check(invocationLog.readLines().size == invocationCount) {
                "Pulumi executable started after delayed source membership drift"
            }
        } finally {
            releaseProcessStart.countDown()
            executor.close()
            dispatcher.close()
            scheduler.shutdownNow()
        }
    }
}

private fun List<CloudEvent>.completedCode(): Int? =
    filterIsInstance<CloudEvent.Completed>().singleOrNull()?.exitCode
