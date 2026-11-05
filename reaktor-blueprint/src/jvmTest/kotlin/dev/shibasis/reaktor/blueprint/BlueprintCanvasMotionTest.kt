@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
import dev.shibasis.reaktor.graph.core.PortWiring
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object BlueprintBench {
    val tones = listOf(Color(0xFF4FA3FF), Color(0xFF59D18C), Color(0xFFE5B84C), Color(0xFFC77DFF), Color(0xFFFF7A6B))
    val icon = ImageVector.Builder("bench", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            moveTo(8f, 8f); lineTo(8f, 16f); lineTo(16f, 16f); lineTo(16f, 8f); close()
        }
    }.build()

    fun tone(id: String) = tones[id.hashCode().mod(tones.size)]

    fun layout(groups: Int = 12, seed: Int = 7): BlueprintLayout<String> {
        val random = Random(seed)
        val wirings = PortWiring.entries
        val nodes = (0 until groups).map { g ->
            (0 until 8 + random.nextInt(14)).map { n ->
                BlueprintNode("n$g-$n", random.nextInt(4),
                    inputs = (0 until random.nextInt(5)).map { PinSpec("needs$it", "Type$it", if (random.nextInt(5) == 0) wirings[random.nextInt(wirings.size)] else PortWiring.Connected) },
                    outputs = (0 until random.nextInt(5)).map { PinSpec("gives$it", "Type$it", PortWiring.Connected) },
                    folded = if (random.nextInt(6) == 0) random.nextInt(1, 9) else 0)
            }
        }
        val providers = nodes.map { list -> list.filter { it.outputs.isNotEmpty() } }
        val edges = nodes.flatMapIndexed { g, list ->
            list.flatMap { node ->
                node.inputs.mapNotNull { pin ->
                    val pool = if (random.nextInt(7) == 0) providers[random.nextInt(groups)] else providers[g]
                    val from = pool.filter { it.id != node.id }.randomOrNull(random) ?: return@mapNotNull null
                    val port = from.outputs.random(random).key
                    BlueprintEdge("${from.id}.$port>${node.id}.${pin.key}", from.id, node.id, port, pin.key)
                }
            }
        }
        return BlueprintEngine.layout("bench", nodes.mapIndexed { g, list -> BlueprintGroup("feature:$g", "Feature $g", list, listOf("session › Feature $g"), muted = g == groups - 1) }, edges, 1.6)
    }

    fun neighbours(layout: BlueprintLayout<String>, id: String) = buildSet {
        add(id)
        layout.links.forEach { if (it.from == id) add(it.to); if (it.to == id) add(it.from) }
    }
}

private fun benchLink(link: Link, highlight: Set<String>, focus: Boolean): LinkStyle {
    val lit = link.from in highlight && link.to in highlight
    return Blueprint.link(BlueprintBench.tone(link.from), highlight.isNotEmpty(), lit, cross = link.cross, moving = focus && lit)
}

private fun pinTone(pin: Pin) = BlueprintBench.tones[pin.key.length % BlueprintBench.tones.size]

@Composable
private fun BenchMap(layout: BlueprintLayout<String>, canvas: GraphCanvasState, selected: String?, highlight: Set<String>, focus: Boolean, looks: AtomicInteger, onCardClick: (String) -> Unit = {}) {
    BlueprintCanvas(layout, canvas, highlight, selected,
        linkStyle = { benchLink(it, highlight, focus) },
        frameLook = { frame -> BlueprintFrameLook(BlueprintBench.icon, "${frame.nodes.size} nodes", marks = if (frame.muted) listOf(BlueprintMark("not running", Color.Gray)) else emptyList()) },
        cardLook = { card ->
            looks.incrementAndGet()
            BlueprintCardLook(BlueprintBench.tone(card.id), BlueprintBench.icon, "Node ${card.id}", "Screen · DesktopPaneNode · /route/${card.id}",
                selected = card.id == selected, folded = card.folded.takeIf { it > 0 }?.let { "+$it endpoints" },
                marks = if (card.id.endsWith("3")) listOf(BlueprintMark("check", Color.Red)) else emptyList(),
                pins = card.pins.map { pin -> BlueprintPinLook(pinTone(pin), pin.key, Color.White, pin.wiring == PortWiring.Connected) })
        },
        onCardClick = onCardClick, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(),
        pinTip = { _, pin -> BlueprintTip { BasicText(pin.type) } })
}

