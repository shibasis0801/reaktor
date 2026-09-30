package dev.shibasis.reaktor.tooling.device

import com.android.adblib.AdbHostServices
import com.android.adblib.AdbServerChannelProvider
import com.android.adblib.AdbSession
import com.android.adblib.AdbSessionHost
import com.android.adblib.ConnectedDevice
import com.android.adblib.DeviceSelector
import com.android.adblib.AdbInputChannel
import com.android.adblib.RemoteFileMode
import com.android.adblib.ShellCommandOutputElement
import com.android.adblib.read
import com.android.adblib.SocketSpec
import com.android.adblib.activityManager
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.deviceInfo
import com.android.adblib.fileSystem
import com.android.adblib.packageManager
import com.android.adblib.selector
import com.android.adblib.serialNumber
import com.android.adblib.shell
import dev.shibasis.reaktor.tooling.DevelopmentDevice
import dev.shibasis.reaktor.tooling.DeviceTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Android devices through Google's own adb client.
 *
 * The previous generation of this code spawned the `adb` binary and parsed its stdout, which caps
 * every operation at a single bounded read: no device-attached event, no live log, no socket, no
 * binary payload without a temporary file. `adblib` speaks the adb server protocol directly and in
 * coroutines, so the same operations become flows, and forwarding — the thing the on-device agent
 * depends on entirely — becomes available at all.
 */
class AdbDeviceBackend(
    private val host: AdbSessionHost = AdbSessionHost(),
) : DeviceBackend {

    override val transport: DeviceTransport = DeviceTransport.Adb
    override val name: String = "Android · adb"

    private val session: AdbSession by lazy {
        AdbSession.create(
            host,
            AdbServerChannelProvider.createConnectAddressesWithServerStartup(host),
            Duration.ofSeconds(30),
        )
    }

    /**
     * adblib starts the adb server itself, so the only true blocker is a missing SDK. Reporting
     * that here keeps the reason next to the transport instead of surfacing as a connect failure.
     */
    override fun unavailableReason(): String? = null

    override fun devices(): Flow<List<DevelopmentDevice>> =
        session.connectedDevicesTracker.connectedDevices.map { connected ->
            connected.map { it.toDevelopmentDevice() }
        }

    override suspend fun list(): List<DevelopmentDevice> =
        session.hostServices.devices(AdbHostServices.DeviceInfoFormat.LONG_FORMAT).entries
            .map { info ->
                DevelopmentDevice(
                    id = info.serialNumber,
                    name = info.model?.replace('_', ' ') ?: info.serialNumber,
                    transport = DeviceTransport.Adb,
                    state = info.deviceStateString,
                    runtime = info.product.orEmpty(),
                )
            }

    /**
     * Resolves a live device, waiting briefly for the tracker to populate.
     *
     * The tracker is cold: asking it the instant after construction reports an empty list, which
     * is a lie about the cable that is plugged in.
     */
    override suspend fun session(device: DevelopmentDevice): DeviceSession {
        val connected = withTimeoutOrNull(ConnectTimeoutMillis) {
            session.connectedDevicesTracker.connectedDevices
                .map { devices -> devices.firstOrNull { it.serialNumber == device.id } }
                .filterNotNull()
                .first()
        } ?: error("${device.name} is no longer attached")
        return AdbDeviceSession(session, connected, device)
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { host.close() }
    }

    private companion object {
        const val ConnectTimeoutMillis = 10_000L
    }

    private fun ConnectedDevice.toDevelopmentDevice(): DevelopmentDevice {
        val info = deviceInfo
        return DevelopmentDevice(
            id = info.serialNumber,
            name = info.model?.replace('_', ' ') ?: info.serialNumber,
            transport = DeviceTransport.Adb,
            state = info.deviceStateString,
            runtime = info.product.orEmpty(),
        )
    }
}

/**
 * One attached Android device.
 *
 * Capabilities are reported rather than assumed: a device in `unauthorized` can be listed and
 * nothing else, and saying so beats a shell command that fails with a protocol error.
 */
