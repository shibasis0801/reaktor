package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.ui.graphics.Color
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
    private val authored = listOf(
        MachineSignal.Bg0, MachineSignal.Bg1, MachineSignal.Bg2, MachineSignal.Bg3, MachineSignal.Bg4,
        MachineSignal.Line1, MachineSignal.Line2, MachineSignal.Line3,
        MachineSignal.Text1, MachineSignal.Text2, MachineSignal.Text3, MachineSignal.Text4,
        MachineSignal.Accent, MachineSignal.AccentSoft, MachineSignal.Status.Warn,
    )

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
    fun theBoardDrawsEveryAuthoredColourAsAuthored() {
        authored.forEach { color -> assertEquals(color, MachineSignalColors.Board.remap(color)) }
    }

    @Test
    fun theEditorRemapIsTheShippedTable() {
        val shipped = mapOf(
            MachineSignal.Bg0 to MachineSignal.Editor.Canvas,
            MachineSignal.Bg1 to MachineSignal.Editor.Surface,
            MachineSignal.Bg2 to MachineSignal.Editor.Surface,
            MachineSignal.Bg3 to MachineSignal.Editor.Raised,
            MachineSignal.Bg4 to MachineSignal.Editor.Raised,
            MachineSignal.Line1 to MachineSignal.Editor.Code.GutterLine,
            MachineSignal.Line2 to MachineSignal.Editor.Line,
            MachineSignal.Line3 to MachineSignal.Editor.Line,
            MachineSignal.Text1 to MachineSignal.Editor.Text,
            MachineSignal.Text2 to MachineSignal.Editor.Text,
            MachineSignal.Text3 to MachineSignal.Editor.Muted,
            MachineSignal.Text4 to MachineSignal.Editor.Unknown,
        )
        authored.forEach { color -> assertEquals(shipped[color] ?: color, MachineSignalColors.Editor.remap(color), "remap of $color") }
        assertEquals(Color.Red, MachineSignalColors.Editor.remap(Color.Red))
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
