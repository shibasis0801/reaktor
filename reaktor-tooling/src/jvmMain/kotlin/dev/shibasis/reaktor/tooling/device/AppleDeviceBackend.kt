package dev.shibasis.reaktor.tooling.device

import dev.shibasis.reaktor.tooling.DevelopmentDevice
import dev.shibasis.reaktor.tooling.DeviceTransport
import dev.shibasis.reaktor.tooling.idb.IdbCompanion
import dev.shibasis.reaktor.tooling.idb.IdbCompanionClient
import dev.shibasis.reaktor.tooling.idb.proto.IdbProto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Physical Apple devices.
 *
 * There is no `adblib` for iOS, and there is no `adb shell` either — Apple exposes a graph of
 * privileged services whose aggregate capability approximates ADB, gated individually on iOS
 * version, Developer Mode, pairing state and whether the developer disk image is mounted. So this
 * backend is a router rather than a client: `devicectl` serves everything Apple supports (and its
 * JSON file is the only interface Apple calls stable), and `idb` serves the real-time surfaces
 * Apple does not expose conveniently — HID injection, video, accessibility, crash logs.
 *
 * The important consequence is that capability is read from the device, not assumed. A paired
 * iPhone with Developer Mode on but no DDI mounted genuinely cannot install an app, and saying so
 * is more useful than a failure three steps later.
 */
class AppleDeviceBackend : DeviceBackend {

    override val transport: DeviceTransport = DeviceTransport.Idb
    override val name: String = "Apple · devicectl + idb"

    private val json = Json { ignoreUnknownKeys = true }

    private val devicectl: String? get() = CommandRunner.locate("xcrun")
    private val idbCompanion: String? get() = IdbCompanion.locate()

    override fun unavailableReason(): String? = when {
        devicectl == null -> "Xcode command line tools are not installed"
        else -> null
    }

    /**
     * Polls rather than subscribes.
     *
     * `devicectl` has no watch mode, so this is honestly a poll and is named as one. Apple's own
     * tooling learns about attach through CoreDevice notifications that are not exposed to a CLI.
     */
    override fun devices(): Flow<List<DevelopmentDevice>> = flow {
        while (true) {
            emit(list())
            kotlinx.coroutines.delay(PollMillis)
        }
    }

    override suspend fun list(): List<DevelopmentDevice> {
        val tool = devicectl ?: return emptyList()
        val payload = runCatching {
            CommandRunner.runJson(listOf(tool, "devicectl", "list", "devices"))
        }.getOrNull().orEmpty()
        if (payload.isBlank()) return emptyList()
        return parse(payload)
    }

