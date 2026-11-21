@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.kanban

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.*

class KanbanStatusAppearanceTest {
    @Test fun everyTextLineContrastsWithTheStatusFillInEveryTheme() {
        for (mode in MachineSignalMode.entries) for (ink in listOf(Ink.Warn, Ink.Accent, Ink.Ok, Ink.Muted)) withCard(mode, ink) { pane ->
            val background = lerp(pane.card, pane.tone, .24f)
            val lines = pane.nodes().mapNotNull { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.firstOrNull()
            }
            assertTrue(lines.size >= 4)
            lines.forEach { layout ->
                val color = layout.layoutInput.style.color
                assertTrue(contrast(color, background) >= 4.5, "$mode/$ink/${layout.layoutInput.text}: contrast ${contrast(color, background)}")
            }
        }
    }

    @Test fun selectedCardsKeepTheirStatusOutlineAndAddASecondCue() {
        for (mode in MachineSignalMode.entries) for (ink in listOf(Ink.Warn, Ink.Accent, Ink.Ok, Ink.Muted)) withCard(mode, ink) { pane ->
            pane.selected.value = true
            pane.frames()
            val bounds = pane.cardNode().boundsInRoot
            val pixels = pane.pixels()
            assertPixel(pane.tone, pixels.getRGB(bounds.left.toInt(), bounds.center.y.toInt()), "$mode/$ink selected outer status edge")
            assertPixel(pane.text, pixels.getRGB(bounds.left.toInt() + 4, bounds.center.y.toInt()), "$mode/$ink selected inner cue")
        }
    }

    @Test fun bodyPixelsMatchExactlyTwentyFourPercentStatusFill() {
        for (mode in MachineSignalMode.entries) for (ink in listOf(Ink.Warn, Ink.Accent, Ink.Ok, Ink.Muted)) withCard(mode, ink) { pane ->
            val bounds = pane.cardNode().boundsInRoot
            val expected = lerp(pane.card, pane.tone, .24f)
            assertPixel(expected, pane.pixels().getRGB((bounds.right - 8).toInt(), (bounds.bottom - 8).toInt()), "$mode/$ink body")
        }
    }

    private fun assertPixel(expected: Color, actual: Int, name: String) {
        val value = expected.toArgb()
        for (shift in listOf(0, 8, 16, 24)) assertTrue(kotlin.math.abs((value ushr shift and 255) - (actual ushr shift and 255)) <= 2, "$name expected ${value.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.compositeOver(background).luminance().toDouble()
        val b = background.luminance().toDouble()
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    private fun withCard(mode: MachineSignalMode, ink: Ink, action: (Pane) -> Unit) = SwingUtilities.invokeAndWait {
        val pane = Pane(mode, ink)
        try { pane.frames(); action(pane) } finally { pane.scene.close() }
    }

    private class Pane(mode: MachineSignalMode, ink: Ink) {
        val selected = mutableStateOf(false)
        var tick = 0L
        var card = Color.Unspecified
        var tone = Color.Unspecified
        var text = Color.Unspecified
        val scene = ImageComposeScene(480, 400, coroutineContext = Dispatchers.Unconfined) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                card = MachineSignal.colors.card
                tone = ink.color
                text = MachineSignal.colors.textStrong
                val node = NodeShape("item", "plan", "Keep the status visible", "Task", NodeKind.Node)
                val look = BlueprintCardLook(tone, BlueprintBench.icon, node.label, "Waiting on prerequisite", outline = tone, eyebrow = "TASK")
                KanbanCard(node, look, selected.value, "Evidence accepted", {}, {}, Modifier.width(MachineSignal.Space.s6 * 12))
            }
        }
        fun frames() { repeat(6) { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() } }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun cardNode() = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "kanban-card-item" }
        fun pixels() = scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { data -> javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data.bytes)) } }
    }
}
