package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class TranscriptPushTest {
    @Test fun aBoundedTranscriptWaitCanBeCancelledWithoutWaitingForItsDeadline(): Unit = runBlocking {
        val folder = Files.createTempDirectory("transcript-push").toFile()
        val file = folder.resolve("transcript.jsonl").apply { writeText("") }
        val tail = TranscriptTail(file, 0)
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) { entered.complete(Unit); tail.await(60_000) }
        try {
            entered.await()
            yield()
            job.cancel()
            assertNotNull(withTimeoutOrNull(500) { job.join(); true })
        } finally {
            tail.close()
            job.cancelAndJoin()
            folder.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
