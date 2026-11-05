@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.Gesture
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintReadingTest {
    @Test
    fun compactCodeCardsMeasureBothLinesIndependentlyOfTheHostBodyLineHeight() {
        val card = Card("provider", 0.0, 0.0, 272.0, 46.0, emptyList(), 0)
        val layout = BlueprintLayout("compact", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
        for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.01..4.0)
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((900 * density).toInt(), (600 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(lineHeight = 24.sp)) {
                        BlueprintCanvas(layout, canvas, emptySet(), null,
                            linkStyle = { Blueprint.link(Color.White, false, false) }, frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                            cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, "Google", "configured in the repository") },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
            try {
                repeat(12) { frame() }
                SwingUtilities.invokeAndWait { canvas.restore(40.0, 70.0, 1.1) }
                repeat(5) { frame() }; Thread.sleep(180); repeat(25) { frame(); Thread.sleep(10) }
                SwingUtilities.invokeAndWait {
                    fun nodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    val text = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                        .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }.map { it.text }
                    val folder = java.io.File("build/reports/compact-cards").apply { mkdirs() }
                    scene.render(time).use { image -> image.encodeToData()!!.use { data -> java.io.File(folder, "provider-${density.toInt()}x.png").writeBytes(data.bytes) } }
                    assertTrue(text.contains("configured in the repository"), "density $density view ${canvas.viewport()} reading ${canvas.reading()}: $text")
                    assertEquals(1, canvas.reading()!![BlueprintTitle.Whole])
                    assertTrue(canvas.reading()!!.smallest >= 11 - 1e-3)
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    private val titles = listOf(
        "Ship it",
        "Agent view lays plans out with the blueprint layout engine",
        "PlanBoardModel adapts plan frames to blueprint layout",
        "Decide whether a shared plan keeps one owner per component, and how a person hands a component to an agent and takes it back again later",
        "GraphEditorNestingFixturesForFolding cover folds, focus and the context cards",
        "Rich tree component",
    )

    private fun layout(width: Double, height: Double) = BlueprintLayout("reading",
        titles.indices.associate { i -> "c$i" to Card("c$i", (i % 3) * (width + 48), (i / 3) * (height + 48), width, height, emptyList(), 0) },
        emptyList(), emptyList(), 3 * (width + 48), 2 * (height + 48))

    private fun look(face: BlueprintFace, title: String) = BlueprintCardLook(BlueprintBench.tones[0], BlueprintBench.icon, title, "23 of 30 done · waits on 2 · unblocks 5",
        face = face, eyebrow = "Task", marks = listOf(BlueprintMark("Doing", Color(0xFF4FA3FF), BlueprintMark.Kind.Badge)))

    private fun sweep(layout: BlueprintLayout<String>, face: (Int) -> BlueprintFace, zooms: List<Double>, density: Float = 1f, check: (Double, BlueprintReading) -> Unit) {
        val canvas = GraphCanvasState(Blueprint.ProseZoom.start..4.0)
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(1440, 900, Density(density), coroutineContext = Dispatchers.Unconfined) {
                BlueprintCanvas(layout, canvas, emptySet(), null,
                    linkStyle = { Blueprint.link(Color.White, false, false) },
                    frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                    cardLook = { card -> card.id.removePrefix("c").toInt().let { i -> look(face(i), titles[i]) } },
                    onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
            }
        }
        var time = 0L
        fun frame() = SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
        try {
            repeat(10) { frame() }
            zooms.forEach { zoom ->
                SwingUtilities.invokeAndWait { canvas.flow.setViewport(Viewport(720 - layout.width / 2 * zoom, 450 - layout.height / 2 * zoom, zoom)) }
                repeat(3) { frame() }
                Thread.sleep(160)
                repeat(10) { frame(); Thread.sleep(4) }
                var reading: BlueprintReading? = null
                SwingUtilities.invokeAndWait { reading = canvas.reading() }
                check(zoom, reading!!)
            }
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }

    private fun there(from: Double, to: Double): List<Double> {
        val down = generateSequence(from) { it * .79 }.takeWhile { it >= to }.toList()
        return down + down.asReversed()
    }

}