    internal fun parse(payload: String): List<DevelopmentDevice> {
        val devices = json.parseToJsonElement(payload).jsonObject["result"]
            ?.jsonObject?.get("devices")?.jsonArray ?: return emptyList()
        return devices.mapNotNull { element ->
            val device = element.jsonObject
            val identifier = device["identifier"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val properties = device["deviceProperties"]?.jsonObject
            val hardware = device["hardwareProperties"]?.jsonObject
            val connection = device["connectionProperties"]?.jsonObject
            DevelopmentDevice(
                id = identifier,
                name = properties?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: hardware?.get("marketingName")?.jsonPrimitive?.contentOrNull
                    ?: identifier,
                transport = DeviceTransport.Idb,
                // `bootState` is absent for a device that is merely paired, which is a different
                // thing from a device that is present and asleep. Both are reported as what they
                // are rather than flattened into "offline".
                state = properties?.get("bootState")?.jsonPrimitive?.contentOrNull
                    ?: connection?.get("pairingState")?.jsonPrimitive?.contentOrNull
                    ?: "unknown",
                runtime = listOfNotNull(
                    hardware?.get("platform")?.jsonPrimitive?.contentOrNull,
                    properties?.get("osVersionNumber")?.jsonPrimitive?.contentOrNull,
                ).joinToString(" "),
            )
        }.sortedBy { it.name }
    }

    /**
     * Opens a session by asking the device about itself, not by reusing the listing.
     *
     * This matters more than it looks. `list devices` reports whatever CoreDevice last cached, so
     * a wired, unlocked iPhone with Developer Mode on can still appear as
     * `ddiServicesAvailable: false` and `tunnelState: disconnected`. Asking for details
     * establishes the tunnel and returns the device's real state — the difference between a
     * router that refuses every operation and one that works.
     */
    override suspend fun session(device: DevelopmentDevice): DeviceSession {
        val tool = devicectl ?: error("Xcode command line tools are not installed")
        val payload = CommandRunner.runJson(
            listOf(tool, "devicectl", "device", "info", "details", "--device", device.id),
            timeoutSeconds = 90,
        )
        val raw = json.parseToJsonElement(payload).jsonObject["result"]?.jsonObject
            ?: error("${device.name} did not answer a details request; is it unlocked and trusted?")
        return AppleDeviceSession(tool, idbCompanion, device, raw, json)
    }

    override fun close() = Unit

    private companion object {
        const val PollMillis = 4_000L
    }
}

/**
 * One physical Apple device, with each operation routed to whichever tool can actually serve it.
 *
 * Refusals here are the feature. `developerModeStatus`, `ddiServicesAvailable` and `tunnelState`
 * each independently disable large parts of the surface, and a developer who is told "Developer
 * Mode is disabled on this device" fixes it in a minute, where "operation failed" costs an hour.
 */
class AppleDeviceSession(
    private val xcrun: String,
    private val idbCompanion: String?,
    override val device: DevelopmentDevice,
    raw: kotlinx.serialization.json.JsonObject,
    private val json: Json,
) : DeviceSession {

    /**
     * One companion per session, started on demand.
     *
     * Starting it eagerly would spawn a process for a device nobody inspects; starting it per call
     * would pay the handshake every time. A session is exactly the right lifetime, and [close]
     * owns it.
     */
    private var companion: IdbCompanion? = null
    private var companionClient: IdbCompanionClient? = null
    private val companionLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun idb(): IdbCompanionClient = companionLock.withLock {
        companionClient?.takeIf { companion?.alive == true } ?: run {
            companion?.close()
            val started = IdbCompanion.start(udid)
            companion = started
            started.client().also { companionClient = it }
        }
    }

    private val properties = raw["deviceProperties"]?.jsonObject
    private val connection = raw["connectionProperties"]?.jsonObject
    private val udid = raw["hardwareProperties"]?.jsonObject?.get("udid")?.jsonPrimitive?.contentOrNull ?: device.id
    private val relays = java.util.concurrent.ConcurrentHashMap<Int, IdbRelay>()

    private val developerMode =
        properties?.get("developerModeStatus")?.jsonPrimitive?.contentOrNull == "enabled"
    private val ddiAvailable =
        properties?.get("ddiServicesAvailable")?.jsonPrimitive?.booleanOrNull == true
    private val paired =
        connection?.get("pairingState")?.jsonPrimitive?.contentOrNull == "paired"
    private val tunnelConnected =
        connection?.get("tunnelState")?.jsonPrimitive?.contentOrNull == "connected"

    override val capabilities: List<DeviceCapabilityReport> = DeviceOperation.entries.map { operation ->
        when (operation) {
            DeviceOperation.Discover ->
                DeviceCapabilityReport(operation, true, null, "devicectl")

            DeviceOperation.Shell ->
                DeviceCapabilityReport(
                    operation,
                    false,
                    "iOS has no shell. Use the app's own agent, or a specific service instead",
                    "none",
                )

            DeviceOperation.Install,
            DeviceOperation.Uninstall,
            DeviceOperation.Launch,
            DeviceOperation.Terminate,
            DeviceOperation.ListApps,
            DeviceOperation.ListProcesses,
            -> appleReport(operation, "devicectl")

            DeviceOperation.Input,
            DeviceOperation.ViewTree,
            ->
                DeviceCapabilityReport(
                    operation,
                    false,
                    "idb drives input and reads the accessibility tree on simulators only; on a physical iPhone use a UI test runner or the app's devtools agent",
                    "none",
                )

            DeviceOperation.Screenshot,
            DeviceOperation.ScreenRecord,
            DeviceOperation.Crash,
            DeviceOperation.Files,
            DeviceOperation.PullFile,
            DeviceOperation.PushFile,
            DeviceOperation.Logs,
            DeviceOperation.LogStream,
            -> idbReport(operation)

            DeviceOperation.Forward -> idbReport(operation)

            DeviceOperation.Reverse ->
                DeviceCapabilityReport(
                    operation,
                    false,
                    "Apple has no reverse forwarding; the device dials the host instead",
                    "none",
                )

            DeviceOperation.ClearData ->
                DeviceCapabilityReport(
                    operation,
                    false,
                    "Uninstall and reinstall to clear an app's container",
                    "devicectl",
                )

            DeviceOperation.DeepLink -> idbReport(operation)
            DeviceOperation.Debugger -> appleReport(operation, "devicectl + lldb")
            DeviceOperation.Location, DeviceOperation.Permissions -> idbReport(operation)
            DeviceOperation.Profile -> appleReport(operation, "xctrace")
            DeviceOperation.Overrides ->
                DeviceCapabilityReport(
                    operation,
                    false,
                    "Overrides are applied by the in-app agent, not by the host",
                    "reaktor-devtools",
                )
        }
    }

    /** Everything Apple gates behind pairing, Developer Mode and a mounted developer disk image. */
    private fun appleReport(operation: DeviceOperation, provider: String) = DeviceCapabilityReport(
        operation = operation,
        available = paired && developerMode && ddiAvailable,
        reason = when {
            !paired -> "${device.name} is not paired with this host"
            !developerMode -> "Developer Mode is disabled on ${device.name}"
            !ddiAvailable ->
                "Developer services are not mounted on ${device.name}; connect it in Xcode once to mount the disk image"

            else -> null
        },
        provider = provider,
    )

    /**
     * Operations served by the idb companion over gRPC.
     *
     * Only the companion binary is required — the Python client is not in the chain at all, which
     * removes the most common reason this surface used to report as unavailable on a machine that
     * had everything installed.
     */
    private fun idbReport(operation: DeviceOperation) = DeviceCapabilityReport(
        operation = operation,
        available = paired && developerMode && idbCompanion != null,
        reason = when {
            !paired -> "${device.name} is not paired with this host"
            !developerMode -> "Developer Mode is disabled on ${device.name}"
            idbCompanion == null -> "Install the companion to reach this device: brew install idb-companion"
            else -> null
        },
        provider = "idb-grpc",
    )

    private fun require(operation: DeviceOperation) {
        require(supports(operation)) { refusalFor(operation) }
    }

    override suspend fun shell(command: String): String = error(refusalFor(DeviceOperation.Shell))

    override fun shellLines(command: String): Flow<String> = error(refusalFor(DeviceOperation.Shell))

    override fun logs(): Flow<String> = kotlinx.coroutines.flow.flow {
        require(DeviceOperation.LogStream)
        emitAll(idb().log())
    }

    override suspend fun listApps(): List<String> {
        require(DeviceOperation.ListApps)
        val payload = CommandRunner.runJson(
            listOf(xcrun, "devicectl", "device", "info", "apps", "--device", device.id),
        )
        val apps = json.parseToJsonElement(payload).jsonObject["result"]?.jsonObject
            ?.get("apps")?.jsonArray.orEmpty()
        return apps.mapNotNull { it.jsonObject["bundleIdentifier"]?.jsonPrimitive?.contentOrNull }.sorted()
    }

    override suspend fun listProcesses(): List<DeviceProcess> {
        require(DeviceOperation.ListProcesses)
        val payload = CommandRunner.runJson(
            listOf(xcrun, "devicectl", "device", "info", "processes", "--device", device.id),
        )
        val processes = json.parseToJsonElement(payload).jsonObject["result"]?.jsonObject
            ?.get("runningProcesses")?.jsonArray.orEmpty()
        return processes.mapNotNull { element ->
            val process = element.jsonObject
            val pid = process["processIdentifier"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: return@mapNotNull null
            DeviceProcess(pid, process["executable"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    override suspend fun install(artifactPath: String) {
        require(DeviceOperation.Install)
        CommandRunner.runJson(
            listOf(xcrun, "devicectl", "device", "install", "app", "--device", device.id, artifactPath),
            timeoutSeconds = 300,
        )
    }

    override suspend fun uninstall(applicationId: String) {
        require(DeviceOperation.Uninstall)
        CommandRunner.runJson(
            listOf(xcrun, "devicectl", "device", "uninstall", "app", "--device", device.id, applicationId),
        )
    }

    override suspend fun launch(applicationId: String, arguments: List<String>) {
        require(DeviceOperation.Launch)
        CommandRunner.runJson(
            listOf(xcrun, "devicectl", "device", "process", "launch", "--device", device.id, applicationId) + arguments,
            timeoutSeconds = 120,
        )
    }

    override suspend fun terminate(applicationId: String) {
        require(DeviceOperation.Terminate)
        idb().terminate(applicationId)
    }

    /**
     * File transfer is not yet on the gRPC path.
     *
     * `pull` and `push` are streaming RPCs whose chunking the companion versions disagree about,
     * and getting that wrong corrupts a file silently. Listing works over gRPC today; transfer
     * says what it needs rather than risking that.
     */
    override suspend fun pull(remotePath: String): ByteArray =
        error("Apple file transfer over gRPC is not implemented; read the container through the app's agent")

    override suspend fun push(bytes: ByteArray, remotePath: String): Unit =
        error("Apple file transfer over gRPC is not implemented; write through the app's agent")

    /** Directory listing inside an app container, over gRPC. */
    suspend fun list(applicationId: String, path: String): List<String> {
        require(DeviceOperation.Files)
        return idb().list(applicationId, path)
    }

    /** Crash reports the target recorded, which is the read Apple gives no other way to take. */
    suspend fun crashes(applicationId: String = ""): List<IdbProto.CrashLogInfo> {
        require(DeviceOperation.Crash)
        return idb().crashes(applicationId)
    }

    suspend fun crash(name: String): String {
        require(DeviceOperation.Crash)
        return idb().crash(name)
    }

    /** The accessibility tree, as the companion's JSON. */
    suspend fun accessibilityTree(): String {
        require(DeviceOperation.ViewTree)
        return idb().accessibilityInfo(nested = true)
    }

    override suspend fun screenshot(): ByteArray {
        require(DeviceOperation.Screenshot)
        return idb().screenshot()
    }

    override suspend fun input(event: DeviceInput) {
        require(DeviceOperation.Input)
        val client = idb()
        when (event.kind) {
            InputKind.Tap -> client.tap(event.x.toDouble(), event.y.toDouble())
            InputKind.Swipe -> client.swipe(
                event.x.toDouble(),
                event.y.toDouble(),
                event.endX.toDouble(),
                event.endY.toDouble(),
                if (event.durationMillis > 0) event.durationMillis / 1000.0 else 0.3,
            )

            InputKind.Home -> client.pressButton(IdbProto.HIDEvent.HIDButtonType.HOME)
            InputKind.Key, InputKind.Text ->
                error("Text and key injection need a keyboard event map that is not wired yet")

            InputKind.Back -> error("iOS has no system back button; drive the app's own navigation")
        }
    }

    override suspend fun openUrl(url: String) {
        require(DeviceOperation.DeepLink)
        idb().openUrl(url)
    }

    suspend fun setLocation(latitude: Double, longitude: Double) {
        require(DeviceOperation.Location)
        idb().setLocation(latitude, longitude)
    }

    /**
     * usbmux forwarding through idb.
     *
     * This is the Apple equivalent of `adb forward`, and it is what puts the in-app agent's
     * loopback listener in reach of the workbench on a physical device.
     */
    override suspend fun forward(localPort: Int, remote: RemoteSocket): Int {
        require(DeviceOperation.Forward)
        val port = when (remote) {
            is RemoteSocket.Tcp -> remote.port
            else -> error("Apple forwarding addresses a TCP port; there are no abstract sockets")
        }
        val binary = idbCompanion ?: error(refusalFor(DeviceOperation.Forward))
        val relay = IdbRelay(binary, udid, port, localPort)
        relays.put(relay.localPort, relay)?.close()
        return relay.localPort
    }

    override suspend fun removeForward(localPort: Int) {
        relays.remove(localPort)?.close()
    }

    override fun close() {
        relays.values.forEach(IdbRelay::close)
        relays.clear()
        companionClient?.close()
        companion?.close()
        companionClient = null
        companion = null
    }
}
