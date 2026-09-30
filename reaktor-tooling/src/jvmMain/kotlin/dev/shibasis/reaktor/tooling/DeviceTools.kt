package dev.shibasis.reaktor.tooling

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class DeviceTransport { Adb, Simctl, Idb }

@Serializable
data class DevelopmentDevice(
    val id: String,
    val name: String,
    val transport: DeviceTransport,
    val state: String,
    val runtime: String = "",
) {
    val key: String get() = "${transport.name.lowercase()}:$id"
    val ready: Boolean get() = when (transport) {
        DeviceTransport.Adb -> state == "device"
        DeviceTransport.Simctl -> state.equals("Booted", true)
        DeviceTransport.Idb -> state.equals("Booted", true) || state.equals("Connected", true)
    }
}

enum class DeviceAction(val label: String, val writes: Boolean = false) {
    Applications("List applications"),
    Logs("Read logs"),
    Files("List app files"),
    ReadFile("Read app file"),
    Databases("List app databases"),
    ViewTree("Inspect view tree"),
    Install("Install app", true),
    Launch("Launch app", true),
    Stop("Stop app", true),
    Uninstall("Uninstall app", true),
    ClearData("Clear app data", true),
    Boot("Boot simulator", true),
}

data class DeviceCommand(
    val argv: List<String>,
    val safety: SafetyClass,
    val definitionFiles: List<File> = emptyList(),
    val timeoutMillis: Long = 30_000,
    val definitionDirectories: List<File> = emptyList(),
)

/** Fixed argv grammars also protect adb's remote shell, which reinterprets argument strings. */
object DeviceTools {
    fun unavailableReason(device: DevelopmentDevice, action: DeviceAction): String? = when {
        action == DeviceAction.Boot && device.ready -> "This device is already running"
        action == DeviceAction.Boot && device.transport == DeviceTransport.Adb -> "Start this emulator from its AVD"
        !device.ready && action != DeviceAction.Boot -> "${device.name} is ${device.state}"
        action == DeviceAction.ClearData && device.transport != DeviceTransport.Adb -> "Uninstall and reinstall this application to clear its data"
        action == DeviceAction.ViewTree && device.transport == DeviceTransport.Simctl -> "Select an idb target for Apple view inspection"
        // Discovery can run on the companion alone; every per-target operation is a gRPC call
        // that needs the client. Naming the install is more use than "not installed".
        device.transport == DeviceTransport.Idb && controlExecutable(DeviceTransport.Idb) == null ->
            "Install the idb client to inspect Apple targets: pipx install fb-idb"
        action == DeviceAction.Logs && device.transport == DeviceTransport.Idb ->
            "idb has no bounded log read; use the simulator transport, or read this device's log from Xcode"
        action == DeviceAction.ReadFile && device.transport != DeviceTransport.Adb -> "Bounded text preview currently requires an adb target"
        else -> null
    }

