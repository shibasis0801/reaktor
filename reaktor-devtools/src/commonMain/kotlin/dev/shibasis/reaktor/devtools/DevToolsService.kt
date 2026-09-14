package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.PostHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import kotlinx.serialization.Serializable

/**
 * The agent's operation surface, declared once for both ends.
 *
 * The app subclasses this with server handlers; the workbench instantiates the client form. Because
 * both compile against the same declarations, an operation that changes shape breaks the build
 * rather than the connection. Bounded reads live here; anything continuous lives on
 * [DevToolsCarrier], because a request that never ends is not a request.
 */
abstract class DevToolsService(baseUrl: String = "") : Service(baseUrl) {
    companion object {
        const val Describe = "/devtools/describe"
        const val Semantics = "/devtools/semantics"
        const val Logs = "/devtools/logs"
        const val Screenshot = "/devtools/screenshot"
        const val Command = "/devtools/command"
    }
}

@Serializable
class DescribeRequest : Request()

@Serializable
data class DescribeResponse(val descriptor: AgentDescriptor) : Response()

@Serializable
data class SemanticsRequest(
    /** Return only the subtree below this node; empty means the whole tree. */
    val rootId: String = "",
    val maxDepth: Int = 64,
    val maxNodes: Int = 4096,
) : Request()

@Serializable
data class SemanticsResponse(
    val tree: SemanticsSnapshot,
) : Response()

@Serializable
data class LogsRequest(
    val sinceSequence: Long = 0,
    val limit: Int = 500,
    val minimumLevel: LogLevel = LogLevel.Verbose,
    val subsystem: String = "",
) : Request()

@Serializable
data class LogsResponse(
    val entries: List<AgentFact.Log>,
    val droppedSinceCursor: Long = 0,
) : Response()

@Serializable
class ScreenshotRequest : Request()

@Serializable
data class ScreenshotResponse(
    /** Base64 PNG, or empty with a reason when the platform could not produce one. */
    val pngBase64: String,
    val widthPixels: Int,
    val heightPixels: Int,
    val unavailableReason: String? = null,
) : Response()

@Serializable
data class CommandRequest(val command: AgentCommand) : Request()

@Serializable
data class CommandResponse(val result: AgentCommandResult) : Response()

/**
 * A Compose semantics tree, flattened.
 *
 * Flat with parent links rather than nested, because the workbench renders it as a virtualised
 * list and a nested structure would have to be flattened on arrival anyway. [truncated] says the
 * capture hit a bound rather than that the tree ended.
 */
@Serializable
data class SemanticsSnapshot(
    val nodes: List<SemanticsNodeFact>,
    val truncated: Boolean = false,
    val capturedAtNanos: Long = 0,
)

@Serializable
data class SemanticsNodeFact(
    val id: String,
    val parentId: String?,
    val depth: Int,
    /** The role Compose reports, when it reports one. */
    val role: String = "",
    val text: String = "",
    val contentDescription: String = "",
    /** `Modifier.testTag`, which is also how a Reaktor element is addressed. */
    val testTag: String = "",
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    /** The graph node this element belongs to, when the app tagged it. */
    val graphNodeId: String = "",
    /** The navigation route this element was composed under, when known. */
    val route: String = "",
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * The client half, for the workbench.
 *
 * Every call is a plain typed request; the transport underneath is whatever the attachment
 * negotiated — HTTP for a desktop or browser agent, the peer socket for a phone behind
 * `adb forward`.
 */
class DevToolsClient(baseUrl: String = "") : DevToolsService(baseUrl) {
    // Bound with an explicit operation equal to the route. The `by` delegate would rename the
    // operation to `<serialName>.<propertyName>`, which couples the wire key to a Kotlin property
    // name on both sides of a process boundary — fine inside one binary, wrong across a cable.
    val describe = GetHandler<DescribeRequest, DescribeResponse>(Describe, Describe)
    val semantics = GetHandler<SemanticsRequest, SemanticsResponse>(Semantics, Semantics)
    val logs = GetHandler<LogsRequest, LogsResponse>(Logs, Logs)
    val screenshot = GetHandler<ScreenshotRequest, ScreenshotResponse>(Screenshot, Screenshot)
    val command = PostHandler<CommandRequest, CommandResponse>(Command, Command)
}
