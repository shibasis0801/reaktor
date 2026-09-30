package dev.shibasis.reaktor.tooling.device

import dev.shibasis.reaktor.tooling.DevelopmentDevice
import dev.shibasis.reaktor.tooling.DeviceTransport
import kotlinx.coroutines.flow.Flow

/**
 * What a host can ask of an attached device.
 *
 * Modelled as a capability set rather than a method list because support genuinely varies: Android
 * grants a shell, Apple does not; a paired iPhone with Developer Mode off can be listed and not
 * much else; a simulator has no HID injection without idb. A router that answers "why not" is more
 * useful than one that throws, so every operation resolves to a [DeviceCapabilityReport] first.
 */
enum class DeviceOperation {
    Discover,
    Shell,
    Install,
    Uninstall,
    Launch,
    Terminate,
    ClearData,
    ListApps,
    ListProcesses,
    Files,
    PullFile,
    PushFile,
    Logs,
    LogStream,
    Forward,
    Reverse,
    Screenshot,
    ScreenRecord,
    Input,
    ViewTree,
    Permissions,
    DeepLink,
    Location,
    Crash,
    Debugger,
    Profile,
    Overrides,
}

data class DeviceCapabilityReport(
    val operation: DeviceOperation,
    val available: Boolean,
    /** Why it is unavailable, phrased as something the developer can act on. */
    val reason: String? = null,
    /** Which backend would serve it, so a mixed Apple stack can say `devicectl` or `idb`. */
    val provider: String = "",
)

/** A remote endpoint a host port can be forwarded to. */
sealed interface RemoteSocket {
    data class Tcp(val port: Int) : RemoteSocket
    data class LocalAbstract(val name: String) : RemoteSocket
    data class Jdwp(val pid: Int) : RemoteSocket
}

data class DeviceProcess(
    val pid: Int,
    val name: String,
    val packageName: String = "",
    val debuggable: Boolean = false,
)

enum class InputKind { Tap, Swipe, Text, Key, Home, Back }

data class DeviceInput(
    val kind: InputKind,
    val x: Int = 0,
    val y: Int = 0,
    val endX: Int = 0,
    val endY: Int = 0,
    val durationMillis: Int = 0,
    val text: String = "",
    val keyCode: String = "",
)

/**
 * A live connection to one device.
 *
 * Everything that can stream, streams. The one-shot reads that the previous generation of this
 * code was limited to are still here, but they are no longer the only shape available — which was
 * the actual ceiling on what the workbench could show.
 */
interface DeviceSession : AutoCloseable {
    val device: DevelopmentDevice
    val capabilities: List<DeviceCapabilityReport>

    fun supports(operation: DeviceOperation): Boolean =
        capabilities.firstOrNull { it.operation == operation }?.available == true

    fun refusalFor(operation: DeviceOperation): String =
        capabilities.firstOrNull { it.operation == operation }?.reason
            ?: "${device.name} does not support ${operation.name}"

    suspend fun shell(command: String): String
    fun shellLines(command: String): Flow<String>
    fun logs(): Flow<String>
    suspend fun listApps(): List<String>
    suspend fun listProcesses(): List<DeviceProcess>
    suspend fun install(artifactPath: String)
    suspend fun uninstall(applicationId: String)
    suspend fun launch(applicationId: String, arguments: List<String> = emptyList())
    suspend fun terminate(applicationId: String)
    suspend fun pull(remotePath: String): ByteArray
    suspend fun push(bytes: ByteArray, remotePath: String)
    suspend fun screenshot(): ByteArray
    suspend fun input(event: DeviceInput)
    suspend fun openUrl(url: String)

    /** Opens [localPort] on the host onto [remote]. Returns the port actually bound. */
    suspend fun forward(localPort: Int, remote: RemoteSocket): Int
    suspend fun removeForward(localPort: Int)
}

/**
 * A family of devices reached by one mechanism.
 *
 * [devices] is a flow rather than a call because attach and detach are events: a cable pulled out
 * should empty the list without anyone having pressed Refresh.
 */
interface DeviceBackend : AutoCloseable {
    val transport: DeviceTransport
    val name: String

    /** Null when the backend can run; a sentence naming what to install when it cannot. */
    fun unavailableReason(): String?

    /**
     * Live attach and detach.
     *
     * A tracker only starts tracking once something collects it, so the first emission of a cold
     * list is not evidence that nothing is attached — [list] is the question to ask when a caller
     * needs an answer now rather than a subscription.
     */
    fun devices(): Flow<List<DevelopmentDevice>>

    /** One bounded read of what is attached right now. */
    suspend fun list(): List<DevelopmentDevice>

    suspend fun session(device: DevelopmentDevice): DeviceSession
}
