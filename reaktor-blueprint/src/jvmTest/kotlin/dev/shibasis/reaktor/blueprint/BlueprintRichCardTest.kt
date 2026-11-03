@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.canvas.BlueprintIcon
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.graph.*
import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import dev.shibasis.reaktor.ui.machinesignal.colors
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlueprintRichCardTest {
    private val glyph = ImageVector.Builder("wide book", 24.dp, 12.dp, 24f, 12f).apply {
        path(fill = SolidColor(Color.White)) { moveTo(0f, 0f); lineTo(24f, 0f); lineTo(24f, 12f); lineTo(0f, 12f); close() }
    }.build()
    private val rows = linkedMapOf(
        "source_units" to listOf("id", "source_id", "kind", "created_at"),
        "Deep Learning" to listOf("1. Introduction", "2. Linear Algebra", "3. Probability", "4. Numerical Computation"),
        "Blueprint contract" to listOf("Identity", "Ports", "Presentation"),
        "Graph shaping" to listOf("Evidence", "Assessment"))

    @Test fun rowsAreSizedBeforeLayoutAndKeepTheirExactKeysAndTargets() {
        val nodes = rows.map { (id, labels) -> BlueprintNode(id, 0,
            inputs = labels.mapIndexed { i, label -> PinSpec("$id:$i", label, PortWiring.Connected) }) }
        val layout = BlueprintEngine.layout("rich", listOf(BlueprintGroup("rich", "Rich cards", nodes)), emptyList(), 1.6)
        for (node in nodes) {
            val card = layout.cards.getValue(node.id)
            assertEquals(BlueprintEngine.cardHeight(node), card.height)
            assertEquals(node.inputs.map { it.key }, card.pins.map { it.key })
            assertTrue(card.pins.all { it.y + BlueprintEngine.RowHeight / 2 <= card.height })
        }
        for (mode in listOf(MachineSignalMode.Dark, MachineSignalMode.Light)) for ((name, labels) in rows) {
            val source = layout.cards.getValue(name)
            val card = source.copy(x = 0.0, y = 0.0)
            val look = BlueprintCardLook(Color.Blue, glyph, name, "${labels.size} rows", pins = labels.map {
                BlueprintPinLook(Color.Blue, it, if (mode == MachineSignalMode.Dark) Color.White else Color.Black, true, icon = glyph)
            })
            specimen(card, look, mode) { canvas, scene, state, frame ->
                assertEquals(1, canvas.reading()!![BlueprintTier.Detail])
                assertTrue(canvas.reading()!!.smallest >= 11.0)
                for (pin in card.pins) assertTrue("port-${pin.key}" in tags(scene))
                val before = canvas.viewport()
                state.value = look.copy(selected = true)
                frame()
                assertEquals(before, canvas.viewport())
                assertEquals(source.height, card.height)
                picture(scene, "${rows.keys.indexOf(name)}-${mode.name.lowercase()}")
            }
        }
    }

    @Test fun rowGlyphWidthParticipatesInTheSameDetailAndHitDecision() {
        var boundary = false
        for (width in 112..144 step 4) {
            if (boundary) break
            val card = Card("long", 0.0, 0.0, width.toDouble(), 100.0, listOf(Pin("unit:1", "Unit", false, 64.0, PortWiring.Connected)), 0)
            val row = BlueprintPinLook(Color.Blue, "Linear algebra", Color.White, true)
            val look = BlueprintCardLook(Color.Blue, glyph, "Source", "", pins = listOf(row))
            specimen(card, look, MachineSignalMode.Dark) { canvas, scene, state, frame ->
                val before = canvas.reading()!![BlueprintTier.Detail]
                assertEquals(before == 1, "port-unit:1" in tags(scene))
                state.value = look.copy(pins = listOf(row.copy(icon = glyph)))
                frame()
                val after = canvas.reading()!![BlueprintTier.Detail]
                assertTrue(after <= before)
                assertEquals(after == 1, "port-unit:1" in tags(scene), "width=$width before=$before after=$after")
                boundary = before == 1 && after == 0
            }
        }
        assertTrue(boundary)
    }

    @Test fun portraitScreenshotKeepsAspectAndHighlightUsesItsContainedBounds() {
        val card = Card("/notifications", 0.0, 0.0, 272.0, BlueprintEngine.cardHeight(12), emptyList(), 0)
        val image = bitmap(Color.Red, Color.Blue)
        val look = BlueprintCardLook(Color.Blue, glyph, "/notifications", "Screen", image = image,
            imageHighlight = Rect(.25f, .25f, .75f, .5f))
        specimen(card, look, MachineSignalMode.Dark) { _, scene, _, _ ->
            val pixels = pixels(scene)
            val height = card.height - BlueprintEngine.HeaderHeight
            val width = height / 2
            val left = (card.width - width) / 2
            fun at(x: Double, y: Double) = pixels.getRGB((40 + x * 1.4).toInt(), (70 + y * 1.4).toInt())
            assertEquals(Color.Red.toArgb(), at(left + width * .1, BlueprintEngine.HeaderHeight + height * .1))
            assertEquals(Color.Blue.toArgb(), at(left + width * .5, BlueprintEngine.HeaderHeight + height * .75))
            assertTrue(at(left / 2, BlueprintEngine.HeaderHeight + height * .1) != Color.Red.toArgb())
            assertTrue(at(left + width * .5, BlueprintEngine.HeaderHeight + height * .375) != Color.Red.toArgb())
            assertEquals(Color.Red.toArgb(), at(left + width * .1, BlueprintEngine.HeaderHeight + height * .375))
            picture(scene, "navigation-dark")
        }
        specimen(card, look, MachineSignalMode.Light) { _, scene, _, _ -> picture(scene, "navigation-light") }
    }

    @Test fun thumbnailLayerIsReusedForCameraMotionAndInvalidatedByTheBitmap() {
        val card = Card("screen", 0.0, 0.0, 272.0, BlueprintEngine.cardHeight(12), emptyList(), 0)
        val image = bitmap(Color.Red, Color.Blue)
        val look = BlueprintCardLook(Color.Blue, glyph, "Screen", "", image = image)
        specimen(card, look, MachineSignalMode.Dark) { canvas, scene, state, frame ->
            val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver
            val layer = (field(painter, "layers") as Array<*>).filterNotNull().first()
            val paintKey = (field(painter, "keys") as Array<*>).filterNotNull().first()
            edt { canvas.restore(45.0, 75.0, 1.4) }
            frame()
            assertSame(layer, (field(painter, "layers") as Array<*>).filterNotNull().first())
            assertSame(paintKey, (field(painter, "keys") as Array<*>).filterNotNull().first())
            val before = pixels(scene).getRGB(230, 180)
            state.value = look.copy(image = bitmap(Color.Green, Color.Blue))
            frame()
            assertTrue(before != pixels(scene).getRGB(230, 180))
            assertEquals(1, canvas.reading()!![BlueprintTier.Detail])
        }
    }

    @Test fun nonSquareGlyphUsesOneScaleAndStaysInsideItsSlot() {
        edt {
            val scene = ImageComposeScene(80, 80, coroutineContext = Dispatchers.Unconfined) {
                Canvas(Modifier.fillMaxSize()) { BlueprintIcon(glyph).draw(this, 20f, 20f, 40f, Color.Red) }
            }
            try {
                scene.render(16_666_667L).use { image -> image.encodeToData()!!.use { data ->
                    val pixels = ImageIO.read(ByteArrayInputStream(data.bytes))
                    assertEquals(Color.Red.toArgb(), pixels.getRGB(30, 35))
                    assertTrue(pixels.getRGB(30, 25) != Color.Red.toArgb())
                    assertTrue(pixels.getRGB(30, 55) != Color.Red.toArgb())
                } }
            } finally { scene.close() }
        }
    }

    @Test fun measuredBodyReachesLayoutWithoutChangingPortRowsOrRoutes() {
        val short = BlueprintNode("source", 0, outputs = listOf(PinSpec("out", "Message", PortWiring.Connected)))
        val large = short.copy(body = BlueprintBody(490.0, 350.0))
        val target = BlueprintNode("target", 1, inputs = listOf(PinSpec("in", "Message", PortWiring.Connected)))
        val inner = BlueprintGroup("inner", "Inner", listOf(large, target))
        val layout = BlueprintEngine.layout("body", listOf(BlueprintGroup("root", "Root", listOf(BlueprintNode("inner", 0, group = inner)))),
            listOf(BlueprintEdge("message", short.id, target.id, "out", "in")), 1.6)
        val card = layout.cards.getValue(large.id)
        assertEquals(490.0, card.width)
        assertEquals(BlueprintEngine.HeaderHeight + 350, card.height)
        assertEquals(BlueprintEngine.pinsOf(short), card.pins)
        val link = layout.links.single()
        assertEquals(card.x + card.width, link.trace.first().first, .001)
        assertEquals(card.y + card.pins.single().y, link.trace.first().second, .001)
        assertTrue(layout.frames.all { frame -> frame.nodes.mapNotNull(layout.cards::get).all { child ->
            child.x >= frame.x && child.x + child.width <= frame.x + frame.width && child.y + child.height <= frame.y + frame.height
        } })
        val baseGroup = BlueprintGroup("base", "Base", listOf(short, target))
        val baseline = BlueprintEngine.layout("same", listOf(baseGroup), listOf(BlueprintEdge("message", short.id, target.id, "out", "in")), 1.6)
        val emptyMinimum = BlueprintEngine.layout("same", listOf(baseGroup.copy(nodes = baseGroup.nodes.map { it.copy(body = BlueprintBody(0.0, 0.0)) })),
            listOf(BlueprintEdge("message", short.id, target.id, "out", "in")), 1.6)
        assertEquals(baseline, emptyMinimum)
    }

    @Test fun measuredRowsAndPortraitsFitBeforeTheyArePainted() {
        val measurements = ArrayList<String>()
        for ((name, labels) in rows) {
            val node = BlueprintNode(name, 0, inputs = labels.mapIndexed { i, label -> PinSpec("$name:$i", label, PortWiring.Connected) })
            val look = BlueprintCardLook(Color.Blue, glyph, name, "${labels.size} rows", pins = labels.map {
                BlueprintPinLook(Color.Blue, it, Color.White, true, icon = glyph)
            })
            val minimum = sizing(node, look)
            val measured = node.copy(body = minimum)
            val laid = BlueprintEngine.layout("measured", listOf(BlueprintGroup("cards", "Cards", listOf(measured))), emptyList(), 1.0)
            val card = laid.cards.getValue(name).copy(x = 0.0, y = 0.0)
            assertEquals(labels.size, card.pins.size)
            assertEquals(BlueprintEngine.pinsOf(node), card.pins)
            assertTrue(card.width >= minimum.width)
            assertTrue(card.height >= BlueprintEngine.HeaderHeight + minimum.height)
            measurements += "$name\t${card.width}\t${card.height}\t${card.pins.size}"
            for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) specimen(card, look, mode) { canvas, scene, _, _ ->
                assertEquals(1, canvas.reading()!![BlueprintTier.Detail])
                picture(scene, "measured-${rows.keys.indexOf(name)}-${mode.name.lowercase()}")
            }
        }
        val node = BlueprintNode("/notifications", 0)
        val look = BlueprintCardLook(Color.Blue, glyph, node.id, "Screen", image = bitmap(Color.Red, Color.Blue))
        val body = sizing(node, look)
        assertEquals(body.width * 2, body.height, .001)
        val card = BlueprintEngine.layout("portrait", listOf(BlueprintGroup("screens", "Screens", listOf(node.copy(body = body)))), emptyList(), 1.0)
            .cards.getValue(node.id).copy(x = 0.0, y = 0.0)
        measurements += "${node.id}\t${card.width}\t${card.height}\t0"
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) specimen(card, look, mode) { _, scene, _, _ -> picture(scene, "measured-navigation-${mode.name.lowercase()}") }
        File("build/reports/blueprint-rich-cards").apply { mkdirs() }.resolve("measurements.tsv").writeText(measurements.joinToString("\n", "name\twidth\theight\trows\n", "\n"))
    }

    @Test fun measuredProseUsesActualPinsAndCountsItsHeaderOnce() {
        val graph = AppGraph(GraphShape(listOf(ScopeShape("plan", "Plan")), listOf(
            NodeShape("task", "plan", "Read the onboarding state", "PlanItemNode", NodeKind.Workflow,
                ports = listOf(PortShape("ready", "PlanItemNode", true, PortWiring.Connected, 1)),
                attributes = mapOf(BlueprintFace.Key to "prose"))), emptyList(), emptyList()))
        val node = AppGraphLayout.node(graph, graph.nodes.getValue("task"), Detail.Ports)
        assertEquals(BlueprintFace.ProseRows, node.rows)
        val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Live)
        val look = BlueprintCardLook(Color.Blue, glyph, "Read the onboarding state", "Unblocks the next task",
            face = BlueprintFace.Prose, eyebrow = "Task", agent = agent,
            pins = listOf(BlueprintPinLook(Color.Blue, "ready: PlanItemNode", Color.White, true)))
        val body = sizing(node, look)
        assertEquals(sizing(node.copy(rows = null), look), body,
            "Measured prose reserves actual port rows rather than five empty rows")
        assertEquals(body, sizing(node.copy(pinHeader = node.pinHeader + BlueprintEngine.RowHeight), look),
            "A measured body excludes the node's own header exactly once")
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, nested = true), GridFrameLayouter) { body }
        val card = layout.cards.getValue(node.id).copy(x = 0.0, y = 0.0)
        assertEquals(node.pinHeader + body.height, card.height)
        assertEquals(BlueprintEngine.pinsOf(node), card.pins)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) specimen(card, look, mode) { canvas, _, _, _ ->
            assertEquals(1, canvas.reading()!![BlueprintTier.Detail])
            val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as dev.shibasis.reaktor.blueprint.canvas.BlueprintPainter
            val footer = requireNotNull(painter.agentBounds(card.id, canvas.zoom))
            assertTrue(!footer.compact)
            assertTrue(card.pins.single().y + BlueprintEngine.RowHeight / 2 + node.pinFooter <= footer.y)
            assertTrue(footer.y + footer.height <= card.height)
        }
    }

    @Test fun longTypedRowsGrowAndClaimStateDoesNotChangeTheMeasuredBody() {
        val node = BlueprintNode("table", 0, inputs = listOf(PinSpec("column", "text", PortWiring.Connected)))
        val row = BlueprintPinLook(Color.Blue, "source_reference_for_learning_material: character varying(255)", Color.White, true,
            marks = listOf(BlueprintMark("required", Color.Blue)), icon = glyph)
        val look = BlueprintCardLook(Color.Blue, glyph, "source_units", "1 column", pins = listOf(row))
        val minimum = sizing(node, look)
        assertTrue(minimum.width > BlueprintEngine.CardWidth)
        val card = BlueprintEngine.layout("long", listOf(BlueprintGroup("table", "Table", listOf(node.copy(body = minimum)))), emptyList(), 1.0)
            .cards.getValue(node.id).copy(x = 0.0, y = 0.0)
        specimen(card, look, MachineSignalMode.Dark) { canvas, _, _, _ -> assertEquals(1, canvas.reading()!![BlueprintTier.Detail]) }
        val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Claimed, effort = "xhigh", model = "sol 6.1", role = "designer")
        val assigned = sizing(node, look.copy(agent = agent))
        for (activity in listOf(BlueprintAgent.Activity.Live, BlueprintAgent.Activity.Stale, BlueprintAgent.Activity.Completed)) {
            assertEquals(assigned, sizing(node, look.copy(agent = agent.copy(activity = activity))))
        }
        assertTrue(assigned.height > minimum.height)
    }

    @Test fun proseKeepsTheFullTypedRowWidthBeforeLayout() {
        val text = "source_reference_for_learning_material: character varying(255)"
        val row = BlueprintPinLook(Color.Blue, text, Color.White, true)
        val input = PinSpec("source", "text", PortWiring.Connected)
        val node = BlueprintNode("source", 0, inputs = listOf(input),
            pinHeader = BlueprintEngine.ProseHeaderHeight, pinFooter = BlueprintEngine.ProseFooterHeight)
        val compact = BlueprintCardLook(Color.Blue, glyph, "Source", "", pins = listOf(row))
        val prose = compact.copy(face = BlueprintFace.Prose)
        val one = sizing(node, prose)
        assertEquals(sizing(node, compact).width, one.width)
        assertTrue(one.width > BlueprintEngine.CardWidth)
        val twoNode = node.copy(outputs = listOf(input.copy(key = "destination")))
        val twoLook = prose.copy(pins = listOf(row, row))
        val two = sizing(twoNode, twoLook)
        assertEquals(one.width * 2, two.width)
        val card = BlueprintEngine.layout("typed", listOf(BlueprintGroup("sources", "Sources", listOf(twoNode.copy(body = two)))), emptyList(), 1.0)
            .cards.getValue(node.id).copy(x = 0.0, y = 0.0)
        assertEquals(BlueprintEngine.pinsOf(twoNode), card.pins)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) specimen(card, twoLook, mode) { canvas, scene, _, _ ->
            assertEquals(1, canvas.reading()!![BlueprintTier.Detail])
            picture(scene, "typed-prose-${mode.name.lowercase()}")
        }
    }

    @Test fun aroundUsesMeasuredBodiesWithoutChangingTheEmptyMapGeometry() {
        val edges = listOf(BlueprintEdge("in", "caller", "centre"), BlueprintEdge("out", "centre", "destination"))
        fun layout(bodies: Map<String, BlueprintBody> = emptyMap()) = BlueprintEngine.aroundLayout("around", "frame", "Around", "centre",
            listOf("caller"), listOf("destination"), edges, 3, 1.6, bodies = bodies)
        assertEquals(layout(), layout(mapOf("centre" to BlueprintBody(0.0, 0.0))))
        val measured = layout(mapOf("centre" to BlueprintBody(530.0, 660.0), "destination" to BlueprintBody(330.0, 500.0)))
        val centre = measured.cards.getValue("centre")
        assertEquals(530.0, centre.width)
        assertEquals(706.0, centre.height)
        assertEquals(BlueprintEngine.CardWidth, measured.cards.getValue("caller").width)
        assertEquals(330.0, measured.cards.getValue("destination").width)
        val frame = measured.frames.single()
        for (card in measured.cards.values) {
            assertTrue(card.x >= frame.x && card.y >= frame.y)
            assertTrue(card.x + card.width <= frame.x + frame.width && card.y + card.height <= frame.y + frame.height)
            for (other in measured.cards.values.filter { it.id != card.id }) {
                assertFalse(card.x < other.x + other.width && card.x + card.width > other.x && card.y < other.y + other.height && card.y + card.height > other.y)
            }
        }
        for (link in measured.links) {
            val from = measured.cards.getValue(link.from)
            val to = measured.cards.getValue(link.to)
            assertEquals(from.x + from.width, link.trace.first().first)
            assertEquals(to.x, link.trace.last().first)
        }
    }

    private fun sizing(node: BlueprintNode, look: BlueprintCardLook): BlueprintBody {
        var result: BlueprintBody? = null
        edt {
            val scene = ImageComposeScene(1, 1, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity.Compact, mode = MachineSignalMode.Dark) { result = measureBlueprintBody(node, look) }
            }
            try { scene.render(16_666_667L).close() } finally { scene.close() }
        }
        return requireNotNull(result)
    }

    private fun specimen(card: Card, initial: BlueprintCardLook, mode: MachineSignalMode,
        inspect: (GraphCanvasState, ImageComposeScene, MutableState<BlueprintCardLook>, () -> Unit) -> Unit) {
        val canvas = GraphCanvasState(.01..4.0)
        val state = mutableStateOf(initial)
        val layout = BlueprintLayout("rich", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun frame() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close() }; Thread.sleep(10) } }
        edt {
            scene = ImageComposeScene(kotlin.math.ceil(card.width * 1.4 + 80).toInt().coerceAtLeast(600), kotlin.math.ceil(card.height * 1.4 + 100).toInt().coerceAtLeast(600), Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity.Compact, mode = mode) {
                    val colors = dev.shibasis.reaktor.ui.machinesignal.MachineSignal.colors
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(colors.controlAccent, false, false) },
                        frameLook = { BlueprintFrameLook(glyph, "") },
                        cardLook = { state.value.copy(pins = state.value.pins.mapIndexed { i, pin -> pin?.copy(textColor = colors.textStrong, tag = "port-${card.pins[i].key}") }) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, onPinClick = { _, _ -> }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        try { frame(); edt { canvas.restore(40.0, 70.0, 1.4) }; frame(); inspect(canvas, scene, state, ::frame) }
        finally { edt { scene.close() } }
    }

    private fun bitmap(top: Color, bottom: Color): ImageBitmap {
        val image = BufferedImage(320, 640, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color(top.toArgb(), true); graphics.fillRect(0, 0, 320, 320)
            graphics.color = java.awt.Color(bottom.toArgb(), true); graphics.fillRect(0, 320, 320, 320)
        } finally { graphics.dispose() }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun pixels(scene: ImageComposeScene): BufferedImage {
        lateinit var result: BufferedImage
        edt { scene.render(9_000_000_000L).use { image -> image.encodeToData()!!.use { data -> result = ImageIO.read(ByteArrayInputStream(data.bytes)) } } }
        return result
    }
    private fun picture(scene: ImageComposeScene, name: String) {
        val output = File("build/reports/blueprint-rich-cards").also { it.mkdirs() }
        ImageIO.write(pixels(scene), "png", File(output, "$name.png"))
    }
    private fun field(owner: Any, name: String): Any? {
        val type = generateSequence(owner.javaClass as Class<*>?) { it.superclass }.first { c -> c.declaredFields.any { it.name == name } }
        return type.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    }
    private fun tags(scene: ImageComposeScene): Set<String> {
        fun nodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        var result = emptySet<String>()
        edt { result = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            .mapNotNull { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.takeIf(String::isNotEmpty) }.toSet() }
        return result
    }
}
