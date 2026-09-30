package dev.shibasis.reaktor.conductor.store

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class FileThreadStoreTest {
    private val agent = AgentSpec(AgentId("agent"), "Agent", RuntimeKind.Echo, "")
    private val thread = ThreadDocument(ThreadId("test"), "test", listOf(agent))

    @Test
    fun aFreshConductorAndStoreContinueTheSameConversation() = runTest {
        val directory = Files.createTempDirectory("conductor-store-test")
        try {
            val path = directory.resolve("thread.json")
            repeat(2) { turn ->
                FileThreadStore.open(path).use { store ->
                    Conductor(mapOf(RuntimeKind.Echo to EchoRuntime())).run(
                        thread = store.load() ?: thread, prompt = "turn $turn", protocol = Protocol.Ask(agent.id),
                        workingDirectory = ".", onCheckpoint = store::checkpoint,
                    )
                }
            }
            FileThreadStore.open(path).use { store ->
                val loaded = assertNotNull(store.load())
                assertEquals(4, loaded.events.size)
                assertEquals(4, loaded.events.map { it.id }.distinct().size)
                assertEquals(listOf(loaded.events[1].id), loaded.events[2].parents)
                assertEquals(2, loaded.usageSummary().input.unknownTurns)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSecondWriterIsRejectedUntilTheOwnerCloses() {
        val directory = Files.createTempDirectory("conductor-owner-test")
        try {
            val path = directory.resolve("thread.json")
            FileThreadStore.open(path).use { store ->
                store.checkpoint(thread)
                assertFailsWith<IllegalStateException> { FileThreadStore.open(path) }
                assertEquals(thread, store.load())
            }
            FileThreadStore.open(path).use { assertEquals(thread, it.load()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun promptAndProviderIdentitySurviveAnInterruptedRuntime() = runTest {
        val directory = Files.createTempDirectory("conductor-interruption-test")
        try {
            val path = directory.resolve("thread.json")
            val session = ProviderSession(RuntimeKind.Echo, "provider-session")
            val runtime = object : AgentRuntime {
                override val kind = RuntimeKind.Echo
                override fun run(request: AgentRequest) = flow<AgentEvent> {
                    emit(AgentEvent.Started(agent.id, session))
                    error("simulated harness interruption")
                }
            }
            FileThreadStore.open(path).use { store ->
                assertFailsWith<IllegalStateException> {
                    Conductor(mapOf(RuntimeKind.Echo to runtime)).run(
                        thread, "keep this prompt", Protocol.Ask(agent.id), ".", onCheckpoint = store::checkpoint,
                    )
                }
            }
            FileThreadStore.open(path).use { store ->
                val saved = assertNotNull(store.load())
                assertEquals("keep this prompt", saved.events.single().text)
                assertEquals(session, saved.providerSessions[agent.id.value])
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
