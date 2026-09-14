package dev.shibasis.reaktor.devtools

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import dev.shibasis.reaktor.core.framework.Async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * The workbench's end of the link.
 *
 * Reaching an agent is two steps that belong to different layers: something has to put a host port
 * onto the device — `adb forward`, `idb forward`, or nothing at all for a simulator — and then this
 * connects to that port. Keeping them apart is what lets one attachment implementation serve
 * Android, Apple and desktop agents without knowing which it is talking to.
 */
class AgentAttachment(
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
) : AutoCloseable {

    private val selector = SelectorManager(Dispatchers.Async)
    private val client = DevToolsClient()

    private val descriptorState = MutableStateFlow<AgentDescriptor?>(null)
    val descriptor: StateFlow<AgentDescriptor?> = descriptorState

    private val factFlow = MutableSharedFlow<Pair<String, AgentFact>>(extraBufferCapacity = 1024)
    val facts: SharedFlow<Pair<String, AgentFact>> = factFlow

    private val droppedState = MutableStateFlow(0L)

    /** Facts the agent discarded before we read them. Surfaced, never hidden. */
    val dropped: StateFlow<Long> = droppedState

    private var channel: PeerChannel? = null
    private var peer: PeerServiceClient? = null
    private var reader: Job? = null

    suspend fun attach(): AgentDescriptor {
        val socket = aSocket(selector).tcp().connect(host, port)
        val opened = SocketPeerChannel(socket)
        channel = opened
        val peerClient = PeerServiceClient(opened, scope) { frame ->
            when (frame) {
                is CarrierFrame.Hello -> descriptorState.value = frame.descriptor
                is CarrierFrame.Facts -> {
                    if (frame.droppedSinceCursor > 0) droppedState.value = frame.droppedSinceCursor
                    frame.facts.forEach { factFlow.emit(frame.capability to it) }
                }

                else -> Unit
            }
        }
        peer = peerClient
        reader = peerClient.start()
        return peerClient.call(client.describe, DescribeRequest())
            .descriptor
            .also { descriptorState.value = it }
    }

    suspend fun semantics(
        rootId: String = "",
        maxDepth: Int = 64,
        maxNodes: Int = 4096,
    ): SemanticsSnapshot =
        requirePeer().call(client.semantics, SemanticsRequest(rootId, maxDepth, maxNodes)).tree

    suspend fun logs(sinceSequence: Long = 0, limit: Int = 500): LogsResponse =
        requirePeer().call(client.logs, LogsRequest(sinceSequence, limit))

    suspend fun screenshot(): ScreenshotResponse =
        requirePeer().call(client.screenshot, ScreenshotRequest())

    suspend fun execute(
        capability: String,
        action: String,
        arguments: Map<String, String> = emptyMap(),
    ): AgentCommandResult = requirePeer()
        .call(client.command, CommandRequest(AgentCommand(Uuid.random().toString(), capability, action, arguments)))
        .result

    suspend fun subscribe(capability: String, sinceSequence: Long = 0) =
        requirePeer().emit(CarrierFrame.Subscribe(StreamSubscription(capability, sinceSequence)))

    suspend fun unsubscribe(capability: String) =
        requirePeer().emit(CarrierFrame.Unsubscribe(capability))

    private fun requirePeer(): PeerServiceClient =
        peer ?: error("Not attached; call attach() first")

    /** Closes the link cleanly. Prefer this to [close] when the caller can suspend. */
    suspend fun detach() {
        reader?.cancel()
        peer = null
        runCatching { channel?.close() }
        channel = null
        selector.close()
    }

    override fun close() {
        reader?.cancel()
        peer = null
        channel = null
        selector.close()
    }
}

/**
 * Attaches to an agent that is only reachable through a forwarded port.
 *
 * The forwarding step is passed in rather than performed here, because it is the one part that
 * differs per platform — `adb forward` onto an abstract socket, `idb forward` over usbmux, or
 * nothing at all for a simulator — and because it belongs to the device layer, which this module
 * deliberately does not depend on.
 *
 * ```
 * attachThroughForward(scope) { port ->
 *     session.forward(port, RemoteSocket.LocalAbstract(DevToolsProtocol.AndroidSocketName))
 * }
 * ```
 */
suspend fun attachThroughForward(
    scope: CoroutineScope,
    host: String = "127.0.0.1",
    requestedPort: Int = 0,
    forward: suspend (localPort: Int) -> Int,
): Pair<AgentAttachment, AgentDescriptor> {
    val boundPort = forward(requestedPort)
    require(boundPort > 0) { "Forwarding did not report a usable local port" }
    val attachment = AgentAttachment(host, boundPort, scope)
    val descriptor = runCatching { attachment.attach() }.getOrElse { failure ->
        attachment.detach()
        throw IllegalStateException(
            "Port $boundPort is forwarded but no agent answered. Is this a build with DevTools " +
                "enabled, and is the app running?",
            failure,
        )
    }
    return attachment to descriptor
}
