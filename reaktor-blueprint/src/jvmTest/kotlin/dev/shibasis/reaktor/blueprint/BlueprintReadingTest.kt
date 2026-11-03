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
import dev.shibasis.reaktor.blueprint.canvas.PaintDetail
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

    @Test
    fun regionCountsPaintAtTheSameMeasuredTierAsTheirSemantics() {
        fun nodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        val card = Card("region", 0.0, 0.0, 900.0, 560.0, emptyList(), 39)
        val layout = BlueprintLayout("region-count", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
        for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.01..4.0)
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(Color.White, false, false) }, frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                        cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, "Application services", "39 children", face = BlueprintFace.Region, eyebrow = "Component") },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
            try {
                repeat(12) { frame() }
                for (zoom in listOf(.43695, .61251, .65703, 1.1, .08, .65703)) {
                    SwingUtilities.invokeAndWait { canvas.restore(40.0, 70.0, zoom) }
                    repeat(5) { frame() }; Thread.sleep(180); repeat(25) { frame(); Thread.sleep(10) }
                    SwingUtilities.invokeAndWait {
                        val semantics = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                        val text = semantics.flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }.map { it.text }
                        assertEquals(zoom > .1, text.contains("39 children"), "density $density zoom $zoom reading ${canvas.reading()} text $text")
                        assertTrue(canvas.reading()!!.smallest >= 11 - 1e-3)
                        if (zoom > .1) assertEquals(1, canvas.reading()!![BlueprintTitle.Whole])
                        val folder = java.io.File("build/reports/region-counts").apply { mkdirs() }
                        scene.render(time).use { image -> image.encodeToData()!!.use { data -> java.io.File(folder, "region-${density.toInt()}x-$zoom.png").writeBytes(data.bytes) } }
                    }
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    @Test
    fun titleAndRowMeasurementsChooseDetailAtBothDensities() {
        for (density in listOf(1.0, 2.0)) {
            fun content(width: Double, height: Double, size: Double, complete: Boolean = true) =
                PaintDetail.Content(width * density, height * density, size * density, complete)
            val titles = listOf(content(248.0, 72.0, 13.0), content(216.0, 96.0, 11.0))
            val fullRows = content(268.0, 140.0, 11.0)
            fun choose(width: Double, height: Double, rows: PaintDetail.Content? = fullRows) =
                PaintDetail.choose(width * density, height * density, density, titles, rows)
            assertEquals(PaintDetail.Tier.Rows, choose(272.0, 144.0).tier)
            assertTrue(choose(272.0, 144.0).pins)
            assertEquals(PaintDetail.Tier.Title, choose(256.0, 144.0).tier)
            assertEquals(0, choose(256.0, 144.0).title)
            assertEquals(1, choose(224.0, 144.0).title)
            assertEquals(PaintDetail.Tier.Summary, choose(212.0, 144.0).tier)
            assertEquals(PaintDetail.Tier.Summary, choose(272.0, 60.0).tier)
            assertEquals(PaintDetail.Tier.Title, choose(272.0, 144.0, fullRows.copy(complete = false)).tier)
        }
    }

    @Test
    fun textAndPinsDisappearTogetherAtTheMeasuredBoundary() {
        for (density in listOf(1.0, 2.0)) for (size in listOf(10.999, 11.0, 11.001)) {
            val title = PaintDetail.Content(240.0 * density, 80.0 * density, size * density, true)
            val rows = title.copy(height = 140.0 * density)
            val result = PaintDetail.choose(272.0 * density, 144.0 * density, density, listOf(title), rows)
            assertEquals(size >= 11.0, result.pins)
            assertEquals(if (size >= 11.0) PaintDetail.Tier.Rows else PaintDetail.Tier.Summary, result.tier)
        }
    }

    @Test
    fun incompleteTitlesNeverQualifyForTheTitleTier() {
        val clipped = PaintDetail.Content(120.0, 20.0, 14.0, false)
        assertEquals(PaintDetail.Tier.Summary, PaintDetail.choose(272.0, 144.0, 1.0, listOf(clipped), null).tier)
        assertEquals(PaintDetail.Tier.Title, PaintDetail.choose(272.0, 144.0, 1.0, listOf(clipped.copy(complete = true)), null).tier)
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
        face = face, eyebrow = "Task", marks = listOf(BlueprintMark("Doing", Color(0xFF4FA3FF), BlueprintMark.Kind.Badge)), progress = .4f.takeIf { face == BlueprintFace.Region })

    private fun sweep(layout: BlueprintLayout<String>, face: (Int) -> BlueprintFace, zooms: List<Double>, density: Float = 1f, check: (Double, BlueprintReading) -> Unit) {
        val canvas = GraphCanvasState(Blueprint.ProseZoom.start..4.0, Blueprint.ProseLevels)
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

    @Test
    fun cardTextStaysAtElevenPixelsAndTitlesAreWholeAtFullDetail() {
        sweep(layout(272.0, 144.0), { BlueprintFace.Prose }, there(2.2, .26)) { zoom, reading ->
            assertTrue(reading.smallest >= 11 - 1e-3, "Text at ${reading.smallest} px on screen at zoom $zoom, level ${reading.level}")
            assertEquals(0, reading[BlueprintTitle.Words], "A title was shortened at zoom $zoom: ${reading.titles}")
        }
    }

    @Test
    fun componentAreasKeepTheirTextAtElevenPixelsAtEveryZoom() {
        sweep(layout(900.0, 560.0), { BlueprintFace.Region }, there(1.6, Blueprint.ProseZoom.start)) { zoom, reading ->
            assertTrue(reading.smallest >= 11 - 1e-3, "Text at ${reading.smallest} px on screen at zoom $zoom")
            assertEquals(0, reading[BlueprintTitle.Words], "A component title was shortened at zoom $zoom: ${reading.titles}")
        }
    }

    @Test
    fun cardsBesideComponentAreasDropTheirTextBeforeItFallsBelowElevenPixels() {
        sweep(layout(900.0, 560.0), { i -> if (i < 3) BlueprintFace.Region else BlueprintFace.Prose }, there(1.6, Blueprint.ProseZoom.start)) { zoom, reading ->
            assertTrue(reading.smallest >= 11 - 1e-3, "Text at ${reading.smallest} px on screen at zoom $zoom, level ${reading.level}")
        }
    }
    @Test
    fun codeProseAndRegionTitlesStayWholeAtBothDensitiesInBothDirections() {
        for (density in listOf(1f, 2f)) for (face in BlueprintFace.entries) {
            sweep(layout(if (face == BlueprintFace.Region) 900.0 else 272.0,
                if (face == BlueprintFace.Region) 560.0 else 144.0), { face },
                listOf(2.4, 1.1, .99, .72, .43, .19, .08, .19, .43, .72, .99, 1.1, 2.4).map { it * density }, density) { zoom, reading ->
                assertEquals(0, reading[BlueprintTitle.Words], "$face density $density zoom $zoom: ${reading.titles}")
                assertTrue(reading.smallest >= 11 - 1e-3, "$face density $density zoom $zoom: ${reading.smallest}")
                assertTrue(reading.titles.isNotEmpty(), "No visible card semantics")
            }
        }
    }

    @Test
    fun paintedPinTargetsFollowTheMeasuredRowsAcrossZoomAndTitleChanges() {
        for (density in listOf(1f, 2f)) for (title in listOf("Whole", titles[3])) {
            val card = Card("c", 0.0, 0.0, 272.0, 144.0, listOf(Pin("value", "Int", false, 64.0, PortWiring.Unmatched)), 0)
            val layout = BlueprintLayout("pins", mapOf("c" to card), emptyList(), emptyList(), 272.0, 144.0)
            val canvas = GraphCanvasState(.01..8.0)
            var hits = 0
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(900, 600, Density(density), coroutineContext = Dispatchers.Unconfined) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(Color.White, false, false) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                        cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, title, "", pins = listOf(BlueprintPinLook(Color.Blue, "value", Color.Black, false))) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, onPinClick = { _, _ -> hits++ }, modifier = Modifier.fillMaxSize())
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
            try {
                repeat(10) { frame() }
                for (scale in listOf(1.5, .9, .06, .9, 1.5)) {
                    val zoom = scale * density
                    val view = Viewport(450 - 136 * zoom, 300 - 72 * zoom, zoom)
                    SwingUtilities.invokeAndWait { canvas.flow.setViewport(view) }
                    repeat(4) { frame() }
                    Thread.sleep(180)
                    repeat(12) { frame() }
                    val before = hits
                    SwingUtilities.invokeAndWait {
                        val at = Offset((view.x + 24 * zoom).toFloat(), (view.y + 64 * zoom).toFloat())
                        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
                    }
                    repeat(4) { frame() }
                    assertEquals(if (title == "Whole" && scale == 1.5) 1 else 0, hits - before, "density $density scale $scale")
                    SwingUtilities.invokeAndWait { assertEquals(0, canvas.reading()!![BlueprintTitle.Words]) }
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    @Test
    fun editorWiringCannotStartOnRowsThePainterHasHidden() {
        for (density in listOf(1f, 2f)) for (title in listOf("Whole", titles[3])) {
            val shape = GraphShape(listOf(ScopeShape("app", "App")), listOf(NodeShape("node", "app", "Node", "Node", NodeKind.Service,
                ports = listOf(PortShape("value", "Int", false, PortWiring.Unmatched)))), emptyList(), emptyList())
            val editor = GraphEditor(shape, Placement(mapOf("node" to Spot(0.0, 0.0))))
            val layout = editor.layout
            val card = layout.cards.getValue("node")
            val pin = card.pins.single()
            val canvas = GraphCanvasState(.01..8.0)
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(900, 600, Density(density), coroutineContext = Dispatchers.Unconfined) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(Color.White, false, false) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                        cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, title, "", pins = listOf(BlueprintPinLook(Color.Blue, "value", Color.Black, false))) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, editor = editor, modifier = Modifier.fillMaxSize())
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
            try {
                repeat(10) { frame() }
                for ((step, scale) in listOf(1.5, .9, 1.5).withIndex()) {
                    val zoom = scale * density
                    val view = Viewport(450 - (card.x + card.width / 2) * zoom, 300 - (card.y + card.height / 2) * zoom, zoom)
                    SwingUtilities.invokeAndWait {
                        canvas.flow.setViewport(view)
                        if (step > 0 && scale == 1.5 && title == "Whole")
                            assertTrue(editor.pinAt(Spot(card.x + card.width * .75, card.y + pin.y)) == null, "rows wait for the matching paint after zoom")
                    }
                    repeat(4) { frame() }
                    Thread.sleep(180)
                    repeat(12) { frame() }
                    SwingUtilities.invokeAndWait {
                        val world = Spot(card.x + card.width * .75, card.y + pin.y)
                        val expected = title == "Whole" && scale == 1.5
                        val context = "density $density scale $scale logical zoom ${canvas.zoom} title length ${title.length}"
                        assertEquals(expected, editor.pinAt(world) != null, context)
                        val at = Offset((view.x + world.x * zoom).toFloat(), (view.y + world.y * zoom).toFloat())
                        assertTrue(at.x in 0f..900f && at.y in 0f..600f, context)
                        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                        assertEquals(expected, editor.gesture is Gesture.Wiring, "$context gesture ${editor.gesture?.let { it::class.simpleName }}")
                        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
                    }
                    repeat(4) { frame() }
                    assertEquals(shape, editor.shape)
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

}
