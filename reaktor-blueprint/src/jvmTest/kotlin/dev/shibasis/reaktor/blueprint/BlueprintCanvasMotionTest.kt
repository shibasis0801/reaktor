@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Density
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
