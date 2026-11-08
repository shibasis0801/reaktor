package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageTest {
    @Test fun reviewedSecretsAndThePreservedCandidateNeverBecomeEvictionCandidates() = fixture { root, state, home ->
        val names = listOf("config.toml", "kubeconfig", "id_rsa", "id_ed25519", "credentials", ".netrc", ".npmrc", ".pgpass",
            "client.pem", "service.env", ".env.production")
        val temp = root.resolve("tmp")
        for (name in names) write(temp.resolve("container/$name"), "protected")
        val preserved = temp.resolve("e2ee-candidate-20261029")
        write(preserved.resolve("build/output"), "generated")
        val report = native(state, home).read()
        assertEquals("Source", report.truth)
        val paths = report.passes.flatMap { it.candidates }.map { Path.of(it.path) }
        assertTrue(paths.none { it.startsWith(preserved) || it == temp.resolve("container") || it.fileName.toString() in names })
        assertTrue(report.passes.single { it.name == "temp" }.bytesBefore.allocated > 0)
    }

    private fun candidate(path: String, priority: Int = 0, size: Long = 100, newest: Long = 0) =
        StorageCandidate(path, "fixture", priority, StorageBytes(size, size), newest, newest)
    private val rules = StorageRules(minimumFreeBytes = 100, minimumIdleSeconds = 0)

    @Test fun configuredNoindexSlotsAndLegacySlotsStayChargedDuringCutover() = fixture { _, state, home ->
        val slots = listOf("slots", "slots.noindex").map { state.resolve("$it/worker") }
        for (slot in slots) {
            write(slot.resolve("source-manifest.json"), "{}")
            write(slot.resolve("app/build/output"), "generated")
        }
        write(state.resolve("config.json"), """{"local_slots_root":"${state.resolve("slots.noindex")}"}""")
        val report = native(state, home).read()
        assertEquals("Source", report.truth)
        assertTrue(slots.all { path -> report.passes.single { it.name == "remote" }.candidates.any { it.path == path.toString() } })
        assertEquals(2, report.passes.count { it.name == "slot-worker" })
        assertTrue(report.passes.single { it.name == "remote" }.bytesBefore.allocated > 0)
        assertEquals(listOf(state.resolve("slots.noindex"), state.resolve("slots")), configuredSlotRoots(state, Json.parseToJsonElement(Files.readString(state.resolve("config.json"))).jsonObject))
    }

    @Test fun configuredRootsRefuseRelativeEscapeBridgeAndNonDirectories() = fixture { root, state, home ->
        for (value in listOf("slots.noindex", root.resolve("outside").toString(), "$state/unused/../slots.noindex")) {
            write(state.resolve("config.json"), """{"local_slots_root":"$value"}""")
            val report = native(state, home).read()
            assertEquals("Partial", report.truth)
            assertTrue(report.passes.flatMap { it.candidates }.none { it.selected })
        }
        val outside = Files.createDirectories(root.resolve("outside"))
        Files.createSymbolicLink(state.resolve("slots.noindex"), outside)
        write(state.resolve("config.json"), """{"local_slots_root":"${state.resolve("slots.noindex")}"}""")
        assertEquals("Partial", native(state, home).read().truth)
    }

    @Test fun floorShrinksTheGoalAndPreservesTheExistingTargetFraction() {
        val pass = StoragePolicy.budget("temp", listOf("/fixture"), StorageBytes(400, 400), (1..4).map { candidate("/$it") },
            1000, rules, 1000, -50)
        assertEquals(250L, pass.diskBoundBytes)
        assertEquals(200L, pass.chargedBytesAfter)
        assertEquals(2, pass.candidates.count { it.selected })
    }

    @Test fun priorityMtimeOpenFilesPinsAndIdleGraceChooseOnlyReviewedCandidates() {
        val rows = listOf(candidate("/later", 2), candidate("/older", 1), candidate("/recent", newest = 999),
            candidate("/open").copy(inUse = true), candidate("/pin").copy(pinned = true), candidate("/keep").copy(retained = "Protected"))
        val pass = StoragePolicy.budget("remote", listOf("/fixture"), StorageBytes(600, 600), rows, 100, rules, 1000, 10_000, idle = 1)
        assertEquals(listOf("/older", "/later"), pass.candidates.filter { it.selected }.map { it.path })
        assertFalse(pass.withinLimit)
        assertTrue(StoragePolicy.budget("remote", emptyList(), StorageBytes(600, 600), rows, 100, rules, 1000, 10_000, skipped = "Held lease").candidates.none { it.selected })
    }

    @Test fun archivesKeepOnlyLatestCompleteFamilyReferencesWithinTheirBudget() {
        val rows = listOf(candidate("/artifacts/003") to setOf("app:jvm"), candidate("/artifacts/002") to setOf("app:jvm"),
            candidate("/artifacts/001") to setOf("app:android"), candidate("/runs/run/artifacts") to emptySet())
        val selected = StoragePolicy.archive(rows, 200, null).filter { it.selected }.map { it.path }.toSet()
        assertEquals(setOf("/artifacts/002", "/runs/run/artifacts"), selected)
    }

    @Test fun workerExpiryRequiresKnownLocalExistenceAndTwentyFourHours() {
        val at = 200_000_000L
        assertTrue(StoragePolicy.worktreeExpired(false, at, at, rules))
        assertTrue(StoragePolicy.worktreeExpired(true, at - 86_400_000, at, rules))
        assertFalse(StoragePolicy.worktreeExpired(true, at - 86_399_999, at, rules))
        assertTrue(StoragePolicy.worktreeExpired(null, 0, at, rules))
        assertFalse(StoragePolicy.worktreeExpired(null, at, at, rules))
        assertEquals(60_000_000_000L, rules.workerLimitBytes)
    }

    @Test fun invalidBudgetsNeverEnterThePass() {
        assertFailsWith<IllegalArgumentException> { StorageRules(tempLimitBytes = 0) }
        assertFailsWith<IllegalArgumentException> { StorageRules(cleanupTargetFraction = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { StorageRules(minimumFreeBytes = -1) }
        assertFailsWith<IllegalArgumentException> { StorageRules(archiveLimitBytes = -1) }
    }

    @Test fun workerReportsItsOwnBudgetAndOnlyKnownAbsentOrExpiredWorktrees() = fixture { _, state, home ->
        val gone = "a".repeat(64)
        val present = "b".repeat(64)
        val unknown = "c".repeat(64)
        for (id in listOf(gone, present, unknown)) write(state.resolve("agent-worktrees/$id/build/output"), "generated")
        val at = System.currentTimeMillis() + 3000
        val presence = mapOf(gone to false, present to true)
        val source = NativeStorage(state, home, now = { at }, systemRoots = emptyList(), openOverride = emptySet<Path>() to emptySet(),
            freeOverride = 100_000_000_000, inspect = { Lease(it.toString(), "Free") }, localWorktrees = presence)
        val report = source.read("worker")
        assertEquals("Source", report.truth)
        assertEquals(60_000_000_000L, report.passes.single { it.name == "worker-offload" }.limitBytes)
        assertEquals(setOf(gone), report.passes.single { it.name == "worktree-expiry" }.candidates.filter { it.selected }.map { Path.of(it.path).fileName.toString() }.toSet())
        val later = NativeStorage(state, home, now = { at + 86_400_000 }, systemRoots = emptyList(), openOverride = emptySet<Path>() to emptySet(),
            freeOverride = 100_000_000_000, inspect = { Lease(it.toString(), "Free") }, localWorktrees = presence).read("worker")
        assertEquals(3, later.passes.single { it.name == "worktree-expiry" }.candidates.count { it.selected })
        assertTrue(Files.exists(state.resolve("agent-worktrees/$gone/build/output")))
    }

    @Test fun twoHostReadPassesOnlyKnownWorktreePresenceAndQualifiesAnUnavailableWorker() = fixture { root, state, home ->
        val id = "a".repeat(64)
        write(state.resolve("config.json"), """{"host":"worker","remote_root":"/fixture/offload"}""")
        write(state.resolve("worktrees/$id.json"), """{"roots":[{"path":"${root.resolve("gone")}"}]}""")
        val at = System.currentTimeMillis() + 3000
        var request: List<String>? = null
        val commands = object : ClusterCommands {
            override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
                if (command.first() != "ssh") return SystemClusterCommands.run(command, timeoutSeconds)
                request = command
                val host = StorageHostReport("worker", at, 100_000_000_000, rules, emptyList(), "Source")
                return CommandResult(0, Json.encodeToString(StorageReport(at, listOf(host), "Source")))
            }
        }
        fun reader() = NativeStorage(state, home, commands = commands, now = { at }, systemRoots = emptyList(),
            openOverride = emptySet<Path>() to emptySet(), freeOverride = 100_000_000_000, inspect = { Lease(it.toString(), "Free") })
        val missing = reader().cluster()
        assertEquals("Partial", missing.truth)
        assertEquals("Unknown", missing.hosts.single { it.host == "worker" }.truth)
        assertEquals(null, request)
        val report = reader().cluster("/reviewed/reaktor-cluster")
        assertEquals("Source", report.truth)
        assertTrue(request!!.last().contains("--local-worktrees"))
        assertTrue(request!!.last().contains("\"$id\":false"))
        assertTrue(request!!.last().contains("--no-record"))
        assertFalse(Files.exists(state.resolve("storage")))
    }

    @Test fun UnreadableReceiptsArePartialAndNeverSelectCandidates() = fixture { root, state, home ->
        write(root.resolve("tmp/archive/output"), "safe")
        write(state.resolve("logs/invalid.json"), "{")
        val report = native(state, home, scanPageEntries = 2).read()
        assertEquals("Partial", report.truth)
        assertTrue(report.problems.any { "receipt" in it })
        assertTrue(report.passes.flatMap { it.candidates }.none { it.selected })
    }

    @Test fun sourceCheckoutsCredentialsAndSymlinkTargetsAreNeverTraversedAsDisposableTrees() = fixture { root, state, home ->
        write(root.resolve("tmp/container/checkout/.git"), "gitdir: /fixture")
        write(root.resolve("tmp/container/checkout/source.kt"), "source")
        write(root.resolve("tmp/container/checkout/build/output"), "generated")
        write(root.resolve("tmp/container/.env.local"), "secret")
        write(root.resolve("tmp/container/archive/file"), "archive")
        val outside = root.resolve("source")
        write(outside.resolve("keep"), "source")
        Files.createSymbolicLink(root.resolve("tmp/link"), outside)
        val report = native(state, home).read(rules = rules.copy(tempLimitBytes = 1, minimumFreeBytes = 0))
        val paths = report.passes.first { it.name == "temp" }.candidates.map { Path.of(it.path) }.toSet()
        assertEquals(setOf(root.resolve("tmp/container/checkout/build"), root.resolve("tmp/container/archive"), root.resolve("tmp/link")), paths)
        assertTrue(Files.exists(outside.resolve("keep")))
        assertTrue(Files.exists(root.resolve("tmp/container/.env.local")))
    }

    @Test fun managedRootCwdsMatchPythonWithoutReleasingNestedCwdsOrOpenFiles() = fixture { root, state, home ->
        val managed = root.resolve("tmp")
        val candidate = managed.resolve("candidate")
        val sibling = managed.resolve("sibling")
        write(candidate.resolve("nested/output"), "payload")
        write(sibling.resolve("output"), "payload")
        fun rows(files: Set<Path>, working: Set<Path>) = NativeStorage(state, home,
            systemRoots = emptyList(), scanDirectory = home.parent.resolve("scan-metadata"),
            openOverride = files to working, freeOverride = 100_000_000_000,
            inspect = { Lease(it.toString(), "Free") }).read(rules = rules)
            .passes.single { it.name == "temp" }.candidates.associate { Path.of(it.path) to it.inUse }
        assertEquals(mapOf(candidate to false, sibling to false), rows(emptySet(), setOf(managed)))
        assertEquals(mapOf(candidate to true, sibling to false), rows(emptySet(), setOf(candidate.resolve("nested"))))
        assertEquals(mapOf(candidate to true, sibling to false), rows(emptySet(), setOf(candidate)))
        assertEquals(mapOf(candidate to true, sibling to false), rows(setOf(candidate.resolve("nested/output")), setOf(managed)))
    }

    @Test fun bothDryRunsChooseTheSameCandidatesOnOneUnchangedTree() = fixture { root, state, home ->
        write(root.resolve("tmp/container/checkout/.git"), "gitdir: /fixture")
        write(root.resolve("tmp/container/checkout/source.kt"), "source")
        write(root.resolve("tmp/container/checkout/build/output"), "generated")
        write(root.resolve("tmp/container/.env.local"), "secret")
        write(root.resolve("tmp/container/old.app/binary"), "archive")
        for (id in listOf("old", "new")) {
            write(state.resolve("artifacts/$id/app/build/output.jar"), "output")
            Files.write(state.resolve("artifacts/$id/app/build/linked.bin"), ByteArray(32768) { 42 })
            Files.createLink(state.resolve("artifacts/$id/app/build/second.bin"), state.resolve("artifacts/$id/app/build/linked.bin"))
            write(state.resolve("artifacts/$id/app/build/reports/test.xml"), "diagnostic")
            write(state.resolve("logs/$id.json"), """{"run_id":"$id","finished":"2026-01-01T00:00:00Z","exit_code":0,"artifact_family":"jvm"}""")
        }
        write(state.resolve("artifacts/pending/app/output"), "pending")
        write(state.resolve("logs/pending.json"), """{"run_id":"pending","workerMayContinue":true}""")
        write(state.resolve("slots/job-1/source-manifest.json"), "{}")
        write(state.resolve("slots/job-1/app/build/cache"), "cache")
        write(state.resolve("artifacts/orphan/app/file"), "orphan")
        for (id in listOf("a".repeat(64), "b".repeat(64), "c".repeat(64))) {
            write(state.resolve("snapshots/$id/manifest.json"), "{}")
            write(state.resolve("snapshot-pins/$id.lock"), "")
        }
        write(state.resolve("runs/pending/receipt.json"), """{"snapshot":"${"c".repeat(64)}","status":"running"}""")
        val clock = System.currentTimeMillis() + 3000
        val before = fingerprint(root)
        val source = native(state, home, clock = clock).read(rules = rules.copy(tempLimitBytes = 1, remoteLimitBytes = 1,
            minimumFreeBytes = 0, archiveLimitBytes = 262144, slotLimitBytes = 0))
        assertEquals("Source", source.truth, source.problems.joinToString())
        val tools = locateTools()
        val script = """
            import json,sys,time,types
            from pathlib import Path
            sys.path.insert(0,sys.argv[1])
            import workspace_storage as s
            import remote_retention as r
            root=Path(sys.argv[2]);state=root/'.remote-dev'
            s.time.time=lambda:float(sys.argv[3])/1000
            entries=list((root/'tmp').iterdir())
            temp=s.enforce(entries,((0,p) for p in s.temp_candidates(entries)),1,0,set(),set(),[root/'tmp'],0,True,None)
            remote=s.enforce([state],s.remote_candidates(state,0),1,0,set(),set(),[state],0,True,None)
            helper=types.SimpleNamespace(STATE=state,artifact_family=lambda tasks:'all')
            retained=0;covered=set();archives=[];sizes={}
            payloads=sorted(r.payloads(helper),key=lambda item:item[0].parent.name if item[0].name=='artifacts' else item[0].name,reverse=True)
            for path,keys in payloads:
                size=r.allocated_bytes(path);sizes[str(path)]=size
                if set(keys)-covered and retained+size<=262144:
                    retained+=size;covered.update(keys)
                else:archives.append(str(path))
            slots=[str(path) for path in sorted((state/'slots').iterdir()) if r.cache_bytes(r.slot_caches(path))>0]
            print(json.dumps({'temp':[row['path'] for row in temp['removed']],'remote':[row['path'] for row in remote['removed']],
                'archives':archives,'retentionAllocatedBytes':sizes,'slots':slots,'tempBytesBefore':temp['bytes_before'],
                'remoteBytesBefore':remote['bytes_before'],'tempBytesAfter':temp['bytes_after'],'remoteBytesAfter':remote['bytes_after']}))
        """.trimIndent()
        val process = ProcessBuilder("python3", "-c", script, tools.toString(), root.toString(), clock.toString()).start()
        val text = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
        val expected = Json.parseToJsonElement(text).jsonObject
        for (name in listOf("temp", "remote")) assertEquals(expected.getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet(),
            source.passes.single { it.name == name }.candidates.filter { it.selected }.map { it.path }.toSet(), name)
        for (name in listOf("temp", "remote")) {
            val pass = source.passes.single { it.name == name }
            assertEquals(expected.getValue("${name}BytesBefore").jsonPrimitive.content.toLong(), pass.bytesBefore.charged)
            assertEquals(expected.getValue("${name}BytesAfter").jsonPrimitive.content.toLong(), pass.chargedBytesAfter)
        }
        val archivePass = source.passes.single { it.name == "archives" }
        assertEquals(expected.getValue("archives").jsonArray.map { it.jsonPrimitive.content }.toSet(), archivePass.candidates.filter { it.selected }.map { it.path }.toSet())
        for (candidate in archivePass.candidates) assertEquals(expected.getValue("retentionAllocatedBytes").jsonObject.getValue(candidate.path).jsonPrimitive.content.toLong(), candidate.retentionAllocatedBytes)
        assertEquals(null, archivePass.chargedBytesAfter)
        assertTrue(archivePass.candidates.any { (it.retentionAllocatedBytes ?: Long.MAX_VALUE) < it.bytes.allocated })
        assertEquals(expected.getValue("slots").jsonArray.map { it.jsonPrimitive.content }.toSet(), source.passes.filter { it.name.startsWith("slot-") && it.candidates.any { row -> row.selected } }.flatMap { it.roots }.toSet())
        assertEquals(before, fingerprint(root))
        val output = Path.of(System.getProperty("user.dir"), "build/reports/cluster-storage")
        Files.createDirectories(output)
        fun relative(value: String) = value.replace(root.toString(), "<tree>")
        val comparison = JsonObject(mapOf("truth" to JsonPrimitive("Fixture"), "treeBefore" to JsonPrimitive(before), "treeAfter" to JsonPrimitive(fingerprint(root)),
            "sameCandidates" to JsonPrimitive(true), "sameBudgetBytes" to JsonPrimitive(true), "sameRetentionAllocations" to JsonPrimitive(true),
            "python" to Json.parseToJsonElement(relative(expected.toString())), "kotlin" to Json.parseToJsonElement(relative(Json.encodeToString(source))),
            "workerAddition" to JsonPrimitive("60 GB and 24-hour expiry are additions, separately covered by JVM tests")))
        Files.writeString(output.resolve("parity.json"), Json { prettyPrint = true }.encodeToString(comparison))
        assertTrue(Files.exists(root.resolve("tmp/container/checkout/source.kt")))
        assertTrue(Files.exists(state.resolve("artifacts/pending/app/output")))
    }

    @Test fun readOnlyStorePreservesMissingStaleFailedAndFixtureTruth() = fixture { root, _, _ ->
        val at = 200_000_000L
        val store = StorageStore(root.resolve("reports")) { at }
        assertEquals("Unknown", store.read().truth)
        store.write(StorageReport(at, emptyList(), "Fixture"))
        assertEquals("Fixture", store.read().truth)
        val before = Files.readString(root.resolve("reports/latest.json"))
        store.read()
        assertEquals(before, Files.readString(root.resolve("reports/latest.json")))
        store.write(StorageReport(at - 300_001, emptyList(), "Source"))
        assertEquals("Stale", store.read().truth)
        Files.writeString(root.resolve("reports/latest.json"), "{")
        assertEquals("Failed", store.read().truth)
    }

    @Test fun protectedDirectoryNamesKeepOnlySafeSiblingsAndStayCharged() = fixture { root, state, home ->
        val container = root.resolve("tmp/container")
        write(container.resolve("config/retained"), "protected")
        write(container.resolve("scratch-a/value"), "candidate")
        write(container.resolve("scratch-b/value"), "candidate")
        val report = native(state, home).read()
        assertEquals("Source", report.truth)
        val pass = report.passes.single { it.name == "temp" }
        val candidates = pass.candidates.map { Path.of(it.path) }.toSet()
        assertEquals(setOf(container.resolve("scratch-a"), container.resolve("scratch-b")), candidates)
        assertTrue(Files.exists(container.resolve("config/retained")))
        assertTrue(pass.bytesBefore.apparent > pass.candidates.sumOf { it.bytes.apparent })
    }

    @Test fun operationalReceiptProjectionDoesNotRelaxTheConfigurationReadLimit() = fixture { _, state, home ->
        write(state.resolve("logs/recorded.json"), "{\"run_id\":\"recorded\",\"finished\":\"2026-01-01T00:00:00Z\",\"ignored\":\"" + "x".repeat(1_048_576) + "\"}")
        assertEquals("Source", native(state, home).read().truth)
        write(state.resolve("config.json"), "{\"ignored\":\"" + "x".repeat(1_048_576) + "\"}")
        val report = native(state, home).read()
        assertEquals("Partial", report.truth)
        assertTrue(report.passes.flatMap { it.candidates }.none { it.selected })
        assertTrue(report.problems.any { it.startsWith("Unreadable storage receipt:") })
    }

    private fun native(state: Path, home: Path, scanPageEntries: Int = 32, clock: Long = System.currentTimeMillis()) = NativeStorage(state, home,
        now = { clock }, systemRoots = emptyList(), scanPageEntries = scanPageEntries, scanDirectory = home.parent.resolve("scan-metadata"), openOverride = emptySet<Path>() to emptySet(),
        freeOverride = 100_000_000_000, inspect = { Lease(it.toString(), "Free") })
    private fun write(path: Path, text: String) { Files.createDirectories(path.parent); Files.writeString(path, text) }
    private fun fingerprint(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream -> stream.sorted().forEach { path ->
            digest.update(root.relativize(path).toString().toByteArray())
            digest.update(Files.getLastModifiedTime(path).toMillis().toString().toByteArray())
            if (Files.isRegularFile(path)) digest.update(Files.readAllBytes(path))
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun locateTools(): Path = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .flatMap { base -> listOf(base.resolve("tooling"), base.resolve("reaktor-cluster/tooling"), base.resolve("reaktor/reaktor-cluster/tooling")).asSequence() }
        .firstOrNull { Files.isRegularFile(it.resolve("workspace_storage.py")) }
        ?: Path.of(System.getProperty("user.home"), "dev/reaktor/reaktor-cluster/tooling").also { require(Files.isRegularFile(it.resolve("workspace_storage.py"))) }
    private fun fixture(block: (Path, Path, Path) -> Unit) {
        val temp = Files.createTempDirectory("cluster-storage-").toRealPath()
        try {
            val root = Files.createDirectories(temp.resolve("dev"))
            val home = Files.createDirectories(temp.resolve("home"))
            val state = Files.createDirectories(root.resolve(".remote-dev"))
            block(root, state, home)
        } finally { temp.toFile().deleteTreeSafely(within = Path.of(System.getProperty("java.io.tmpdir")).toFile()) }
    }
}
