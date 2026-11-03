package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeStorageScanTest {
    private val policy = NativeStorageScan.hash("reviewed-protection".encodeToByteArray())

    @Test fun boundedPagesAndDiskFrontierReachEveryPathWithATerminalReceipt() = fixture { root, journal ->
        repeat(83) { index -> write(root.resolve("directory-$index/nested/value"), "value") }
        val scan = NativeStorageScan(root, journal, policy, pageEntries = 7, pageBytes = 4096)
        val done = finish(scan)
        assertEquals("Source", done.truth, done.problem)
        assertTrue(done.terminal)
        assertEquals(250L, done.entries)
        assertEquals(250, scan.entries(done.cursor).map { it.path }.toSet().size)
        assertTrue(done.pages > 30)
        Files.list(journal).use { paths -> paths.filter { it.fileName.toString().endsWith(".json") }.forEach {
            assertTrue(Files.size(it) <= 4096)
        } }
        assertEquals(done, scan.next(done.cursor))
    }

    @Test fun interruptedProcessResumesTheSameScanWithoutRepeatingOrDroppingMembers() = fixture { root, journal ->
        repeat(31) { write(root.resolve("file-$it"), "value") }
        var scan = NativeStorageScan(root, journal, policy, pageEntries = 3)
        val first = scan.next(scan.start().cursor)
        assertEquals("Partial", first.truth)
        assertFalse(first.terminal)
        scan = NativeStorageScan(root, journal, policy, pageEntries = 3)
        var next = scan.next(first.cursor)
        while (!next.terminal && next.cursor.isNotEmpty()) next = scan.next(next.cursor)
        assertEquals("Source", next.truth, next.problem)
        assertEquals(first.scan, next.scan)
        assertEquals(32, scan.entries(next.cursor).map { it.path }.toSet().size)
        assertEquals(32L, next.entries)
    }

    @Test fun missingOrCorruptedCommittedPageCannotBecomeComplete() = fixture { root, journal ->
        repeat(12) { write(root.resolve("file-$it"), "value") }
        val scan = NativeStorageScan(root, journal, policy, pageEntries = 2)
        val done = finish(scan)
        val page = journal.resolve("page-00000001.json")
        val bytes = Files.readAllBytes(page)
        Files.delete(page)
        assertEquals("Partial", scan.validate(done.cursor).truth)
        Files.write(page, bytes)
        Files.writeString(page, "{}")
        assertEquals("Partial", scan.validate(done.cursor).truth)
    }

    @Test fun fileChurnWithAnUnchangedParentAndDirectoryChurnBothRefuseCompletion() = fixture { root, journal ->
        val file = root.resolve("a-file")
        write(file, "first")
        repeat(10) { write(root.resolve("z-$it"), "value") }
        val scan = NativeStorageScan(root, journal, policy, pageEntries = 2)
        var progress = scan.next(scan.start().cursor)
        write(file, "changed contents")
        while (!progress.terminal && progress.cursor.isNotEmpty()) progress = scan.next(progress.cursor)
        assertEquals("Partial", progress.truth)
        val restarted = NativeStorageScan(root, journal.resolveSibling("restart"), policy, pageEntries = 2)
        val start = restarted.next(restarted.start().cursor)
        write(root.resolve("new-child"), "value")
        assertEquals("Partial", restarted.next(start.cursor).truth)
    }

    @Test fun protectedRootsAndGitStopBeforeDescentAndSymlinksNeverFollow() = fixture { root, journal ->
        write(root.resolve("preserved/build/value"), "protected")
        write(root.resolve(".git/objects/value"), "protected")
        write(root.resolve("ordinary/value"), "normal")
        val outside = Files.createDirectories(root.resolveSibling("outside"))
        write(outside.resolve("must-not-read"), "protected")
        Files.createSymbolicLink(root.resolve("bridge"), outside)
        val scan = NativeStorageScan(root, journal, policy, pageEntries = 2, protected = { it.fileName.toString() == "preserved" })
        val done = finish(scan)
        assertEquals("Source", done.truth, done.problem)
        val rows = scan.entries(done.cursor).toList()
        assertTrue(rows.any { it.path == root.resolve("preserved").toString() && it.decision == "Protected" })
        assertTrue(rows.any { it.path == root.resolve("bridge").toString() && it.decision == "Link" })
        assertTrue(rows.any { it.decision == "Git" })
        assertTrue(rows.none { "/build/" in it.path || "/objects/" in it.path || "must-not-read" in it.path })
        assertTrue(Files.exists(outside.resolve("must-not-read")))
    }

    @Test fun staleCursorDifferentPolicyAndTruncatedFrontierArePartial() = fixture { root, journal ->
        repeat(8) { write(root.resolve("file-$it"), "value") }
        val scan = NativeStorageScan(root, journal, policy, pageEntries = 2)
        val first = scan.start()
        val next = scan.next(first.cursor)
        assertEquals("Partial", scan.next(first.cursor).truth)
        assertEquals("Partial", NativeStorageScan(root, journal, "0".repeat(64), pageEntries = 2).next(next.cursor).truth)
        Files.writeString(journal.resolve("frontier.jsonl"), "{")
        assertEquals("Partial", scan.next(next.cursor).truth)
    }

    @Test fun measurementRequiresBothExactCompletePerPathRowsAcrossBoundedBatches() {
        val paths = (1..79).map { Path.of("/fixture/path-$it") }
        val sizes = mutableListOf<Int>()
        val commands = object : ClusterCommands {
            override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
                val requested = command.filter { it.startsWith("/fixture/") }
                sizes += requested.size
                return CommandResult(0, requested.joinToString("\n") { "1\t$it" })
            }
        }
        val problems = mutableListOf<String>()
        val measured = NativeStorageMeasurement.measure(paths, commands, problems::add)
        assertTrue(problems.isEmpty())
        assertEquals(79, measured.size)
        assertTrue(measured.values.all { it.apparent == 1024L && it.allocated == 1024L })
        assertEquals(listOf(32, 32, 32, 32, 15, 15), sizes)
    }

    @Test fun missingDuplicateForeignErrorAndOverflowMeasurementRowsAreRefused() {
        val paths = listOf(Path.of("/fixture/a"), Path.of("/fixture/b"))
        for (result in listOf(CommandResult(0, "1\t/fixture/a"), CommandResult(0, "1\t/fixture/a\n1\t/fixture/a\n1\t/fixture/b"),
            CommandResult(0, "1\t/fixture/a\n1\t/fixture/foreign"), CommandResult(0, "du: unreadable\n1\t/fixture/a\n1\t/fixture/b"),
            CommandResult(1, "1\t/fixture/a\n1\t/fixture/b"), CommandResult(0, "9223372036854775807\t/fixture/a\n1\t/fixture/b"))) {
            assertEquals(null, NativeStorageMeasurement.parse(paths, result))
        }
    }

    @Test fun interruptedMeasurementsResumeAndChangedPagesCannotSelect() = fixture { root, journal ->
        val paths = (1..70).map { root.resolve("file-$it").also { path -> write(path, "value") } }
        var calls = 0
        var interrupt = true
        val commands = object : ClusterCommands {
            override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
                calls++
                if (interrupt && calls > 2) return CommandResult(124, "Interrupted")
                return CommandResult(0, command.filter { it.startsWith(root.toString()) }.joinToString("\n") { "1\t$it" })
            }
        }
        val problems = mutableListOf<String>()
        assertTrue(NativeStorageMeasurement.measure(paths, commands, { problems += it }, journal, policy).isEmpty())
        assertTrue(problems.isNotEmpty())
        interrupt = false; calls = 0; problems.clear()
        assertEquals(70, NativeStorageMeasurement.measure(paths, commands, { problems += it }, journal, policy).size)
        assertEquals(4, calls)
        assertTrue(problems.isEmpty())
        assertTrue(Files.readString(journal.resolve("terminal.json")).contains("\"terminal\":true"))
        write(paths.first(), "changed")
        assertTrue(NativeStorageMeasurement.measure(paths, commands, { problems += it }, journal, policy).isEmpty())
        assertTrue(problems.isNotEmpty())
    }

    @Test fun aMissingSealedMeasurementPageIsNotSilentlyRecreated() = fixture { root, journal ->
        val paths = listOf(root.resolve("value").also { write(it, "value") })
        var calls = 0
        val commands = object : ClusterCommands {
            override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
                calls++
                return CommandResult(0, "1\t" + paths.single())
            }
        }
        val problems = mutableListOf<String>()
        assertEquals(1, NativeStorageMeasurement.measure(paths, commands, { problems += it }, journal, policy).size)
        Files.delete(journal.resolve("measurement-00000000.json"))
        calls = 0
        repeat(2) {
            problems.clear()
            assertTrue(NativeStorageMeasurement.measure(paths, commands, { problems += it }, journal, policy).isEmpty())
            assertEquals(0, calls)
            assertTrue(problems.isNotEmpty())
        }
    }

    @Test fun manyRootDigestsKeepMeasurementPagesAndTerminalBounded() = fixture { root, journal ->
        val paths = (1..70).map { root.resolve("file-$it").also { path -> write(path, "value") } }
        val input = (1..1_551).joinToString { policy }
        var calls = 0
        val commands = object : ClusterCommands {
            override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
                calls++
                return CommandResult(0, command.filter { it.startsWith(root.toString()) }.joinToString("\n") { "1\t$it" })
            }
        }
        val problems = mutableListOf<String>()
        assertEquals(70, NativeStorageMeasurement.measure(paths, commands, problems::add, journal, input).size)
        assertEquals(6, calls)
        assertTrue(problems.isEmpty())
        Files.list(journal).use { files -> files.filter { it.fileName.toString().endsWith(".json") }.forEach {
            assertTrue(Files.size(it) <= 65_536)
            assertTrue(Files.readString(it).contains(NativeStorageScan.hash(input.encodeToByteArray())))
        } }
        calls = 0
        assertEquals(70, NativeStorageMeasurement.measure(paths, commands, problems::add, journal, input).size)
        assertEquals(0, calls)
        assertTrue(problems.isEmpty())
    }

    @Test fun unavailableTerminalReceiptCannotReturnSelectableMeasurements() = fixture { root, journal ->
        val paths = listOf(root.resolve("value").also { write(it, "value") })
        Files.createDirectories(journal.resolve("terminal.json"))
        write(journal.resolve("terminal.json/occupied"), "value")
        val commands = object : ClusterCommands {
            override fun run(command: List<String>, timeoutSeconds: Long) = CommandResult(0, "1\t" + paths.single())
        }
        val problems = mutableListOf<String>()
        assertTrue(NativeStorageMeasurement.measure(paths, commands, problems::add, journal, policy).isEmpty())
        assertTrue(problems.contains("Measurement terminal receipt unavailable"))
    }

    @Test fun oversizedReceiptProjectsOnlyRequiredFieldsWithoutRaisingTheRetainedBound() = fixture { root, _ ->
        val path = root.resolve("receipt.json")
        Files.newBufferedWriter(path).use { out ->
            out.write("{\"events\":[{\"text\":\"")
            repeat(2_100) { out.write("x".repeat(1024)) }
            out.write(""""}],"run_id":"recorded","started":"2026-01-01T00:00:00Z","owner":{"pid":17,"started":"recorded-start","ignored":"value"},"finished":"recorded-finish","abandoned":null,"status":"succeeded","workerMayContinue":false,"snapshot":"sealed","exit_code":0,"copy_error":null,"artifact_family":"jvm","ignored":{"nested":[true,false,null,-1.2e3,"escaped\\\"value"]}}""")
        }
        assertTrue(Files.size(path) > 1_048_576)
        val projected = NativeStorageReceipt.read(path)
        assertEquals(setOf("run_id", "started", "owner", "finished", "abandoned", "status", "workerMayContinue", "snapshot", "exit_code", "copy_error", "artifact_family"), projected.keys)
        assertEquals(setOf("pid", "started"), (projected.getValue("owner") as kotlinx.serialization.json.JsonObject).keys)
        assertEquals("recorded", (projected.getValue("run_id") as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("succeeded", (projected.getValue("status") as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(projected.toString().encodeToByteArray().size < 1024)
        write(path, "{\"run_id\":\"" + "x".repeat(1_048_576) + "\"}")
        assertFailsWith<IllegalArgumentException> { NativeStorageReceipt.read(path) }
    }

    @Test fun receiptProjectionRejectsEmptyTruncatedMalformedTrailingAndLinkedInput() = fixture { root, _ ->
        val path = root.resolve("receipt.json")
        for (text in listOf("", "{", "{} trailing", "[]", "{\"ignored\":[1,]}", "{\"ignored\":\"bad\\q\"}")) {
            write(path, text)
            assertFailsWith<IllegalArgumentException> { NativeStorageReceipt.read(path) }
        }
        write(path, """{"finished":"earlier","finished":"later","unknown":"\u0041"}""")
        assertEquals("later", (NativeStorageReceipt.read(path).getValue("finished") as kotlinx.serialization.json.JsonPrimitive).content)
        val bridge = root.resolve("bridge")
        Files.createSymbolicLink(bridge, path)
        assertFailsWith<IllegalArgumentException> { NativeStorageReceipt.read(bridge) }
    }

    @Test fun measurementGapsDistinguishCommandFailureMissingRowsAndConcurrentMutation() = fixture { root, journal ->
        val path = root.resolve("value").also { write(it, "value") }
        for (mode in listOf("command", "rows", "churn")) {
            var calls = 0
            val problems = mutableListOf<String>()
            val commands = object : ClusterCommands {
                override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
                    calls++
                    if (mode == "command") return CommandResult(124, "not retained")
                    if (mode == "rows") return CommandResult(0, "")
                    if (calls == 1) write(path, "changed payload")
                    return CommandResult(0, "1\t$path")
                }
            }
            val target = journal.resolve(mode)
            assertTrue(NativeStorageMeasurement.measure(listOf(path), commands, problems::add, target, policy).isEmpty())
            val phase = when (mode) { "command" -> "apparent-command-exit-124"; "rows" -> "apparent-rows"; else -> "during-measurement-stamp" }
            assertTrue(problems.contains("Measurement page 0 refused at $phase"))
            assertFalse(Files.exists(target.resolve("measurement-00000000.json")))
            assertTrue(Files.readString(target.resolve("terminal.json")).contains("\"truth\":\"Partial\""))
        }
    }

    private fun finish(scan: NativeStorageScan): NativeStorageScan.Progress {
        var progress = scan.start()
        var pages = 0
        while (!progress.terminal && progress.cursor.isNotEmpty()) {
            progress = scan.next(progress.cursor)
            check(pages++ < 1000)
        }
        return progress
    }
    private fun write(path: Path, value: String) { Files.createDirectories(path.parent); Files.writeString(path, value) }
    private fun fixture(block: (Path, Path) -> Unit) {
        val temp = Files.createTempDirectory("native-storage-scan-").toRealPath()
        try { block(Files.createDirectories(temp.resolve("root")), temp.resolve("journal")) }
        finally { temp.toFile().deleteTreeSafely(within = Path.of(System.getProperty("java.io.tmpdir")).toFile()) }
    }
}
