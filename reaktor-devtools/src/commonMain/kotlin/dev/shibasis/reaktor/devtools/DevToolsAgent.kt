package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * What the agent will let the workbench do.
 *
 * A release build is expected to construct this with [writable] false, which is enforced in one
 * place — [DevToolsAgent.execute] — rather than trusted to every command implementation.
 */
data class AgentPolicy(
    val writable: Boolean = true,
    /** Payload capture. Off until the redaction contract exists; descriptors are not payloads. */
    val captureValues: Boolean = false,
    val logLevel: LogLevel = LogLevel.Debug,
    val factCapacity: Int = 512,
)

/** A write the agent knows how to perform, declared with the capability that owns it. */
interface CommandHandler {
    val capability: String
    val actions: Set<String>
    suspend fun execute(command: AgentCommand): AgentCommandResult
}

/** Produces a bounded snapshot of the running UI. Supplied by the Compose layer. */
interface SemanticsProvider {
    suspend fun capture(rootId: String, maxDepth: Int, maxNodes: Int): SemanticsSnapshot
}

/** Produces a PNG of the current surface. Supplied by the Compose layer. */
interface ScreenshotProvider {
    suspend fun capture(): ScreenshotResponse
}

/**
 * The agent embedded in the app.
 *
 * It owns the streams, the policy and the command registry, and it is deliberately ignorant of how
 * it is reached: [DevToolsHost] serves it over whatever channel the platform provides. Constructing
 * one costs a handful of allocations and starts nothing; [start] is what makes it live.
 */
