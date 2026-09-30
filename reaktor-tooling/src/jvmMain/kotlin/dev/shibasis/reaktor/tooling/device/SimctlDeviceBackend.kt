package dev.shibasis.reaktor.tooling.device

import dev.shibasis.reaktor.tooling.DevelopmentDevice
import dev.shibasis.reaktor.tooling.DeviceTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apple simulators.
 *
 * Far more capable than a physical Apple device for everything except realism: `simctl` grants
 * push notifications, simulated location, privacy grants, status-bar overrides and appearance
 * switching that no paired iPhone will give a host. It has no HID injection and no accessibility
 * tree — those come from idb, or from the in-app agent, which is why the agent exists.
 *
 * It also needs no forwarding at all: the simulator shares the host's loopback, so the workbench
 * connects to the agent directly.
 */
class SimctlDeviceBackend : DeviceBackend {

    override val transport: DeviceTransport = DeviceTransport.Simctl
    override val name: String = "Apple simulators · simctl"

    private val json = Json { ignoreUnknownKeys = true }
    private val xcrun: String? get() = CommandRunner.locate("xcrun")

    override fun unavailableReason(): String? =
        if (xcrun == null) "Xcode command line tools are not installed" else null

    override fun devices(): Flow<List<DevelopmentDevice>> = flow {
        while (true) {
            emit(list())
            delay(PollMillis)
        }
    }

    override suspend fun list(): List<DevelopmentDevice> {
        val tool = xcrun ?: return emptyList()
        val output = CommandRunner.run(
            listOf(tool, "simctl", "list", "devices", "available", "--json"),
        )
        if (!output.succeeded) return emptyList()
        return parse(output.stdout)
    }

