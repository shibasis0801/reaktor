package dev.shibasis.reaktor.tooling.device

import dev.shibasis.reaktor.tooling.DevelopmentDevice
import dev.shibasis.reaktor.tooling.DeviceTransport
import dev.shibasis.reaktor.tooling.infra.InfrastructureOperation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Executes a planned [InfrastructureOperation.DeviceCall].
 *
 * Every device write reaches the ledger through here, which is the point: the backends are library
 * calls rather than argv, and without this they would run with no plan, no approval and no receipt
 * — which is exactly what the workspace was doing.
 *
 * Backends are shared process-wide because each one owns a connection: an `AdbSession` holds the
 * adb server link, and an idb companion is a child process. Constructing them per call would
 * reconnect on every operation.
 */
object DeviceBackends {
    private val lock = Mutex()
    private var adb: AdbDeviceBackend? = null
    private var apple: AppleDeviceBackend? = null
    private var simctl: SimctlDeviceBackend? = null

    suspend fun of(transport: DeviceTransport): DeviceBackend = lock.withLock {
        when (transport) {
            DeviceTransport.Adb -> adb ?: AdbDeviceBackend().also { adb = it }
            DeviceTransport.Idb -> apple ?: AppleDeviceBackend().also { apple = it }
            DeviceTransport.Simctl -> simctl ?: SimctlDeviceBackend().also { simctl = it }
        }
    }

    suspend fun all(): List<DeviceBackend> =
        DeviceTransport.entries.map { of(it) }

    fun close() {
        listOfNotNull(adb, apple, simctl).forEach { runCatching { it.close() } }
        adb = null
        apple = null
        simctl = null
    }
}

class DeviceJvmClient {

    /**
     * Runs the call and returns what the workbench should show.
     *
     * A string rather than a typed result because this is the ledger's capture channel, which is
     * text for every other operation kind; the structured form a pane wants is re-read from the
     * device afterwards rather than smuggled through here.
     */
    suspend fun execute(operation: InfrastructureOperation.DeviceCall): String {
        val transport = DeviceTransport.entries.firstOrNull { it.name == operation.transport }
            ?: error("Unknown transport '${operation.transport}'")
        val backend = DeviceBackends.of(transport)
        val device = backend.list().firstOrNull { it.id == operation.deviceId }
            ?: error("${operation.deviceName.ifBlank { operation.deviceId }} is no longer attached")
        return backend.session(device).use { session -> session.run(operation) }
    }

