package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ClusterDevicesTest {
    @Test fun pairedDisconnectedPhysicalAppleRecordRetainsIdentityAndCachedFacts() {
        val observed = ClusterDeviceReader.parse("m1-worker", envelope(apple = apple()), 42)
        assertEquals("Live", observed.truth) // The query is current; the individual device is cached.
        assertEquals(42L, observed.observedAt)
        val phone = observed.devices.single()
        assertEquals("m1-worker", phone.host)
        assertEquals("physical-udid", phone.id)
        assertEquals("cluster:m1-worker:physical-udid", phone.key)
        assertEquals("My Phone", phone.name)
        assertEquals("iPhone 14 Pro", phone.model)
        assertEquals("27.0.1", phone.osVersion)
        assertEquals("iOS", phone.platform)
        assertEquals("disconnected", phone.state)
        assertFalse(phone.connected)
        assertEquals("Stale", phone.truth)
        assertEquals("paired", phone.pairingState)
        assertEquals("enabled", phone.developerMode)
        assertEquals("localNetwork", phone.transport)
        assertEquals("2026-11-03T13:51:00.000Z", phone.lastConnectedAt)
    }

    @Test fun appleConnectionRequiresObservedTunnelAndUnknownDoesNotBecomeLive() {
        val connected = ClusterDeviceReader.parse("m1-worker", envelope(apple = apple("connected")), 42).devices.single()
        assertTrue(connected.connected)
        assertEquals("Live", connected.truth)
        val unknown = ClusterDeviceReader.parse("m1-worker", envelope(apple = """{"result":{"devices":[{"identifier":"core-id","connectionProperties":{"pairingState":"paired"}}]}}"""), 42).devices.single()
        assertFalse(unknown.connected)
        assertEquals("Unknown", unknown.state)
        assertEquals("Unknown", unknown.truth)
        assertEquals("core-id", unknown.id)
    }

    @Test fun androidRetainsUnauthorizedOfflineAndRecoveryInsteadOfFilteringThemOut() {
        val observed = ClusterDeviceReader.parse("m1-worker", envelope(android = """
            List of devices attached
            ready device usb:1-2 product:panther model:Pixel_7 device:panther transport_id:1
            locked unauthorized usb:1-3 transport_id:2
            absent offline transport_id:3
            recovery-phone recovery usb:1-4
            restricted no permissions (missing udev rules)
        """.trimIndent()), 42)
        assertEquals("Live", observed.truth)
        assertEquals(listOf("device", "unauthorized", "offline", "recovery", "no permissions"), observed.devices.map { it.state })
        assertEquals(listOf(true, false, false, false, false), observed.devices.map { it.connected })
        assertEquals(listOf("Live", "Partial", "Stale", "Unknown", "Partial"), observed.devices.map { it.truth })
        assertEquals("Pixel 7", observed.devices.first().model)
        assertEquals("USB", observed.devices.first().transport)
        assertTrue(observed.devices.all { it.platform == "Android" && it.host == "m1-worker" })
    }

    @Test fun emptyInventoryIsSuccessfulWhileTransportFailuresAreQualifiedAndKeepOtherDevices() {
        val empty = ClusterDeviceReader.parse("m1-worker", envelope(), 42)
        assertTrue(empty.devices.isEmpty())
        assertEquals("Live", empty.truth)
        assertTrue(empty.problems.isEmpty())
        val partial = ClusterDeviceReader.parse("m1-worker", envelope(apple = apple(), adbExit = 124), 42)
        assertEquals("Partial", partial.truth)
        assertEquals(1, partial.devices.size)
        assertEquals("m1-worker / adb: Probe timed out", partial.problems.single())
        val failed = ClusterDeviceReader.parse("m1-worker", envelope(adbExit = 1, appleExit = 124), 42)
        assertEquals("Failed", failed.truth)
        assertEquals(2, failed.problems.size)
        assertTrue(failed.devices.isEmpty())
    }

    @Test fun malformedTransportAndRowsCannotSilentlyBecomeCompleteEmptyInventory() {
        val observed = ClusterDeviceReader.parse("m1-worker", envelope(android = "unexpected diagnostic", apple = apple()), 42)
        assertEquals("Partial", observed.truth)
        assertEquals(1, observed.devices.size)
        assertTrue(observed.problems.single().startsWith("m1-worker / adb:"))
        val malformed = ClusterDeviceReader.parse("m1-worker", envelope(android = "List of devices attached\nbad-row\n", apple = """{"result":{"devices":[{}]}}"""), 42)
        assertEquals("Partial", malformed.truth)
        assertEquals(2, malformed.problems.size)
    }

    @Test fun graphServiceProbesTheConfiguredWorkerOverSshWithAFiniteTimeout() = fixture { state ->
        val commands = mutableListOf<List<String>>()
        val deviceReader = ClusterDeviceReader(state, ClusterCommands { command, timeout ->
            commands += command
            assertEquals(30L, timeout)
            CommandResult(0, markers(devicectl = apple()))
        }, now = { 42 })
        runBlocking {
            ClusterRuntime(ClusterReader(state), deviceReader = deviceReader).use { runtime ->
                assertEquals("physical-udid", runtime.api.devices(Unit).devices.single().id)
            }
        }
        val ssh = commands.single()
        assertEquals(listOf("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=3", "-o", "ServerAliveInterval=3", "-o", "ServerAliveCountMax=1", "m1-worker"), ssh.dropLast(1))
        assertEquals(ClusterDeviceReader.probe("/worker/root"), ssh.last())
    }

    @Test fun timeoutMalformedOrTruncatedResponsesNeverClaimLiveDevices() = fixture { state ->
        for (result in listOf(CommandResult(124, "timeout"), CommandResult(255, "ssh unavailable"), CommandResult(0, "not an inventory"),
            CommandResult(0, "reaktor-adb x y\n\nreaktor-devicectl 0 2\n{}"), CommandResult(0, "x".repeat(65_536)))) {
            val observed = ClusterDeviceReader(state, ClusterCommands { _, _ -> result }, now = { 42 }).read()
            assertEquals("Failed", observed.truth)
            assertEquals(42L, observed.observedAt)
            assertTrue(observed.devices.isEmpty())
            assertTrue(observed.problems.single().startsWith("m1-worker / inventory:"))
        }
    }

    @Test fun nativeProbeFailuresTimeoutsAndOversizedCapturesStayQualified() {
        val observed = ClusterDeviceReader.parse("m1-worker", ClusterDeviceReader.observation(
            "reaktor-adb 142 0\n\nreaktor-devicectl 0 30000\n{\"result\":{\"devices\":[]}}"), 42)
        assertEquals("Failed", observed.truth)
        assertEquals(listOf("m1-worker / adb: Native device probe timed out", "m1-worker / devicectl: Device capture limit reached"), observed.problems)
        val missing = ClusterDeviceReader.parse("m1-worker", ClusterDeviceReader.observation("reaktor-adb 127 0\n\nreaktor-devicectl 1 0\n"), 42)
        assertEquals(listOf("m1-worker / adb: Native device probe failed", "m1-worker / devicectl: Native device probe failed"), missing.problems)
    }

    @Test fun probeRunsBothNativeToolsUnderTheWorkerEnvironment() {
        val root = Files.createTempDirectory("cluster-probe-").toRealPath()
        try {
            val tools = Files.createDirectories(root.resolve("bin"))
            Files.createDirectories(root.resolve(".remote-dev"))
            Files.writeString(root.resolve(".remote-dev/mobile-env.sh"), "export PIXEL_MODEL=Pixel_9\nPRIVATE=not-exported\n")
            executable(tools.resolve("adb"), "#!/bin/sh\nprintf 'List of devices attached\\nphone\\tdevice usb:1-1 model:%s%s\\n\\n' \"\$PIXEL_MODEL\" \"\$PRIVATE\"\n")
            executable(tools.resolve("xcrun"), "#!/bin/sh\nwhile [ \$# -gt 0 ]; do [ \"\$1\" = --json-output ] && printf '%s' '${apple("connected").replace("\n", " ")}' > \"\$2\"; shift; done\n")
            val process = ProcessBuilder("/bin/zsh", "-c", ClusterDeviceReader.probe(root.toString())).redirectErrorStream(true)
                .apply { environment()["PATH"] = "$tools:/usr/bin:/bin" }.start()
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.waitFor())
            val observed = ClusterDeviceReader.parse("m1-worker", ClusterDeviceReader.observation(output), 42)
            assertEquals("Live", observed.truth)
            assertEquals(listOf("Pixel 9", "iPhone 14 Pro"), observed.devices.map { it.model })
            assertTrue(observed.devices.all { it.connected })
        } finally {
            root.toFile().deleteTreeSafely(root.parent.toFile())
        }
    }

    @Test fun invalidConfigurationDoesNotInvokeTransportOrExposeConfigurationContents() = fixture { state ->
        var calls = 0
        val reader = ClusterDeviceReader(state, ClusterCommands { _, _ -> calls++; error("Must not run") })
        for (config in listOf("""{"host":"--proxy-command","secret":"private-value"}""", """{"secret":"private-value",broken}""")) {
            Files.writeString(state.resolve("config.json"), config)
            val observed = reader.read()
            assertEquals("Unknown", observed.truth)
            assertFalse(observed.problems.joinToString().contains("private-value"))
        }
        Files.writeString(state.resolve("config.json"), """{"host":"m1-worker","remote_root":"relative; private-value"}""")
        assertEquals(listOf("m1-worker / inventory: Configured worker folder is invalid"), reader.read().problems)
        assertEquals(0, calls)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun nullableFieldsHaveWireDefaultsAndComputedKeyDoesNotCreateWireState() {
        val observation = ClusterDevices(42, listOf(ClusterDevice("m1-worker", "phone", "phone", "Android", "offline", false)), "Live")
        val producer = Json { explicitNulls = false; encodeDefaults = true }
        val wire = producer.encodeToString(observation)
        assertFalse("\"model\"" in wire)
        assertFalse("\"key\"" in wire)
        assertEquals(observation, Json.decodeFromString<ClusterDevices>(wire))
    }

    private fun envelope(android: String = "List of devices attached\n\n", apple: String = """{"result":{"devices":[]}}""",
        adbExit: Int = 0, appleExit: Int = 0): JsonObject = buildJsonObject {
        put("adb", buildJsonObject { put("exitCode", adbExit); put("output", android) })
        put("devicectl", buildJsonObject { put("exitCode", appleExit); put("data", Json.parseToJsonElement(apple)) })
    }

    private fun markers(adb: String = "List of devices attached\n\n", devicectl: String = """{"result":{"devices":[]}}""") =
        "reaktor-adb 0 ${adb.length}\n$adb\nreaktor-devicectl 0 ${devicectl.length}\n$devicectl"

    private fun executable(path: Path, script: String) {
        Files.writeString(path, script)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }

    private fun apple(tunnel: String = "disconnected") = """{"result":{"devices":[{
        "identifier":"core-id","hardwareProperties":{"udid":"physical-udid","marketingName":"iPhone 14 Pro","platform":"iOS"},
        "deviceProperties":{"name":"My Phone","osVersionNumber":"27.0.1","developerModeStatus":"enabled","ddiServicesAvailable":false},
        "connectionProperties":{"pairingState":"paired","tunnelState":"$tunnel","transportType":"localNetwork","lastConnectionDate":"2026-11-03T13:51:00.000Z"}
    }]}}"""

    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("cluster-devices-").toRealPath()
        try {
            val state = Files.createDirectories(root.resolve(".remote-dev"))
            Files.writeString(state.resolve("config.json"), """{"host":"m1-worker","remote_root":"/worker/root"}""")
            block(state)
        } finally {
            root.toFile().deleteTreeSafely(root.parent.toFile())
        }
    }
}
