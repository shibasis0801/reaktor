package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.PostHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * The server half of [DevToolsService], answered by a live [DevToolsAgent].
 *
 * Declaring the handlers here rather than in the agent keeps the agent free of the protocol: the
 * agent knows how to take a screenshot, this knows what a screenshot request looks like.
 */
class DevToolsHostService(private val agent: DevToolsAgent) : DevToolsService() {
    val describe = GetHandler<DescribeRequest, DescribeResponse>(Describe, Describe) {
        DescribeResponse(agent.describe())
    }

    val semantics = GetHandler<SemanticsRequest, SemanticsResponse>(Semantics, Semantics) { request ->
        SemanticsResponse(agent.semantics(request))
    }

    val logs = GetHandler<LogsRequest, LogsResponse>(Logs, Logs) { request ->
        val page = agent.logs.since(request.sinceSequence, request.limit)
        val entries = page.facts.filterIsInstance<AgentFact.Log>()
            .filter { it.level.ordinal >= request.minimumLevel.ordinal }
            .filter { request.subsystem.isBlank() || it.subsystem == request.subsystem }
        LogsResponse(entries, page.droppedSinceCursor)
    }

    val screenshot = GetHandler<ScreenshotRequest, ScreenshotResponse>(Screenshot, Screenshot) {
        agent.screenshot()
    }

    val command = PostHandler<CommandRequest, CommandResponse>(Command, Command) { request ->
        CommandResponse(agent.execute(request.command))
    }
}

/**
 * A place the workbench can connect to.
 *
 * The agent listens and the desktop dials, rather than the reverse, because that is what the
 * platform forwarding primitives already do: `adb forward` and usbmux both open a host-side socket
 * onto a device-side listener. Nothing here needs to discover an IP address.
 */
interface AgentTransport {
    /** Human-readable, and precise enough to tell the developer what to forward. */
    val description: String

    /** Suspends until a workbench connects. */
    suspend fun accept(): PeerChannel

    fun close()
}

expect fun devToolsTransport(port: Int = DevToolsProtocol.DefaultLoopbackPort): AgentTransport

/**
 * Serves one agent over one transport.
 *
 * Connections are handled one at a time on purpose. Two workbenches attached to one app would both
 * be issuing writes into the same process with no arbitration between them, and a tool that lets
 * that happen silently is worse than one that makes the second developer wait.
 */
class DevToolsHost(
    private val agent: DevToolsAgent,
    private val transport: AgentTransport,
    private val scope: CoroutineScope = agent.scope(),
) {
    private val service = DevToolsHostService(agent)
    private var connection: Job? = null

    fun start(): Job = scope.launch {
        while (isActive) {
            val channel = runCatching { transport.accept() }.getOrNull() ?: break
            connection?.cancelAndJoin()
            connection = launch { serve(channel) }
        }
    }

    private suspend fun serve(channel: PeerChannel) {
        val subscriptions = mutableMapOf<String, Job>()
        val host = PeerServiceHost(service, channel, scope) { frame ->
            when (frame) {
                is CarrierFrame.Subscribe -> subscribe(frame.subscription, channel, subscriptions)
                is CarrierFrame.Unsubscribe -> subscriptions.remove(frame.capability)?.cancel()
                is CarrierFrame.Command -> {
                    val result = agent.execute(frame.request)
                    emit(channel, CarrierFrame.Result(result))
                }

                is CarrierFrame.Ping -> emit(
                    channel,
                    CarrierFrame.Ping(frame.token, DevToolsClock.nanos()),
                )

                else -> Unit
            }
        }
        val reader = host.start()
        emit(channel, CarrierFrame.Hello(agent.describe()))
        // Anything the app crashed on since the last attach is delivered before live facts, so the
        // first thing a developer sees is the failure rather than whatever happened after it.
        val pending = agent.crashes.latest()
        if (pending.facts.isNotEmpty()) {
            emit(channel, CarrierFrame.Facts(AgentCapability.Crash, pending.facts))
            agent.crashes.clear()
        }
        try {
            reader.join()
        } finally {
            subscriptions.values.forEach { it.cancel() }
            channel.close()
        }
    }

    private fun subscribe(
        subscription: StreamSubscription,
        channel: PeerChannel,
        subscriptions: MutableMap<String, Job>,
    ) {
        val stream = agent.stream(subscription.capability) ?: return
        subscriptions.remove(subscription.capability)?.cancel()
        subscriptions[subscription.capability] = scope.launch {
            val backlog = stream.since(subscription.sinceSequence)
            if (backlog.facts.isNotEmpty()) {
                emit(
                    channel,
                    CarrierFrame.Facts(subscription.capability, backlog.facts, backlog.droppedSinceCursor),
                )
            }
            stream.facts
                .onEach { emit(channel, CarrierFrame.Facts(subscription.capability, listOf(it))) }
                .launchIn(this)
        }
    }

    private suspend fun emit(channel: PeerChannel, frame: CarrierFrame) {
        val envelope = PeerEnvelope.Carrier(Uuid.random().toString(), frame)
        runCatching {
            channel.send(dev.shibasis.reaktor.core.framework.json.encodeToString<PeerEnvelope>(envelope))
        }
    }

    fun stop() {
        connection?.cancel()
        transport.close()
    }
}
