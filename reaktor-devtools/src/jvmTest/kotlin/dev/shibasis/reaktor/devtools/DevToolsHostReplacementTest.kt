package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DevToolsHostReplacementTest {

    @Test
    fun aReplacedConnectionHandlesNoMoreFramesEvenIfItsChannelStaysReadable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val agent = DevToolsAgent(
            applicationId = "ai.bestbuds.replacement",
            displayName = "Replacement",
            revision = AgentRevision("test", "debug", "run-1", "digest-1"),
        )
        agent.start()
        val transport = HeldTransport()
        val host = DevToolsHost(agent, transport, scope)
        host.start()
        try {
            val first = transport.connect()
            first.awaitFrame<CarrierFrame.Hello>()
            first.deliver(CarrierFrame.Ping("before"))
            assertEquals("before", first.awaitFrame<CarrierFrame.Ping>().token)
            val second = transport.connect()
            second.awaitFrame<CarrierFrame.Hello>()
            assertEquals(CarrierFrame.Goodbye.Replaced, first.awaitFrame<CarrierFrame.Goodbye>().reason)
            first.deliver(CarrierFrame.Ping("after"))
            second.deliver(CarrierFrame.Ping("second"))
            assertEquals("second", second.awaitFrame<CarrierFrame.Ping>().token)
            delay(300)
            assertTrue(first.frames.filterIsInstance<CarrierFrame.Ping>().none { it.token == "after" })
        } finally {
            host.stop()
            agent.stop()
        }
    }

    private class HeldTransport : AgentTransport {
        private val waiting = Channel<PeerChannel>(Channel.UNLIMITED)
        override val description = "held in memory"

        override suspend fun accept(): PeerChannel = waiting.receive()

        override fun close() {
            waiting.close()
        }

        fun connect(): HeldChannel = HeldChannel().also { waiting.trySend(it) }
    }

    private class HeldChannel : PeerChannel {
        private val inbox = Channel<String>(Channel.UNLIMITED)
        val frames = CopyOnWriteArrayList<CarrierFrame>()
        override val incoming: Flow<String> = inbox.receiveAsFlow()

        override suspend fun send(message: String) {
            (json.decodeFromString<PeerEnvelope>(message) as? PeerEnvelope.Carrier)?.let { frames += it.frame }
        }

        override suspend fun close() = Unit

        suspend fun deliver(frame: CarrierFrame) {
            inbox.send(json.encodeToString<PeerEnvelope>(PeerEnvelope.Carrier(Uuid.random().toString(), frame)))
        }

        suspend inline fun <reified F : CarrierFrame> awaitFrame(): F = withTimeout(4_000) {
            while (frames.none { it is F }) delay(10)
            frames.filterIsInstance<F>().last()
        }
    }
}
