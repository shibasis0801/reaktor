package dev.shibasis.reaktor.devtools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire vocabulary shared by the in-app agent and the desktop workbench.
 *
 * Both ends compile against this file, so a protocol change is a compile error rather than a
 * runtime surprise. Nothing here performs I/O; the carrier ([DevToolsCarrier]) and the request
 * surface ([DevToolsService]) are separate on purpose, because they have different failure modes:
 * a dropped stream degrades fidelity, a failed request fails an operation.
 */
object DevToolsProtocol {
    const val Version: Int = 1

    /** Abstract socket name on Android; reached with `adb forward tcp:<port> localabstract:<name>`. */
    const val AndroidSocketName: String = "reaktor-devtools"

    /** Loopback port the Apple and desktop agents listen on when no port is supplied. */
    const val DefaultLoopbackPort: Int = 47_821

    /** Header the traffic tap stamps so proxy bytes can be attributed back to a graph port. */
    const val CorrelationHeader: String = "X-Reaktor-Correlation"
}

/**
 * How well a single fact is known. The rung is per fact, not per target: an attachment can serve
 * `Semantics` at [Attributed] while only reaching [Snapshot] for memory, and saying so is the
 * point. A consumer that needs L3 must be able to discover that it will not get it.
 */
@Serializable
enum class Fidelity {
    /** Declared by the build, never observed. */
    Static,

    /** One bounded read, valid at the instant it was taken. */
    Snapshot,

    /** A bounded recording with a start and an end. */
    Capture,

    /** Continuous, live, ordered. */
    Stream,

    /** Live and joined to graph identity — port, actor, causal chain. */
    Attributed,

    /** Live, attributed, and accepts commands that change the running system. */
    Interactive,
}

/** Whether an operation observes or changes the app, and how dangerous the change is. */
@Serializable
enum class CommandSafety {
    /** Bounded, no side effect on the running system. */
    Read,

    /** Changes app state that the app itself could change. Reversible, needs approval. */
    Write,

    /** Stops the world or changes timing. Facts taken under it are stamped `perturbed`. */
    Perturbing,
}

/**
 * Build identity, split three ways.
 *
 * A digest alone cannot identify an implementation: two builds of one source tree differ by
 * configuration, and two runs of one build differ by activation. Keeping them separate is what
 * lets the workbench say "same code, different config" instead of reporting `unknown`.
 */
@Serializable
data class AgentRevision(
    /** What was built — a version, a git description, or a build fingerprint. */
    val artifact: String,
    /** How it was configured — flavour, build type, remote-config version. */
    val configuration: String,
    /** This particular run. Changes on every process start. */
    val activation: String,
    /** Digest over the graph's declared ports, derived from descriptors rather than declared. */
    val graphDigest: String,
) {
    companion object {
        val Unknown = AgentRevision("unknown", "unknown", "unknown", "unknown")
    }
}

/** One thing this agent can do, and how well. A refusal carries its reason rather than vanishing. */
@Serializable
data class AgentCapability(
    val name: String,
    val fidelity: Fidelity,
    val safety: CommandSafety = CommandSafety.Read,
    /** Present when the capability is known but unavailable here; shown instead of hiding it. */
    val unavailableReason: String? = null,
) {
    val available: Boolean get() = unavailableReason == null

    companion object {
        const val Semantics = "semantics"
        const val Screenshot = "screenshot"
        const val Logs = "logs"
        const val PortEvents = "port.events"
        const val Traffic = "traffic"
        const val FrameVitals = "vitals.frame"
        const val MemoryVitals = "vitals.memory"
        const val Crash = "crash"
        const val Input = "input"
        const val Navigate = "navigate"
        const val Overrides = "overrides"
        const val FaultInjection = "fault"
        const val StateWrite = "state.write"
        const val HeapDump = "heap.dump"
    }
}

/**
 * The handshake. Everything the workbench needs to identify a target before it subscribes to
 * anything, including what it will be refused.
 */
@Serializable
data class AgentDescriptor(
    val agentId: String,
    val applicationId: String,
    val displayName: String,
    val platform: String,
    val osVersion: String,
    val deviceModel: String,
    val revision: AgentRevision,
    val capabilities: List<AgentCapability>,
    val protocolVersion: Int = DevToolsProtocol.Version,
    /** Agent clock at the moment the descriptor was built, for the offset handshake. */
    val epochMillis: Long,
    val monotonicNanos: Long,
    /** False in release builds, where the agent serves reads and refuses every write. */
    val writable: Boolean,
) {
    fun capability(name: String): AgentCapability? = capabilities.firstOrNull { it.name == name }
}

/**
 * A single observation.
 *
 * Every fact carries its own ordering and its own clock reading, so the desktop can merge streams
 * from several agents onto one timeline without trusting wall clocks. [perturbed] is set whenever
 * the fact was produced while something was holding the app still; no duration may be derived
 * from a perturbed interval.
 */
@Serializable
sealed interface AgentFact {
    val sequence: Long
    val monotonicNanos: Long
    val perturbed: Boolean

    @Serializable
    @SerialName("log")
    data class Log(
        override val sequence: Long,
        override val monotonicNanos: Long,
        val level: LogLevel,
        val subsystem: String,
        val message: String,
        val fields: Map<String, String> = emptyMap(),
        /** Set when the entry was emitted inside a correlated call. */
        val correlationId: String? = null,
        val throwable: String? = null,
        override val perturbed: Boolean = false,
    ) : AgentFact

