package dev.shibasis.reaktor.cluster

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

@Serializable
data class ClusterDevices(
    val observedAt: Long,
    val devices: List<ClusterDevice>,
    val truth: String,
    val problems: List<String> = emptyList(),
)

@Serializable
data class ClusterDevice(
    val host: String,
    val id: String,
    val name: String,
    val platform: String,
    val state: String,
    val connected: Boolean,
    val model: String? = null,
    val osVersion: String? = null,
    val pairingState: String? = null,
    val developerMode: String? = null,
    val lastConnectedAt: String? = null,
    val transport: String? = null,
    val truth: String = "Live",
) {
    val key: String get() = "cluster:$host:$id"
}

/** Read-only worker inventory; local devices remain owned by the existing DevTools graph. */
class ClusterDeviceReader(
    private val state: Path = ClusterLocations.state(),
    private val commands: ClusterCommands = SystemClusterCommands,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun read(): ClusterDevices {
        val at = now()
        var host: String? = null
        var problem = "Worker configuration is unavailable"
        return try {
            val config = state.resolve("config.json")
            require(Files.isRegularFile(config, NOFOLLOW_LINKS) && Files.size(config) <= 1_048_576) {
                "Configured worker is unavailable"
            }
            val configured = Json.parseToJsonElement(Files.readString(config)) as? JsonObject
            val configuredHost = configured?.text("host")
            problem = "Configured worker hostname is invalid"
            require(configuredHost != null && configuredHost.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")))
            host = configuredHost
            val remote = configured.text("remote_root")
            problem = "Configured worker folder is invalid"
            require(remote != null && remote.startsWith("/") && remote.matches(Regex("[A-Za-z0-9/._-]{1,512}")))
            problem = "Inventory probe unavailable"
            val result = commands.run(listOf("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=3", "-o", "ServerAliveInterval=3",
                "-o", "ServerAliveCountMax=1", configuredHost, probe(remote)), 30)
            problem = if (result.exitCode == 124) "Inventory probe timed out" else "Inventory probe exited ${result.exitCode}"
            require(result.exitCode == 0)
            problem = "Inventory capture limit reached"
            require(result.output.length < 65_536)
            problem = "Invalid inventory response"
            parse(host, observation(result.output), at)
        } catch (_: Exception) {
            ClusterDevices(at, emptyList(), if (host == null) "Unknown" else "Failed",
                listOf("${host ?: "Configured worker"} / inventory: $problem"))
        }
    }

    internal companion object {
        private const val CaptureLimit = 24_576

        fun probe(remote: String): String =
            "eval \"$(/usr/bin/sed -n '/^export /p' '$remote/.remote-dev/mobile-env.sh' 2>/dev/null)\"; export PATH=/opt/homebrew/bin:\$PATH TMPDIR=/tmp;" +
            " d=$(mktemp -d /tmp/reaktor-device-inventory-XXXXXX) || exit 70;" +
            " LC_ALL=C /usr/bin/perl -e 'alarm 8; exec @ARGV or exit 127' adb devices -l </dev/null >\"\$d/adb\" 2>/dev/null; a=$?;" +
            " LC_ALL=C /usr/bin/perl -e 'alarm 8; exec @ARGV or exit 127' xcrun devicectl list devices --timeout 5 --json-output \"\$d/devicectl\" </dev/null >/dev/null 2>&1; x=$?;" +
            " touch \"\$d/devicectl\";" +
            " printf 'reaktor-adb %s %s\\n' \$a $(wc -c <\"\$d/adb\"); head -c ${CaptureLimit + 1} \"\$d/adb\";" +
            " printf '\\nreaktor-devicectl %s %s\\n' \$x $(wc -c <\"\$d/devicectl\"); head -c ${CaptureLimit + 1} \"\$d/devicectl\";" +
            " rm -rf \"\$d\""

        fun observation(output: String): JsonObject {
            val adb = output.substringAfter("reaktor-adb ", "").substringBefore("\nreaktor-devicectl ")
            val devicectl = output.substringAfter("\nreaktor-devicectl ", "")
            require(adb.isNotEmpty() && devicectl.isNotEmpty()) { "Inventory markers unavailable" }
            return JsonObject(mapOf("adb" to capture(adb, json = false), "devicectl" to capture(devicectl, json = true)))
        }

        private fun capture(section: String, json: Boolean): JsonObject {
            val (exit, size) = section.substringBefore('\n').split(' ').map { it.toInt() }
            val contents = section.substringAfter('\n', "")
            return JsonObject(when {
                exit == 142 -> mapOf("exitCode" to JsonPrimitive(124), "problem" to JsonPrimitive("Native device probe timed out"))
                exit != 0 -> mapOf("exitCode" to JsonPrimitive(exit), "problem" to JsonPrimitive("Native device probe failed"))
                size > CaptureLimit -> mapOf("exitCode" to JsonPrimitive(1), "problem" to JsonPrimitive("Device capture limit reached"))
                !json -> mapOf("exitCode" to JsonPrimitive(0), "output" to JsonPrimitive(contents))
                else -> runCatching { mapOf("exitCode" to JsonPrimitive(0), "data" to Json.parseToJsonElement(contents)) }
                    .getOrElse { mapOf("exitCode" to JsonPrimitive(1), "problem" to JsonPrimitive("Native device observation unavailable")) }
            })
        }

        fun parse(host: String, payload: JsonObject, at: Long): ClusterDevices {
            val rows = mutableListOf<ClusterDevice>()
            val problems = mutableListOf<String>()
            var successful = 0
            for (transport in listOf("adb", "devicectl")) {
                try {
                    val capture = payload[transport] as? JsonObject ?: error("Observation unavailable")
                    val exit = (capture["exitCode"] as? JsonPrimitive)?.intOrNull ?: error("Exit status unavailable")
                    require(exit == 0) {
                        capture.text("problem")?.take(160) ?: if (exit == 124) "Probe timed out" else "Probe exited $exit"
                    }
                    if (transport == "adb") {
                        val text = capture.text("output") ?: error("Device list unavailable")
                        parseAndroid(host, text, rows, problems)
                    } else {
                        val data = capture["data"] as? JsonObject ?: error("Device JSON unavailable")
                        parseApple(host, data, rows, problems)
                    }
                    successful++
                } catch (error: Exception) {
                    problems += "$host / $transport: ${error.message?.take(200) ?: "Unavailable"}"
                }
            }
            return ClusterDevices(at, rows.distinctBy { it.key }, when {
                successful == 0 -> "Failed"
                problems.isNotEmpty() -> "Partial"
                else -> "Live"
            }, problems)
        }

        private fun parseAndroid(host: String, text: String, rows: MutableList<ClusterDevice>, problems: MutableList<String>) {
            val lines = text.lines()
            val header = lines.indexOfFirst { it.trim() == "List of devices attached" }
            require(header >= 0) { "Device list header unavailable" }
            for (line in lines.drop(header + 1).filter { it.isNotBlank() }) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 2 || !fields[0].matches(Regex("[A-Za-z0-9._:-]{1,256}"))) {
                    problems += "$host / adb: Unrecognized device row"
                    continue
                }
                val state = if (fields[1] == "no" && fields.getOrNull(2) == "permissions") "no permissions" else fields[1]
                val properties = fields.drop(2).mapNotNull { field ->
                    val colon = field.indexOf(':')
                    if (colon < 1) null else field.take(colon) to field.drop(colon + 1)
                }.toMap()
                val model = properties["model"]?.replace('_', ' ')
                rows += ClusterDevice(host, fields[0], model ?: fields[0], "Android", state,
                    connected = state == "device", model = model,
                    transport = if (properties.containsKey("usb")) "USB" else null,
                    truth = when (state) {
                        "device" -> "Live"
                        "offline" -> "Stale"
                        "unauthorized", "no permissions" -> "Partial"
                        else -> "Unknown"
                    })
            }
        }

        private fun parseApple(host: String, data: JsonObject, rows: MutableList<ClusterDevice>, problems: MutableList<String>) {
            val result = data["result"] as? JsonObject ?: error("Device result unavailable")
            val devices = result["devices"] as? JsonArray ?: error("Device list unavailable")
            for (entry in devices) {
                val device = entry as? JsonObject
                val hardware = device?.get("hardwareProperties") as? JsonObject
                val properties = device?.get("deviceProperties") as? JsonObject
                val connection = device?.get("connectionProperties") as? JsonObject
                val id = hardware?.text("udid") ?: device?.text("identifier")
                if (id == null) {
                    problems += "$host / devicectl: Device identity unavailable"
                    continue
                }
                val tunnel = connection?.text("tunnelState")
                // Paired devices remain in CoreDevice's cache after disconnecting. Pairing is not a live transport.
                val connected = tunnel == "connected"
                rows += ClusterDevice(host, id, properties?.text("name") ?: hardware?.text("marketingName") ?: id,
                    platform = hardware?.text("platform") ?: "iOS", state = tunnel ?: "Unknown", connected = connected,
                    model = hardware?.text("marketingName") ?: hardware?.text("productType"),
                    osVersion = properties?.text("osVersionNumber"), pairingState = connection?.text("pairingState"),
                    developerMode = properties?.text("developerModeStatus"), lastConnectedAt = connection?.text("lastConnectionDate"),
                    transport = connection?.text("transportType"),
                    truth = if (connected) "Live" else if (tunnel == "disconnected") "Stale" else "Unknown")
            }
        }

        private fun JsonObject.text(name: String): String? =
            (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