    internal fun parse(payload: String): List<DevelopmentDevice> {
        val runtimes = json.parseToJsonElement(payload).jsonObject["devices"]?.jsonObject
            ?: return emptyList()
        return runtimes.flatMap { (runtime, devices) ->
            devices.jsonArray.mapNotNull { element ->
                val device = element.jsonObject
                if (device["isAvailable"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
                val udid = device["udid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                DevelopmentDevice(
                    id = udid,
                    name = device["name"]?.jsonPrimitive?.contentOrNull ?: udid,
                    transport = DeviceTransport.Simctl,
                    state = device["state"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                    runtime = runtime.substringAfterLast("SimRuntime."),
                )
            }
        }.sortedWith(compareBy<DevelopmentDevice> { !it.ready }.thenBy { it.name })
    }

    override suspend fun session(device: DevelopmentDevice): DeviceSession =
        SimctlDeviceSession(xcrun ?: error("Xcode command line tools are not installed"), device)

    override fun close() = Unit

    private companion object {
        const val PollMillis = 4_000L
    }
}

class SimctlDeviceSession(
    private val xcrun: String,
    override val device: DevelopmentDevice,
) : DeviceSession {

    private val booted = device.state.equals("Booted", ignoreCase = true)

    override val capabilities: List<DeviceCapabilityReport> = DeviceOperation.entries.map { operation ->
        when (operation) {
            DeviceOperation.Discover -> report(operation, true)

            // `simctl spawn` runs a process inside the simulator, which is as close to a shell as
            // Apple offers — and unlike a device, it genuinely works.
            DeviceOperation.Shell -> report(operation, booted)

            DeviceOperation.Input, DeviceOperation.ViewTree ->
                DeviceCapabilityReport(
                    operation,
                    false,
                    "simctl has no HID or accessibility surface; use an idb target or the in-app agent",
                    "none",
                )

            DeviceOperation.Reverse ->
                DeviceCapabilityReport(
                    operation,
                    false,
                    "A simulator shares the host's loopback, so nothing needs reversing",
                    "none",
                )

            DeviceOperation.Forward ->
                DeviceCapabilityReport(
                    operation,
                    true,
                    null,
                    "loopback (shared with the host)",
                )

            DeviceOperation.ClearData ->
                DeviceCapabilityReport(
                    operation,
                    false,
                    "simctl erases a whole device, not one app; uninstall and reinstall instead",
                    "simctl",
                )

            DeviceOperation.Debugger, DeviceOperation.Profile ->
                DeviceCapabilityReport(operation, booted, reasonFor(booted), "lldb / xctrace")

            else -> report(operation, booted)
        }
    }

    private fun report(operation: DeviceOperation, available: Boolean) =
        DeviceCapabilityReport(operation, available, reasonFor(available), "simctl")

    private fun reasonFor(available: Boolean) =
        if (available) null else "${device.name} is ${device.state}; boot it first"

    private fun requireBooted(operation: DeviceOperation) {
        require(supports(operation)) { refusalFor(operation) }
    }

    private suspend fun simctl(vararg args: String, timeoutSeconds: Long = 60) =
        CommandRunner.run(listOf(xcrun, "simctl") + args, timeoutSeconds)
            .requireSuccess("simctl ${args.firstOrNull().orEmpty()}")

    override suspend fun shell(command: String): String {
        requireBooted(DeviceOperation.Shell)
        return CommandRunner.run(spawnArgv(command)).requireSuccess("simctl spawn")
    }

    override fun shellLines(command: String): Flow<String> = CommandRunner.stream(spawnArgv(command))

    /**
     * Runs a command inside the simulator's own userland.
     *
     * The spawned shell inherits the *host's* environment, which does not describe the simulator's
     * filesystem — so `uname` resolves to nothing and every command fails with 127 until a PATH is
     * set. Setting it here rather than at each call site is why callers can write ordinary shell.
     */
    private fun spawnArgv(command: String): List<String> = listOf(
        xcrun, "simctl", "spawn", device.id, "/bin/sh", "-c",
        "export PATH=/usr/bin:/bin:/usr/sbin:/sbin; $command",
    )

    override fun logs(): Flow<String> = CommandRunner.stream(
        listOf(xcrun, "simctl", "spawn", device.id, "log", "stream", "--style", "compact"),
    )

    override suspend fun listApps(): List<String> {
        requireBooted(DeviceOperation.ListApps)
        // `listapps` emits an old-style property list, not JSON. Bundle identifiers are the only
        // thing wanted here, and they are the plist's top-level keys.
        return simctl("listapps", device.id).lineSequence()
            .mapNotNull { line ->
                Regex("^\\s{4}\"?([A-Za-z0-9_.-]+)\"?\\s*=\\s*\\{").find(line)?.groupValues?.get(1)
            }
            .filter { it.contains('.') }
            .distinct()
            .sorted()
            .toList()
    }

    override suspend fun listProcesses(): List<DeviceProcess> {
        requireBooted(DeviceOperation.ListProcesses)
        return shell("ps -A -o pid,comm").lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"), limit = 2)
                val pid = fields.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                DeviceProcess(pid, fields.getOrNull(1)?.substringAfterLast('/').orEmpty())
            }
            .toList()
    }

    override suspend fun install(artifactPath: String) {
        requireBooted(DeviceOperation.Install)
        simctl("install", device.id, artifactPath, timeoutSeconds = 300)
    }

    override suspend fun uninstall(applicationId: String) {
        requireBooted(DeviceOperation.Uninstall)
        simctl("uninstall", device.id, applicationId)
    }

    override suspend fun launch(applicationId: String, arguments: List<String>) {
        requireBooted(DeviceOperation.Launch)
        CommandRunner.run(
            listOf(xcrun, "simctl", "launch", device.id, applicationId) + arguments,
        ).requireSuccess("simctl launch")
    }

    override suspend fun terminate(applicationId: String) {
        requireBooted(DeviceOperation.Terminate)
        simctl("terminate", device.id, applicationId)
    }

    /** Resolves the app's data container, then reads through the host filesystem. */
    override suspend fun pull(remotePath: String): ByteArray {
        requireBooted(DeviceOperation.PullFile)
        return java.io.File(remotePath).readBytes()
    }

    override suspend fun push(bytes: ByteArray, remotePath: String) {
        requireBooted(DeviceOperation.PushFile)
        java.io.File(remotePath).writeBytes(bytes)
    }

    suspend fun containerPath(applicationId: String, kind: String = "data"): String {
        requireBooted(DeviceOperation.Files)
        return simctl("get_app_container", device.id, applicationId, kind).trim()
    }

    override suspend fun screenshot(): ByteArray {
        requireBooted(DeviceOperation.Screenshot)
        val target = kotlin.io.path.createTempFile("reaktor-sim", ".png").toFile()
        return try {
            simctl("io", device.id, "screenshot", target.absolutePath)
            target.readBytes()
        } finally {
            target.delete()
        }
    }

    override suspend fun input(event: DeviceInput): Unit = error(refusalFor(DeviceOperation.Input))

    override suspend fun openUrl(url: String) {
        requireBooted(DeviceOperation.DeepLink)
        simctl("openurl", device.id, url)
    }

    /** Nothing to do: the simulator is on the host's own loopback. */
    override suspend fun forward(localPort: Int, remote: RemoteSocket): Int = when (remote) {
        is RemoteSocket.Tcp -> remote.port
        else -> error("A simulator is reached on loopback; only TCP ports apply")
    }

    override suspend fun removeForward(localPort: Int) = Unit

    /** Simulator-only controls that make a capture reproducible. */
    suspend fun boot() = simctl("boot", device.id, timeoutSeconds = 180)

    suspend fun shutdown() = simctl("shutdown", device.id, timeoutSeconds = 120)

    suspend fun erase() = simctl("erase", device.id, timeoutSeconds = 180)

    suspend fun setAppearance(dark: Boolean) =
        simctl("ui", device.id, "appearance", if (dark) "dark" else "light")

    /** Freezes the status bar so two screenshots differ only where the app differs. */
    suspend fun pinStatusBar(time: String = "9:41") =
        simctl(
            "status_bar", device.id, "override",
            "--time", time,
            "--batteryState", "charged",
            "--batteryLevel", "100",
            "--cellularBars", "4",
            "--wifiBars", "3",
        )

    suspend fun clearStatusBar() = simctl("status_bar", device.id, "clear")

    suspend fun setLocation(latitude: Double, longitude: Double) =
        simctl("location", device.id, "set", "$latitude,$longitude")

    suspend fun grantPrivacy(service: String, applicationId: String) =
        simctl("privacy", device.id, "grant", service, applicationId)

    suspend fun push(applicationId: String, payloadPath: String) =
        simctl("push", device.id, applicationId, payloadPath)

    override fun close() = Unit
}
