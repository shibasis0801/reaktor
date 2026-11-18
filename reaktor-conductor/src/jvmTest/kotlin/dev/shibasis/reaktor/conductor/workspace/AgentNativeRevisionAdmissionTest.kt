package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AgentNativeRevisionAdmissionTest {
    @Test fun aReviewedNativeRevisionCannotBeSilentlyIgnoredAtAdmission() {
        val root = Files.createTempDirectory("native-revision-admission").toFile()
        val runtime = EchoRuntime(RuntimeKind.Codex) { "Unexpected continuation" }
        try {
            AgentWorkspace(root, root.resolve("state").toPath(), mapOf(runtime.kind to runtime),
                discover = { ProviderCapability(it) }).use { workspace ->
                val encoded = ConductorJson.encodeToJsonElement(AgentSubmission.serializer(),
                    AgentSubmission("reviewed", runtime.kind, "Continue the reviewed turn",
                        continues = ProviderSession(runtime.kind, "fixture-session"))).jsonObject
                val request = ConductorJson.decodeFromJsonElement(AgentSubmission.serializer(), JsonObject(encoded +
                    ("expectedTranscriptRevision" to JsonPrimitive("the-pane-reviewed-this-revision"))))
                val failure = assertFailsWith<IllegalArgumentException> { workspace.submit(request) }
                assertTrue(failure.message.orEmpty().contains("transcript"), failure.message)
                assertTrue(workspace.list().isEmpty(), "A refused continuation creates no run")
            }
        } finally { root.deleteTreeSafely(root.parentFile) }
    }
    @Test fun admissionChecksTheCurrentRevisionWhileHoldingItsLockAndKeepsIdenticalRetries() {
        val root = Files.createTempDirectory("native-revision-current").toFile()
        val runtime = EchoRuntime(RuntimeKind.Codex) { "Continued" }
        var revision = "reviewed"
        var checked = 0
        lateinit var workspace: AgentWorkspace
        try {
            workspace = AgentWorkspace(root, root.resolve("state").toPath(), mapOf(runtime.kind to runtime),
                discover = { ProviderCapability(it) }, transcriptRevision = {
                    val monitor = workspace.javaClass.getDeclaredField("lock").let { field -> field.isAccessible = true; field.get(workspace) }
                    assertTrue(Thread.holdsLock(monitor))
                    assertEquals("fixture-session", it.sessionId)
                    checked++
                    revision
                })
            workspace.use {
                val request = AgentSubmission("reviewed", runtime.kind, "Continue the reviewed turn",
                    continues = ProviderSession(runtime.kind, "fixture-session"), expectedTranscriptRevision = "reviewed")
                val accepted = workspace.submit(request)
                revision = "new-turn"
                assertEquals(accepted.id, workspace.submit(request).id)
                assertEquals(1, checked, "Identical retries return their already admitted run")
                val failure = assertFailsWith<IllegalArgumentException> { workspace.submit(request.copy(requestId = "stale")) }
                assertTrue(failure.message.orEmpty().contains("transcript changed"), failure.message)
                assertEquals(1, workspace.list().size)
            }
        } finally { root.deleteTreeSafely(root.parentFile) }
    }

}
