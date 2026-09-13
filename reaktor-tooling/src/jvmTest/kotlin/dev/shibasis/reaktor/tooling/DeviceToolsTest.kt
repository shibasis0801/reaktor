package dev.shibasis.reaktor.tooling

import kotlin.test.*

class DeviceToolsTest {
    private val android = DevelopmentDevice("emulator-5554", "Pixel", DeviceTransport.Adb, "device")
    private fun plan(action: DeviceAction, app: String = "ai.bestbuds", path: String = "") =
        DeviceTools.command(android, action, app, path) { "/test/adb" }

    @Test fun inventoryKeepsOfflineAndUnauthorizedTargets() {
        val devices = DeviceTools.parseDevices(DeviceTransport.Adb, """
            List of devices attached
            emulator-5554 device product:sdk model:Pixel_9 device:emu
            usb-device unauthorized
            192.168.0.1:5555 offline
            * daemon started successfully
        """.trimIndent())
        assertEquals(3, devices.size)
        assertEquals("Pixel 9", devices.first().name)
        assertEquals(1, devices.count { it.ready })
    }

    @Test fun simulatorInventoryPreservesRuntimeAndAvailability() {
        val devices = DeviceTools.parseDevices(DeviceTransport.Simctl,
            """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-26-0":[{"udid":"A-B","name":"iPhone","state":"Booted","isAvailable":true},{"udid":"C-D","state":"Shutdown","isAvailable":false}]}}""")
        assertEquals(1, devices.size)
        assertEquals("iOS-26-0", devices.single().runtime)
        assertTrue(devices.single().ready)
    }

    @Test fun idbInventoryAcceptsJsonLinesAndNeverInfersReadiness() {
        val devices = DeviceTools.parseDevices(DeviceTransport.Idb,
            """{"udid":"A-B","name":"Phone","state":"Booted"}
                |{"udid":"C-D","name":"Tablet"}
            """.trimMargin())
        assertEquals(2, devices.size)
        assertEquals(1, devices.count { it.ready })
    }

    @Test fun commandsAlwaysAddressExactDeviceAndWritesRequireReview() {
        val command = plan(DeviceAction.Launch)
        assertEquals(listOf("/test/adb", "-s", "emulator-5554"), command.argv.take(3))
        assertEquals(SafetyClass.DeviceWrite, command.safety)
        assertEquals(SafetyClass.LiveRead, plan(DeviceAction.Logs).safety)
        assertEquals(listOf("shell", "run-as", "ai.bestbuds", "ls", "-la", "databases"),
            plan(DeviceAction.Databases).argv.drop(3))
    }

    @Test fun remoteShellArgumentsCannotEscapeAppSandbox() {
        for (app in listOf("ai.bestbuds;id", "ai.bestbuds x", "-x", "ai.bestbuds\nwhoami")) {
            assertFailsWith<IllegalArgumentException> { plan(DeviceAction.Files, app) }
        }
        for (path in listOf("../secret", "/data", "files/../../x", "files;id", "files/\$(id)", "files x")) {
            assertFailsWith<IllegalArgumentException> { plan(DeviceAction.Files, path = path) }
        }
    }

    @Test fun offlineTargetCannotReceiveAnAppCommand() {
        assertFailsWith<IllegalArgumentException> {
            DeviceTools.command(android.copy(state = "offline"), DeviceAction.Uninstall, "ai.bestbuds") { "adb" }
        }
    }
    @Test fun installationSealsBundleMembershipAndRejectsRelativeArtifacts() {
        val root = java.nio.file.Files.createTempDirectory("install-seal-").toFile()
        try {
            val bundle = root.resolve("Example.app").apply { mkdirs() }
            bundle.resolve("Info.plist").writeText("initial definition")
            val device = DevelopmentDevice("A-B", "Phone", DeviceTransport.Simctl, "Booted")
            val command = DeviceTools.command(device, DeviceAction.Install, path = bundle.path) { "/usr/bin/xcrun" }
            val seal = ProcessDefinitionSeal.capture(command.definitionFiles, command.definitionDirectories.map(::ProcessDefinitionDirectory))
            bundle.resolve("build").mkdirs()
            bundle.resolve("build/new-code").writeText("added after approval")
            assertNotEquals(seal.digest, seal.currentDigest(), "Every app bundle member affects the reviewed artifact")
            assertFailsWith<IllegalArgumentException> { DeviceTools.command(device, DeviceAction.Install, path = "Example.app") { "xcrun" } }
        } finally { root.deleteRecursively() }
    }

    @Test fun unsupportedDeviceActionsExplainTheRequiredTransport() {
        val simulator = DevelopmentDevice("A-B", "Phone", DeviceTransport.Simctl, "Booted")
        assertContains(DeviceTools.unavailableReason(simulator, DeviceAction.ViewTree).orEmpty(), "idb")
        assertContains(DeviceTools.unavailableReason(android, DeviceAction.Boot).orEmpty(), "already running")
        assertNull(DeviceTools.unavailableReason(simulator.copy(state = "Shutdown"), DeviceAction.Boot))
    }

}

/**
 * Apple's two binaries are not interchangeable, and conflating them made an attached iPhone
 * invisible: `executable(Idb)` looked only for the `idb` client, so a machine with the Homebrew
 * `idb_companion` reported "transport not installed" while the companion could see the device.
 */
class IdbTransportTest {
    @Test fun companionAloneCanStillDiscoverTargets() {
        val companionOnly = DeviceTools.discoveryExecutable(DeviceTransport.Idb)
        // Whatever this machine has, discovery must not be gated on the client.
        if (companionOnly == null) return
        val argv = DeviceTools.discovery(DeviceTransport.Idb).argv
        if (companionOnly.endsWith("idb_companion")) {
            assertEquals(listOf(companionOnly, "--list", "1"), argv)
        } else {
            assertEquals(listOf(companionOnly, "list-targets", "--json"), argv)
        }
    }

    @Test fun theCompanionsTargetListingParsesAsDevicesAndSimulators() {
        // Verbatim shape of `idb_companion --list 1`: one JSON object per line, physical devices
        // reported as type "Device" with state "Booted".
        val devices = DeviceTools.parseDevices(
            DeviceTransport.Idb,
            """
            {"udid":"00008120-001A60CA3C90C01E","name":"Shibasis's Phone","type":"Device","state":"Booted","os_version":"iOS 26.5"}
            {"udid":"A1B2C3D4-0000-0000-0000-00000000BEEF","name":"iPhone 17 Pro","type":"simulator","state":"Shutdown","os_version":"iOS 26.0"}
            """.trimIndent(),
        )

        assertEquals(2, devices.size)
        val phone = devices.first { it.udidIsPhysical() }
        assertEquals("Shibasis's Phone", phone.name)
        assertTrue(phone.ready, "a Booted physical device must be actionable, not greyed out")
        assertEquals("iOS 26.5", phone.runtime)
    }

    /** Physical UDIDs carry a hyphenated hardware prefix; simulator UDIDs are plain v4 UUIDs. */
    private fun DevelopmentDevice.udidIsPhysical() = id.substringBefore('-').length == 8 && !id.contains("A1B2")

    @Test fun anIdbTargetWithoutTheClientSaysHowToInstallIt() {
        val device = DevelopmentDevice("00008120-001A60CA3C90C01E", "Phone", DeviceTransport.Idb, "Booted")
        val reason = DeviceTools.unavailableReason(device, DeviceAction.Applications)
        if (DeviceTools.controlExecutable(DeviceTransport.Idb) == null) {
            assertContains(reason.orEmpty(), "fb-idb")
        } else {
            assertEquals(null, reason)
        }
    }
}