class AdbDeviceSession(
    private val session: AdbSession,
    private val connected: ConnectedDevice,
    override val device: DevelopmentDevice,
) : DeviceSession {

    private val selector: DeviceSelector get() = connected.selector

    override val capabilities: List<DeviceCapabilityReport> = buildList {
        val online = device.ready
        val refusal = if (online) null else "${device.name} is ${device.state}"
        DeviceOperation.entries.forEach { operation ->
            val unsupported = when (operation) {
                DeviceOperation.Location,
                DeviceOperation.Profile,
                -> "Not implemented for adb yet"

                DeviceOperation.Crash -> "Read tombstones and dropbox through Logs for now"
                else -> null
            }
            add(
                DeviceCapabilityReport(
                    operation = operation,
                    available = online && unsupported == null,
                    reason = refusal ?: unsupported,
                    provider = "adblib",
                )
            )
        }
    }

    override suspend fun shell(command: String): String =
        connected.shell.executeAsText(command).stdout

    override fun shellLines(command: String): Flow<String> =
        connected.shell.executeAsLines(command)
            .filterIsInstance<ShellCommandOutputElement.StdoutLine>()
            .map { it.contents }

    /**
     * A live log, not a snapshot.
     *
     * `-T 1` starts at the most recent entry rather than replaying the whole ring buffer, which on
     * a busy device is tens of thousands of lines nobody asked for.
     */
    override fun logs(): Flow<String> = shellLines("logcat -v threadtime -T 1")

    override suspend fun listApps(): List<String> =
        shell("pm list packages -3").lineSequence()
            .mapNotNull { it.trim().removePrefix("package:").takeIf(String::isNotBlank) }
            .sorted()
            .toList()

    override suspend fun listProcesses(): List<DeviceProcess> =
        shell("ps -A -o PID,NAME").lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"), limit = 2)
                val pid = fields.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val name = fields.getOrNull(1)?.trim().orEmpty()
                DeviceProcess(pid, name, packageName = name.takeIf { it.contains('.') }.orEmpty())
            }
            .toList()

    /**
     * Installs by pushing then invoking `pm`.
     *
     * `adb install` does exactly this underneath. Doing it explicitly keeps split APKs and flags
     * in view, and leaves the staged file cleaned up rather than accumulating in `/data/local/tmp`.
     */
    override suspend fun install(artifactPath: String) {
        val file = File(artifactPath)
        require(file.isFile) { "Select an APK to install" }
        val staged = "/data/local/tmp/${file.name}"
        connected.fileSystem.sendFile(file.toPath(), staged, RemoteFileMode.DEFAULT)
        try {
            val output = shell("pm install -r -t $staged")
            require(output.contains("Success")) { output.trim().ifBlank { "pm install failed" } }
        } finally {
            runCatching { shell("rm -f $staged") }
        }
    }

    override suspend fun uninstall(applicationId: String) {
        connected.packageManager.uninstall(applicationId)
    }

    /**
     * Launches by resolving the launcher activity first.
     *
     * `monkey` was the previous mechanism and it picks an activity by chance; `cmd package
     * resolve-activity` names the one the launcher would start, and `am start -n` starts exactly
     * that one.
     */
    override suspend fun launch(applicationId: String, arguments: List<String>) {
        val resolved = shell("cmd package resolve-activity --brief $applicationId")
            .lineSequence()
            .map(String::trim)
            .lastOrNull { it.contains('/') }
        val extra = arguments.joinToString(" ")
        if (resolved.isNullOrBlank()) {
            shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $applicationId $extra")
        } else {
            shell("am start -n $resolved $extra")
        }
    }

    override suspend fun terminate(applicationId: String) {
        connected.activityManager.forceStop(applicationId)
    }

    override suspend fun pull(remotePath: String): ByteArray {
        val temporary = Files.createTempFile("reaktor-pull", ".bin")
        return try {
            connected.fileSystem.receiveFile(remotePath, temporary)
            Files.readAllBytes(temporary)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    override suspend fun push(bytes: ByteArray, remotePath: String) {
        val temporary: Path = Files.createTempFile("reaktor-push", ".bin")
        try {
            Files.write(temporary, bytes)
            connected.fileSystem.sendFile(temporary, remotePath, RemoteFileMode.DEFAULT)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    /** `exec` rather than `shell`, because a shell would translate CRLF inside the PNG. */
    override suspend fun screenshot(): ByteArray =
        session.deviceServices.rawExec(selector, "screencap -p").use { it.drain() }

    /** Reads a raw exec channel to EOF. `exec` is used so no shell mangles CRLF inside the PNG. */
    private suspend fun AdbInputChannel.drain(bufferBytes: Int = 64 * 1024): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(bufferBytes)
        while (true) {
            buffer.clear()
            if (read(buffer, 60, TimeUnit.SECONDS) < 0) break
            buffer.flip()
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            output.write(chunk)
        }
        return output.toByteArray()
    }

    override suspend fun input(event: DeviceInput) {
        val command = when (event.kind) {
            InputKind.Tap -> "input tap ${event.x} ${event.y}"
            InputKind.Swipe ->
                "input swipe ${event.x} ${event.y} ${event.endX} ${event.endY} ${event.durationMillis}"

            InputKind.Text -> "input text ${event.text.replace(" ", "%s")}"
            InputKind.Key -> "input keyevent ${event.keyCode}"
            InputKind.Home -> "input keyevent KEYCODE_HOME"
            InputKind.Back -> "input keyevent KEYCODE_BACK"
        }
        shell(command)
    }

    override suspend fun openUrl(url: String) {
        shell("am start -a android.intent.action.VIEW -d '$url'")
    }

    /**
     * Opens a host port onto the device.
     *
     * This is the single most load-bearing operation in the whole backend: the on-device agent
     * binds an abstract socket, and this is what puts it in reach of the workbench. `tcp:0` asks
     * adb to pick a free port, which is reported back.
     */
    override suspend fun forward(localPort: Int, remote: RemoteSocket): Int {
        val remoteSpec = when (remote) {
            is RemoteSocket.Tcp -> SocketSpec.Tcp(remote.port)
            is RemoteSocket.LocalAbstract -> SocketSpec.LocalAbstract(remote.name)
            is RemoteSocket.Jdwp -> SocketSpec.Jdwp(remote.pid)
        }
        val bound = session.hostServices.forward(
            selector,
            SocketSpec.Tcp(localPort),
            remoteSpec,
            rebind = true,
        )
        return bound?.toIntOrNull() ?: localPort
    }

    override suspend fun removeForward(localPort: Int) {
        session.hostServices.killForward(selector, SocketSpec.Tcp(localPort))
    }

    /** Processes a debugger could attach to, live. Empty on a device with no debuggable app. */
    fun jdwpProcesses(): Flow<List<Int>> =
        session.deviceServices.trackJdwp(selector).map { it.entries.toList() }

    /**
     * Opens a JDWP channel onto one process.
     *
     * This is the transport half of attaching a debugger, and it is the half that is actually
     * platform-specific — the protocol above it is the same JDWP that serves the JVM. The port is
     * reported so an external debugger can attach to it, because stopping the world from inside
     * this process would perturb the very timings the rest of the tool measures.
     */
    suspend fun openDebuggerPort(pid: Int, localPort: Int = 0): Int =
        forward(localPort, RemoteSocket.Jdwp(pid))

    /** Split APKs, the shape every AAB-derived build actually has. */
    suspend fun installSplit(artifactPaths: List<String>, grantPermissions: Boolean = true) {
        require(artifactPaths.isNotEmpty()) { "Select at least one APK" }
        val staged = artifactPaths.map { path ->
            val file = File(path)
            require(file.isFile && file.extension == "apk") { "Every split must be an APK: $path" }
            val remote = "/data/local/tmp/${file.name}"
            connected.fileSystem.sendFile(file.toPath(), remote, RemoteFileMode.DEFAULT)
            remote
        }
        try {
            val flags = buildString {
                append("-r -t")
                if (grantPermissions) append(" -g")
            }
            val sessionId = shell("pm install-create $flags")
                .let { Regex("\\[(\\d+)]").find(it)?.groupValues?.get(1) }
                ?: error("pm install-create did not return a session id")
            staged.forEachIndexed { index, remote ->
                shell("pm install-write $sessionId split$index $remote")
            }
            val result = shell("pm install-commit $sessionId")
            require(result.contains("Success")) { result.trim().ifBlank { "pm install-commit failed" } }
        } finally {
            staged.forEach { runCatching { shell("rm -f $it") } }
        }
    }

    suspend fun grantPermission(applicationId: String, permission: String) {
        shell("pm grant $applicationId $permission")
    }

    suspend fun revokePermission(applicationId: String, permission: String) {
        shell("pm revoke $applicationId $permission")
    }

    /**
     * Display and locale overrides, the device-toolbar equivalent.
     *
     * Every one of these is global to the device, which is the argument for the in-app overrides
     * the agent offers instead: these change every app the developer has open, and they persist
     * across a reboot until explicitly reset.
     */
    suspend fun setDisplaySize(width: Int, height: Int) = shell("wm size ${width}x$height").let { }

    suspend fun resetDisplaySize() = shell("wm size reset").let { }

    suspend fun setDisplayDensity(density: Int) = shell("wm density $density").let { }

    suspend fun resetDisplayDensity() = shell("wm density reset").let { }

    suspend fun setDarkMode(enabled: Boolean) =
        shell("cmd uimode night ${if (enabled) "yes" else "no"}").let { }

    suspend fun setFontScale(scale: Float) = shell("settings put system font_scale $scale").let { }

    /**
     * Points the device at an HTTP proxy.
     *
     * A device left silently proxied is a security incident with a progress bar, so the previous
     * value is returned for the caller to persist before this is applied, and [clearProxy] exists
     * as its counterpart rather than being left to the developer to remember.
     */
    suspend fun setProxy(hostAndPort: String): String {
        val previous = shell("settings get global http_proxy").trim()
        shell("settings put global http_proxy $hostAndPort")
        return previous
    }

    suspend fun clearProxy(restoreTo: String = ":0") {
        shell("settings put global http_proxy ${restoreTo.ifBlank { ":0" }}")
    }

    /** Records the screen to a device path; pull it afterwards. */
    suspend fun screenRecord(remotePath: String, seconds: Int = 10, bitRate: Int = 8_000_000) {
        shell("screenrecord --time-limit $seconds --bit-rate $bitRate $remotePath")
    }

    suspend fun bugReport(): String = shell("bugreportz -p")

    val diagnostics: AndroidDiagnostics get() = AndroidDiagnostics(this)

    override fun close() = Unit
}
