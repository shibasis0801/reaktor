package dev.shibasis.reaktor.tooling

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlin.test.*

class StructuredProcessCaptureTest {
    private fun capture(content: String, limit: Int, secret: String? = null): Pair<TaskRun, List<RunEvent.Output>> = runBlocking {
        val directory = Files.createTempDirectory("structured-capture-").toFile()
        val source = directory.resolve("response.txt").apply { writeText(content) }
        val executor = SupervisedProcessExecutor()
        try {
            val plan = AdHocProcessPlan.create(listOf("/bin/cat", source.path), directory,
                TaskId("structured-read"), safety = SafetyPolicy(SafetyClass.ReadOnly),
                redactions = setOfNotNull(secret), timeoutMillis = 5000).copy(captureStdoutChars = limit)
            val handle = executor.start(plan)
            val events = handle.events.toList()
            handle.await() to events.filterIsInstance<RunEvent.Output>()
        } finally { executor.close(); directory.deleteRecursively() }
    }

    @Test fun capturePreservesMoreThanOneThousandLinesAndLongSingleLineDocuments() {
        val content = "{\"items\":[\n" + List(1400) { "{\"name\":\"pod-$it\"}" }.joinToString(",\n") + "\n]}"
        for (value in listOf(content, content.replace("\n", ""))) {
            val (run, output) = capture(value, 100_000)
            assertEquals(RunStatus.Succeeded, run.status)
            assertEquals(value, output.single { it.channel == OutputChannel.Stdout }.text)
        }
    }

    @Test fun oversizedCapturesFailWithoutPublishingAPartialDocument() {
        val (run, output) = capture("x".repeat(50_000), 20_000)
        assertEquals(RunStatus.Failed, run.status)
        assertContains(run.failure?.message.orEmpty(), "capture limit")
        assertTrue(output.none { it.channel == OutputChannel.Stdout })
    }

    @Test fun completePayloadStillUsesTheExecutorsSecretRedactionBoundary() {
        val (run, output) = capture("{\"token\":\"private-token\"}", 1000, "private-token")
        assertEquals(RunStatus.Succeeded, run.status)
        assertFalse(output.single().text.contains("private-token"))
        assertContains(output.single().text, "[REDACTED]")
    }
}