    /** Graph port lifecycle. Invocation events arrive once the kernel makes the call interceptable. */
    @Serializable
    @SerialName("port")
    data class Port(
        override val sequence: Long,
        override val monotonicNanos: Long,
        val kind: PortEventKind,
        val portKey: String,
        val portType: String,
        val nodeId: String? = null,
        val peerPortKey: String? = null,
        /** Wall time the call took, for [PortEventKind.Invoked] and [PortEventKind.Failed]. */
        val durationNanos: Long? = null,
        val failure: String? = null,
        override val perturbed: Boolean = false,
    ) : AgentFact

    /**
     * A boundary crossing observed inside the process.
     *
     * In-process capture is what makes this useful: it sees the request before TLS, so pinning and
     * network-security configuration cannot hide it, and it knows which port issued the call.
     */
    @Serializable
    @SerialName("traffic")
    data class Traffic(
        override val sequence: Long,
        override val monotonicNanos: Long,
        val correlationId: String,
        val operation: String,
        val transport: String,
        val method: String?,
        val url: String,
        val requestBytes: Long,
        val responseBytes: Long,
        val statusCode: Int?,
        val durationMillis: Long,
        val failure: String? = null,
        /** The graph port that issued the call, when the call went through a typed handler. */
        val portKey: String? = null,
        override val perturbed: Boolean = false,
    ) : AgentFact

    @Serializable
    @SerialName("frame")
    data class Frame(
        override val sequence: Long,
        override val monotonicNanos: Long,
        val durationMillis: Double,
        val jank: Boolean,
        val frameIntervalMillis: Double,
        override val perturbed: Boolean = false,
    ) : AgentFact

    @Serializable
    @SerialName("memory")
    data class Memory(
        override val sequence: Long,
        override val monotonicNanos: Long,
        val usedBytes: Long,
        val totalBytes: Long,
        val nativeBytes: Long = 0,
        override val perturbed: Boolean = false,
    ) : AgentFact

    /**
     * A crash or an ANR, replayed on the next connection.
     *
     * The agent cannot deliver this while it is dying, so it is persisted and sent at handshake.
     * That is the whole reason the agent writes to disk at all.
     */
    @Serializable
    @SerialName("crash")
    data class Crash(
        override val sequence: Long,
        override val monotonicNanos: Long,
        val epochMillis: Long,
        val kind: String,
        val message: String,
        val stack: String,
        val threadName: String,
        /** The facts buffered immediately before the crash, oldest first. */
        val precedingFacts: List<String> = emptyList(),
        override val perturbed: Boolean = false,
    ) : AgentFact
}

@Serializable
enum class LogLevel { Verbose, Debug, Info, Warn, Error, Assert }

@Serializable
enum class PortEventKind { Created, Connected, Disconnected, Invoked, Failed }

/** A stream the workbench can subscribe to, named by the capability that produces it. */
@Serializable
data class StreamSubscription(
    val capability: String,
    /** Resume point; facts at or below this sequence are not resent. */
    val sinceSequence: Long = 0,
    /** Producer-side filter, interpreted by the capability. */
    val selector: String = "",
    val bufferPolicy: BufferPolicy = BufferPolicy.DropOldest,
)

/**
 * What the agent does when the desktop cannot keep up.
 *
 * The producer never blocks: an app that stutters because a developer tool is slow is worse than
 * a tool that admits it lost data, so both policies drop and report.
 */
@Serializable
enum class BufferPolicy {
    /** Keep the newest; for event streams where history matters less than currency. */
    DropOldest,

    /** Keep only the latest value; for state that supersedes itself. */
    Conflate,
}

/** One frame on the carrier. */
@Serializable
sealed interface CarrierFrame {
    @Serializable
    @SerialName("hello")
    data class Hello(val descriptor: AgentDescriptor) : CarrierFrame

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val subscription: StreamSubscription) : CarrierFrame

    @Serializable
    @SerialName("unsubscribe")
    data class Unsubscribe(val capability: String) : CarrierFrame

    @Serializable
    @SerialName("fact")
    data class Facts(
        val capability: String,
        val facts: List<AgentFact>,
        /** Non-zero when the buffer policy discarded facts before this batch. */
        val droppedSinceCursor: Long = 0,
    ) : CarrierFrame

    @Serializable
    @SerialName("command")
    data class Command(val request: AgentCommand) : CarrierFrame

    @Serializable
    @SerialName("result")
    data class Result(val result: AgentCommandResult) : CarrierFrame

    /** Clock handshake; the desktop derives the offset from the round trip. */
    @Serializable
    @SerialName("ping")
    data class Ping(val token: String, val agentMonotonicNanos: Long = 0) : CarrierFrame
}

/**
 * A write, addressed by capability.
 *
 * Commands are data rather than methods so the desktop can plan one, show it for approval and
 * record the receipt without the agent needing to know about any of that.
 */
@Serializable
data class AgentCommand(
    val id: String,
    val capability: String,
    val action: String,
    val arguments: Map<String, String> = emptyMap(),
)

@Serializable
data class AgentCommandResult(
    val id: String,
    val accepted: Boolean,
    val detail: String = "",
    val payload: String? = null,
)
