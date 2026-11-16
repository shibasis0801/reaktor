package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.worklog.WorkLogs
import dev.shibasis.reaktor.conductor.worklog.WorkStep
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentEventProjectionTest {
    @Test fun theTransportDeliversEveryFiveMegabyteSampleAndTheWholeFinalAnswer() = runBlocking {
        val directory = Files.createTempDirectory("stream-record-cost").toFile()
        val data = directory.resolve("plane").toPath()
        val checkpoints = kotlinx.coroutines.channels.Channel<kotlinx.coroutines.CompletableDeferred<Unit>>()
        val text = "01234567890123456789".repeat(250_000)
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = kotlinx.coroutines.flow.flow {
                emit(AgentEvent.Started(request.agent.id, null))
                repeat(250_000) { index ->
                    emit(AgentEvent.Delta(request.agent.id, "01234567890123456789"))
                    if ((index + 1) % 5_000 == 0) {
                        val next = kotlinx.coroutines.CompletableDeferred<Unit>()
                        checkpoints.send(next)
                        next.await()
                    }
                }
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, text, true)))
            }
        }
        try {
            lateinit var workspace: AgentWorkspace
            AgentWorkspaceConnection.open(directory, data, mapOf(RuntimeKind.Echo to runtime), extraTools = { workspace = it; emptyList() },
                discover = { ProviderCapability(it) }).use { pane ->
                val started = System.nanoTime()
                var current = pane.submit(AgentSubmission("stream-record-cost", RuntimeKind.Echo, "Measure the synthetic answer"))
                val firstRevision = current.revision
                val file = data.resolve("runs/${current.id}.json")
                val saves = mutableListOf<Pair<Long, Long>>()
                var modified: java.nio.file.attribute.FileTime? = null
                val snapshots = mutableListOf<Int>()
                val transfers = mutableListOf<Long>()
                val received = mutableListOf<Int>()
                val deliveredCharacters = mutableListOf<Int>()
                val transportFailures = mutableListOf<Pair<Int, String>>()
                fun save() {
                    val attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
                    if (attributes.lastModifiedTime() != modified) {
                        modified = attributes.lastModifiedTime()
                        saves += attributes.lastModifiedTime().toMillis() to attributes.size()
                    }
                }
                repeat(50) { index ->
                    val release = kotlinx.coroutines.withTimeout(30_000) { checkpoints.receive() }
                    save()
                    current = workspace.get(current.id)
                    assertEquals((index + 1) * 100_000, current.output.length)
                    snapshots += ConductorJson.encodeToString(AgentRunRecord.serializer(), current).encodeToByteArray().size
                    try {
                        val bytesBefore = pane.receivedRunBytes
                        val delivered = pane.get(current.id)
                        transfers += pane.receivedRunBytes - bytesBefore
                        assertEquals(current.output, delivered.output)
                        deliveredCharacters += delivered.output.length
                        received += ConductorJson.encodeToString(AgentRunRecord.serializer(), delivered).encodeToByteArray().size
                    } catch (failure: IllegalStateException) {
                        if (failure.message != "Workspace response exceeded the transport budget") throw failure
                        transportFailures += current.output.length to failure.message.orEmpty()
                    }
                    release.complete(Unit)
                }
                assertEquals((1..50).map { it * 100_000 }, deliveredCharacters)
                assertEquals(50, received.size)
                assertEquals(50, transfers.size)
                assertTrue(transfers.all { it <= AgentRunTransfer.Budget }, "A sample sends only its changed text")
                assertTrue(transportFailures.isEmpty(), "Every live sample reaches the pane")
                assertEquals(5_000_000, current.output.length)
                while (current.status == AgentRunStatus.Running) current = workspace.awaitChange(current.id, current.revision, 30_000)
                save()
                assertEquals(5_000_000, current.participants.values.single().output.length)
                val completed = pane.get(current.id)
                assertEquals(5_000_000, completed.participants.values.single().output.length)
                assertEquals(5_000_000, completed.output.length)
                val attachment = pane.attach(current.id)
                assertEquals(completed.output, attachment.run.output)
                val persisted = AgentRunTransfer.read(file)
                assertEquals(5_000_000, persisted.participants.values.single().output.length)
                println("HC_RECORD_METRICS=" + buildJsonObject {
                    put("chunks", 250_000)
                    put("wallMillis", (System.nanoTime() - started) / 1_000_000)
                    put("recordRevisions", current.revision - firstRevision)
                    put("sampledRunUpdates", snapshots.size)
                    put("largestRunUpdateBytes", snapshots.max())
                    put("legacyFullSampleBytes", snapshots.sum())
                    put("liveTransportBytes", transfers.sum())
                    put("receivedLivePaneUpdates", received.size)
                    put("actualTransportBytes", pane.receivedRunBytes)
                    put("largestLiveDeltaBytes", transfers.max())
                    put("smallestLiveDeltaBytes", transfers.min())
                    put("journalBytes", Files.size(file))
                    var replay: AgentRunRecord? = null
                    var legacySaveBytes = 0L
                    var journalSaves = 0
                    Files.newBufferedReader(file).useLines { lines -> lines.filter(String::isNotBlank).forEach { line ->
                        replay = AgentRunTransfer.apply(ConductorJson.parseToJsonElement(line), replay)
                        legacySaveBytes += ConductorJson.encodeToString(AgentRunRecord.serializer(), requireNotNull(replay)).encodeToByteArray().size
                        journalSaves++
                    } }
                    put("journalSaves", journalSaves)
                    put("equivalentFullSaveBytes", legacySaveBytes)
                    put("smallestReceivedPaneUpdateBytes", received.min())
                    put("largestReceivedPaneUpdateBytes", received.max())
                    put("completedPaneUpdateBytes", ConductorJson.encodeToString(AgentRunRecord.serializer(), completed).encodeToByteArray().size)
                    putJsonArray("transportFailures") { transportFailures.forEach { (characters, message) -> add(buildJsonObject { put("characters", characters); put("message", message) }) } }
                    put("finalRunOutputCharacters", current.output.length)
                    put("finalParticipantOutputCharacters", current.participants.values.single().output.length)
                    putJsonArray("observedSaves") { saves.forEach { (at, bytes) -> add(buildJsonObject { put("atMillis", at - saves.first().first); put("bytes", bytes) }) } }
                })
            }
        } finally {
            checkpoints.close()
            directory.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun aRunJournalKeepsTheLastCompleteSaveAfterAnInterruptedAppend() {
        val directory = Files.createTempDirectory("run-journal-recovery").toFile()
        try {
            val path = directory.resolve("run.json").toPath()
            val first = run().copy(output = "first", revision = 1)
            Files.writeString(path, ConductorJson.encodeToString(AgentRunRecord.serializer(), first) + "\n")
            val second = first.copy(output = "first then second", revision = 2)
            Files.writeString(path, AgentRunTransfer.delta(second, AgentRunTransfer.held(first)).toString() + "\n", java.nio.file.StandardOpenOption.APPEND)
            Files.writeString(path, "{\"runDelta\":1,", java.nio.file.StandardOpenOption.APPEND)
            assertEquals(second, AgentRunTransfer.read(path))
        } finally { directory.deleteTreeSafely(within = directory.parentFile) }
    }

    @Test fun runDeltasDeduplicateAnswersAndRestartAChangedPrefixWithoutSplittingUnicode() {
        val text = "évidence🧭".repeat(1_000)
        val full = run().copy(output = text, participants = mapOf(agent.id.value to AgentParticipantRun(RuntimeKind.Echo, output = text)))
        var received: AgentRunRecord? = null
        var count = 0
        do {
            val delta = AgentRunTransfer.delta(full, AgentRunTransfer.held(received), 4096)
            assertTrue(delta.toString().toByteArray(Charsets.UTF_8).size <= 4096)
            assertEquals("output", delta.getValue("streams").jsonObject.getValue("participant:${agent.id.value}:output").jsonObject.getValue("alias").jsonPrimitive.content)
            received = AgentRunTransfer.apply(delta, received)
            assertFalse(received.output.lastOrNull()?.isHighSurrogate() == true)
            count++
        } while (delta.getValue("complete").jsonPrimitive.boolean.not())
        assertTrue(count > 1)
        assertEquals(full, received)
        val replacement = full.copy(output = "changed", participants = emptyMap(), revision = 2)
        assertEquals(replacement, AgentRunTransfer.apply(AgentRunTransfer.delta(replacement, AgentRunTransfer.held(received)), received))
    }

    @Test fun aMegabyteInTwentyCharacterChunksReportsProjectionAndSnapshotCosts() {
        val meter = java.lang.management.ManagementFactory.getThreadMXBean()
        val cpuStart = meter.currentThreadCpuTime
        val processCpu = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
        val processStart = processCpu.processCpuTime
        val started = System.nanoTime()
        var state = run()
        var projectedCpu = 0L
        var lastSave = started
        val saves = mutableListOf<Pair<Long, Int>>()
        repeat(50_000) {
            val before = meter.currentThreadCpuTime
            state = projectAgentEvent(state, AgentEvent.Delta(agent.id, "01234567890123456789"), RuntimeKind.Echo)
            projectedCpu += meter.currentThreadCpuTime - before
            val now = System.nanoTime()
            if (now - lastSave >= 1_000_000_000) {
                saves += (now - started) / 1_000_000 to ConductorJson.encodeToString(AgentRunRecord.serializer(), state).encodeToByteArray().size
                lastSave = now
            }
        }
        val snapshotBytes = ConductorJson.encodeToString(AgentRunRecord.serializer(), state).encodeToByteArray().size
        saves += (System.nanoTime() - started) / 1_000_000 to snapshotBytes
        assertEquals(1_000_000, state.output.length)
        assertEquals(state.output, state.participants.getValue(agent.id.value).output)
        println("HC_PROJECTION_METRICS=" + buildJsonObject {
            put("characters", state.output.length)
            put("chunks", 50_000)
            put("projectionCpuMillis", projectedCpu / 1_000_000)
            put("totalCpuMillis", (meter.currentThreadCpuTime - cpuStart) / 1_000_000)
            put("processCpuMillis", (processCpu.processCpuTime - processStart) / 1_000_000)
            put("wallMillis", (System.nanoTime() - started) / 1_000_000)
            put("paneSnapshotBytes", snapshotBytes)
            putJsonArray("saves") { saves.forEach { (at, bytes) -> add(buildJsonObject { put("atMillis", at); put("bytes", bytes) }) } }
        })
    }

    private val agent = AgentSpec(AgentId("stub"), "Stub", RuntimeKind.Echo, "")
    private fun run() = AgentRunRecord("stub-run", "stub-thread", RuntimeKind.Echo, "stub", "Stub", startedAt = 1, updatedAt = 1)

    @Test fun theSecondTurnKeepsEveryStreamedCharacterAndTheCompletedLog() = runBlocking {
        val runtime = DelayedProcessRuntime()
        val directory = Files.createTempDirectory("projection-stream-stub").toFile()
        try {
            val file = directory.resolve("rollout.jsonl")
            val answers = listOf("First answer", "0123456789".repeat(5_000))
            answers.forEachIndexed { turn, answer ->
                var state = run()
                var length = 0
                val outcome = runtime.awaitSession(AgentRequest(agent, answer, directory.path), {}) { event ->
                    state = projectAgentEvent(state, event, RuntimeKind.Echo)
                    if (event is AgentEvent.Delta) {
                        length += event.text.length
                        assertEquals(length, state.output.length)
                        assertEquals(length, state.participants.getValue(agent.id.value).output.length)
                        assertFalse(state.outputTruncated)
                        assertFalse(state.participants.getValue(agent.id.value).outputTruncated)
                    }
                }
                assertTrue(outcome.ok)
                assertEquals(answer, state.participants.getValue(agent.id.value).output)
                fun record(type: String, content: String) = buildJsonObject {
                    put("type", "event_msg")
                    putJsonObject("payload") {
                        put("type", "item_completed")
                        putJsonObject("item") { put("type", type); put("id", "$turn-$type"); put("content", content) }
                    }
                }.toString() + "\n"
                file.appendText(record("UserMessage", "Turn ${turn + 1}") + record("AgentMessage", answer))
            }
            val log = WorkLogs.read(RuntimeKind.Codex, file, directory.path)
            assertEquals(2, log.turns.size)
            assertEquals(answers, log.turns.map { it.steps.filterIsInstance<WorkStep.Message>().single().text })
            assertEquals(runtime.starts, runtime.closes)
        } finally {
            directory.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun reasoningIsLosslessForTheParticipantAndTheRun() {
        var state = run()
        val text = "reasoning ".repeat(5_000)
        text.chunked(997).forEach { chunk -> state = projectAgentEvent(state, AgentEvent.Reasoning(agent.id, chunk, ReasoningFidelity.Thinking), RuntimeKind.Echo) }
        assertEquals(text, state.reasoning)
        assertEquals(text, state.participants.getValue(agent.id.value).reasoning)
        assertFalse(state.reasoningTruncated)
        assertFalse(state.participants.getValue(agent.id.value).reasoningTruncated)
    }

    @Test fun anotherStageKeepsTheRunsEarlierTextWhileItsParticipantStartsFresh() {
        var state = projectAgentEvent(run(), AgentEvent.Delta(agent.id, "First stage. "), RuntimeKind.Echo)
        state = projectAgentEvent(state, AgentEvent.Started(agent.id, null), RuntimeKind.Echo)
        state = projectAgentEvent(state, AgentEvent.Delta(agent.id, "Second stage."), RuntimeKind.Echo)
        assertEquals("First stage. Second stage.", state.output)
        assertEquals("Second stage.", state.participants.getValue(agent.id.value).output)
    }

    @Test fun aFinishedFailureKeepsItsWholeDiagnostic() {
        val failure = "diagnostic ".repeat(5_000)
        val state = projectAgentEvent(run(), AgentEvent.Finished(agent.id, AgentOutcome(agent.id, "", false, failure = failure)), RuntimeKind.Echo)
        assertEquals(failure, state.participants.getValue(agent.id.value).output)
        assertFalse(state.participants.getValue(agent.id.value).outputTruncated)
    }
}
