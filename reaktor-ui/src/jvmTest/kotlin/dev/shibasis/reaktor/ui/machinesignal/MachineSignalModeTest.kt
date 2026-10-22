package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.code.CodeEditor
import dev.shibasis.reaktor.ui.code.rememberCodeEditorState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.shibasis.reaktor.core.truth.TruthClass
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MachineSignalModeTest {
    @Test
    fun switchingModeUpdatesTheSurfaceAndPaletteWithoutRemountingContent() = runComposeUiTest {
        val mode = mutableStateOf(MachineSignalMode.Dark)
        var snapshot: MachineSignalSnapshot? = null
        var colors: MachineSignalColors? = null
        var identity: Any? = null
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode.value) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    snapshot = LocalThemeSnapshot.current.machineSignal
                    colors = MachineSignal.colors
                    identity = androidx.compose.runtime.remember { Any() }
                }
            }
        }
        waitForIdle()
        assertEquals(MachineSignalColors.Editor, colors)
        val retained = identity
        runOnIdle { mode.value = MachineSignalMode.Light }
        waitForIdle()
        assertEquals(MachineSignalMode.Light, snapshot?.mode)
        assertEquals(MachineSignalColors.Light, colors)
        assertTrue(identity === retained)
        runOnIdle { mode.value = MachineSignalMode.Matrix }
        waitForIdle()
        assertEquals(MachineSignalMode.Matrix, snapshot?.mode)
        assertEquals(MachineSignalColors.Matrix, colors)
        assertEquals(snapshot?.fonts?.mono, snapshot?.fonts?.ui)
        assertTrue(identity === retained)
        runOnIdle { mode.value = MachineSignalMode.Dark }
        waitForIdle()
        assertEquals(MachineSignalColors.Editor, colors)
        assertTrue(identity === retained)
    }

    @Test
    fun openCodeDocumentRemainsReadableWhenTheThemeChanges() = runComposeUiTest {
        val mode = mutableStateOf(MachineSignalMode.Light)
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode.value) {
                CodeEditor(
                    rememberCodeEditorState("unchanged document"),
                    Modifier.size(480.dp, 160.dp),
                    showGutter = false,
                    showStatusBar = false,
                )
            }
        }
        for (next in MachineSignalMode.entries + MachineSignalMode.Light) {
            runOnIdle { mode.value = next }
            waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText("unchanged document", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val theme = MachineSignalSnapshot.of(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = next)
            assertEquals(theme.colors.textStrong, layouts.single().layoutInput.style.color)
        }
    }

    @Test
    fun themeTextStatusAndProvenanceRemainReadableOnEveryNeutralSurface() {
        val inks = listOf(Ink.Strong, Ink.Text, Ink.Muted, Ink.Unknown, Ink.Accent, Ink.Ok, Ink.Warn, Ink.Danger,
            SignalInk.Code, SignalInk.Gutter) + TruthClass.entries.map(::ProvenanceInk) + EntityInk.entries + EdgeInk.entries
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Matrix)) {
            val theme = MachineSignalSnapshot.of(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode)
            for (background in listOf(theme.colors.canvas, theme.colors.surface, theme.colors.raised)) {
                inks.forEach { role -> assertTrue(contrast(theme.ink(role), background) >= 4.5, "$mode $role lacks contrast on $background") }
            }
            assertTrue(contrast(theme.colors.onAccent, theme.colors.controlAccent) >= 4.5)
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
}
