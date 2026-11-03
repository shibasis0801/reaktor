package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class HygieneTest {
    private val at = 1_793_480_000_000L
    private fun process(kind: String) = ManagedProcess(71, 1, "Sun Nov 1 01:00:00 2026", 500, kind, kind, "/managed/workspace",
        idleSince = at - 3_600_000, expiryMillis = 300_000, stopProgram = "/managed/gradle/bin/gradle", gradleHome = "/managed/.gradle",
        clients = 0, runs = 0, children = 0, logAt = at - 3_600_000, managedProfile = true, daemonState = "Idle", daemonStateSource = "Fixture owner status", clientMarkers = 0, runDirectory = "/managed/run")
    private class Fake(var table: ProcessTable) : HygieneBackend {
        val actions = mutableListOf<String>()
        override fun table(host: String, previous: ProcessTable?) = table
        override fun act(host: String, process: ManagedProcess, action: String): String { actions += "$host:${process.pid}:$action"; return "Fixture action recorded" }
        override fun actRegistration(host: String, registration: WorkspaceRegistration): String { actions += "$host:registration:${registration.path}"; return "Fixture quarantine recorded" }
    }
    private fun finding(process: ManagedProcess, lease: String = "Free", truth: String = "Live") =
        Hygiene(Fake(ProcessTable("local", at, lease, listOf(process), truth)), { at }).report(listOf("local")).findings.single()

    @Test fun expiredDaemonsUseTheirVerifiedPid() {
        for (kind in listOf("Gradle", "Kotlin")) assertEquals("TerminateDaemon", finding(process(kind)).action)
    }
    @Test fun daemonExpiryAndProtocolStatusMustBeEstablished() {
        for (kind in listOf("Gradle", "Kotlin")) {
            assertFalse(finding(process(kind).copy(idleSince = at - 299_999)).eligible)
            assertFalse(finding(process(kind).copy(expiryMillis = null)).eligible)
            assertFalse(finding(process(kind).copy(expiryMillis = 0)).eligible)
            assertFalse(finding(process(kind).copy(daemonState = "Busy")).eligible)
            assertFalse(finding(process(kind).copy(daemonState = null)).eligible)
            assertFalse(finding(process(kind).copy(daemonStateSource = null)).eligible)
        }
    }
    @Test fun orphanedTestsAndFlowsNeedTenMinutesOfUnchangedCpu() {
        for (kind in listOf("Test", "Flow")) {
            assertEquals("Terminate", finding(process(kind).copy(idleSince = at - 600_000)).action)
            assertFalse(finding(process(kind).copy(parent = 72)).eligible)
            assertFalse(finding(process(kind).copy(idleSince = at - 599_999)).eligible)
            assertFalse(finding(process(kind).copy(idleSince = null)).eligible)
        }
    }
    @Test fun chromeRequiresAManagedProfileAndAnAbsentLauncher() {
        assertEquals("Terminate", finding(process("HeadlessChrome")).action)
        assertFalse(finding(process("HeadlessChrome").copy(managedProfile = false)).eligible)
        assertFalse(finding(process("HeadlessChrome").copy(parent = 72)).eligible)
    }
    @Test fun workspaceOwnerRetirementRemainsUnavailableEvenWhenIdle() {
        val owner = process("WorkspaceOwner")
        assertFalse(finding(owner).eligible)
        for (busy in listOf(owner.copy(clients = 1), owner.copy(runs = 1), owner.copy(children = 1), owner.copy(logAt = at - 3_599_999),
            owner.copy(clients = null), owner.copy(runs = null), owner.copy(children = null), owner.copy(logAt = null), owner.copy(primary = true))) {
            assertFalse(finding(busy).eligible)
        }
    }
    @Test fun unknownOwnershipInspectionAndLeasesRetainEveryKind() {
        for (kind in listOf("Gradle", "Kotlin", "Test", "Flow", "HeadlessChrome", "WorkspaceOwner")) {
            assertFalse(finding(process(kind).copy(owner = null)).eligible)
            for (lease in listOf("Held", "Unknown")) assertFalse(finding(process(kind), lease).eligible)
            for (truth in listOf("Partial", "Failed", "Fixture")) assertFalse(finding(process(kind), truth = truth).eligible)
        }
        assertFalse(finding(process("Other")).eligible)
    }
    @Test fun applyRechecksPidStartLeaseAndCpuBeforeAnyAction() = temporary { folder ->
        val initial = ProcessTable("local", at, "Free", listOf(process("Test")), "Live")
        val backend = Fake(initial)
        val hygiene = Hygiene(backend) { at + 1 }
        val report = hygiene.report(listOf("local"))
        for ((index, changed) in listOf(initial.copy(lease = "Held"), initial.copy(processes = listOf(process("Test").copy(started = "reused pid"))),
            initial.copy(processes = listOf(process("Test").copy(idleSince = at, cpuMillis = 600)))).withIndex()) {
            backend.table = changed
            val receipt = hygiene.apply(report, report.digest, folder.resolve("refusal-$index")).single()
            assertTrue(receipt.result.startsWith("Refused"))
        }
        assertTrue(backend.actions.isEmpty())
    }
    @Test fun applyRecordsTheReviewedEvidenceAndAction() = temporary { folder ->
        val backend = Fake(ProcessTable("local", at, "Free", listOf(process("Test"), process("WorkspaceOwner").copy(pid = 72)), "Live"))
        val hygiene = Hygiene(backend) { at }
        val report = hygiene.report(listOf("local"))
        val receipts = hygiene.apply(report, report.digest, folder)
        assertEquals(listOf("local:71:Terminate"), backend.actions)
        assertEquals(1, receipts.size)
        assertTrue(receipts.all { it.report == report.digest && it.owner != null && it.evidence.isNotEmpty() })
        assertEquals(1L, Files.list(folder).use { it.count() })
    }
    @Test fun applyRefusesMismatchedReviewAndFixtureReports() = temporary { folder ->
        val hygiene = Hygiene(Fake(ProcessTable("local", at, "Free", listOf(process("Test")), "Fixture"))) { at }
        val report = hygiene.report(listOf("local"))
        assertFailsWith<IllegalArgumentException> { hygiene.apply(report, "other", folder) }
        assertFailsWith<IllegalArgumentException> { hygiene.apply(report, report.digest, folder) }
        assertEquals(0L, Files.list(folder).use { it.count() })
    }
    @Test fun storedReportsPreserveMissingStaleAndFailedTruth() = temporary { folder ->
        val store = HygieneStore(folder) { at }
        assertEquals("Unknown", store.read().truth)
        val report = Hygiene(Fake(ProcessTable("local", at, "Free", emptyList(), "Live"))) { at - 300_001 }.report(listOf("local"))
        store.write(report)
        assertEquals("Stale", store.read().truth)
        assertEquals(report.digest, store.read().report!!.digest)
        Files.writeString(folder.resolve("latest.json"), "broken")
        assertEquals("Failed", store.read().truth)
    }
    @Test fun nativeReportNeverPublishesRawArgumentsAndRequiresRepeatedCpuEvidence() = temporary { folder ->
        val calls = mutableListOf<List<String>>()
        val commands = ClusterCommands { args, _ ->
            calls += args
            when (args.first()) {
                "/bin/sh" -> CommandResult(0, "71 1 Sun Nov 1 01:00:00 2026 0:00.50 01:00:00 java")
                "ps" -> CommandResult(0, "worker.org.gradle.process.internal.worker.GradleWorkerMain --secret=NEVER_EMIT_THIS")
                "lsof" -> CommandResult(0, "p71\nn${folder}/dev/bestbuds")
                else -> CommandResult(1, "")
            }
        }
        val backend = NativeHygiene(folder, commands, { at }, folder)
        val first = backend.table("local", null)
        assertEquals(null, first.processes.single().idleSince)
        val next = backend.table("local", first.copy(observedAt = at - 600_000))
        assertEquals(at - 600_000, next.processes.single().idleSince)
        val text = Json.encodeToString(ProcessTable.serializer(), next)
        assertFalse(text.contains("NEVER_EMIT_THIS"))
        assertTrue(calls.none { it.first() == "kill" })
    }

    @Test fun savedRunHistoryIsNotActivityAndIncompletePagesCannotProveIdleness() {
        fun page(vararg statuses: String) = buildJsonObject { put("runs", buildJsonArray {
            statuses.forEach { state -> add(buildJsonObject { put("status", state) }) }
        }) }
        assertEquals(0, activeWorkspaceRuns(page("Completed", "Failed", "Interrupted", "Limited")))
        assertEquals(1, activeWorkspaceRuns(page("Completed", "Running")))
        assertEquals(0, activeWorkspaceRuns(page()))
        assertEquals(null, activeWorkspaceRuns(page(*Array(50) { "Completed" })))
        assertEquals(null, activeWorkspaceRuns(page("Unrecognized")))
        assertEquals(null, activeWorkspaceRuns(buildJsonObject {}))
    }

    @Test fun ownerRpcUsesTheStructuredPayloadInsteadOfHumanReadableText() {
        val payload = buildJsonObject { put("service", buildJsonObject { put("processId", 71) }) }
        val reply = buildJsonObject {
            put("structuredContent", payload)
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "Workspace owner summary") }) })
        }
        assertEquals(payload, workspaceMcpResult(reply))
        assertEquals(payload, workspaceMcpResult(buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("text", payload.toString()) }) }) }))
    }

    @Test fun failedOrMissingOwnerRpcPayloadCannotEstablishOwnership() {
        assertFailsWith<IllegalArgumentException> { workspaceMcpResult(buildJsonObject { put("isError", true); put("structuredContent", buildJsonObject {}) }) }
        assertFailsWith<IllegalArgumentException> { workspaceMcpResult(buildJsonObject {}) }
        assertFailsWith<IllegalArgumentException> { workspaceMcpResult(buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("text", "Workspace owner summary") }) }) }) }
    }

    @Test fun kotlinDaemonsRequireKnownEmptyClientMarkersAndRunDirectory() {
        assertTrue(finding(process("Kotlin")).eligible)
        assertFalse(finding(process("Kotlin").copy(clientMarkers = 1)).eligible)
        assertFalse(finding(process("Kotlin").copy(clientMarkers = null)).eligible)
        assertFalse(finding(process("Kotlin").copy(runDirectory = null)).eligible)
    }

    @Test fun nativeDaemonTerminationTargetsOnlyTheVerifiedPidAndNeverGradleStop() = temporary { folder ->
        val calls = mutableListOf<List<String>>()
        val backend = NativeHygiene(folder, ClusterCommands { args, _ -> calls += args; CommandResult(0, "") }, { at }, folder)
        assertEquals("SIGTERM sent to verified process", backend.act("local", process("Kotlin"), "TerminateDaemon"))
        assertEquals(1, calls.size)
        val command = calls.single()
        assertEquals(listOf("/bin/sh", "-c"), command.take(2))
        assertEquals(listOf("hygiene", "71", process("Kotlin").started), command.takeLast(3))
        assertTrue(command[2].contains("kill -TERM"))
        assertTrue(calls.none { "--stop" in it })
        assertFailsWith<IllegalArgumentException> { backend.act("local", process("WorkspaceOwner"), "DrainOwner") }
    }

    @Test fun gradleStatusAttributesOnlyTheExactPidAndRejectsAmbiguousRows() {
        val status = "PID STATUS INFO\n71 BUSY 9.7.0\n72 IDLE 9.7.0\n73 STOPPED (by user)"
        assertEquals("Busy", gradleDaemonState(status, 71))
        assertEquals("Idle", gradleDaemonState(status, 72))
        assertEquals("Stopped", gradleDaemonState(status, 73))
        assertEquals(null, gradleDaemonState(status, 74))
        assertEquals(null, gradleDaemonState(status + "\n72 BUSY 9.7.0", 72))
        assertEquals(null, gradleDaemonState("72 IDLE", 72))
    }

    private fun registration() = WorkspaceRegistration("/managed/Library/LaunchAgents/build.reaktor.agents.fixture.plist", "/managed/removed", "build.reaktor.agents.fixture", at - 86_400_000,
        false, true, null, false, "Source", listOf("Fixture absent-root and launchctl evidence"))

    @Test fun missingRootRegistrationsHaveTheirOwnQualifiedFindings() {
        fun result(source: WorkspaceRegistration, lease: String = "Free") = Hygiene(Fake(ProcessTable("local", at, lease, emptyList(), "Live", registrations = listOf(source))), { at })
            .report(listOf("local")).registrationFindings.single()
        assertEquals("QuarantineRegistration", result(registration()).action)
        for (source in listOf(registration().copy(rootExists = true), registration().copy(rootExists = null), registration().copy(primary = true),
            registration().copy(loaded = null), registration().copy(runningPid = 71), registration().copy(modifiedAt = null), registration().copy(truth = "Unknown"))) {
            assertFalse(result(source).eligible)
        }
        assertFalse(result(registration(), "Held").eligible)
        assertFalse(result(registration(), "Unknown").eligible)
        for (lease in listOf("Free", "Held", "Unknown")) assertTrue(result(registration().copy(loaded = false), lease).eligible)
    }

    @Test fun quarantineRevalidatesRegistrationMetadataAndRecordsASeparateReceipt() = temporary { folder ->
        val table = ProcessTable("local", at, "Free", emptyList(), "Live", registrations = listOf(registration()))
        val backend = Fake(table)
        val hygiene = Hygiene(backend) { at }
        val report = hygiene.report(listOf("local"))
        backend.table = table.copy(registrations = listOf(registration().copy(modifiedAt = at)))
        val refused = hygiene.apply(report, report.digest, folder.resolve("refused")).single()
        assertTrue(refused.result.startsWith("Refused"))
        assertTrue(backend.actions.isEmpty())
        backend.table = table
        val receipt = hygiene.apply(report, report.digest, folder.resolve("accepted")).single()
        assertEquals(null, receipt.pid)
        assertEquals(registration().path, receipt.registration)
        assertEquals("Fixture quarantine recorded", receipt.result)
        assertEquals(1, backend.actions.size)
    }

    @Test fun nativeQuarantineBootsOutOnlyTheUnchangedMissingRootRegistration() = temporary { folder ->
        val agents = folder.resolve("Library/LaunchAgents")
        Files.createDirectories(agents)
        val path = agents.resolve("build.reaktor.agents.fixture.plist")
        Files.writeString(path, "fixture registration")
        val source = registration().copy(path = path.toString(), root = folder.resolve("removed").toString(), modifiedAt = Files.getLastModifiedTime(path).toMillis())
        val calls = mutableListOf<List<String>>()
        var loaded = true
        val commands = ClusterCommands { args, _ ->
            calls += args
            when (args.first()) {
                "id" -> CommandResult(0, "501")
                "plutil" -> CommandResult(0, if (args[2] == "WorkingDirectory") source.root!! else source.label!!)
                "launchctl" -> if (args[1] == "bootout") { loaded = false; CommandResult(0, "") } else if (loaded) CommandResult(0, "state = waiting") else CommandResult(113, "")
                else -> CommandResult(1, "")
            }
        }
        val state = folder.resolve("state")
        val backend = NativeHygiene(state, commands, { at }, folder)
        assertEquals("Registration booted out and plist quarantined", backend.actRegistration("local", source))
        assertFalse(Files.exists(path))
        assertTrue(Files.isRegularFile(state.resolve("hygiene/quarantine/$at-${path.fileName}")))
        assertEquals(1, calls.count { it.take(2) == listOf("launchctl", "bootout") })
        assertTrue(calls.none { "--stop" in it || it.first() == "kill" })
    }

    @Test fun nativeQuarantineRefusesChangedRegistrationBeforeAnyBootout() = temporary { folder ->
        val agents = folder.resolve("Library/LaunchAgents")
        Files.createDirectories(agents)
        val path = agents.resolve("build.reaktor.agents.fixture.plist")
        Files.writeString(path, "fixture registration")
        val calls = mutableListOf<List<String>>()
        val backend = NativeHygiene(folder.resolve("state"), ClusterCommands { args, _ -> calls += args; CommandResult(1, "") }, { at }, folder)
        val source = registration().copy(path = path.toString(), root = folder.resolve("removed").toString(), modifiedAt = Files.getLastModifiedTime(path).toMillis() - 1)
        assertFailsWith<IllegalArgumentException> { backend.actRegistration("local", source) }
        assertTrue(calls.isEmpty())
        assertTrue(Files.exists(path))
    }

    @Test fun nativeRegistrationReadsOnlyItsTwoFieldsWithoutTheLargeClasspathPayload() = temporary { folder ->
        val agents = folder.resolve("Library/LaunchAgents")
        Files.createDirectories(agents)
        val path = agents.resolve("build.reaktor.agents.fixture.plist")
        Files.writeString(path, "fixture with a large launcher classpath")
        val calls = mutableListOf<List<String>>()
        val commands = ClusterCommands { args, _ ->
            calls += args
            when (args.first()) {
                "/bin/sh" -> CommandResult(0, "")
                "id" -> CommandResult(0, "501")
                "launchctl" -> CommandResult(0, "state = waiting")
                "plutil" -> CommandResult(0, if (args[2] == "WorkingDirectory") folder.resolve("removed").toString() else "build.reaktor.agents.fixture")
                else -> CommandResult(1, "")
            }
        }
        val table = NativeHygiene(folder.resolve("state"), commands, { at }, folder).table("local", null)
        assertEquals(1, table.registrations.size)
        assertEquals(false, table.registrations.single().rootExists)
        assertEquals("Source", table.registrations.single().truth)
        assertTrue(calls.filter { it.first() == "plutil" }.all { it[1] == "-extract" })
    }

    @Test fun nativeGradleServerJarUsesItsOwnStatusAndConfiguredExpiry() = temporary { folder ->
        val program = folder.resolve(".gradle/wrapper/dists/gradle-9.7.0/instance/gradle-9.7.0/bin/gradle").toString()
        val calls = mutableListOf<List<String>>()
        val commands = ClusterCommands { args, _ ->
            calls += args
            when (args.first()) {
                "/bin/sh" -> CommandResult(0, "71 1 Sun Nov 1 01:00:00 2026 0:00.50 01:00:00 java")
                "ps" -> CommandResult(0, "java -cp ${program.removeSuffix("/bin/gradle")}/lib/gradle-daemon-server-9.7.0.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.7.0")
                "lsof" -> CommandResult(0, "p71\nn${folder}/dev/bestbuds")
                "head" -> CommandResult(0, "x".repeat(9000) + " idleTimeout=300000 ")
                program -> CommandResult(0, "PID STATUS INFO\n71 IDLE 9.7.0")
                else -> CommandResult(1, "")
            }
        }
        val backend = NativeHygiene(folder.resolve("state"), commands, { at }, folder)
        val first = backend.table("local", null)
        assertEquals("Idle", first.processes.single().daemonState)
        assertEquals(300_000L, first.processes.single().expiryMillis)
        assertEquals(null, first.processes.single().idleSince)
        val next = backend.table("local", first.copy(observedAt = at - 600_000))
        assertEquals(at - 600_000, next.processes.single().idleSince)
        assertTrue(calls.any { it.first() == program && "--status" in it })
        assertTrue(calls.none { "--stop" in it })
    }

    @Test fun remoteProcessMetadataUsesOneBatchedSshCapture() = temporary { folder ->
        val calls = mutableListOf<List<String>>()
        val snapshot = buildJsonObject {
            put("table", "71 1 Sun Nov 1 01:00:00 2026 0:00.50 01:00:00 java")
            put("arguments", buildJsonObject { put("71", "worker.org.gradle.process.internal.worker.GradleWorkerMain --secret=NEVER_EMIT_THIS") })
            put("cwd", buildJsonObject { put("71", folder.resolve("dev/bestbuds").toString()) })
            put("expiry", buildJsonObject {})
            put("problems", buildJsonArray {})
        }
        val backend = NativeHygiene(folder.resolve("state"), ClusterCommands { args, timeout ->
            calls += args
            assertEquals(8L, timeout)
            CommandResult(0, snapshot.toString())
        }, { at }, folder)
        val first = backend.table("worker", null)
        assertEquals(1, calls.size)
        assertEquals("ssh", calls.single().first())
        assertTrue(calls.single().last().contains("/usr/bin/python3"))
        assertTrue(calls.single().last().contains("lsof"))
        assertEquals("Test", first.processes.single().kind)
        assertEquals(folder.resolve("dev/bestbuds").toString(), first.processes.single().owner)
        val next = backend.table("worker", first.copy(observedAt = at - 600_000))
        assertEquals(2, calls.size)
        assertEquals(at - 600_000, next.processes.single().idleSince)
        assertFalse(Json.encodeToString(ProcessTable.serializer(), next).contains("NEVER_EMIT_THIS"))
        assertEquals("Partial", next.truth)
    }

    @Test fun remoteGradleMetadataBatchesLogExpiryAndQueriesStatusOnce() = temporary { folder ->
        val program = folder.resolve(".gradle/wrapper/dists/gradle-9.7.0/instance/gradle-9.7.0/bin/gradle").toString()
        val calls = mutableListOf<List<String>>()
        val snapshot = buildJsonObject {
            put("table", "71 1 Sun Nov 1 01:00:00 2026 0:00.50 01:00:00 java\n72 1 Sun Nov 1 01:00:00 2026 0:00.50 01:00:00 java")
            put("arguments", buildJsonObject {
                for (pid in listOf("71", "72")) put(pid, "java -cp ${program.removeSuffix("/bin/gradle")}/lib/gradle-daemon-server-9.7.0.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.7.0")
            })
            put("cwd", buildJsonObject { for (pid in listOf("71", "72")) put(pid, folder.resolve("dev/bestbuds").toString()) })
            put("expiry", buildJsonObject { for (pid in listOf("71", "72")) put(pid, 300_000) })
            put("problems", buildJsonArray {})
        }
        val backend = NativeHygiene(folder.resolve("state"), ClusterCommands { args, _ ->
            calls += args
            if (args.last().contains("/usr/bin/python3")) CommandResult(0, snapshot.toString())
            else CommandResult(0, "PID STATUS INFO\n71 BUSY 9.7.0\n72 IDLE 9.7.0")
        }, { at }, folder)
        val table = backend.table("worker", null)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.first() == "ssh" })
        assertEquals(1, calls.count { it.last().contains("--status") })
        assertEquals(listOf("Busy", "Idle"), table.processes.map { it.daemonState })
        assertTrue(table.processes.all { it.expiryMillis == 300_000L })
        assertTrue(table.processes.all { it.idleSince == null })
    }

    @Test fun malformedOrUnavailableRemoteBatchesNeverEstablishProcessOwnership() = temporary { folder ->
        for (response in listOf(CommandResult(1, ""), CommandResult(0, "broken"), CommandResult(0, "{}"), CommandResult(0, "x".repeat(65_536)))) {
            var calls = 0
            val backend = NativeHygiene(folder.resolve("state"), ClusterCommands { _, _ -> calls++; response }, { at }, folder)
            val report = Hygiene(backend) { at }.report(listOf("worker"))
            assertEquals(1, calls)
            assertEquals("Failed", report.tables.single().truth)
            assertEquals("Unknown", report.tables.single().lease)
            assertTrue(report.findings.isEmpty())
        }
    }

    @Test fun nativeKotlinRunDirectoryPreservesSpacesAndKeepsUnknownClientsProtected() = temporary { folder ->
        val directory = folder.resolve("Library/Application Support/kotlin/daemon").toString()
        val commands = ClusterCommands { args, _ ->
            when (args.first()) {
                "/bin/sh" -> CommandResult(0, "71 1 Sun Nov 1 01:00:00 2026 0:00.50 01:00:00 java")
                "ps" -> CommandResult(0, "java org.jetbrains.kotlin.daemon.KotlinCompileDaemon --daemon-autoshutdownIdleSeconds=7200 --daemon-runFilesPath $directory --daemon-compilerClasspath /managed/compiler")
                "lsof" -> CommandResult(0, "p71\nn${folder}/dev/bestbuds")
                else -> CommandResult(1, "")
            }
        }
        val table = NativeHygiene(folder.resolve("state"), commands, { at }, folder).table("local", null)
        assertEquals(directory, table.processes.single().runDirectory)
        assertEquals(7_200_000L, table.processes.single().expiryMillis)
        assertEquals("Unknown", table.processes.single().daemonState)
        assertEquals(null, table.processes.single().clientMarkers)
        assertFalse(Hygiene(Fake(table)) { at }.report(listOf("local")).findings.single().eligible)
    }

    @Test fun unloadedMissingRootQuarantineDoesNotTakeTheBuildLeaseOrBootOut() = temporary { folder ->
        val agents = folder.resolve("Library/LaunchAgents")
        Files.createDirectories(agents)
        val path = agents.resolve("build.reaktor.agents.fixture.plist")
        Files.writeString(path, "fixture registration")
        val source = registration().copy(path = path.toString(), root = folder.resolve("removed").toString(), modifiedAt = Files.getLastModifiedTime(path).toMillis(), loaded = false)
        val state = folder.resolve("state")
        val calls = mutableListOf<List<String>>()
        val commands = ClusterCommands { args, _ ->
            calls += args
            when (args.first()) {
                "id" -> CommandResult(0, "501")
                "plutil" -> CommandResult(0, if (args[2] == "WorkingDirectory") source.root!! else source.label!!)
                "launchctl" -> CommandResult(113, "")
                else -> CommandResult(1, "")
            }
        }
        val held = assertNotNull(NativeLease.tryAcquire(state.resolve("local-gradle.lock")))
        try {
            assertEquals("Unloaded registration plist quarantined", NativeHygiene(state, commands, { at }, folder).actRegistration("local", source))
            assertTrue(Files.isRegularFile(state.resolve("hygiene/quarantine/$at-${path.fileName}")))
            assertTrue(calls.none { it.take(2) == listOf("launchctl", "bootout") })
            assertEquals("Held", NativeLease.inspect(state.resolve("local-gradle.lock")).state)
        } finally { held.close() }
    }

    @Test fun unloadedQuarantineRefusesIfTheRegistrationBecomesLoaded() = temporary { folder ->
        val agents = folder.resolve("Library/LaunchAgents")
        Files.createDirectories(agents)
        val path = agents.resolve("build.reaktor.agents.fixture.plist")
        Files.writeString(path, "fixture registration")
        val source = registration().copy(path = path.toString(), root = folder.resolve("removed").toString(), modifiedAt = Files.getLastModifiedTime(path).toMillis(), loaded = false)
        val calls = mutableListOf<List<String>>()
        val commands = ClusterCommands { args, _ ->
            calls += args
            when (args.first()) {
                "id" -> CommandResult(0, "501")
                "plutil" -> CommandResult(0, if (args[2] == "WorkingDirectory") source.root!! else source.label!!)
                "launchctl" -> CommandResult(0, "state = waiting")
                else -> CommandResult(1, "")
            }
        }
        assertEquals("Refused: unloaded registration became loaded", NativeHygiene(folder.resolve("state"), commands, { at }, folder).actRegistration("local", source))
        assertTrue(Files.exists(path))
        assertTrue(calls.none { it.take(2) == listOf("launchctl", "bootout") })
    }

    private fun temporary(block: (Path) -> Unit) {
        val folder = Files.createTempDirectory("cluster-hygiene-")
        try { block(folder) } finally { folder.toFile().deleteTreeSafely(within = folder.parent.toFile()) }
    }
}
