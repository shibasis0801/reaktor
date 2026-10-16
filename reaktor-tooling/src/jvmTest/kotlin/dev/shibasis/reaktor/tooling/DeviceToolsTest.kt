package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.device.AppleDeviceBackend
import kotlin.test.*

class DeviceToolsTest {
    @Test fun inventoryRoundTripsAndPutsReadyTargetsFirst() {
        val devices = listOf(
            DevelopmentDevice("usb-device", "Tablet", DeviceTransport.Adb, "unauthorized"),
            DevelopmentDevice("emulator-5554", "Pixel 9", DeviceTransport.Adb, "device", "sdk"),
            DevelopmentDevice("emulator-5554", "Pixel 9", DeviceTransport.Adb, "device", "sdk"),
        )
        val parsed = DeviceTools.parseInventory(DeviceTransport.Adb, DeviceTools.inventory(devices))
        assertEquals(listOf("emulator-5554", "usb-device"), parsed.map { it.id })
        assertEquals(1, parsed.count { it.ready })
        assertEquals("sdk", parsed.first().runtime)
    }

    @Test fun anInventoryForAnotherTransportIsRefused() {
        val simulator = DeviceTools.inventory(listOf(DevelopmentDevice("A-B", "Phone", DeviceTransport.Simctl, "Booted")))
        assertFails { DeviceTools.parseInventory(DeviceTransport.Adb, simulator) }
    }

    @Test fun coreDeviceInventoryExcludesTheMacHostAndKeepsPhysicalAppleDevices() {
        val devices = AppleDeviceBackend().parse("""
            {"result":{"devices":[
              {"identifier":"host","hardwareProperties":{"platform":"macOS"},
               "deviceProperties":{"bootState":"booted"},"connectionProperties":{"pairingState":"unsupported"}},
              {"identifier":"phone-core-id","hardwareProperties":{"platform":"iOS","udid":"phone-udid"},
               "deviceProperties":{"name":"Phone","bootState":"booted","osVersionNumber":"26.7.1"},
               "connectionProperties":{"pairingState":"paired"}},
              {"identifier":"tablet-core-id","hardwareProperties":{"platform":"iOS"},
               "deviceProperties":{"name":"Tablet","osVersionNumber":"26.5.2"},
               "connectionProperties":{"pairingState":"paired"}}
            ]}}
        """.trimIndent())
        assertEquals(listOf("phone-core-id", "tablet-core-id"), devices.map { it.id })
        assertEquals("Phone", devices.first().name)
        assertEquals("iOS 26.7.1", devices.first().runtime)
        assertTrue(devices.first().ready)
        assertEquals("paired", devices.last().state)
        assertTrue(devices.all { it.transport == DeviceTransport.Idb })
    }
}
