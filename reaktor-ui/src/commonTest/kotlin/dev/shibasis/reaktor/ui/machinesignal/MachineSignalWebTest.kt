package dev.shibasis.reaktor.ui.machinesignal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MachineSignalWebTest {
    @Test fun webShellProjectionTracksHostModeAndDensity() {
        val dark = MachineSignalSnapshot.of(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Dark)
        val light = MachineSignalSnapshot.of(MachineSignalVariant.Board, MachineSignalDensity.Comfortable, mode = MachineSignalMode.Light)
        val first = dark.webChrome()
        val second = light.webChrome()
        assertEquals("dark", first["--hangar-scheme"])
        assertEquals("light", second["--hangar-scheme"])
        assertNotEquals(first["--hangar-background"], second["--hangar-background"])
        assertNotEquals(first["--hangar-space"], second["--hangar-space"])
        assertEquals("${light.metrics.focusRing.value}px", second["--hangar-focus-width"])
        assertTrue(second.keys.all { it.startsWith("--hangar-") })
    }
}
