package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.shibasis.reaktor.surface.ThemeMismatch
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.BareTheme
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MachineSignalThemeTest {
    @Test
    fun theTwoShippedPairsHaveStableIds() {
        assertEquals("machine-signal/board/comfortable", MachineSignalSnapshot.Board.id)
        assertEquals("machine-signal/editor/compact", MachineSignalSnapshot.Editor.id)
    }

    @Test
    fun anUndefinedPairIsRefusedByName() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            MachineSignalSnapshot.of(MachineSignalVariant.Editor, MachineSignalDensity.Comfortable)
        }
        assertTrue("Editor with Comfortable" in refusal.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { MachineSignalSnapshot.of(MachineSignalVariant.Board, MachineSignalDensity.Compact) }
    }

    @Test
    fun theAccessorUsesMachineSignalFallsBackToTheBoardAndRefusesOtherThemes() {
        assertSame(MachineSignalSnapshot.Editor, MachineSignalSnapshot.Editor.machineSignal)
        assertSame(MachineSignalSnapshot.Board, BareTheme.machineSignal)
        val foreign = object : ThemeSnapshot { override val id = "human-signal/light" }
        val mismatch = assertFailsWith<ThemeMismatch> { foreign.machineSignal }
        assertEquals("human-signal/light", mismatch.actual)
    }

    @Test
    fun theScopeProvidesTheSnapshotTheLegacyFlagAndTheFonts() = runComposeUiTest {
        val fonts = MachineSignalFonts()
        var snapshot: ThemeSnapshot? = null
        var editorFlag: Boolean? = null
        var providedFonts: MachineSignalFonts? = null
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, fonts) {
                snapshot = LocalThemeSnapshot.current
                editorFlag = ((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)
                providedFonts = LocalMachineSignalFonts.current
            }
        }
        waitForIdle()
        assertEquals(MachineSignalSnapshot.Editor.copy(fonts = fonts), snapshot)
        assertEquals(true, editorFlag)
        assertEquals(fonts, providedFonts)
    }
}