    /** Looks a binary up on PATH and the usual install roots. */
    private fun locate(name: String, androidSdk: Boolean = false): String? {
        val candidates = buildList {
            if (androidSdk) {
                listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
                    "${System.getProperty("user.home")}/Library/Android/sdk").forEach {
                    add(File(it, "platform-tools/adb"))
                }
            }
            System.getenv("PATH").orEmpty().split(File.pathSeparator).filter(String::isNotBlank)
                .forEach { add(File(it, name)) }
            listOf("/usr/bin", "/opt/homebrew/bin", "/usr/local/bin").forEach { add(File(it, name)) }
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    /**
     * The tool that can *list* a transport's targets.
     *
     * Apple has two, and they are not interchangeable. `idb` is the Python client; `idb_companion`
     * is the native server it drives over gRPC. The companion alone can enumerate targets —
     * `idb_companion --list 1` reports simulators *and* physically attached devices — so a machine
     * with only the Homebrew companion installed can still discover an attached iPhone. Looking
     * only for `idb` reported "transport not installed" on exactly that machine.
     */
    fun discoveryExecutable(transport: DeviceTransport): String? = when (transport) {
        DeviceTransport.Adb -> locate("adb", androidSdk = true)
        DeviceTransport.Simctl -> locate("xcrun")
        DeviceTransport.Idb -> locate("idb") ?: locate("idb_companion")
    }

    /**
     * The tool that can *act on* one target.
     *
     * Per-target operations are gRPC calls the companion serves but does not expose as
     * subcommands, so they need the `idb` client. Discovery can proceed without it; inspection
     * cannot, and [unavailableReason] says so rather than hiding the target.
     */
    fun controlExecutable(transport: DeviceTransport): String? = when (transport) {
        DeviceTransport.Idb -> locate("idb")
        else -> discoveryExecutable(transport)
    }

    @Deprecated("Say which capability is needed", ReplaceWith("discoveryExecutable(transport)"))
    fun executable(transport: DeviceTransport): String? = discoveryExecutable(transport)

    fun discovery(transport: DeviceTransport): DeviceCommand {
        val tool = discoveryExecutable(transport)
            ?: error("${transport.name} is not installed or cannot be found")
        return DeviceCommand(when (transport) {
            DeviceTransport.Adb -> listOf(tool, "devices", "-l")
            DeviceTransport.Simctl -> listOf(tool, "simctl", "list", "devices", "available", "--json")
            // Both emit one JSON object per target with udid/name/state/os_version, which
            // parseDevices already reads; only the invocation differs.
            DeviceTransport.Idb ->
                if (tool.endsWith("idb_companion")) listOf(tool, "--list", "1")
                else listOf(tool, "list-targets", "--json")
        }, SafetyClass.ReadOnly)
    }

    fun parseDevices(transport: DeviceTransport, output: String): List<DevelopmentDevice> = when (transport) {
        DeviceTransport.Adb -> output.lineSequence().map(String::trim).mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2 || parts[1] !in setOf("device", "offline", "unauthorized", "recovery", "sideload", "no")) null
            else DevelopmentDevice(parts[0], parts.firstOrNull { it.startsWith("model:") }
                ?.substringAfter(':')?.replace('_', ' ') ?: parts[0], transport,
                if (parts[1] == "no") "no permissions" else parts[1])
        }.toList()
        DeviceTransport.Simctl -> Json.parseToJsonElement(output).jsonObject["devices"]?.jsonObject
            ?.flatMap { (runtime, devices) -> devices.jsonArray.mapNotNull { value ->
                val device = value.jsonObject
                if (device["isAvailable"]?.jsonPrimitive?.booleanOrNull == false) null
                else device["udid"]?.jsonPrimitive?.contentOrNull?.let { id ->
                    DevelopmentDevice(id, device["name"]?.jsonPrimitive?.contentOrNull ?: id, transport,
                        device["state"]?.jsonPrimitive?.contentOrNull ?: "Unknown", runtime.substringAfterLast("SimRuntime."))
                }
            } }.orEmpty()
        DeviceTransport.Idb -> output.lineSequence().filter(String::isNotBlank).flatMap { line ->
            val value = Json.parseToJsonElement(line)
            (if (value is JsonArray) value.toList() else listOf(value)).asSequence()
        }.map { value ->
            val device = value.jsonObject
            val id = requireNotNull(device["udid"]?.jsonPrimitive?.contentOrNull) { "idb target has no UDID" }
            DevelopmentDevice(id, device["name"]?.jsonPrimitive?.contentOrNull ?: id, transport,
                device["state"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                device["os_version"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.toList()
    }.distinctBy(DevelopmentDevice::key).sortedWith(compareBy<DevelopmentDevice> { !it.ready }.thenBy { it.name })

    fun command(
        device: DevelopmentDevice,
        action: DeviceAction,
        applicationId: String = "",
        path: String = "",
        resolveExecutable: (DeviceTransport) -> String? = ::controlExecutable,
    ): DeviceCommand {
        require(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}").matches(device.id)) { "Invalid device identity" }
        require(unavailableReason(device, action) == null) { unavailableReason(device, action).orEmpty() }
        val tool = resolveExecutable(device.transport) ?: error("${device.transport} is not installed or cannot be found")
        if (action in setOf(DeviceAction.Files, DeviceAction.ReadFile, DeviceAction.Databases, DeviceAction.Launch, DeviceAction.Stop,
                DeviceAction.Uninstall, DeviceAction.ClearData)) {
            require(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+").matches(applicationId)) {
                "Enter an exact application / bundle identifier"
            }
        }
        val artifact = if (action == DeviceAction.Install) {
            require(File(path).isAbsolute) { "Select an absolute application artifact path" }
            File(path).canonicalFile.also {
                require(it.isAbsolute && it.exists() && it.canRead()) { "Select an existing application artifact" }
                require(if (device.transport == DeviceTransport.Adb) it.isFile && it.extension == "apk"
                    else (it.isDirectory && it.extension == "app") ||
                        (device.transport == DeviceTransport.Idb && it.isFile && it.extension == "ipa")) {
                    "Android requires an APK; Apple requires an app bundle or IPA"
                }
            }
        } else null
        val relative = path.ifBlank { "." }
        if (action in setOf(DeviceAction.Files, DeviceAction.ReadFile)) {
            require(Regex("[A-Za-z0-9_.][A-Za-z0-9_./-]*").matches(relative) &&
                relative.split('/').none { it == ".." }) { "Use a relative path within the application sandbox" }
        }
        val args = when (device.transport) {
            DeviceTransport.Adb -> listOf(tool, "-s", device.id) + when (action) {
                DeviceAction.Applications -> listOf("shell", "pm", "list", "packages", "-3")
                DeviceAction.Logs -> listOf("logcat", "-d", "-t", "300", "-v", "threadtime")
                DeviceAction.Files -> listOf("shell", "run-as", applicationId, "ls", "-la", relative)
                DeviceAction.ReadFile -> listOf("exec-out", "run-as", applicationId, "head", "-c", "262144", relative)
                DeviceAction.Databases -> listOf("shell", "run-as", applicationId, "ls", "-la", "databases")
                DeviceAction.ViewTree -> listOf("exec-out", "uiautomator", "dump", "/dev/tty")
                DeviceAction.Install -> listOf("install", "-r", requireNotNull(artifact).path)
                DeviceAction.Launch -> listOf("shell", "monkey", "-p", applicationId, "-c", "android.intent.category.LAUNCHER", "1")
                DeviceAction.Stop -> listOf("shell", "am", "force-stop", applicationId)
                DeviceAction.Uninstall -> listOf("uninstall", applicationId)
                DeviceAction.ClearData -> listOf("shell", "pm", "clear", applicationId)
                DeviceAction.Boot -> error("Boot an Android emulator from its AVD; adb cannot boot an offline target")
            }
            DeviceTransport.Simctl -> listOf(tool, "simctl") + when (action) {
                DeviceAction.Applications -> listOf("listapps", device.id)
                DeviceAction.Logs -> listOf("spawn", device.id, "log", "show", "--last", "1m", "--style", "compact")
                DeviceAction.Files, DeviceAction.Databases -> listOf("get_app_container", device.id, applicationId, "data")
                DeviceAction.Install -> listOf("install", device.id, requireNotNull(artifact).path)
                DeviceAction.Launch -> listOf("launch", device.id, applicationId)
                DeviceAction.Stop -> listOf("terminate", device.id, applicationId)
                DeviceAction.Uninstall -> listOf("uninstall", device.id, applicationId)
                DeviceAction.Boot -> listOf("boot", device.id)
                DeviceAction.ViewTree -> error("Apple view inspection requires idb; select an idb target")
                DeviceAction.ReadFile -> error("Bounded text preview currently requires an adb target")
                DeviceAction.ClearData -> error("simctl has no application-only clear-data command; uninstall and reinstall explicitly")
            }
            DeviceTransport.Idb -> listOf(tool) + when (action) {
                DeviceAction.Applications -> listOf("list-apps", "--udid", device.id)
                DeviceAction.ViewTree -> listOf("ui", "describe-all", "--udid", device.id)
                DeviceAction.Files -> listOf("file", "ls", "--udid", device.id, "--bundle-id", applicationId, relative)
                DeviceAction.Databases -> listOf("file", "ls", "--udid", device.id, "--bundle-id", applicationId, "Library/Application Support/databases")
                DeviceAction.Install -> listOf("install", "--udid", device.id, requireNotNull(artifact).path)
                DeviceAction.Launch -> listOf("launch", "--udid", device.id, applicationId)
                DeviceAction.Stop -> listOf("terminate", "--udid", device.id, applicationId)
                DeviceAction.Uninstall -> listOf("uninstall", "--udid", device.id, applicationId)
                DeviceAction.Logs -> error("Use the simulator log reader or the attached target's log stream")
                DeviceAction.ReadFile -> error("Bounded text preview currently requires an adb target")
                DeviceAction.Boot -> listOf("boot", "--udid", device.id)
                DeviceAction.ClearData -> error("idb has no application-only clear-data command")
            }
        }
        val definitions = when {
            artifact == null -> emptyList()
            artifact.isFile -> listOf(artifact)
            else -> artifact.walkTopDown().filter(File::isFile).take(10_001).toList().also {
                require(it.size <= 10_000 && it.isNotEmpty()) { "Application bundle is empty or exceeds the 10,000-file review limit" }
            }
        }
        return DeviceCommand(args, if (action.writes) SafetyClass.DeviceWrite else SafetyClass.LiveRead,
            definitions, if (action == DeviceAction.Install) 120_000 else 30_000,
            listOfNotNull(artifact?.takeIf(File::isDirectory)))
    }
}