    private suspend fun DeviceSession.run(operation: InfrastructureOperation.DeviceCall): String {
        val arguments = operation.arguments
        fun argument(name: String): String =
            arguments[name] ?: error("'${operation.action}' needs a '$name' argument")

        return when (operation.action) {
            "install" -> {
                install(argument("path"))
                "Installed ${argument("path")}"
            }

            "installSplit" -> {
                val adb = this as? AdbDeviceSession ?: error("Split installs are Android only")
                val paths = argument("paths").split('\n').filter(String::isNotBlank)
                adb.installSplit(paths, arguments["grant"]?.toBoolean() ?: true)
                "Installed ${paths.size} splits"
            }

            "uninstall" -> {
                uninstall(argument("applicationId"))
                "Uninstalled ${argument("applicationId")}"
            }

            "launch" -> {
                launch(argument("applicationId"), arguments["args"]?.split(' ').orEmpty())
                "Launched ${argument("applicationId")}"
            }

            "terminate" -> {
                terminate(argument("applicationId"))
                "Stopped ${argument("applicationId")}"
            }

            "openUrl" -> {
                openUrl(argument("url"))
                "Opened ${argument("url")}"
            }

            "input" -> {
                input(
                    DeviceInput(
                        kind = InputKind.valueOf(argument("kind")),
                        x = arguments["x"]?.toIntOrNull() ?: 0,
                        y = arguments["y"]?.toIntOrNull() ?: 0,
                        endX = arguments["endX"]?.toIntOrNull() ?: 0,
                        endY = arguments["endY"]?.toIntOrNull() ?: 0,
                        durationMillis = arguments["durationMillis"]?.toIntOrNull() ?: 0,
                        text = arguments["text"].orEmpty(),
                        keyCode = arguments["keyCode"].orEmpty(),
                    )
                )
                "Dispatched ${argument("kind")}"
            }

            "grantPermission", "revokePermission" -> {
                val adb = this as? AdbDeviceSession ?: error("Permission control is Android only here")
                val applicationId = argument("applicationId")
                val permission = argument("permission")
                if (operation.action == "grantPermission") adb.grantPermission(applicationId, permission)
                else adb.revokePermission(applicationId, permission)
                "${operation.action} $permission for $applicationId"
            }

            "setDarkMode" -> androidOnly { it.setDarkMode(argument("enabled").toBoolean()) }
            "setFontScale" -> androidOnly { it.setFontScale(argument("scale").toFloat()) }
            "setDisplaySize" -> androidOnly {
                it.setDisplaySize(argument("width").toInt(), argument("height").toInt())
            }

            "resetDisplaySize" -> androidOnly { it.resetDisplaySize() }
            "setDisplayDensity" -> androidOnly { it.setDisplayDensity(argument("density").toInt()) }
            "resetDisplayDensity" -> androidOnly { it.resetDisplayDensity() }
            "screenRecord" -> androidOnly {
                it.screenRecord(
                    argument("remotePath"),
                    arguments["seconds"]?.toIntOrNull() ?: 10,
                )
            }

            // The proxy returns the previous value so the caller can persist a recovery contract
            // before anything is mutated. A device left silently proxied is a security incident.
            "setProxy" -> {
                val adb = this as? AdbDeviceSession ?: error("Proxy control is Android only")
                "previous=" + adb.setProxy(argument("hostAndPort"))
            }

            "clearProxy" -> androidOnly { it.clearProxy(arguments["restoreTo"].orEmpty()) }
            "bugReport" -> {
                val adb = this as? AdbDeviceSession ?: error("bugreport is Android only")
                adb.bugReport()
            }

            "boot" -> simulatorOnly { it.boot() }
            "shutdown" -> simulatorOnly { it.shutdown() }
            "erase" -> simulatorOnly { it.erase() }
            "setAppearance" -> simulatorOnly { it.setAppearance(argument("dark").toBoolean()) }
            "pinStatusBar" -> simulatorOnly { it.pinStatusBar() }
            "clearStatusBar" -> simulatorOnly { it.clearStatusBar() }
            "grantPrivacy" -> simulatorOnly {
                it.grantPrivacy(argument("service"), argument("applicationId"))
            }

            "pushNotification" -> simulatorOnly {
                it.push(argument("applicationId"), argument("payloadPath"))
            }

            "setLocation" -> {
                val latitude = argument("latitude").toDouble()
                val longitude = argument("longitude").toDouble()
                when (this) {
                    is SimctlDeviceSession -> setLocation(latitude, longitude)
                    is AppleDeviceSession -> setLocation(latitude, longitude)
                    else -> error("Simulated location is an Apple capability")
                }
                "Location set"
            }

            // A capture is long-running and consumes the device, so it earns a plan and a receipt
            // like any other write. The result is the local path; the pane reads the file.
            "profile" -> {
                val seconds = arguments["seconds"]?.toIntOrNull() ?: 10
                val destination = java.io.File(argument("destination"))
                val applicationId = arguments["applicationId"].orEmpty()
                val probe: ProfileProbe = when (arguments["probe"]) {
                    "simpleperf" -> SimpleperfProbe(
                        this as? AdbDeviceSession ?: error("simpleperf is Android only"),
                        applicationId,
                    )

                    "xctrace" -> XctraceProbe(
                        operation.deviceId,
                        applicationId,
                        arguments["template"] ?: "Time Profiler",
                    )

                    else -> PerfettoProbe(this as? AdbDeviceSession ?: error("Perfetto is Android only"))
                }
                val capture = probe.record(seconds, destination)
                "tool=${capture.tool} format=${capture.format} seconds=${capture.durationSeconds} " +
                    "target=${capture.targetId} perturbed=${capture.perturbed} path=${capture.file.absolutePath}"
            }

            "pushFile" -> {
                push(java.io.File(argument("localPath")).readBytes(), argument("remotePath"))
                "Pushed to ${argument("remotePath")}"
            }

            else -> error("Unknown device action '${operation.action}'")
        }
    }

    private suspend fun DeviceSession.androidOnly(block: suspend (AdbDeviceSession) -> Unit): String {
        val adb = this as? AdbDeviceSession ?: error("This operation is Android only")
        block(adb)
        return "ok"
    }

    private suspend fun DeviceSession.simulatorOnly(block: suspend (SimctlDeviceSession) -> Unit): String {
        val simulator = this as? SimctlDeviceSession ?: error("This operation is simulator only")
        block(simulator)
        return "ok"
    }
}