class DevToolsAgent(
    val applicationId: String,
    val displayName: String,
    val revision: AgentRevision = AgentRevision.Unknown,
    val policy: AgentPolicy = AgentPolicy(),
    val agentId: String = Uuid.random().toString(),
    /** Off in a build that cannot afford a per-frame callback; on by default in a debug build. */
    private val recordVitals: Boolean = true,
    private val platform: DevToolsPlatformInfo = devToolsPlatformInfo(),
) {
    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("reaktor-devtools"))

    val portEvents = FactStream(AgentCapability.PortEvents, policy.factCapacity)
    val traffic = FactStream(AgentCapability.Traffic, policy.factCapacity)
    val logs = FactStream(AgentCapability.Logs, policy.factCapacity)
    val frames = FactStream(AgentCapability.FrameVitals, policy.factCapacity)
    val memory = FactStream(AgentCapability.MemoryVitals, 64, BufferPolicy.Conflate)
    val crashes = FactStream(AgentCapability.Crash, 16)

    private val streams = listOf(portEvents, traffic, logs, frames, memory, crashes)
        .associateBy { it.capability }

    val log = LogSink(logs, policy.logLevel, platform::mirrorLog)

    /** The interceptor to install on the app's service client, so traffic is captured at source. */
    val trafficTap = TrafficTap(traffic)

    var semanticsProvider: SemanticsProvider? = null
    var screenshotProvider: ScreenshotProvider? = null

    private val commands = mutableListOf<CommandHandler>()
    private val started = MutableStateFlow(false)
    val running: StateFlow<Boolean> = started

    private val crashes0 = crashStore(applicationId)
    private var crashHook: Cancellable? = null
    private var vitals: VitalsRecorder? = null

    fun register(handler: CommandHandler): DevToolsAgent = apply { commands += handler }

    fun stream(capability: String): FactStream? = streams[capability]

    /**
     * Brings the agent to life.
     *
     * Order matters: the streams start first so the crash replay has somewhere to go, the crash
     * hook goes in before vitals so a crash during startup is still caught, and pending reports
     * from the previous run are replayed last so they arrive as the oldest facts.
     */
    fun start(): Job = scope.launch {
        if (started.value) return@launch
        started.value = true
        streams.values.forEach { it.start(scope) }
        // Every optional capability is installed defensively. A developer tool that takes the app
        // down because a platform API had thread affinity it did not document is worse than one
        // that quietly reports the capability as absent — and that is exactly what happened here
        // with `Choreographer.getInstance()` off the main looper.
        crashHook = runCatching { installCrashHandler(this@DevToolsAgent, crashes0) }.getOrNull()
        if (recordVitals) {
            runCatching { VitalsRecorder(this@DevToolsAgent).also { vitals = it }.start(scope) }
        }
        runCatching { replayPendingCrashes() }
    }

    /**
     * Delivers reports written by a previous run.
     *
     * A crashing process cannot send anything, so the crash the developer most wants to see is
     * always one connection behind. Replaying at startup is what closes that gap.
     */
    private suspend fun replayPendingCrashes() {
        val pending = crashes0.readAll()
        if (pending.isEmpty()) return
        pending.forEach { raw ->
            crashes.emit { sequence, nanos -> parseCrashReport(raw, sequence, nanos) }
        }
        crashes0.clear()
    }

    fun stop() {
        started.value = false
        vitals?.stop()
        crashHook?.cancel()
        scope.cancel()
    }

    /**
     * The handshake payload.
     *
     * Capabilities are derived from what is actually wired rather than declared by hand, so an
     * agent cannot advertise a provider it does not have. A missing one is reported with the reason
     * instead of being omitted — "no screenshot provider" is a more useful answer than silence.
     */
    fun describe(): AgentDescriptor = AgentDescriptor(
        agentId = agentId,
        applicationId = applicationId,
        displayName = displayName,
        platform = platform.name,
        osVersion = platform.osVersion,
        deviceModel = platform.deviceModel,
        revision = revision,
        capabilities = capabilities(),
        epochMillis = platform.epochMillis(),
        monotonicNanos = DevToolsClock.nanos(),
        writable = policy.writable,
    )

    /**
     * What this agent can do, one entry per name.
     *
     * The merge at the end is load-bearing. A capability can be both a stream and a control —
     * `logs` is exactly that: it streams, and it accepts a level change. Adding both produced two
     * entries under one name, and since [AgentDescriptor.capability] answers with the first match,
     * whichever was added second became unreachable. A workbench asking whether it could change the
     * log level got the stream's `Read` entry and concluded it could not.
     *
     * Merged, the entry says both things without contradicting itself: [Fidelity.Interactive]
     * already means "accepts commands that change the running system", so the fidelity carries the
     * control and the safety keeps describing how the capability is *reached*. Safety therefore
     * settles on the most permissive contributor — a read-only build can still subscribe to logs —
     * and the write is refused where it is actually enforced, in [execute], for every handler at
     * once.
     */
    private fun capabilities(): List<AgentCapability> = declaredCapabilities()
        .groupBy { it.name }
        .map { (_, declared) -> declared.reduce(::mergeCapability) }

    private fun mergeCapability(left: AgentCapability, right: AgentCapability): AgentCapability {
        // Reachable through any of its facets is reachable. A refusal only survives when every
        // facet was refused, and then the first reason is the one that explains it.
        val reachable = listOf(left, right).filter { it.available }
        // Only a facet that is actually served may raise the rung. Otherwise a read-only build,
        // where the stream is live and the command is refused, would still advertise `Interactive`
        // — claiming it accepts commands that it will in fact turn away.
        val contributing = reachable.ifEmpty { listOf(left, right) }
        return AgentCapability(
            name = left.name,
            fidelity = contributing.maxOf { it.fidelity },
            safety = contributing.minOf { it.safety },
            unavailableReason = if (reachable.isNotEmpty()) null
            else left.unavailableReason ?: right.unavailableReason,
        )
    }

    private fun declaredCapabilities(): List<AgentCapability> = buildList {
        add(
            AgentCapability(
                AgentCapability.Semantics,
                if (semanticsProvider != null) Fidelity.Snapshot else Fidelity.Static,
                unavailableReason = if (semanticsProvider == null) "No semantics provider is installed" else null,
            )
        )
        add(
            AgentCapability(
                AgentCapability.Screenshot,
                Fidelity.Snapshot,
                unavailableReason = if (screenshotProvider == null) "No screenshot provider is installed" else null,
            )
        )
        add(AgentCapability(AgentCapability.Logs, Fidelity.Stream))
        add(AgentCapability(AgentCapability.PortEvents, Fidelity.Attributed))
        add(AgentCapability(AgentCapability.Traffic, Fidelity.Attributed))
        add(AgentCapability(AgentCapability.FrameVitals, platform.frameVitalsFidelity))
        add(AgentCapability(AgentCapability.MemoryVitals, platform.memoryVitalsFidelity))
        add(AgentCapability(AgentCapability.Crash, Fidelity.Capture))
        commands.forEach { handler ->
            add(
                AgentCapability(
                    handler.capability,
                    Fidelity.Interactive,
                    CommandSafety.Write,
                    unavailableReason = if (policy.writable) null else "This build serves reads only",
                )
            )
        }
    }

    /**
     * Runs a command, or says why it will not.
     *
     * The writable check lives here because it must hold for every handler, including ones added
     * later by an app that never thought about release builds.
     */
    suspend fun execute(command: AgentCommand): AgentCommandResult {
        if (!policy.writable) {
            return AgentCommandResult(command.id, false, "This build serves reads only")
        }
        val handler = commands.firstOrNull {
            it.capability == command.capability && command.action in it.actions
        } ?: return AgentCommandResult(
            command.id,
            false,
            "No handler for ${command.capability}/${command.action}",
        )
        return runCatching { handler.execute(command) }.getOrElse {
            AgentCommandResult(command.id, false, it.message ?: it::class.simpleName ?: "failed")
        }
    }

    suspend fun semantics(request: SemanticsRequest): SemanticsSnapshot =
        semanticsProvider?.capture(request.rootId, request.maxDepth, request.maxNodes)
            ?: SemanticsSnapshot(emptyList(), truncated = false, capturedAtNanos = DevToolsClock.nanos())

    suspend fun screenshot(): ScreenshotResponse =
        screenshotProvider?.capture()
            ?: ScreenshotResponse("", 0, 0, "No screenshot provider is installed")

    internal fun scope(): CoroutineScope = scope
}

/** Platform identity and the platform-specific readings the agent cannot take itself. */
interface DevToolsPlatformInfo {
    val name: String
    val osVersion: String
    val deviceModel: String
    val frameVitalsFidelity: Fidelity
    val memoryVitalsFidelity: Fidelity
    fun epochMillis(): Long
    fun mirrorLog(level: LogLevel, subsystem: String, message: String)
}

expect fun devToolsPlatformInfo(): DevToolsPlatformInfo