class BlueprintCanvasMotionTest {
    @Test
    fun densityRebaseDuringAnObservedCentreFlightKeepsTheOriginalTargetAndPin() {
        val layout = BlueprintEngine.layout("flight", listOf(BlueprintGroup("group", "Group", listOf(
            BlueprintNode("source", 0, outputs = listOf(PinSpec("value", "Int", PortWiring.Connected))),
            BlueprintNode("target", 0, inputs = listOf(PinSpec("value", "Int", PortWiring.Connected))),
        ))), listOf(BlueprintEdge("edge", "source", "target", "value", "value")), 1.0)
        val target = layout.cards.getValue("target")
        val pin = target.pins.single()
        val originalCards = layout.cards.toMap()
        val originalLinks = layout.links.toList()
        for (initialDensity in listOf(1f, 2f)) {
            var density by mutableStateOf(initialDensity)
            val canvas = GraphCanvasState(.002..16.0)
            canvas.insetEnd = 240.0
            canvas.insetBottom = 64.0
            var selectedPin: PinRef? = null
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(),
                    Density(initialDensity), coroutineContext = Dispatchers.Unconfined) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides Density(density),
                    ) {
                        BlueprintCanvas(layout, canvas, emptySet(), target.id,
                            linkStyle = { Blueprint.link(Color.White, false, false) },
                            frameLook = { BlueprintFrameLook(BlueprintBench.icon, "2 declared nodes") },
                            cardLook = { card -> BlueprintCardLook(Color.Blue, BlueprintBench.icon, card.id,
                                "declared operation", selected = card.id == target.id,
                                pins = card.pins.map { BlueprintPinLook(Color.Blue, it.key, Color.Black, true) }) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            onPinClick = { card, row -> selectedPin = card.ref(row) },
                            startInset = 80.dp, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait {
                Snapshot.sendApplyNotifications()
                time += 16_666_667L
                scene.render(time).close()
            }
            fun until(message: String, condition: () -> Boolean) {
                repeat(120) { if (!condition()) frame() }
                assertTrue(condition(), message)
            }
            try {
                repeat(12) { frame() }
                SwingUtilities.invokeAndWait { canvas.restore(-1800.0, -900.0, .2) }
                repeat(4) { frame() }
                val before = canvas.viewport()
                SwingUtilities.invokeAndWait { canvas.centre(target.x, target.y, target.width, target.height) }
                until("The real mounted camera must enter and advance its flight") {
                    canvas.aim != null && canvas.viewport() != before
                }
                val expected = checkNotNull(canvas.aim)
                SwingUtilities.invokeAndWait {
                    density = if (initialDensity == 1f) 2f else 1f
                    scene.constraints = androidx.compose.ui.unit.Constraints.fixed(
                        (1100 * density).toInt(), (800 * density).toInt())
                }
                until("Density rebasing must complete at the original logical Centre target") {
                    val actual = canvas.viewport()
                    canvas.aim == null && kotlin.math.abs(actual.zoom - expected.zoom) < 1e-8 &&
                        kotlin.math.abs(actual.x - expected.x) * density <= 1.0 &&
                        kotlin.math.abs(actual.y - expected.y) * density <= 1.0
                }
                SwingUtilities.invokeAndWait {
                    val view = canvas.viewport()
                    val seen = checkNotNull(canvas.seen())
                    assertEquals(target.x + target.width / 2, seen.x + seen.width / 2, 1.0 / (view.zoom * density))
                    assertEquals(target.y + target.height / 2, seen.y + seen.height / 2, 1.0 / (view.zoom * density))
                    val point = Offset(((view.x + (target.x + 24.0) * view.zoom) * density).toFloat(),
                        ((view.y + (target.y + pin.y) * view.zoom) * density).toFloat())
                    scene.sendPointerEvent(PointerEventType.Press, point,
                        buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                    assertEquals(target.ref(pin), selectedPin)
                    assertEquals(view, canvas.viewport())
                    assertEquals(originalCards, layout.cards)
                    assertEquals(originalLinks, layout.links)
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    @Test
    fun pointerZoomKeepsTheObservedWorldPointAtBothDensities() {
        for (density in listOf(1.0, 2.0)) {
            val state = GraphCanvasState(.002..16.0)
            state.resize(IntSize((1512 * density).toInt(), (900 * density).toInt()))
            state.density(density)
            state.restore(125.0, -25.0, .05)
            val x = 701.0; val y = 319.0
            val before = state.viewport()
            val worldX = (x - before.x) / before.zoom; val worldY = (y - before.y) / before.zoom
            state.zoomAt(2.0, x, y)
            val after = state.viewport()
            assertEquals(.1, state.zoom, 1e-10)
            assertEquals(worldX, (x - after.x) / after.zoom, 1e-7)
            assertEquals(worldY, (y - after.y) / after.zoom, 1e-7)
            state.zoomAt(1000.0, x, y)
            assertEquals(16.0, state.zoom)
        }
    }

    @Test
    fun fitCentresTheExtentInTheAreaLeftByTheInsets() {
        for (density in listOf(1.0, 2.0)) for (width in listOf(1100, 1512)) {
            val canvas = GraphCanvasState()
            canvas.resize(IntSize((width * density).toInt(), (800 * density).toInt()))
            canvas.density(density)
            canvas.insetTop = 48.0
            canvas.insetStart = 260.0
            canvas.fit(0.0, 0.0, 4280.0, 2600.0)
            val view = canvas.viewport()
            assertEquals(260.0 + (width - 260.0) / 2, view.x + 4280.0 / 2 * view.zoom, .0001)
            assertEquals(48.0 + (800 - 48.0) / 2, view.y + 2600.0 / 2 * view.zoom, .0001)
            assertTrue(view.x + 4280.0 * view.zoom <= width - (width - 260) * .03 + .001, "Right edge at $width")
            assertTrue(view.y >= 48.0 && view.y + 2600.0 * view.zoom <= 800.0)
            val before = canvas.viewport()
            canvas.insetEnd = 100.0
            canvas.resize(IntSize(((width + 100) * density).toInt(), (800 * density).toInt()))
            assertEquals(before, canvas.viewport())
        }
    }

    @Test
    fun restoringAConfiguredSixteenTimesViewKeepsItsActualZoomAtEveryDensity() {
        for (density in listOf(1.0, 2.0)) {
            val canvas = GraphCanvasState(0.002..16.0)
            canvas.resize(IntSize((1512 * density).toInt(), (900 * density).toInt()))
            canvas.density(density)
            canvas.restore(125.0, -25.0, 16.0)
            assertEquals(16.0, canvas.zoom)
            assertEquals(125.0, canvas.viewport().x)
            assertEquals(-25.0, canvas.viewport().y)
            canvas.restore(125.0, -25.0, 32.0)
            assertEquals(16.0, canvas.zoom)
        }
    }

    @Test
    fun explicitPinRevealWaitsForCompleteRowsAndUsesTheAreaBesideTheInspector() {
        for (density in listOf(1.0, 2.0)) for (width in listOf(900, 1100, 1512, 1920)) {
            val card = Card("node", 1200.0, 900.0, 272.0, 3000.0,
                listOf(Pin("operation", "Int", false, 2700.0, PortWiring.Unmatched)), 0)
            val canvas = GraphCanvasState(.01..4.0)
            canvas.resize(androidx.compose.ui.unit.IntSize((width * density).toInt(), (800 * density).toInt()))
            canvas.density(density)
            canvas.insetEnd = 360.0
            canvas.insetTop = 42.0
            canvas.extent = listOf(card)
            canvas.restore(0.0, 0.0, .1)
            val before = canvas.viewport()
            val pin = PinRef(card.id, "operation", false)
            canvas.reveal(card.x, card.y, card.width, card.height, pin)
            assertEquals(before, canvas.viewport())
            assertEquals(pin, canvas.pinRequest)
            canvas.readableZoom = { _, _, _, _ -> 1.1 }
            canvas.revealPin()
            val view = canvas.viewport()
            assertTrue(view.zoom >= 1.1)
            assertEquals(null, canvas.pinRequest)
            assertTrue(view.x + card.x * view.zoom >= 16)
            assertTrue(view.x + (card.x + card.width) * view.zoom <= width - 360 - 16)
            assertTrue(view.y + (card.y + 2700) * view.zoom in 58.0..784.0)
            canvas.insetEnd = 0.0
            canvas.resize(androidx.compose.ui.unit.IntSize(((width + 80) * density).toInt(), (800 * density).toInt()))
            assertEquals(view, canvas.viewport())
        }
    }

    @Test
    fun explicitFocusUsesTheMeasuredRowsThresholdAndMakesThePinSelectable() {
        val card = Card("node", 1200.0, 900.0, 272.0, 144.0, listOf(Pin("value", "Int", false, 64.0, PortWiring.Unmatched)), 0)
        val layout = BlueprintLayout("focus", mapOf(card.id to card), emptyList(), emptyList(), 1600.0, 1200.0)
        for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.01..4.0)
            var hits = 0
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(Color.White, false, false) }, frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                        cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, "Node", "configured in the repository", pins = listOf(BlueprintPinLook(Color.Blue, "value", Color.Black, false))) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, onPinClick = { _, _ -> hits++ }, modifier = Modifier.fillMaxSize())
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
            try {
                repeat(12) { frame() }
                SwingUtilities.invokeAndWait { canvas.restore(40.0, 70.0, .52) }
                repeat(4) { frame() }
                SwingUtilities.invokeAndWait { canvas.centre(card.x, card.y, card.width, card.height) }
                repeat(50) { frame() }; Thread.sleep(180); repeat(15) { frame() }
                SwingUtilities.invokeAndWait {
                    val view = canvas.viewport()
                    assertTrue(view.zoom >= 11.0 / 10.5, "density $density: $view")
                    assertTrue(canvas.reading()!!.smallest >= 11 - 1e-3)
                    fun nodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    val text = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                        .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }.map { it.text }
                    assertTrue(text.contains("configured in the repository"))
                    val at = Offset((density * (view.x + (card.x + 24) * view.zoom)).toFloat(), (density * (view.y + (card.y + 64) * view.zoom)).toFloat())
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
                    assertEquals(1, hits)
                    assertEquals(view, canvas.viewport())
                    val folder = java.io.File("build/reports/camera-focus").apply { mkdirs() }
                    scene.render(time).use { image -> image.encodeToData()!!.use { data -> java.io.File(folder, "focus-${density.toInt()}x.png").writeBytes(data.bytes) } }
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    @Test
    fun wholeMapFitCanCrossTheNormalZoomFloorWithoutClippingOrZoomJumps() {
        for (density in listOf(1.0, 2.0)) for (width in listOf(900, 1100, 1512, 1920)) {
            val canvas = GraphCanvasState(.01..2.5)
            canvas.density(density)
            canvas.resize(IntSize((width * density).toInt(), (982 * density).toInt()))
            val box = Card("large-map", -120_000.0, -20_000.0, 240_000.0, 40_000.0, emptyList(), 0)
            canvas.extent = listOf(box)
            canvas.overview()
            val view = canvas.viewport()
            assertTrue(view.zoom < .01)
            assertEquals(width * .94 / box.width, view.zoom, 1e-12)
            assertTrue(view.x + box.x * view.zoom >= 0)
            assertTrue(view.x + (box.x + box.width) * view.zoom <= width)
            assertTrue(view.y + box.y * view.zoom >= 0)
            assertTrue(view.y + (box.y + box.height) * view.zoom <= 982)
            canvas.zoomBy(1.01)
            assertEquals(view.zoom * 1.01, canvas.zoom, 1e-12)
            canvas.back()
            assertEquals(view, canvas.viewport())
        }
    }

    @Test
    fun aPhysicalCardHitWinsOverAnOverlappingFarFrameBanner() {
        val card = Card("card", 1000.0, 960.0, 304.0, 120.0, emptyList(), 0)
        val layout = BlueprintLayout("overlap", mapOf(card.id to card),
            listOf(Frame("frame", "Feature", 1000.0, 1000.0, 1000.0, 1000.0, setOf(card.id), emptyList(), false)), emptyList(), 2000.0, 2000.0)
        for (density in listOf(1f, 2f)) {
            var clicked: String? = null
            SwingUtilities.invokeAndWait {
                val canvas = GraphCanvasState()
                val scene = ImageComposeScene((1512 * density).toInt(), (982 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    BenchMap(layout, canvas, null, emptySet(), false, AtomicInteger()) { clicked = it }
                }
                var time = 0L
                fun frame() { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
                try {
                    repeat(12) { frame() }
                    canvas.restore(240.0, 240.0, .13)
                    repeat(12) { frame() }
                    val before = canvas.viewport()
                    val at = Offset((density * (before.x + (card.x + card.width / 2) * before.zoom)).toFloat(),
                        (density * (before.y + (card.y + 12) * before.zoom)).toFloat())
                    scene.sendPointerEvent(PointerEventType.Move, at)
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                    repeat(3) { frame() }
                    assertEquals(card.id, clicked, "density $density")
                    assertEquals(before, canvas.viewport())
                } finally { scene.close() }
            }
        }
    }

    @Test
    fun aCameraChangeBetweenDeliveredClicksKeepsTheSecondRawSelection() {
        val first = Card("first", 100.0, 100.0, 200.0, 80.0, emptyList(), 0)
        val second = Card("second", 1100.0, 500.0, 200.0, 80.0, emptyList(), 0)
        val layout = BlueprintLayout("camera-click", mapOf(first.id to first, second.id to second),
            emptyList(), emptyList(), 1400.0, 700.0)
        for (density in listOf(1f, 2f)) for (change in 0..2) SwingUtilities.invokeAndWait {
            val canvas = GraphCanvasState()
            val selected = mutableListOf<String>()
            val scene = ImageComposeScene((900 * density).toInt(), (600 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                BlueprintGraph(layout, canvas, emptySet(), null,
                    linkStyle = { LinkStyle(Color.White) },
                    frameLook = { BlueprintFrameLook(BlueprintBench.icon, "Declared") },
                    cardLook = { BlueprintCardLook(Color.White, BlueprintBench.icon, it.id, "Declared") },
                    onCardClick = { selected += it }, onLinkClick = {}, onPaneClick = {},
                    modifier = Modifier.fillMaxSize(), initialFit = {}, editable = false)
            }
            var time = 0L
            fun frame() { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
            val point = Offset(400 * density, 300 * density)
            fun click() {
                scene.sendPointerEvent(PointerEventType.Move, point)
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            try {
                repeat(12) { frame() }
                canvas.restore(400 - (first.x + first.width / 2), 300 - (first.y + 20), 1.0)
                repeat(3) { frame() }
                click()
                assertEquals(listOf(first.id), selected)
                if (change != 0) {
                    val zoom = if (change == 1) 1.0 else 1.5
                    canvas.restore(400 - (second.x + second.width / 2) * zoom, 300 - (second.y + 20) * zoom, zoom)
                }
                val before = canvas.viewport()
                click()
                repeat(36) { frame() }
                if (change == 0) {
                    assertEquals(listOf(first.id), selected)
                    assertEquals(2.0, canvas.zoom, 1e-8)
                } else {
                    assertEquals(listOf(first.id, second.id), selected)
                    assertEquals(before, canvas.viewport())
                }
                assertEquals(mapOf(first.id to first, second.id to second), layout.cards)
            } finally { scene.close() }
        }
    }

    @Test
    fun inspectorResizePreservesEveryAnchorAtEveryHostWidth() {
        val layout = BlueprintBench.layout()
        for (density in listOf(1.0, 2.0)) for (width in listOf(900, 1100, 1512, 1920)) for (fitted in listOf(true, false)) {
            val canvas = GraphCanvasState()
            canvas.density(density)
            canvas.extent = layout.frames + layout.cards.values
            val window = IntSize((width * density).toInt(), (982 * density).toInt())
            canvas.resize(window, window)
            canvas.frame { canvas.overview() }
            if (!fitted) canvas.restore(-170.0, 83.0, .75)
            val before = canvas.viewport()
            val anchors = layout.cards.values.map { (before.x + it.x * before.zoom) to (before.y + it.y * before.zoom) }
            canvas.resize(IntSize(((width - 320) * density).toInt(), window.height), window)
            assertEquals(before, canvas.viewport(), "opening inspector: $width @$density fitted=$fitted")
            assertEquals(anchors, layout.cards.values.map { (canvas.viewport().x + it.x * canvas.zoom) to (canvas.viewport().y + it.y * canvas.zoom) })
            canvas.resize(window, window)
            assertEquals(before, canvas.viewport(), "closing inspector: $width @$density fitted=$fitted")
        }
    }

    @Test
    fun unclassifiedPanelResizeDoesNotFitOrMoveTheGraph() {
        val canvas = GraphCanvasState()
        canvas.extent = BlueprintBench.layout(groups = 3).cards.values.toList()
        canvas.resize(IntSize(1512, 982))
        canvas.frame { canvas.overview() }
        val before = canvas.viewport()
        canvas.resize(IntSize(1192, 982))
        assertEquals(before, canvas.viewport())
        canvas.resize(IntSize(1512, 982))
        assertEquals(before, canvas.viewport())
    }

    private class Motion(val name: String, val millis: List<Double>) {
        override fun toString(): String {
            val sorted = millis.sorted()
            fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            return "$name p50=${"%.2f".format(at(.5))} p95=${"%.2f".format(at(.95))} max=${"%.2f".format(sorted.last())} ms (${millis.size} frames)"
        }
    }

    @Test
    fun densityKeepsTheFitAndTheTaps() {
        val layout = BlueprintBench.layout(groups = 3)
        val card = layout.cards.values.sortedBy { it.x + it.y }[layout.cards.size / 2]
        val seen = listOf(1f, 2f).map { density ->
            val canvas = GraphCanvasState()
            var clicked: String? = null
            var fitted: Viewport? = null
            SwingUtilities.invokeAndWait {
                val scene = ImageComposeScene((1440 * density).toInt(), (900 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    BenchMap(layout, canvas, null, emptySet(), false, AtomicInteger()) { clicked = it }
                }
                var time = 0L
                fun frame() { Snapshot.sendApplyNotifications(); time += 8_333_333L; scene.render(time).close() }
                repeat(10) { frame() }
                val view = canvas.viewport().also { fitted = it }
                val at = Offset((density * (view.x + (card.x + card.width / 2) * view.zoom)).toFloat(), (density * (view.y + (card.y + 12) * view.zoom)).toFloat())
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                repeat(3) { frame() }
                scene.close()
            }
            fitted to clicked
        }
        assertEquals(seen[0].first, seen[1].first)
        assertEquals(listOf(card.id, card.id), seen.map { it.second })
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun deliveredKeyboardZoomUsesOnlyAPointerInsideTheResizedMountedCanvas() {
        val target = Card("target", 200.0, 120.0, 304.0, 180.0,
            listOf(Pin("value", "Int", false, 80.0, PortWiring.Connected)), 0)
        val source = Card("source", 700.0, 120.0, 304.0, 180.0,
            listOf(Pin("value", "Int", true, 80.0, PortWiring.Connected)), 0)
        val group = Frame("group", "Declared group", 130.0, 60.0, 970.0, 360.0,
            setOf(source.id, target.id), listOf("2 declared operations"), false)
        val wire = Link("wire", source.id, target.id, "value", "value", LinkKind.Wire, listOf("wire"),
            listOf(source.x + source.width to source.y + 80.0, target.x to target.y + 80.0))
        val layout = BlueprintLayout("resized-pointer", listOf(target, source).associateBy { it.id },
            listOf(group), listOf(wire), 1400.0, 900.0)
        val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        for (density in listOf(1f, 2f)) for (retainedY in listOf(210f, 620f)) {
            val canvas = GraphCanvasState(.002..16.0)
            var height by mutableStateOf(700)
            var selected by mutableStateOf<String?>(null)
            var selections = 0
            var bounds = Rect.Zero
            var drawnFull: BlueprintLayout<String>? = null
            var drawnView: Viewport? = null
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(),
                    Density(density), coroutineContext = Dispatchers.Unconfined) {
                    Box(Modifier.fillMaxSize().padding(start = 40.dp, top = 32.dp)) {
                        Box(Modifier.fillMaxWidth().height(height.dp)
                            .onGloballyPositioned { bounds = it.boundsInWindow() }) {
                            BlueprintGraph(layout, canvas, emptySet(), selected,
                                linkStyle = { LinkStyle(Color.Blue) },
                                frameLook = { BlueprintFrameLook(BlueprintBench.icon, "${it.nodes.size} declared operations") },
                                cardLook = { card -> BlueprintCardLook(Color.Blue, BlueprintBench.icon, card.id,
                                    "Declared operation", selected = selected == card.id,
                                    pins = card.pins.map { BlueprintPinLook(Color.Blue, "${it.key}: ${it.type}", Color.Black, true) }) },
                                onCardClick = { selected = it; selections++ }, onLinkClick = {}, onPaneClick = {},
                                startInset = 80.dp, initialFit = {}, focusable = true,
                                onGeometry = { full, _ -> drawnFull = full; drawnView = canvas.viewport() },
                                modifier = Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.CenterEnd).width(160.dp).fillMaxHeight()
                                .onSizeChanged { canvas.insetEnd = it.width / density.toDouble() }) { BasicText("Inspector") }
                            Box(Modifier.align(Alignment.BottomStart).height(48.dp).fillMaxWidth()
                                .onSizeChanged { canvas.insetBottom = it.height / density.toDouble() }) { BasicText("Details") }
                        }
                    }
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait {
                Snapshot.sendApplyNotifications()
                time += 16_666_667L
                scene.render(time).close()
            }
            fun until(message: String, condition: () -> Boolean) {
                repeat(120) { if (!condition()) frame() }
                assertTrue(condition(), "$message; density=$density; retainedY=$retainedY; bounds=$bounds; size=${canvas.flow.canvasSize}; actual=${canvas.viewport()}; drawn=$drawnView")
            }
            fun root(local: Offset) = Offset(bounds.left, bounds.top) + local * density
            fun hover(local: Offset) = SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Move, root(local), buttons = PointerButtons())
            }
            fun key(key: Key, primary: Boolean = false) = SwingUtilities.invokeAndWait {
                assertTrue(scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown,
                    isMetaPressed = primary && mac, isCtrlPressed = primary && !mac)))
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp,
                    isMetaPressed = primary && mac, isCtrlPressed = primary && !mac))
            }
            fun landed() = canvas.aim == null && drawnFull === layout && drawnView == canvas.viewport()
            fun world(view: Viewport, local: Offset) =
                (local.x - view.x) / view.zoom to (local.y - view.y) / view.zoom
            try {
                until("The initial measured canvas must be mounted") {
                    landed() && bounds.height == 700f * density && canvas.flow.canvasSize.height == (700 * density).toInt()
                }
                SwingUtilities.invokeAndWait { canvas.restore(20.0, 40.0, 1.0) }
                until("Fixture camera must be drawn before delivered selection") { landed() && canvas.zoom == 1.0 }
                SwingUtilities.invokeAndWait {
                    val view = canvas.viewport()
                    val point = root(Offset((view.x + (target.x + 100.0) * view.zoom).toFloat(),
                        (view.y + (target.y + 12.0) * view.zoom).toFloat()))
                    assertTrue(bounds.contains(point))
                    scene.sendPointerEvent(PointerEventType.Move, point, buttons = PointerButtons())
                    scene.sendPointerEvent(PointerEventType.Press, point,
                        buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                }
                until("Delivered raw-card selection must complete") { landed() && selected == target.id }
                val retained = Offset(317f, retainedY)
                hover(retained)
                val beforeResize = canvas.viewport()
                SwingUtilities.invokeAndWait { height = 360 }
                until("The drawer must deliver its current smaller canvas") {
                    landed() && bounds.height == 360f * density && canvas.flow.canvasSize.height == (360 * density).toInt()
                }
                assertEquals(beforeResize, canvas.viewport())
                assertEquals(80.0, canvas.insetStart)
                assertEquals(160.0, canvas.insetEnd)
                assertEquals(48.0, canvas.insetBottom)
                val retainedInside = retained.y < canvas.flow.canvasSize.height / density
                assertEquals(retainedY == 210f, retainedInside)
                val anchor = if (retainedInside) retained else Offset(
                    ((canvas.insetStart + canvas.flow.canvasSize.width / density - canvas.insetEnd) / 2).toFloat(),
                    ((canvas.insetTop + canvas.flow.canvasSize.height / density - canvas.insetBottom) / 2).toFloat())
                val beforeMinus = canvas.viewport()
                val anchored = world(beforeMinus, anchor)
                key(Key.Minus)
                until("Delivered Minus must finish on the current canvas") { landed() && canvas.zoom == beforeMinus.zoom / 1.25 }
                val afterMinus = world(canvas.viewport(), anchor)
                assertEquals(anchored.first, afterMinus.first, 1e-5, "Current-canvas horizontal anchor")
                assertEquals(anchored.second, afterMinus.second, 1e-5, "Current-canvas vertical anchor")
                val current = Offset(410f, 170f)
                hover(current)
                val beforeEquals = canvas.viewport()
                val currentWorld = world(beforeEquals, current)
                key(Key.Equals, primary = true)
                until("A fresh current-canvas pointer must anchor delivered primary Equals") {
                    landed() && canvas.zoom == beforeEquals.zoom * 1.25
                }
                val afterEquals = world(canvas.viewport(), current)
                assertEquals(currentWorld.first, afterEquals.first, 1e-5)
                assertEquals(currentWorld.second, afterEquals.second, 1e-5)
                assertEquals(target.id, selected)
                assertEquals(1, selections)
                assertEquals(listOf(target, source).associateBy { it.id }, layout.cards)
                assertEquals(listOf(group), layout.frames)
                assertEquals(listOf(wire), layout.links)
                assertEquals(target.pins.single(), layout.cards.getValue(target.id).pins.single())
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun deliveredFitFramesEveryPreparedTopLevelBelowOnePercentBesideMeasuredPanels() {
        val source = Card("source", 1600.0, 1000.0, 304.0, 180.0,
            listOf(Pin("value", "Int", true, 80.0, PortWiring.Connected)), 0)
        val target = Card("target", 88000.0, 68000.0, 304.0, 180.0,
            listOf(Pin("value", "Int", false, 80.0, PortWiring.Connected)), 0)
        val outside = Card("outside", 96000.0, 75000.0, 304.0, 180.0, emptyList(), 0)
        val system = Frame("system", "System", 0.0, 0.0, 92000.0, 72000.0,
            setOf(source.id, target.id), emptyList(), false)
        val other = Frame("other", "Other", 94000.0, 74000.0, 6000.0, 4000.0,
            setOf(outside.id), emptyList(), false)
        val wire = Link("wire", source.id, target.id, "value", "value", LinkKind.Wire, listOf("wire"),
            listOf(source.x + source.width to source.y + 80.0, target.x to target.y + 80.0))
        val layout = BlueprintLayout("full-opening", listOf(source, target, outside).associateBy { it.id },
            listOf(system, other), listOf(wire), 100000.0, 78000.0)
        val expectedPin = source.ref(source.pins.single())
        for (density in listOf(1f, 2f)) for (width in listOf(1100, 1512)) for (restored in listOf(false, true)) {
            val canvas = GraphCanvasState(.002..16.0)
            val saved = Viewport(170.0, -230.0, .75)
            if (restored) canvas.restore(saved.x, saved.y, saved.zoom)
            var selected by mutableStateOf<String?>(null)
            var inspected: PinRef? = null
            var pinClicks = 0
            var bounds = Rect.Zero
            var drawnFull: BlueprintLayout<String>? = null
            var drawnView: Viewport? = null
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((width * density).toInt(), (800 * density).toInt(),
                    Density(density), coroutineContext = Dispatchers.Unconfined) {
                    Box(Modifier.fillMaxSize().padding(start = 40.dp, top = 32.dp)
                        .onGloballyPositioned { bounds = it.boundsInWindow() }) {
                        BlueprintGraph(layout, canvas, emptySet(), selected,
                            linkStyle = { LinkStyle(Color.Blue) },
                            frameLook = { BlueprintFrameLook(BlueprintBench.icon, "Declared") },
                            cardLook = { card -> BlueprintCardLook(Color.Blue, BlueprintBench.icon, card.id,
                                "Declared", selected = selected == card.id,
                                pins = card.pins.map { BlueprintPinLook(Color.Blue, "${it.key}: ${it.type}", Color.Black, true) }) },
                            onCardClick = { selected = it }, onLinkClick = {}, onPaneClick = {},
                            onPinClick = { card, pin -> selected = card.id; inspected = card.ref(pin); pinClicks++ },
                            startInset = 80.dp, focusable = true,
                            onGeometry = { full, _ ->
                                drawnFull = full; drawnView = canvas.viewport()
                            }, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.CenterEnd).width(240.dp).fillMaxHeight()
                            .onSizeChanged { canvas.insetEnd = it.width / density.toDouble() }) { BasicText("Inspector") }
                        Box(Modifier.align(Alignment.BottomStart).height(64.dp).fillMaxWidth()
                            .onSizeChanged { canvas.insetBottom = it.height / density.toDouble() }) { BasicText("Details") }
                    }
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait {
                Snapshot.sendApplyNotifications()
                time += 16_666_667L
                scene.render(time).close()
            }
            fun landed() = canvas.aim == null && drawnFull === layout && drawnView == canvas.viewport()
            fun until(message: String, condition: () -> Boolean) {
                repeat(120) { if (!condition()) frame() }
                assertTrue(condition(), "$message; density=$density; width=$width; restored=$restored; bounds=$bounds; size=${canvas.flow.canvasSize}; actual=${canvas.viewport()}; drawn=$drawnView")
            }
            fun assertFramed() {
                val view = canvas.viewport()
                val right = canvas.flow.canvasSize.width / density - canvas.insetEnd
                val bottom = canvas.flow.canvasSize.height / density - canvas.insetBottom
                val pixel = 1.0 / density
                assertTrue(view.zoom < .01, "This complete prepared extent requires a sub-one-percent Fit")
                for (box in layout.frames + layout.cards.values) {
                    assertTrue(view.x + box.x * view.zoom >= canvas.insetStart - pixel, "Left full or painted bound")
                    assertTrue(view.y + box.y * view.zoom >= canvas.insetTop - pixel, "Top full or painted bound")
                    assertTrue(view.x + (box.x + box.width) * view.zoom <= right + pixel, "Right full or painted bound")
                    assertTrue(view.y + (box.y + box.height) * view.zoom <= bottom + pixel, "Bottom full or painted bound")
                }
                assertEquals(80.0, canvas.insetStart)
                assertTrue(canvas.insetTop > 0)
                assertEquals(240.0, canvas.insetEnd)
                assertEquals(64.0, canvas.insetBottom)
            }
            fun fitKey() = SwingUtilities.invokeAndWait {
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.One, KeyEventType.KeyDown, isShiftPressed = true)))
                scene.sendKeyEvent(KeyEvent(Key.One, KeyEventType.KeyUp, isShiftPressed = true))
            }
            try {
                until("The actual measured opening must finish its current draw") {
                    landed() && (restored || canvas.untouched) &&
                        bounds.width == (width - 40) * density && bounds.height == 768f * density &&
                        canvas.flow.canvasSize.width == bounds.width.toInt() && canvas.flow.canvasSize.height == bounds.height.toInt() &&
                        canvas.insetEnd == 240.0 && canvas.insetBottom == 64.0
                }
                if (restored) assertEquals(saved, canvas.viewport()) else assertFramed()
                SwingUtilities.invokeAndWait { canvas.restore(240.0 - source.x, 200.0 - source.y, 1.0) }
                until("The raw-pin fixture camera must be committed before delivery") { landed() && canvas.zoom == 1.0 }
                SwingUtilities.invokeAndWait {
                    canvas.reveal(source.x, source.y, source.width, source.height, expectedPin)
                }
                until("The original pin must reach the existing measured readable-row contract before delivery") {
                    landed() && canvas.pinRequest == null &&
                        canvas.extent.filterIsInstance<Card>().any { it.id == source.id } &&
                        canvas.zoom >= (canvas.readableZoom(source.x, source.y, source.width, source.height) ?: Double.POSITIVE_INFINITY) - 1e-8 &&
                        (canvas.reading()?.smallest ?: 0.0) >= 11.0 - 1e-3
                }
                SwingUtilities.invokeAndWait {
                    val view = canvas.viewport()
                    val point = Offset(bounds.left + ((view.x + (source.x + 24.0) * view.zoom) * density).toFloat(),
                        bounds.top + ((view.y + (source.y + source.pins.single().y) * view.zoom) * density).toFloat())
                    assertTrue(bounds.contains(point))
                    scene.sendPointerEvent(PointerEventType.Move, point, buttons = PointerButtons())
                    scene.sendPointerEvent(PointerEventType.Press, point,
                        buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                }
                until("The same original typed pin must be selected through the mounted graph") {
                    landed() && inspected == expectedPin && selected == source.id
                }
                fitKey()
                until("Delivered Frame everything must settle on the complete prepared extent") { landed() && canvas.zoom < .01 }
                assertFramed()
                val fitted = canvas.viewport()
                val anchor = Offset(bounds.left + ((canvas.insetStart + canvas.flow.canvasSize.width / density - canvas.insetEnd) / 2 * density).toFloat(),
                    bounds.top + ((canvas.insetTop + canvas.flow.canvasSize.height / density - canvas.insetBottom) / 2 * density).toFloat())
                SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Scroll, anchor, scrollDelta = Offset(24f, 16f)) }
                until("Plain delivered scroll must pan the fitted map") { landed() && canvas.viewport() != fitted }
                assertEquals(fitted.zoom, canvas.zoom)
                fitKey()
                until("Delivered Fit must return to the same complete framing") { landed() && canvas.viewport() == fitted }
                assertFramed()
                assertEquals(source.id, selected)
                assertEquals(expectedPin, inspected)
                assertEquals(1, pinClicks)
                assertEquals(listOf(source, target, outside).associateBy { it.id }, layout.cards)
                assertEquals(listOf(system, other), layout.frames)
                assertEquals(listOf(wire), layout.links)
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun laterPreparedLayoutReframesOnlyAnUntouchedOpeningAndDeliveredFitKeepsTheRawPinReturn() {
        val source = Card("source", 1600.0, 1000.0, 304.0, 180.0,
            listOf(Pin("value", "Int", true, 80.0, PortWiring.Connected)), 0)
        val firstTarget = Card("target", 2800.0, 2400.0, 304.0, 180.0,
            listOf(Pin("value", "Int", false, 80.0, PortWiring.Connected)), 0)
        val firstGroup = Frame("system", "System", 0.0, 0.0, 4200.0, 3600.0,
            setOf(source.id, firstTarget.id), emptyList(), false)
        val firstWire = Link("wire", source.id, firstTarget.id, "value", "value", LinkKind.Wire, listOf("wire"),
            listOf(source.x + source.width to source.y + 80.0, firstTarget.x to firstTarget.y + 80.0))
        val small = BlueprintLayout("preparation", listOf(source, firstTarget).associateBy { it.id },
            listOf(firstGroup), listOf(firstWire), 4200.0, 3600.0)
        val laterTarget = firstTarget.copy(x = 88000.0, y = 68000.0)
        val outside = Card("outside", 96000.0, 75000.0, 304.0, 180.0,
            listOf(Pin("value", "Int", false, 80.0, PortWiring.Connected)), 0)
        val laterGroup = firstGroup.copy(width = 92000.0, height = 72000.0)
        val other = Frame("other", "Other", 94000.0, 74000.0, 6000.0, 4000.0,
            setOf(outside.id), emptyList(), false)
        val laterWire = firstWire.copy(points = listOf(source.x + source.width to source.y + 80.0,
            laterTarget.x to laterTarget.y + 80.0))
        val boundary = Link("boundary", source.id, outside.id, "value", "value", LinkKind.Wire, listOf("boundary"),
            listOf(source.x + source.width to source.y + 80.0, outside.x to outside.y + 80.0))
        val large = BlueprintLayout(small.key, listOf(source, laterTarget, outside).associateBy { it.id },
            listOf(laterGroup, other), listOf(laterWire, boundary), 100000.0, 78000.0)
        val expectedPin = source.ref(source.pins.single())
        for (density in listOf(1f, 2f)) for (authority in listOf("untouched", "pan", "saved")) {
            val canvas = GraphCanvasState(.002..16.0)
            val sceneKey = Any()
            val saved = Viewport(170.0, -230.0, .75)
            if (authority == "saved") canvas.restore(saved.x, saved.y, saved.zoom)
            var current by mutableStateOf(small)
            var selected by mutableStateOf<String?>(null)
            var inspected: PinRef? = null
            var pinClicks = 0
            var bounds = Rect.Zero
            var drawnFull: BlueprintLayout<String>? = null
            var drawnView: Viewport? = null
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(),
                    Density(density), coroutineContext = Dispatchers.Unconfined) {
                    val full = current
                    Box(Modifier.fillMaxSize().padding(start = 40.dp, top = 32.dp)
                        .onGloballyPositioned { bounds = it.boundsInWindow() }) {
                        BlueprintCanvas(full, canvas, emptySet(), selected,
                            linkStyle = { LinkStyle(Color.Blue) },
                            frameLook = { BlueprintFrameLook(BlueprintBench.icon, "Declared") },
                            cardLook = { card -> BlueprintCardLook(Color.Blue, BlueprintBench.icon, card.id,
                                "Declared", selected = selected == card.id,
                                pins = card.pins.map { BlueprintPinLook(Color.Blue, "${it.key}: ${it.type}", Color.Black, true) }) },
                            onCardClick = { selected = it }, onLinkClick = {}, onPaneClick = {},
                            onPinClick = { card, pin -> selected = card.id; inspected = card.ref(pin); pinClicks++ },
                            fitScene = sceneKey, startInset = 80.dp, focusable = true,
                            onGeometry = { geometry, _ ->
                                drawnFull = geometry; drawnView = canvas.viewport()
                            }, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.CenterEnd).width(240.dp).fillMaxHeight()
                            .onSizeChanged { canvas.insetEnd = it.width / density.toDouble() }) { BasicText("Inspector") }
                        Box(Modifier.align(Alignment.BottomStart).height(64.dp).fillMaxWidth()
                            .onSizeChanged { canvas.insetBottom = it.height / density.toDouble() }) { BasicText("Details") }
                    }
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait {
                Snapshot.sendApplyNotifications()
                time += 16_666_667L
                scene.render(time).close()
            }
            fun landed(full: BlueprintLayout<String>) = canvas.aim == null && drawnFull === full && drawnView == canvas.viewport()
            fun until(message: String, condition: () -> Boolean) {
                repeat(120) { if (!condition()) frame() }
                assertTrue(condition(), "$message; density=$density; authority=$authority; fullCurrent=${drawnFull === current}; bounds=$bounds; actual=${canvas.viewport()}; drawn=$drawnView; untouched=${canvas.untouched}")
            }
            fun assertFramed(full: BlueprintLayout<String>) {
                assertTrue(landed(full))
                val view = canvas.viewport()
                val right = canvas.flow.canvasSize.width / density - canvas.insetEnd
                val bottom = canvas.flow.canvasSize.height / density - canvas.insetBottom
                val pixel = 1.0 / density
                for (box in full.frames + full.cards.values) {
                    assertTrue(view.x + box.x * view.zoom >= canvas.insetStart - pixel, "Current full or painted left bound")
                    assertTrue(view.y + box.y * view.zoom >= canvas.insetTop - pixel, "Current full or painted top bound")
                    assertTrue(view.x + (box.x + box.width) * view.zoom <= right + pixel, "Current full or painted right bound")
                    assertTrue(view.y + (box.y + box.height) * view.zoom <= bottom + pixel, "Current full or painted bottom bound")
                }
            }
            fun centrePoint() = Offset(
                bounds.left + ((canvas.insetStart + canvas.flow.canvasSize.width / density - canvas.insetEnd) / 2 * density).toFloat(),
                bounds.top + ((canvas.insetTop + canvas.flow.canvasSize.height / density - canvas.insetBottom) / 2 * density).toFloat())
            try {
                until("The initial prepared pair must finish its measured opening") {
                    landed(small) && bounds.width == 1060f * density && bounds.height == 768f * density &&
                        canvas.insetEnd == 240.0 && canvas.insetBottom == 64.0 &&
                        (if (authority == "saved") canvas.viewport() == saved else canvas.untouched)
                }
                if (authority == "saved") assertEquals(saved, canvas.viewport()) else assertFramed(small)
                val opening = canvas.viewport()
                if (authority == "pan") {
                    SwingUtilities.invokeAndWait {
                        scene.sendPointerEvent(PointerEventType.Scroll, centrePoint(), scrollDelta = Offset(24f, 16f))
                    }
                    until("A delivered user pan must take camera authority before later preparation") {
                        landed(small) && canvas.viewport() != opening && !canvas.untouched
                    }
                    assertEquals(opening.zoom, canvas.zoom)
                }
                val authoritative = canvas.viewport()
                SwingUtilities.invokeAndWait { current = large }
                until("The larger prepared pair must be committed on the same mounted camera") {
                    landed(large) && (if (authority == "untouched") canvas.untouched && canvas.viewport() != opening
                        else canvas.viewport() == authoritative)
                }
                frame()
                assertTrue(landed(large))
                if (authority == "untouched") {
                    assertFramed(large)
                    assertTrue(canvas.zoom < opening.zoom)
                } else {
                    assertEquals(authoritative, canvas.viewport())
                    assertTrue(!canvas.untouched)
                }
                assertEquals(80.0, canvas.insetStart)
                assertTrue(canvas.insetTop > 0)
                assertEquals(240.0, canvas.insetEnd)
                assertEquals(64.0, canvas.insetBottom)
                SwingUtilities.invokeAndWait {
                    canvas.centre(source.x, source.y, source.width, source.height)
                    canvas.reveal(source.x, source.y, source.width, source.height, expectedPin)
                }
                until("The retained full source must expose its original typed pin naturally") {
                    landed(large) && canvas.pinRequest == null &&
                        canvas.extent.filterIsInstance<Card>().any { it.id == source.id } &&
                        canvas.zoom >= (canvas.readableZoom(source.x, source.y, source.width, source.height) ?: Double.POSITIVE_INFINITY) - 1e-8 &&
                        (canvas.reading()?.smallest ?: 0.0) >= 11.0 - 1e-3
                }
                val revealed = canvas.viewport()
                SwingUtilities.invokeAndWait {
                    val point = Offset(bounds.left + ((revealed.x + (source.x + 24.0) * revealed.zoom) * density).toFloat(),
                        bounds.top + ((revealed.y + (source.y + source.pins.single().y) * revealed.zoom) * density).toFloat())
                    assertTrue(bounds.contains(point))
                    scene.sendPointerEvent(PointerEventType.Move, point, buttons = PointerButtons())
                    scene.sendPointerEvent(PointerEventType.Press, point,
                        buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                }
                until("Delivered selection must retain the exact original raw pin and inspector") {
                    landed(large) && selected == source.id && inspected == expectedPin
                }
                SwingUtilities.invokeAndWait {
                    assertTrue(scene.sendKeyEvent(KeyEvent(Key.One, KeyEventType.KeyDown, isShiftPressed = true)))
                    scene.sendKeyEvent(KeyEvent(Key.One, KeyEventType.KeyUp, isShiftPressed = true))
                }
                until("Delivered Fit must use the larger current prepared extent") {
                    landed(large) && canvas.zoom < revealed.zoom
                }
                assertFramed(large)
                assertEquals(source.id, selected)
                assertEquals(expectedPin, inspected)
                SwingUtilities.invokeAndWait { canvas.back() }
                until("Return must recover the exact raw-pin camera after the second preparation") {
                    landed(large) && canvas.viewport() == revealed
                }
                assertEquals(source.id, selected)
                assertEquals(expectedPin, inspected)
                assertEquals(1, pinClicks)
                assertEquals(listOf(source, firstTarget).associateBy { it.id }, small.cards)
                assertEquals(listOf(firstGroup), small.frames)
                assertEquals(listOf(firstWire), small.links)
                assertEquals(listOf(source, laterTarget, outside).associateBy { it.id }, large.cards)
                assertEquals(listOf(laterGroup, other), large.frames)
                assertEquals(listOf(laterWire, boundary), large.links)
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    @Test
    fun hangarScaleMapMotion() {
        val layout = BlueprintBench.layout()
        println("bench cards=${layout.cards.size} frames=${layout.frames.size} links=${layout.links.size} pins=${layout.cards.values.sumOf { it.pins.size }} cpus=${Runtime.getRuntime().availableProcessors()} load=${java.lang.management.ManagementFactory.getOperatingSystemMXBean().systemLoadAverage}")
        listOfNotNull(true.takeIf { System.getProperty("os.name").startsWith("Mac") }, false).forEach { gpu ->
            listOf(Triple(1440, 900, 1f), Triple(2880, 1800, 2f)).forEach { (width, height, density) ->
                var failure: Throwable? = null
                SwingUtilities.invokeAndWait { failure = runCatching { measure(gpu, layout, width, height, density) }.exceptionOrNull() }
                failure?.let { throw it }
            }
        }
    }

    private fun measure(gpu: Boolean, layout: BlueprintLayout<String>, width: Int, height: Int, density: Float) {
        val canvas = GraphCanvasState()
        var selected by mutableStateOf<String?>(null)
        var highlight by mutableStateOf(emptySet<String>())
        var focus by mutableStateOf(false)
        val looks = AtomicInteger()
        val scene = ImageComposeScene(width, height, Density(density), coroutineContext = Dispatchers.Unconfined) {
            BenchMap(layout, canvas, selected, highlight, focus, looks)
        }
        val target = if (gpu) MetalTarget(width, height).also { it.attach(scene) } else null
        var time = 0L
        fun frame(): Double {
            val start = System.nanoTime()
            Snapshot.sendApplyNotifications()
            time += 8_333_333L
            scene.render(time).close()
            target?.finish()
            return (System.nanoTime() - start) / 1e6
        }
        fun motion(name: String, steps: Int, step: (Int) -> Unit) = Motion(name, (0 until steps).map { step(it); frame() })
        try {
            repeat(30) { frame() }
            val centre = layout.cards.values.sortedBy { it.x + it.y }[layout.cards.size / 2]
            fun look(zoom: Double, x: Double = centre.x, y: Double = centre.y) =
                canvas.flow.setViewport(Viewport(width / 2.0 - x * density * zoom, height / 2.0 - y * density * zoom, zoom * density))
            fun zoom(zoom: Double) = canvas.flow.zoomTo(zoom * density, width / 2.0, height / 2.0, .01, 10.0)
            look(.9)
            repeat(30) { frame() }
            val results = mutableListOf<Motion>()
            val evaluated = looks.get()
            results += motion("detail pan", 90) { canvas.flow.panBy(-6.0 * density, -3.0 * density) }
            results += motion("detail zoom", 90) { zoom(.9 - it * .004) }
            results += motion("zoom out past far", 60) { zoom(.6 * Math.pow(.985, it.toDouble())) }
            results += motion("settle after zoom", 10) { }
            results += motion("far pan", 60) { canvas.flow.panBy(-10.0 * density, -5.0 * density) }
            results += motion("zoom back in", 60) { zoom(.25 * Math.pow(1.025, it.toDouble())) }
            assertEquals(evaluated, looks.get(), "camera motion evaluated card looks")
            look(.9)
            repeat(10) { frame() }
            val picks = layout.cards.values.filter { kotlin.math.abs(it.x - centre.x) < 500 && kotlin.math.abs(it.y - centre.y) < 300 }.take(10)
            val select = mutableListOf<Double>()
            val after = mutableListOf<Double>()
            picks.forEach { card ->
                selected = card.id; highlight = BlueprintBench.neighbours(layout, card.id); focus = true
                select += frame()
                repeat(5) { after += frame() }
            }
            results += Motion("select", select)
            results += Motion("flowing after select", after)
            selected = null; highlight = emptySet(); focus = false
            results += Motion("clear", listOf(frame()))
            results += motion("idle", 30) { }
            println("== ${if (gpu) "metal" else "raster"} ${width}x$height @$density")
            results.forEach(::println)
        } finally {
            scene.close()
            target?.close()
        }
    }
}
