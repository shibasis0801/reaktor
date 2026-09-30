package dev.shibasis.reaktor.tooling

import kotlin.test.*

class DeviceInspectionTest {
    private val xml = """UI dump started
        <hierarchy rotation="0"><node class="android.FrameLayout" bounds="[0,0][1080,2400]">
          <node class="android.Button" text="Ship" resource-id="app:id/ship" clickable="true"/>
          <node class="android.ListView"><node class="android.TextView" text="Build complete"/></node>
        </node></hierarchy>
        UI hierchary dumped to: /dev/tty
    """.trimIndent()

    @Test fun androidCaptureSupportsFoldingAndSearchingInsideFoldedBranches() {
        val tree = DeviceInspection.viewTree(DeviceTransport.Adb, xml)
        assertEquals(4, tree.elements.size)
        assertEquals(listOf("0"), tree.visible(setOf("0")).map { it.id })
        assertEquals(listOf("0", "2", "3"), tree.visible(setOf("0"), "Build complete").map { it.id })
        assertEquals("[0,0][1080,2400]", tree.elements.first().bounds)
        assertEquals("app:id/ship", tree.elements[1].resourceId)
        assertEquals(listOf("0", "1", "2"), tree.visible(setOf("2")).map { it.id })
    }

    @Test fun idbCapturePreservesNestedAndFlatAccessibilityProperties() {
        val tree = DeviceInspection.viewTree(DeviceTransport.Idb,
            """[{"type":"Application","frame":{"x":0,"y":0,"width":390,"height":844},"children":[{"type":"Button","AXLabel":"Deploy","enabled":true}]}]""")
        assertEquals("Deploy", tree.elements[1].text)
        assertEquals("true", tree.elements[1].attributes["enabled"])
        assertEquals("0", tree.elements[1].parentId)
        assertEquals(1, tree.visible(setOf("0")).size)
    }

    @Test fun untrustedAndIncompleteTreesFailWithoutResolvingExternalResources() {
        assertFails { DeviceInspection.viewTree(DeviceTransport.Adb, "<hierarchy><node>") }
        assertFails { DeviceInspection.viewTree(DeviceTransport.Adb,
            "<hierarchy><!DOCTYPE node SYSTEM 'file:///private/device-inspector-must-not-open'><node/></hierarchy>") }
        assertFails { DeviceInspection.viewTree(DeviceTransport.Adb, " ".repeat(DeviceInspection.MaxCharacters + 1)) }
        assertFails { DeviceInspection.viewTree(DeviceTransport.Adb,
            "<hierarchy>" + "<node>".repeat(130) + "</node>".repeat(130) + "</hierarchy>") }
    }

    @Test fun fileListingKeepsNamesAndNeverTreatsSymlinksAsNavigableDirectories() {
        val files = DeviceInspection.adbFiles("""
            total 16
            drwx------ 2 u0_a1 u0_a1 4096 2026-09-08 10:42 .
            drwx------ 2 u0_a1 u0_a1 4096 2026-09-08 10:42 databases
            -rw------- 1 u0_a1 u0_a1 128 2026-09-08 10:42 app config.json
            lrwxrwxrwx 1 u0_a1 u0_a1 12 2026-09-08 10:42 link -> /outside
        """.trimIndent())
        assertEquals(listOf("databases", "app config.json", "link -> /outside"), files.map { it.name })
        assertTrue(files.first().directory)
        assertEquals(128, files[1].bytes)
        assertTrue(files.last().symbolicLink)
    }

    @Test fun filePreviewIsBoundedAndAddressesTheExactAppOnTheExactDevice() {
        val device = DevelopmentDevice("emulator-5554", "Pixel", DeviceTransport.Adb, "device")
        val command = DeviceTools.command(device, DeviceAction.ReadFile, "ai.bestbuds.app", "files/state.json") { "adb" }
        assertEquals(listOf("adb", "-s", "emulator-5554", "exec-out", "run-as", "ai.bestbuds.app", "head", "-c", "262144", "files/state.json"), command.argv)
        assertEquals(SafetyClass.LiveRead, command.safety)
        for (path in listOf("../state", "/data/state", "--help", "files/x;id")) assertFails {
            DeviceTools.command(device, DeviceAction.ReadFile, "ai.bestbuds.app", path) { "adb" }
        }
    }
}
