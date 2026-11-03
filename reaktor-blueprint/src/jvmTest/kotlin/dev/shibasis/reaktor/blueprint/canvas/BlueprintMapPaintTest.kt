@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphAppearance
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphGroup
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphLink
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphNode
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphRow
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSimple
import dev.shibasis.reaktor.blueprint.product.look
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.pow
import kotlin.test.*

class BlueprintMapPaintTest {
    @Test fun projectedSummaryAndBoundaryPointerHitsKeepOriginalIdentityAndCamera() {
        val source = SurfaceGraphNode("source", "Source", "Recorded source", scope = "area",
            rows = listOf(SurfaceGraphRow("source-row", "Provides", "exact|row")))
        val target = SurfaceGraphNode("target", "Target", "Recorded target", scope = "other",
            rows = listOf(SurfaceGraphRow("target-row", "Needs", "exact|row")))
        val a = Card(source.id, 40.0, 80.0, 420.0, 320.0, emptyList(), 0)
        val b = a.copy(id = target.id, x = 1100.0)
        val frame = Frame("area", "Area", 0.0, 0.0, 900.0, 800.0, setOf(a.id), emptyList(), false)
        val placed = BlueprintLayout("pointer", listOf(a, b).associateBy { it.id }, listOf(frame), emptyList(), 1520.0, 800.0)
        val appearance = SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.Gray, Color.Blue)
        val model = SurfaceGraphSimple(listOf(source, target), listOf(SurfaceGraphLink("recorded", "source-row", "target-row")), appearance,
            groups = listOf(SurfaceGraphGroup(frame.key, frame.label, owner = source.id)), initialLayout = placed)
        val editor = model.editor
        val full = editor.settled
        val levels = BlueprintMapLevels.prepare(full, { 4.0 })
        val original = full.cards.getValue(source.id)
        val folder = File("build/reports/blueprint-map-paint/projected-hits").apply { mkdirs() }
        val samples = mutableListOf("mode\tdensity\tphase\tzoom\tnameLabels\tfullCards\tfullLinks")
        val failures = mutableListOf<String>()
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val hits = mutableListOf<Pair<Card, Pin>>()
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(4) } }
            fun click(x: Double, y: Double) = edt {
                val view = canvas.viewport()
                val point = Offset(((view.x + x * view.zoom) * density).toFloat(), ((view.y + y * view.zoom) * density).toFloat())
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
            }
            edt {
                editor.select(dev.shibasis.reaktor.blueprint.editor.Selection())
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val themed = appearance.copy(canvas = colors.canvas, card = colors.surface, text = colors.textStrong, muted = colors.textMuted, line = colors.line)
                        BlueprintGraph(editor, canvas, { model.look(it.id, themed) }, { BlueprintFrameLook(SurfaceGraphSymbol, "1 source") },
                            { Blueprint.link(Color.Blue, false, false) }, { null }, Modifier.fillMaxSize(), mapLevels = levels,
                            mapLevel = levels.at(canvas.zoom), onPinClick = { card, pin -> hits += card to pin })
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(80.0, 90.0, .5) }; settle()
                val summary = levels.at(.5).layout.cards.getValue("summary:area")
                val before = canvas.viewport()
                click(summary.x + summary.width / 2, summary.y + summary.height / 2); settle()
                assertEquals(setOf(frame.key), editor.selection.notes)
                assertTrue(editor.selection.nodes.isEmpty())
                assertEquals(before, canvas.viewport())
                Thread.sleep(350)
                val band = levels.at(4.0).frames.getValue(frame.key)
                for ((phase, zoom) in listOf("far" to 1.5, "blend" to (band.enterZoom + band.leaveZoom) / 2)) {
                    val level = levels.at(zoom)
                    val boundary = level.forwardedPorts.entries.single { it.key.provides == true }
                    val card = level.layout.cards.getValue(boundary.key.card)
                    val pin = card.pins.single { boundary.key.matches(card.id, it) }
                    val count = hits.size
                    edt { canvas.restore(780.0 - (card.x + card.width) * zoom, 350.0 - (card.y + pin.y) * zoom, zoom) }; settle()
                    val atPin = canvas.viewport()
                    click(card.x + card.width - 10, card.y + pin.y); settle()
                    assertEquals(count + 1, hits.size)
                    assertEquals(original, hits.last().first)
                    assertEquals(boundary.value.single().key, hits.last().second.key)
                    assertEquals("source-row", hits.last().second.id)
                    val rawPin = original.pins.single { boundary.value.single().matches(original.id, it) }
                    assertSame(original, hits.last().first)
                    assertSame(rawPin, hits.last().second)
                    assertEquals(rawPin.type, hits.last().second.type)
                    assertEquals(rawPin.provides, hits.last().second.provides)
                    assertEquals(setOf(source.id), editor.selection.nodes)
                    assertEquals(atPin, canvas.viewport())
                    assertEquals(full.cards, editor.settled.cards)
                    assertEquals(full.links, editor.settled.links)
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val names = (field(painter, "mapLabels") as List<*>).size
                        samples += listOf(mode.name, density, phase, zoom, names, full.cards.size, full.links.size).joinToString("\t")
                        if (names != 1) failures += "$mode density $density $phase paints $names group names"
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                            File(folder, "boundary-$phase-${density.toInt()}x-${mode.name.lowercase()}.png").writeBytes(it.bytes)
                            val pixels = ImageIO.read(ByteArrayInputStream(it.bytes))
                            val rgb = pixels.getRGB(((780.0 - 10 * zoom) * density).toInt(), (350 * density).toInt())
                            assertTrue((rgb and 255) - (rgb shr 16 and 255) > if (phase == "far") 80 else 40,
                                "Selected original row must keep its visible summary port lit")
                        } }
                    }
                    Thread.sleep(350)
                }
            } finally { edt { scene.close() } }
        }
        File(folder, "frame-names.tsv").writeText(samples.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }
    @Test fun mountedSummaryAndMembersShareLooksAndHitsAcrossTheBlendBand() {
        val pin = Pin("grant", "Permission", true, 110.0, PortWiring.Connected)
        val a = Card("roles", 40.0, 80.0, 420.0, 320.0, listOf(pin), 0)
        val b = a.copy(id = "checks", y = 440.0)
        val outside = a.copy(id = "operations", x = 1100.0, pins = listOf(pin.copy(provides = false)))
        val frame = Frame("access", "Access", 0.0, 0.0, 900.0, 800.0, setOf(a.id, b.id), emptyList(), false)
        val links = listOf(a, b).map { card -> Link(card.id, card.id, outside.id, pin.key, pin.key, LinkKind.Wire, listOf(card.id),
            listOf(card.x + card.width to card.y + pin.y, outside.x to outside.y + pin.y), family = "grant") }
        val full = BlueprintLayout("access", listOf(a, b, outside).associateBy { it.id }, listOf(frame), links, 1520.0, 800.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 })
        val band = levels.at(1.0).frames.getValue(frame.key)
        val zooms = listOf(.12, band.leaveZoom, (band.leaveZoom + band.enterZoom) / 2, band.enterZoom, 1.5, 4.0, 16.0).distinct().sorted()
        val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Live, effort = "xhigh", model = "sol 6.1", role = "designer")
        val folder = File("build/reports/blueprint-map-paint").apply { mkdirs() }
        val rows = mutableListOf("mode\tzoom\tblend\tpaintedCards\tmarkers\tboundaryPins")
        val drawCuts = mutableListOf("mode\tsourceZoom\tdrawZoom\tpaintedCards\texpectedCards")
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val canvas = GraphCanvasState(.002..16.0)
            val corridors = androidx.compose.runtime.mutableStateOf(true)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(36) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(4) } }
            edt {
                scene = ImageComposeScene(1100, 700, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val current = levels.at(canvas.zoom, bundles = corridors.value)
                        BlueprintCanvas(current.layout, canvas, emptySet(), null, mapLevels = levels, mapLevel = current,
                            cardLook = { card -> full.cards[card.id]?.let {
                                BlueprintCardLook(colors.source, SurfaceGraphSymbol, when (card.id) { "roles" -> "Roles"; "checks" -> "Checks"; else -> "Operations" },
                                    "Permission grants", pins = card.pins.map { BlueprintPinLook(colors.source, "grant: Permission", colors.textStrong, it.provides) },
                                    marks = listOf(BlueprintMark("Doing", colors.source)),
                                    agent = agent.takeIf { card.id == a.id }, tag = "map-${card.id}")
                            } },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 cards", marks = listOf(BlueprintMark("Protected", colors.source))) },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize(), onPinClick = { _, _ -> })
                    }
                }
            }
            try {
                settle()
                for ((index, zoom) in zooms.withIndex()) {
                    edt { canvas.restore(if (zoom > band.enterZoom) 80.0 - a.x * zoom else 70.0,
                        if (zoom > band.enterZoom) 100.0 - a.y * zoom else 90.0, zoom) }; settle()
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val paint = paintMap(levels, levels.at(zoom))
                        val looks = field(painter, "looks") as List<*>
                        val cards = (field(painter, "cards") as Array<*>).filterIsInstance<Card>()
                        assertEquals(paint.layout.cards.keys, cards.map { it.id }.toSet())
                        assertTrue(looks.all { it is BlueprintCardLook && it.title.isNotBlank() })
                        assertEquals(cards.size, looks.size)
                        val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        val tags = nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
                        val summary = cards.indexOfFirst { it.id == "summary:access" }
                        if (summary >= 0) {
                            val look = looks[summary] as BlueprintCardLook
                            assertEquals("Access", look.title)
                            assertEquals("2 nodes", look.subtitle)
                            assertEquals(listOf("Protected", "Doing"), look.marks.map { it.text })
                            assertEquals(agent, look.agent)
                            assertEquals(1, cards[summary].pins.size)
                            assertEquals("2 Permission", look.pins.single()?.text)
                            assertTrue(tags.contains("blueprint-summary:access"))
                            assertNotNull(painter.agentBounds(cards[summary].id, zoom))
                        }
                        cards.filter { it.id in full.cards }.forEach { assertEquals(full.cards[it.id], it) }
                        rows += listOf(mode.name, zoom, levels.at(zoom).frames.getValue(frame.key).blend, cards.size,
                            tags.count { it.startsWith("map-") || it.startsWith("blueprint-summary:") }, cards.getOrNull(summary)?.pins?.size ?: 0).joinToString("\t")
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { File(folder, "${mode.name.lowercase()}-$index.png").writeBytes(it.bytes) } }
                        if (zoom == 16.0) {
                            val moved = a.copy(x = a.x + 24.0)
                            val live = full.copy(cards = full.cards + (a.id to moved))
                            painter.sync(live, looks.filterIsInstance<BlueprintCardLook>(), emptyList(),
                                listOf(BlueprintFrameLook(SurfaceGraphSymbol, "2 cards")), links.map { Blueprint.link(Color.Blue, false, false) },
                                null, emptySet(), emptyMap(), levels = levels, current = levels.at(zoom))
                            assertSame(live, field(painter, "layout"))
                            assertEquals(moved, (field(painter, "cards") as Array<*>).filterIsInstance<Card>().single { it.id == a.id })
                        }
                    }
                }
                for (bundles in listOf(false, true)) {
                    edt { corridors.value = bundles; canvas.restore(70.0, 90.0, .12) }; settle()
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val painted = field(painter, "layout") as BlueprintLayout<*>
                        assertEquals(if (bundles) 1 else 2, painted.links.size)
                        assertEquals(links.flatMap { it.members }.toSet(), painted.links.flatMap { it.members }.toSet())
                    }
                }
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    val sourceLooks = full.cards.mapValues { (_, card) -> BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, card.id, "") }
                    val frameLook = BlueprintFrameLook(SurfaceGraphSymbol, "2 cards")
                    for ((sourceZoom, drawZoom) in listOf(16.0 to .12, .12 to 16.0)) {
                        val source = levels.at(sourceZoom)
                        painter.sync(full, full.cards.keys.map(sourceLooks::getValue), emptyList(), listOf(frameLook),
                            links.map { Blueprint.link(Color.Blue, false, false) }, null, emptySet(), emptyMap(),
                            levels = levels, current = source, fullLooks = sourceLooks,
                            fullFrames = mapOf(frame.key to frameLook), zoom = drawZoom)
                        val actual = (field(painter, "cards") as Array<*>).filterIsInstance<Card>()
                        val expected = paintMap(levels, levels.at(drawZoom, source.expanded))
                        assertEquals(expected.layout.cards.keys, actual.map { it.id }.toSet())
                        drawCuts += listOf(mode.name, sourceZoom, drawZoom, actual.size, expected.layout.cards.size).joinToString("\t")
                        if (drawZoom == 16.0) {
                            assertSame(full, field(painter, "layout"))
                            actual.forEach { assertSame(full.cards.getValue(it.id), it) }
                        }
                    }
                }
            } finally { edt { scene.close() }; File(folder, "ladder.tsv").writeText(rows.joinToString("\n") + "\n") }
        }
        File(folder, "draw-cut.tsv").writeText(drawCuts.joinToString("\n") + "\n")
    }
    @Test fun cardRowsCrossfadeAtTheirMeasuredThresholdInEitherDirection() {
        val pin = Pin("value", "Record", true, 110.0, PortWiring.Connected)
        val card = Card("schema", 0.0, 0.0, 420.0, 320.0, listOf(pin), 0)
        val full = BlueprintLayout("rows", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
        val folder = File("build/reports/blueprint-map-paint/card-tiers").apply { mkdirs() }
        val contrast = mutableListOf("mode\ttextToBodyContrast\tpaintedGlyphCoreContrast\treadablePixels\trowBlend\trowFontPixels")
        val titleContrast = mutableListOf("mode\tblend\ttitleContrast\tfullContrastGlyphPixels")
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var textColor = Color.Unspecified
            var bodyColor = Color.Unspecified
            var tick = 0L
            fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            edt {
                scene = ImageComposeScene(900, 700, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        textColor = colors.textStrong
                        bodyColor = lerp(colors.surface, colors.source, .06f)
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { BlueprintCardLook(colors.source, SurfaceGraphSymbol, "Schema", "Record fields",
                                pins = listOf(BlueprintPinLook(colors.source, "value: Record", colors.textStrong, true))) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(70.0, 90.0, 4.0) }; settle()
                val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                val initial = (field(painter, "paints") as Array<*>).single()!!
                val measure = field(initial, "measured")!!
                val threshold = 11.0 / (field(measure, "rows") as PaintDetail.Content).smallest
                val phases = listOf(.0, .25, .5, .75, 1.0)
                for ((direction, fractions) in listOf(phases, phases.asReversed()).withIndex()) {
                    for ((index, fraction) in fractions.withIndex()) {
                        val zoom = threshold * (1.0 + .12 * fraction)
                        edt { canvas.restore(70.0, 90.0, zoom) }; settle()
                        edt {
                            val paint = (field(painter, "paints") as Array<*>).single()!!
                            val blend = field(paint, "blend") as Float
                            assertEquals(fraction.toFloat(), blend, .02f)
                            assertEquals(fraction > 0, painter.rows(card.id, zoom))
                            assertEquals(card, (field(painter, "cards") as Array<*>).single())
                            assertEquals(1, canvas.reading()!![BlueprintTitle.Whole])
                            if (fraction < 1.0) {
                                val previous = assertNotNull(field(paint, "previous"))
                                assertEquals(PaintDetail.Tier.Title, (field(previous, "detail") as PaintDetail).tier)
                            }
                            if (direction == 0) scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                                File(folder, "${mode.name.lowercase()}-$index.png").writeBytes(it.bytes)
                                if (fraction == 1.0) {
                                    fun ratio(a: Color, b: Color): Double {
                                        val first = a.luminance().toDouble(); val second = b.luminance().toDouble()
                                        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
                                    }
                                    val tokens = ratio(textColor, bodyColor)
                                    assertTrue(tokens >= 4.5, "$mode token contrast $tokens")
                                    val pixels = ImageIO.read(ByteArrayInputStream(it.bytes))
                                    val sample = (70 + card.width * zoom / 2).toInt()
                                    val middle = (90 + pin.y * zoom).toInt()
                                    val background = Color(pixels.getRGB(sample, middle + (12 * zoom).toInt()))
                                    var core = 0.0; var readable = 0
                                    for (y in middle - (10 * zoom).toInt()..middle + (10 * zoom).toInt()) {
                                        for (x in (70 + 20 * zoom).toInt()..(70 + (card.width - 20) * zoom).toInt()) {
                                            val value = ratio(Color(pixels.getRGB(x, y)), background)
                                            core = maxOf(core, value)
                                            if (value >= 4.5) readable++
                                        }
                                    }
                                    assertTrue(readable > 8, "$mode has $readable readable row pixels")
                                    contrast += listOf(mode.name, tokens, core, readable, blend, 10.5 * zoom).joinToString("\t")
                                }
                            } }
                        }
                    }
                }
                val titleZoom = 2.0.pow(-.25) * 1.06
                edt { canvas.restore(70.0, 90.0, titleZoom) }; settle()
                edt {
                    val paint = (field(painter, "paints") as Array<*>).single()!!
                    val previous = assertNotNull(field(paint, "previous"))
                    assertEquals(PaintDetail.Tier.Title, (field(paint, "detail") as PaintDetail).tier)
                    assertEquals(PaintDetail.Tier.Title, (field(previous, "detail") as PaintDetail).tier)
                    val blend = field(paint, "blend") as Float
                    assertTrue(blend in .25f.. .75f)
                    scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                        val titleFolder = File(folder.parentFile, "title-band").apply { mkdirs() }
                        File(titleFolder, "${mode.name.lowercase()}.png").writeBytes(it.bytes)
                        val pixels = ImageIO.read(ByteArrayInputStream(it.bytes))
                        fun ratio(a: Color, b: Color): Double = (maxOf(a.luminance(), b.luminance()) + .05) / (minOf(a.luminance(), b.luminance()) + .05)
                        val background = Color(pixels.getRGB(90, 120))
                        val expected = ratio(textColor, background)
                        var core = 0
                        for (y in (90 + 100 * titleZoom).toInt()..(90 + 220 * titleZoom).toInt()) {
                            for (x in (70 + 30 * titleZoom).toInt()..(70 + 400 * titleZoom).toInt()) {
                                if (ratio(Color(pixels.getRGB(x, y)), background) >= expected * .9) core++
                            }
                        }
                        assertTrue(core > 8, "$mode title loses full-contrast glyphs in its size transition: $core")
                        titleContrast += listOf(mode.name, blend, expected, core).joinToString("\t")
                    } }
                }
            } finally { edt { scene.close() } }
        }
        File(folder, "contrast.tsv").writeText(contrast.joinToString("\n") + "\n")
        File(folder.parentFile, "title-band.tsv").writeText(titleContrast.joinToString("\n") + "\n")
    }
    @Test fun wrappedTitleCrossfadeKeepsOneReadableTitle() {
        val card = Card("message", 0.0, 0.0, 420.0, 320.0, emptyList(), 0)
        val full = BlueprintLayout("wrapped-title", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
        val folder = File("build/reports/blueprint-map-paint/wrapped-title").apply { mkdirs() }
        val samples = mutableListOf("mode\tzoom\tfromLines\ttoLines\tblend\tfullContrastPixels")
        val failures = mutableListOf<String>()
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var textColor = Color.Unspecified
            var tick = 0L
            fun settle() { repeat(20) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            edt {
                scene = ImageComposeScene(900, 700, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        textColor = colors.textStrong
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { BlueprintCardLook(colors.source, SurfaceGraphSymbol, "Finish an accepted message", "") },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                var found = false
                for (band in 0..20) {
                    val zoom = 2.0.pow(-band / 4.0) * 1.06
                    edt { canvas.restore(70.0, 90.0, zoom) }; settle()
                    val paint = (field(painter, "paints") as Array<*>).single()!!
                    val previous = field(paint, "previous") ?: continue
                    if ((field(paint, "detail") as PaintDetail).tier != PaintDetail.Tier.Title ||
                        (field(previous, "detail") as PaintDetail).tier != PaintDetail.Tier.Title) continue
                    val to = field(paint, "title") as? androidx.compose.ui.text.TextLayoutResult ?: continue
                    val from = field(previous, "title") as? androidx.compose.ui.text.TextLayoutResult ?: continue
                    if (to.lineCount == from.lineCount) continue
                    found = true
                    edt {
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                            File(folder, "${mode.name.lowercase()}.png").writeBytes(it.bytes)
                            val pixels = ImageIO.read(ByteArrayInputStream(it.bytes))
                            fun ratio(a: Color, b: Color): Double = (maxOf(a.luminance(), b.luminance()) + .05) / (minOf(a.luminance(), b.luminance()) + .05)
                            val background = Color(pixels.getRGB((70 + 20 * zoom).toInt(), (90 + 100 * zoom).toInt()))
                            val expected = ratio(textColor, background)
                            var core = 0
                            for (y in (90 + 65 * zoom).toInt()..(90 + 255 * zoom).toInt()) {
                                for (x in (70 + 45 * zoom).toInt()..(70 + 390 * zoom).toInt()) {
                                    if (ratio(Color(pixels.getRGB(x, y)), background) >= expected * .9) core++
                                }
                            }
                            samples += listOf(mode.name, zoom, from.lineCount, to.lineCount, field(paint, "blend"), core).joinToString("\t")
                            File(folder, "measurements.tsv").writeText(samples.joinToString("\n") + "\n")
                            if (core <= 8) failures += "$mode wrapped title has no full-contrast title during crossfade: $core"
                        } }
                    }
                    break
                }
                assertTrue(found, "$mode must exercise a title line-count transition")
            } finally { edt { scene.close() } }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }
    @Test fun aRoomyRegionTitleDoesNotFallIntoTheQuarterOctaveReadabilityGap() {
        val title = "Capture helpful findings and new questions beside our work graph while keeping every task, typed port and link visible."
        assertEquals(119, title.length)
        assertEquals(19, title.split(" ").size)
        val card = Card("summary", 0.0, 0.0, 24299.0, 14102.0, emptyList(), 0)
        val full = BlueprintLayout("readable-summary", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
        val folder = File("build/reports/blueprint-map-paint/readable-summary").apply { mkdirs() }
        val measurements = mutableListOf("mode\tdensity\tzoom\twidth\theight\ttier\ttextPx\tlines\toverflow\tdrawDensity\tlogicalTextPx\tphysicalTextPx\tphysicalWidth\tphysicalHeight")
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            edt {
                scene = ImageComposeScene((580 * density).toInt(), (820 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { BlueprintCardLook(colors.source, SurfaceGraphSymbol, title, "235 members", face = BlueprintFace.Region) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                val zoom = .007706353642630892
                edt { canvas.restore(26.0, 390.0, zoom) }; settle()
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    val paint = (field(painter, "paints") as Array<*>).single()!!
                    val detail = field(paint, "detail") as PaintDetail
                    val measured = field(paint, "measured")!!
                    val text = assertNotNull(field(paint, "title") as? androidx.compose.ui.text.TextLayoutResult)
                    assertEquals(PaintDetail.Tier.Title, detail.tier)
                    assertFalse(text.hasVisualOverflow)
                    assertTrue(detail.smallest >= 11.0)
                    assertEquals(1, canvas.reading()!![BlueprintTitle.Whole])
                    assertEquals(listOf(card), (field(painter, "cards") as Array<*>).toList())
                    val camera = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                        .first { type -> type.declaredFields.any { it.name == "camera" } }
                        .getDeclaredField("camera").let { it.isAccessible = true; it.get(painter) as dev.shibasis.composeflow.model.Viewport }
                    val drawDensity = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                        .first { type -> type.declaredFields.any { it.name == "density" } }
                        .getDeclaredField("density").let { it.isAccessible = true; it.getFloat(painter) }
                    assertEquals(density, drawDensity)
                    val contents = (field(measured, "contents") as List<*>).filterIsInstance<PaintDetail.Content>()
                    val logicalText = contents[detail.title].smallest * camera.zoom
                    val physicalText = logicalText * drawDensity
                    assertTrue(contents.any { content ->
                        content.complete && content.smallest * camera.zoom * drawDensity >= 11.0 * drawDensity
                    }, "$mode density=$drawDensity zoom=${camera.zoom} logicalText=$logicalText physicalText=$physicalText")
                    measurements += listOf(mode.name, density, zoom, card.width * zoom, card.height * zoom,
                        detail.tier, detail.smallest, text.lineCount, text.hasVisualOverflow, drawDensity,
                        logicalText, physicalText, card.width * camera.zoom * drawDensity, card.height * camera.zoom * drawDensity).joinToString("\t")
                    scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                        File(folder, "${mode.name.lowercase()}-${density.toInt()}x.png").writeBytes(it.bytes)
                    } }
                }
            } finally { edt { scene.close() } }
        }
        File(folder, "measurements.tsv").writeText(measurements.joinToString("\n") + "\n")
    }
    @Test fun candidateBannerBoundsDoNotDependOnTheLastPaintedLevel() {
        val pin = Pin("grant", "Permission", true, 110.0, PortWiring.Connected)
        val a = Card("roles", 40.0, 80.0, 420.0, 320.0, listOf(pin), 0)
        val b = a.copy(id = "checks", y = 440.0, pins = listOf(pin.copy(provides = false)))
        val frame = Frame("access", "Access", 0.0, 0.0, 900.0, 800.0, setOf(a.id, b.id), emptyList(), false)
        val link = Link("grant-wire", a.id, b.id, pin.key, pin.key, LinkKind.Wire, listOf("grant-wire"),
            listOf(a.x + a.width to a.y + pin.y, b.x to b.y + pin.y), family = "grant")
        val group = frame.copy(key = "roles-group", label = "Roles", x = 20.0, y = 60.0, width = 860.0, height = 720.0, parent = frame.key)
        val full = BlueprintLayout("bounds", listOf(a, b).associateBy { it.id }, listOf(frame.copy(nodes = emptySet()), group), listOf(link), 900.0, 800.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 }, { if (it.key == frame.key) null else "summary:${it.key}" })
        val samples = mutableListOf("mode\tphase\trequestedZoom\tdrawZoom\tpaintedCards\tx\ty\twidth\theight")
        val failures = mutableListOf<String>()
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            edt {
                scene = ImageComposeScene(900, 700, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(full, canvas, emptySet(), null, mapLevels = levels, mapLevel = levels.at(canvas.zoom),
                            cardLook = { BlueprintCardLook(colors.source, SurfaceGraphSymbol, it.id, "Permission grants",
                                pins = it.pins.map { p -> BlueprintPinLook(colors.source, "grant: Permission", colors.textStrong, p.provides) }) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 cards", marks = listOf(BlueprintMark("Protected", colors.source))) },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                val candidateZoom = .05
                var first: List<List<Double>>? = null
                for ((phase, drawZoom) in listOf("fit" to .12, "near" to 16.0, "return" to .12)) {
                    edt { canvas.restore(70.0, 90.0, drawZoom) }; settle()
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val before = canvas.viewport()
                        val painted = field(painter, "layout") as BlueprintLayout<*>
                        val requested = object : Bounds { override val x = 0.0; override val y = 0.0; override val width = full.width; override val height = full.height }
                        val retained = painter.stats
                        val result = painter.paintedBounds(requested, candidateZoom)
                        assertEquals(retained, painter.stats)
                        val legacy = painter.javaClass.superclass.declaredMethods.single { it.name == "bannerBounds" && it.parameterCount == 2 }.apply { isAccessible = true }
                        val current = (legacy.invoke(painter, requested, drawZoom) as List<*>).filterIsInstance<Bounds>()
                        assertEquals(current.map { listOf(it.x, it.y, it.width, it.height) }, painter.paintedBounds(requested, drawZoom).map { listOf(it.x, it.y, it.width, it.height) })
                        assertTrue(result.isNotEmpty())
                        val boxes = result.map { listOf(it.x, it.y, it.width, it.height) }
                        result.forEach { samples += listOf(mode.name, phase, candidateZoom, canvas.zoom, painted.cards.size, it.x, it.y, it.width, it.height).joinToString("\t") }
                        if (first == null) first = boxes else if (first != boxes) failures += "$mode $phase changes candidate banner bounds"
                        assertTrue(result.first().y < frame.y)
                        assertTrue(result.first().height * candidateZoom >= 29.0)
                        assertSame(painted, field(painter, "layout"))
                        assertEquals(before, canvas.viewport())
                        assertSame(full, levels.full)
                        assertEquals(listOf(a, b), full.cards.values.toList())
                        assertSame(pin, full.cards.getValue(a.id).pins.single())
                        assertSame(link, full.links.single())
                    }
                }
            } finally { edt { scene.close() } }
        }
        File("build/reports/blueprint-map-paint/candidate-bounds.tsv").apply { parentFile.mkdirs() }.writeText(samples.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test fun suppliedCodeFactsAppearBeforeTypedRowsWithoutMovingTheTitle() {
        val pins = listOf(Pin("event", "Event", false, 80.0, PortWiring.Connected), Pin("state", "PaneState", true, 104.0, PortWiring.Connected))
        val card = Card("pane", 0.0, 0.0, 340.0, 300.0, pins, 0)
        val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Assigned, effort = "xhigh", model = "sol 6.1", role = "designer")
        val full = BlueprintLayout("facts", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
        val folder = File("build/reports/blueprint-map-paint/supplied-facts").apply { mkdirs() }
        val rows = mutableListOf("mode\tdensity\tphase\tdrawZoom\ttier\tchangedFactPixels\tlogicalFloor\tphysicalFloor")
        val failures = mutableListOf<String>()
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val supplied = androidx.compose.runtime.mutableStateOf(false)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            fun awaitAgent() {
                val deadline = System.nanoTime() + 1_000_000_000L
                var ready = 0
                while (ready < 2 && System.nanoTime() < deadline) {
                    edt {
                        Snapshot.sendApplyNotifications()
                        scene.render(++tick * 16_666_667L).close()
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val view = canvas.viewport()
                        val expected = painter.agentBounds(card.id, view.zoom)
                        val actual = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                            .singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-agent-${card.id}" }?.boundsInRoot
                        val placed = expected?.let { androidx.compose.ui.geometry.Rect(
                            ((view.x + it.x * view.zoom) * density).toFloat(), ((view.y + it.y * view.zoom) * density).toFloat(),
                            ((view.x + (it.x + it.width) * view.zoom) * density).toFloat(),
                            ((view.y + (it.y + it.height) * view.zoom) * density).toFloat()) }
                        val matches = actual != null && placed != null && actual.width > 0f && actual.height > 0f &&
                            listOf(actual.left - placed.left, actual.top - placed.top, actual.right - placed.right, actual.bottom - placed.bottom)
                                .all { kotlin.math.abs(it) <= 2f * density }
                        ready = if (matches) ready + 1 else 0
                    }
                    if (ready < 2) Thread.sleep(8)
                }
                assertEquals(2, ready, "The actual assigned marker must reach its committed bounds before either raster is sampled")
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { BlueprintCardLook(colors.source, SurfaceGraphSymbol, "Pane state", if (supplied.value) "Data · PaneState" else "",
                                pins = listOf(BlueprintPinLook(colors.source, "event: Event", colors.textStrong, false),
                                    BlueprintPinLook(colors.source, "state: PaneState", colors.textStrong, true)),
                                marks = listOf(BlueprintMark("Doing", colors.source)), agent = agent) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(40.0, 60.0, 2.0) }; settle()
                val initialPainter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                val initialPaint = (field(initialPainter, "paints") as Array<*>).single()!!
                val rowThreshold = 11.0 / (field(field(initialPaint, "measured")!!, "rows") as PaintDetail.Content).smallest
                for ((phase, zoom) in listOf("middle" to 1.0, "blend" to rowThreshold * 1.06, "near" to 2.0)) {
                    edt { supplied.value = false; canvas.restore(40.0, 60.0, zoom) }; settle()
                    val before = canvas.viewport()
                    awaitAgent()
                    val control = raster()
                    var controlAgent: BlueprintBadgeBounds? = null
                    var controlTier: PaintDetail.Tier? = null
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val paint = (field(painter, "paints") as Array<*>).single()!!
                        val visible = if (field(paint, "blend") == 0f) field(paint, "previous") ?: paint else paint
                        controlTier = (field(visible, "detail") as PaintDetail).tier
                        controlAgent = painter.agentBounds(card.id, zoom)
                    }
                    edt { supplied.value = true }; settle(); awaitAgent()
                    val actual = raster()
                    ImageIO.write(actual, "png", File(folder, "$phase-${mode.name.lowercase()}-${density.toInt()}x.png"))
                    ImageIO.write(control, "png", File(folder, "$phase-control-${mode.name.lowercase()}-${density.toInt()}x.png"))
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val paint = (field(painter, "paints") as Array<*>).single()!!
                        val visible = if (field(paint, "blend") == 0f) field(paint, "previous") ?: paint else paint
                        val detail = field(visible, "detail") as PaintDetail
                        assertEquals(if (phase == "middle") PaintDetail.Tier.Title else PaintDetail.Tier.Rows, detail.tier)
                        assertEquals(1, canvas.reading()!![BlueprintTitle.Whole])
                        if (phase == "blend") {
                            assertTrue((field(paint, "blend") as Float) in .25f.. .75f)
                            val previous = assertNotNull(field(paint, "previous"))
                            assertEquals(PaintDetail.Tier.Title, (field(previous, "detail") as PaintDetail).tier)
                        }
                        val cameraField = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                            .first { it.declaredFields.any { field -> field.name == "camera" } }.getDeclaredField("camera").apply { isAccessible = true }
                        val camera = cameraField.get(painter) as dev.shibasis.composeflow.model.Viewport
                        val densityField = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                            .first { it.declaredFields.any { field -> field.name == "density" } }.getDeclaredField("density").apply { isAccessible = true }
                        val inheritedDensity = densityField.getFloat(painter)
                        assertEquals(density, inheritedDensity)
                        assertEquals(zoom, camera.zoom)
                        assertEquals(before, canvas.viewport())
                        assertSame(full, field(painter, "layout"))
                        assertEquals(listOf(card), (field(painter, "cards") as Array<*>).toList())
                        pins.forEachIndexed { index, pin -> assertSame(pin, full.cards.getValue(card.id).pins[index]) }
                        val look = (field(painter, "looks") as List<*>).single() as BlueprintCardLook
                        assertEquals(agent, look.agent)
                        assertEquals(listOf("Doing"), look.marks.map { it.text })
                        val agentBox = assertNotNull(painter.agentBounds(card.id, zoom))
                        File(folder, "$phase-agent-${mode.name.lowercase()}-${density.toInt()}x.tsv").writeText(
                            "phase\tcontrolTier\tactualTier\tcontrolBounds\tactualBounds\n$phase\t$controlTier\t${detail.tier}\t$controlAgent\t$agentBox\n")
                        assertEquals(controlTier, detail.tier, "Changing a supplied fact must retain the agent's committed tier")
                        assertEquals(controlAgent, agentBox, "Changing a supplied fact must retain the agent's measured bounds")
                        val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-agent-${card.id}" })
                        val measured = field(visible, "measured")!!
                        val title = field(visible, "title") as? androidx.compose.ui.text.TextLayoutResult
                        if (phase == "middle") {
                            assertNotNull(title)
                            assertFalse(title.hasVisualOverflow)
                            assertEquals("Pane state", title.layoutInput.text.text)
                        } else {
                            val content = assertNotNull(field(measured, "rows") as? PaintDetail.Content)
                            assertTrue(content.complete)
                            assertEquals(listOf("event", "state"), card.pins.map { it.key })
                        }
                        val logicalFloor = if (phase == "middle") MachineSignal.Type.body2.value * (field(measured, "unit") as Float) * camera.zoom
                            else (field(measured, "rows") as PaintDetail.Content).smallest * camera.zoom
                        val physicalFloor = logicalFloor * inheritedDensity
                        assertTrue(logicalFloor >= 11.0)
                        assertTrue(physicalFloor >= 11.0 * inheritedDensity)
                        val factRows = mutableSetOf<Int>()
                        fun count(left: Double, top: Double, right: Double, bottom: Double, track: Boolean = false): Int {
                            val x0 = ((camera.x + left * camera.zoom) * inheritedDensity).toInt().coerceIn(0, actual.width)
                            val y0 = ((camera.y + top * camera.zoom) * inheritedDensity).toInt().coerceIn(0, actual.height)
                            val x1 = ((camera.x + right * camera.zoom) * inheritedDensity).toInt().coerceIn(x0, actual.width)
                            val y1 = ((camera.y + bottom * camera.zoom) * inheritedDensity).toInt().coerceIn(y0, actual.height)
                            assertTrue(x1 > x0 && y1 > y0)
                            var changed = 0
                            for (y in y0 until y1) for (x in x0 until x1) if (actual.getRGB(x, y) != control.getRGB(x, y)) {
                                changed++
                                if (track) factRows += y
                            }
                            return changed
                        }
                        assertEquals(0, count(agentBox.x, agentBox.y, agentBox.x + agentBox.width, agentBox.y + agentBox.height),
                            "The assigned agent must remain painted identically while the fact changes")
                        val factTop = if (phase == "middle") card.height / 2 + assertNotNull(title).size.height / 2.0 else 28.0
                        val factBottom = if (phase == "near") 60.0 else card.height - 10.0
                        val changed = count(10.0, factTop, card.width - 10.0, factBottom, true)
                        if (phase == "blend" && factRows.isNotEmpty()) {
                            val styles = field(painter, "subtitleStyles") as Array<*>
                            val style = styles[0] as androidx.compose.ui.text.TextStyle
                            val unit = field(measured, "unit") as Float
                            val measurer = field(painter, "measurer") as androidx.compose.ui.text.TextMeasurer
                            val sample = measurer.measure("Data · PaneState", style.copy(
                                fontSize = style.fontSize * maxOf(unit, 1f), lineHeight = style.lineHeight * maxOf(unit, 1f)), maxLines = 1)
                            val oneLine = sample.size.height * zoom * inheritedDensity
                            assertTrue(factRows.max() - factRows.min() + 1 <= oneLine + 2 * inheritedDensity,
                                "The transition must paint one fact line, not two separated fading copies")
                        }
                        val nameTop = if (phase == "middle") card.height / 2 - assertNotNull(title).size.height / 2.0 else 0.0
                        val nameBottom = if (phase == "middle") card.height / 2 + assertNotNull(title).size.height / 2.0 else 26.0
                        assertEquals(0, count(0.0, nameTop, card.width, nameBottom), "The supplied fact must not move or repaint the title")
                        rows += listOf(mode.name, density, phase, camera.zoom, detail.tier, changed, logicalFloor, physicalFloor).joinToString("\t")
                        if (changed == 0) failures += "$mode density $density $phase: supplied fact has no painted pixels after complete readable setup"
                        if (phase == "near") assertEquals(0, count(0.0, 64.0, card.width, 120.0), "The original typed rows must remain identical")
                    }
                    control.flush(); actual.flush()
                }
            } finally { edt { scene.close() } }
        }
        File(folder, "facts.tsv").writeText(rows.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test fun collapsedSummaryKeepsMemberFactsAndOneWholeGroupName() {
        val pin = Pin("state", "PaneState", true, 110.0, PortWiring.Connected, id = "source-row")
        val a = Card("state", 40.0, 80.0, 420.0, 320.0, listOf(pin), 0)
        val b = a.copy(id = "events", y = 440.0, pins = listOf(pin.copy(id = "event-row")))
        val outside = a.copy(id = "operations", x = 1100.0, pins = listOf(pin.copy(provides = false, id = "target-row")))
        val frame = Frame("area", "Desktop", 0.0, 0.0, 900.0, 800.0, setOf(a.id, b.id), emptyList(), false)
        val links = listOf(a, b).map { card -> Link("wire:${card.id}", card.id, outside.id, pin.key, pin.key, LinkKind.Wire, listOf("wire:${card.id}"),
            listOf(card.x + card.width to card.y + pin.y, outside.x to outside.y + pin.y), family = "state") }
        val full = BlueprintLayout("member-facts", listOf(a, b, outside).associateBy { it.id }, listOf(frame), links, 1520.0, 800.0)
        val levels = BlueprintMapLevels.prepare(full, { 4.0 })
        val memberTones = mapOf(a.id to Color(0xff16a6aa), b.id to Color(0xff9a4ac8))
        val folder = File("build/reports/blueprint-map-paint/member-facts").apply { mkdirs() }
        val rows = mutableListOf("mode\tdensity\tzoom\tfullMembers\tnameLabels\tchangedMemberPixels\tlogicalFloor\tphysicalFloor")
        val failures = mutableListOf<String>()
        data class PaintedName(val id: String, val text: String, val bounds: androidx.compose.ui.geometry.Rect, val opacity: Float)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val renamed = androidx.compose.runtime.mutableStateOf(false)
            val hits = mutableListOf<Pair<Card, Pin>>()
            val shape = dev.shibasis.reaktor.graph.core.GraphShape(
                listOf(dev.shibasis.reaktor.graph.core.ScopeShape("area", "Area"),
                    dev.shibasis.reaktor.graph.core.ScopeShape("outside", "Outside")),
                full.cards.values.map { card -> dev.shibasis.reaktor.graph.core.NodeShape(card.id,
                    if (card.id in frame.nodes) frame.key else "outside", card.id, "PaneState",
                    dev.shibasis.reaktor.graph.core.NodeKind.Service,
                    ports = card.pins.map { dev.shibasis.reaktor.graph.core.PortShape(it.key, it.type, it.provides, it.wiring) }) },
                links.map { link -> dev.shibasis.reaktor.graph.core.WireShape(link.to, pin.key, link.from, pin.key, pin.type, sourceId = link.id) },
                emptyList())
            val editor = dev.shibasis.reaktor.blueprint.editor.GraphEditor(shape,
                dev.shibasis.reaktor.blueprint.editor.Placement(full.cards.mapValues { (_, card) ->
                    dev.shibasis.reaktor.blueprint.editor.Spot(card.x, card.y) }),
                pins = { node -> full.cards.getValue(node.id).pins })
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val level = levels.at(canvas.zoom)
                        BlueprintCanvas(level.layout, canvas, emptySet(), a.id, mapLevels = levels, mapLevel = level,
                            editor = editor, editable = false,
                            cardLook = { card -> full.cards[card.id]?.let {
                                BlueprintCardLook(memberTones[card.id] ?: colors.source, SurfaceGraphSymbol, when (card.id) {
                                    a.id -> if (renamed.value) "Screen state" else "Pane state"
                                    b.id -> "Recorded events"
                                    else -> "Operations"
                                }, if (card.id == b.id) "Event · Event" else "Data · PaneState",
                                    pins = card.pins.map { BlueprintPinLook(colors.source, "state: PaneState", colors.textStrong, it.provides) },
                                    selected = card.id == a.id)
                            } },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 nodes") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize(), onPinClick = { card, hit -> hits += card to hit })
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(70.0, 90.0, .5) }; settle()
                val before = canvas.viewport()
                val level = levels.at(.5)
                val group = level.frames.getValue(frame.key)
                val summary = assertNotNull(group.summary)
                assertEquals(setOf(a.id, b.id), group.members)
                assertTrue(summary.id in level.layout.cards)
                assertFalse(a.id in level.layout.cards)
                val boundary = level.forwardedPorts.entries.single { it.key.provides == true }
                assertEquals(setOf(a.id, b.id), boundary.value.map { it.card }.toSet())
                assertTrue(boundary.value.all { it.key == pin.key && it.provides == true })
                fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                fun paintedNames(name: String) = (field(painter(), name) as List<*>).filterNotNull().map { entry ->
                    val text = field(entry, "layout") as androidx.compose.ui.text.TextLayoutResult
                    val x = field(entry, "x") as Float; val y = field(entry, "y") as Float
                    PaintedName(field(entry, "id") as String, text.layoutInput.text.text,
                        androidx.compose.ui.geometry.Rect(x, y, x + text.size.width, y + text.size.height),
                        field(entry, "opacity") as Float)
                }
                edt { painter().javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter(), true) }
                settle()
                val control = raster()
                var originalMembers = emptyList<PaintedName>()
                var originalCaptions = emptyList<PaintedName>()
                var originalCaptionBounds = emptyList<androidx.compose.ui.geometry.Rect>()
                edt {
                    originalMembers = paintedNames("memberPaints")
                    originalCaptions = paintedNames("summaryPaints")
                    originalCaptionBounds = (field(painter(), "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                }
                edt { renamed.value = true }; settle()
                val actual = raster()
                edt {
                    val painter = painter()
                    val inherited = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                    val cameraField = inherited.first { it.declaredFields.any { field -> field.name == "camera" } }.getDeclaredField("camera").apply { isAccessible = true }
                    val camera = cameraField.get(painter) as dev.shibasis.composeflow.model.Viewport
                    val densityField = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                        .first { it.declaredFields.any { field -> field.name == "density" } }.getDeclaredField("density").apply { isAccessible = true }
                    val actualDensity = densityField.getFloat(painter)
                    assertEquals(before, camera)
                    assertEquals(density, actualDensity)
                    assertEquals(before, canvas.viewport())
                    for (member in listOf(a, b)) {
                        assertSame(member, full.cards.getValue(member.id))
                        val x = ((camera.x + member.x * camera.zoom) * actualDensity).toInt()
                        val y = ((camera.y + (member.y + member.height / 2) * camera.zoom) * actualDensity).toInt()
                        val center = ((camera.x + (member.x + member.width / 2) * camera.zoom) * actualDensity).toInt()
                        val surface = control.getRGB(center, y)
                        assertTrue((-2..2).any { dx -> listOf(16, 8, 0).any { shift ->
                            kotlin.math.abs(((control.getRGB(x + dx, y) ushr shift) and 255) - ((surface ushr shift) and 255)) >= 20
                        } }, "A far member retains an outline at its actual scaled rectangle edge")
                        for (dy in -actualDensity.toInt() until actualDensity.toInt()) for (dx in -2..2) {
                            assertEquals(control.getRGB(x + dx, y + dy), actual.getRGB(x + dx, y + dy),
                                "Changing a member name cannot erase the original miniature geometry")
                        }
                        assertEquals(surface, actual.getRGB(center, y))
                    }
                    val paint = paintMap(levels, level)
                    assertEquals(paint.layout.cards, (field(painter, "layout") as BlueprintLayout<*>).cards)
                    assertEquals(paint.layout.links.flatMap { it.members }.toSet(), links.flatMap { it.members }.toSet())
                    val looks = field(painter, "looks") as List<*>
                    val cards = (field(painter, "cards") as Array<*>).filterIsInstance<Card>()
                    val look = looks[cards.indexOfFirst { it.id == summary.id }] as BlueprintCardLook
                    assertEquals("Desktop", look.title)
                    assertEquals("2 nodes", look.subtitle)
                    assertTrue(painter.rows(summary.id, before.zoom))
                    val summaryIndex = cards.indexOfFirst { it.id == summary.id }
                    val paintedSummary = cards[summaryIndex]
                    val measuredPins = field(assertNotNull((field(painter, "paints") as Array<*>)[summaryIndex]), "measured")!!
                    val retainedPins = field(measuredPins, "pins") as List<*>
                    assertEquals(paintedSummary.pins.size, retainedPins.size)
                    retainedPins.forEachIndexed { k, value ->
                        val row = assertNotNull(value)
                        val text = field(row, "text") as androidx.compose.ui.text.TextLayoutResult
                        assertTrue(field(row, "complete") as Boolean)
                        assertFalse(text.hasVisualOverflow)
                        assertTrue(text.layoutInput.style.fontSize.value * before.zoom >= 11.0)
                        val height = field(row, "height") as Float
                        assertTrue(paintedSummary.pins[k].y >= height / 2 && paintedSummary.pins[k].y + height / 2 <= paintedSummary.height)
                    }
                    val labels = (field(painter, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                    assertEquals(1, labels.size, "The summary and frame share one whole group name")
                    val members = paintedNames("memberPaints")
                    val captions = paintedNames("summaryPaints")
                    assertEquals(originalCaptionBounds, labels)
                    assertEquals(originalCaptions, captions, "Renaming a member preserves the one whole group caption")
                    assertEquals(listOf("Desktop"), captions.map { it.text })
                    assertEquals(setOf(a.id, b.id), members.map { it.id }.toSet())
                    assertEquals(originalMembers.map { it.id }.toSet(), members.map { it.id }.toSet())
                    assertEquals("Pane state", originalMembers.single { it.id == a.id }.text)
                    assertEquals("Screen state", members.single { it.id == a.id }.text)
                    assertEquals(originalMembers.single { it.id == b.id }, members.single { it.id == b.id })
                    for ((memberNames, captionBounds) in listOf(originalMembers to originalCaptionBounds, members to labels)) {
                        memberNames.forEachIndexed { index, name ->
                            val member = full.cards.getValue(name.id)
                            val body = androidx.compose.ui.geometry.Rect(
                                (camera.x + member.x * camera.zoom).toFloat(), (camera.y + member.y * camera.zoom).toFloat(),
                                (camera.x + (member.x + member.width) * camera.zoom).toFloat(),
                                (camera.y + (member.y + member.height) * camera.zoom).toFloat())
                            assertTrue(name.bounds.left >= body.left && name.bounds.top >= body.top &&
                                name.bounds.right <= body.right && name.bounds.bottom <= body.bottom)
                            assertTrue(captionBounds.none { it.overlaps(name.bounds) }, "A member name cannot cover the group caption")
                            assertTrue(memberNames.drop(index + 1).none { it.bounds.overlaps(name.bounds) }, "Member names cannot cover each other")
                        }
                    }
                    for (box in labels) {
                        val left = kotlin.math.floor(box.left * actualDensity).toInt().coerceIn(0, actual.width)
                        val top = kotlin.math.floor(box.top * actualDensity).toInt().coerceIn(0, actual.height)
                        val right = kotlin.math.ceil(box.right * actualDensity).toInt().coerceIn(left, actual.width)
                        val bottom = kotlin.math.ceil(box.bottom * actualDensity).toInt().coerceIn(top, actual.height)
                        for (y in top until bottom) for (x in left until right) {
                            assertEquals(control.getRGB(x, y), actual.getRGB(x, y), "The whole group caption's pixels remain stable")
                        }
                    }
                    val allowedChanges = (originalMembers + members).filter { it.id == a.id }.map { name ->
                        androidx.compose.ui.geometry.Rect(
                            kotlin.math.floor(name.bounds.left * actualDensity),
                            kotlin.math.floor(name.bounds.top * actualDensity),
                            kotlin.math.ceil(name.bounds.right * actualDensity),
                            kotlin.math.ceil(name.bounds.bottom * actualDensity))
                    }
                    var changedOutsideName = 0
                    for (y in 0 until actual.height) for (x in 0 until actual.width) {
                        if (actual.getRGB(x, y) != control.getRGB(x, y) &&
                            allowedChanges.none { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) changedOutsideName++
                    }
                    assertEquals(0, changedOutsideName, "Only the renamed member's old/new measured glyph bounds may change")
                    val x0 = ((camera.x + summary.x * camera.zoom + 12) * actualDensity).toInt().coerceIn(0, actual.width)
                    val y0 = ((camera.y + summary.y * camera.zoom + 34) * actualDensity).toInt().coerceIn(0, actual.height)
                    val x1 = ((camera.x + (summary.x + summary.width) * camera.zoom - 12) * actualDensity).toInt().coerceIn(x0, actual.width)
                    val y1 = ((camera.y + (summary.y + summary.height) * camera.zoom - 12) * actualDensity).toInt().coerceIn(y0, actual.height)
                    assertTrue(x1 - x0 >= 200 * actualDensity && y1 - y0 >= 150 * actualDensity,
                        "The complete collapsed body must be visible before comparing its facts")
                    var changed = 0
                    for (y in y0 until y1) for (x in x0 until x1) if (actual.getRGB(x, y) != control.getRGB(x, y)) changed++
                    val logicalFloor = MachineSignal.Type.body2.value
                    val physicalFloor = logicalFloor * actualDensity
                    assertTrue(logicalFloor >= 11.0)
                    assertTrue(physicalFloor >= 11.0 * actualDensity)
                    rows += listOf(mode.name, density, camera.zoom, group.members.size, labels.size, changed, logicalFloor, physicalFloor).joinToString("\t")
                    if (changed == 0) failures += "$mode density $density: the real member's renamed glyphs were not painted"
                }
                ImageIO.write(control, "png", File(folder, "far-control-${mode.name.lowercase()}-${density.toInt()}x.png"))
                ImageIO.write(actual, "png", File(folder, "far-${mode.name.lowercase()}-${density.toInt()}x.png"))
                control.flush(); actual.flush()
                val summaryPin = level.layout.cards.getValue(boundary.key.card).pins.single { boundary.key.matches(summary.id, it) }
                edt {
                    val point = Offset(((before.x + (summary.x + summary.width - 10) * before.zoom) * density).toFloat(),
                        ((before.y + (summary.y + summaryPin.y) * before.zoom) * density).toFloat())
                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                }; settle()
                assertEquals(2, boundary.value.distinct().size)
                assertTrue(hits.isEmpty(), "An aggregate of two original pins must not choose an arbitrary raw callback")
                assertEquals(setOf("wire:state", "wire:events"), editor.selection.wires)
                assertEquals(links.flatMap { it.members }.toSet(), editor.selection.wires)
                assertTrue(editor.selection.nodes.isEmpty())
                assertTrue(editor.selection.notes.isEmpty())
                assertEquals(before, canvas.viewport())
                edt { canvas.restore(800.0 - (a.x + a.width) * 16.0, 350.0 - (a.y + pin.y) * 16.0, 16.0) }; settle()
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    assertSame(full, field(painter, "layout"))
                    val cards = (field(painter, "cards") as Array<*>).filterIsInstance<Card>()
                    cards.forEach { assertSame(full.cards.getValue(it.id), it) }
                    assertEquals(links, full.links)
                }
                val nearCamera = canvas.viewport()
                edt {
                    val point = Offset(((nearCamera.x + (a.x + a.width - 10) * nearCamera.zoom) * density).toFloat(),
                        ((nearCamera.y + (a.y + pin.y) * nearCamera.zoom) * density).toFloat())
                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                }; settle()
                assertEquals(1, hits.size)
                assertSame(a, hits.single().first)
                assertSame(pin, hits.single().second)
                assertEquals("source-row", hits.single().second.id)
                assertEquals("state", hits.single().second.key)
                assertEquals("PaneState", hits.single().second.type)
                assertTrue(hits.single().second.provides)
                assertEquals(nearCamera, canvas.viewport())
                val near = raster()
                ImageIO.write(near, "png", File(folder, "near-${mode.name.lowercase()}-${density.toInt()}x.png")); near.flush()
                edt { canvas.restore(before.x, before.y, before.zoom) }; settle()
                assertEquals(before, canvas.viewport())
                val returned = raster()
                ImageIO.write(returned, "png", File(folder, "return-${mode.name.lowercase()}-${density.toInt()}x.png")); returned.flush()
            } finally { edt { scene.close() } }
        }
        File(folder, "members.tsv").writeText(rows.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test fun onlyTheOwningFrontierNamesARegionWithoutCoveringItsMeasuredContent() {
        val title = "Capture helpful findings and new questions beside our work graph while keeping every task, typed port and link visible."
        assertEquals(119, title.length)
        assertEquals(19, title.split(" ").size)
        val card = Card("summary", 2500.0, 100.0, 24266.0, 14708.5, emptyList(), 0)
        val outer = Frame("outer", "Areas", 0.0, 0.0, 26866.0, 14908.5, emptySet(), emptyList(), false)
        val inner = outer.copy(key = "inner", label = "Capture notes and task groups", nodes = setOf(card.id), parent = outer.key)
        val full = BlueprintLayout("label-occupancy", mapOf(card.id to card), listOf(outer, inner), emptyList(), outer.width, outer.height)
        val folder = File("build/reports/blueprint-map-paint/frame-occupancy").apply { mkdirs() }
        val samples = mutableListOf("mode\tdensity\twidth\tzoom\tkind\tordinal\tleft\ttop\tright\tbottom")
        val failures = mutableListOf<String>()
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            for (width in listOf(580, 780)) {
                val canvas = GraphCanvasState(.002..16.0)
                lateinit var scene: ImageComposeScene
                var tick = 0L
                fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
                edt {
                    scene = ImageComposeScene((width * density).toInt(), (820 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                        MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                            val colors = MachineSignal.colors
                            BlueprintCanvas(full, canvas, emptySet(), null,
                                cardLook = { BlueprintCardLook(colors.source, SurfaceGraphSymbol, title, "235 members", face = BlueprintFace.Region) },
                                frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "235 members", marks = listOf(BlueprintMark("Protected", colors.source))) },
                                linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                                modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                try {
                    settle()
                    val zoom = if (width == 580) .00771037619775549 else .014719809104805933
                    val x = if (width == 580) 6.6000000000000085 else 12.600000000000023
                    val y = if (width == 580) 392.5095077698265 else 340.2454231439352
                    edt { canvas.restore(x, y, zoom) }; settle()
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val cameraBefore = canvas.viewport()
                        val camera = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                            .first { type -> type.declaredFields.any { it.name == "camera" } }
                            .getDeclaredField("camera").let { it.isAccessible = true; it.get(painter) as dev.shibasis.composeflow.model.Viewport }
                        val drawDensity = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                            .first { type -> type.declaredFields.any { it.name == "density" } }
                            .getDeclaredField("density").let { it.isAccessible = true; it.getFloat(painter) }
                        assertEquals(density, drawDensity)
                        assertEquals(zoom, camera.zoom)
                        assertEquals(x, camera.x)
                        assertEquals(y, camera.y)
                        val paint = (field(painter, "paints") as Array<*>).single()!!
                        val look = (field(painter, "looks") as List<*>).single()!!
                        val detail = field(paint, "detail") as PaintDetail
                        assertTrue(detail.tier != PaintDetail.Tier.Summary)
                        assertTrue(detail.smallest >= 11.0)
                        assertTrue(detail.smallest * drawDensity >= 11.0 * drawDensity)
                        val titleMethod = painter.javaClass.declaredMethods.single { it.name == "cardTitle" && it.parameterCount == 5 }.apply { isAccessible = true }
                        fun measuredTitle(value: Any) = assertNotNull(titleMethod.invoke(painter, card, look, value, card.x.toFloat(), card.y.toFloat()) as? Pair<*, *>)
                        fun project(box: androidx.compose.ui.geometry.Rect) = androidx.compose.ui.geometry.Rect(
                            (camera.x + box.left * camera.zoom).toFloat(), (camera.y + box.top * camera.zoom).toFloat(),
                            (camera.x + box.right * camera.zoom).toFloat(), (camera.y + box.bottom * camera.zoom).toFloat())
                        val titleTo = measuredTitle(paint)
                        val titleText = titleTo.first as androidx.compose.ui.text.TextLayoutResult
                        assertFalse(titleText.hasVisualOverflow)
                        val to = titleTo.second as androidx.compose.ui.geometry.Rect
                        val previous = field(paint, "previous")
                        val from = previous?.let { measuredTitle(it).second as androidx.compose.ui.geometry.Rect } ?: to
                        val blend = field(paint, "blend") as Float
                        fun mix(a: Float, b: Float) = a + (b - a) * blend
                        val titleBox = project(androidx.compose.ui.geometry.Rect(mix(from.left, to.left), mix(from.top, to.top), mix(from.right, to.right), mix(from.bottom, to.bottom)))
                        val textBoxes = mutableListOf("title" to titleBox)
                        for ((value, alpha) in listOf(previous to (1f - blend), paint to if (previous == null) 1f else blend)) {
                            if (value == null || alpha <= 0f || (field(value, "detail") as PaintDetail).tier != PaintDetail.Tier.Rows) continue
                            val region = field(field(value, "measured")!!, "region") ?: continue
                            for (text in field(region, "texts") as List<*>) {
                                text ?: continue
                                if (field(text, "title") as Boolean) continue
                                val layout = field(text, "text") as androidx.compose.ui.text.TextLayoutResult
                                val tx = card.x.toFloat() + (field(text, "x") as Float)
                                val ty = card.y.toFloat() + (field(text, "y") as Float)
                                textBoxes += "count" to project(androidx.compose.ui.geometry.Rect(tx, ty, tx + layout.size.width, ty + layout.size.height))
                            }
                        }
                        val labels = (field(painter, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                        assertEquals(1, labels.size, "An empty ancestor must not add a phantom header")
                        val shownRaw = (field(painter, "cards") as Array<*>).filterIsInstance<Card>().map { it.id }.toSet()
                        val owning = full.frames.filter { frame -> frame.nodes.any(shownRaw::contains) }
                        assertEquals(listOf(inner.key), owning.map { it.key }, "The only caption belongs to the visible direct owner")
                        labels.forEachIndexed { i, label ->
                            if (textBoxes.any { it.second.overlaps(label) }) failures += "$mode density=$density width=$width frame=$i covers measured title/count"
                            assertFalse(labels.take(i).any { it.overlaps(label) })
                        }
                        val frameBoxes = full.frames.map { frame -> project(androidx.compose.ui.geometry.Rect(frame.x.toFloat(), frame.y.toFloat(), (frame.x + frame.width).toFloat(), (frame.y + frame.height).toFloat())) }
                        for ((kind, boxes) in listOf("label" to labels, "frame" to frameBoxes, "card" to listOf(project(androidx.compose.ui.geometry.Rect(card.x.toFloat(), card.y.toFloat(), (card.x + card.width).toFloat(), (card.y + card.height).toFloat()))))) {
                            boxes.forEachIndexed { i, box -> samples += listOf(mode.name, density, width, camera.zoom, kind, i, box.left, box.top, box.right, box.bottom).joinToString("\t") }
                        }
                        textBoxes.forEachIndexed { i, (kind, box) -> samples += listOf(mode.name, density, width, camera.zoom, kind, i, box.left, box.top, box.right, box.bottom).joinToString("\t") }
                        assertSame(full, field(painter, "layout"))
                        assertEquals(listOf(card), (field(painter, "cards") as Array<*>).toList())
                        assertEquals(listOf(outer, inner), full.frames)
                        assertEquals(emptyList(), full.links)
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                            File(folder, "${mode.name.lowercase()}-${density.toInt()}x-$width.png").writeBytes(it.bytes)
                        } }
                        assertEquals(cameraBefore, canvas.viewport())
                    }
                } finally { edt { scene.close() } }
            }
        }
        File(folder, "rectangles.tsv").writeText(samples.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test fun farSummaryKeepsFactsAccessibleAndPaintsOnlyOneBoundedName() {
        val pin = Pin("grant", "Permission", true, 110.0, PortWiring.Connected)
        val a = Card("roles", 40.0, 80.0, 420.0, 320.0, listOf(pin), 0)
        val b = a.copy(id = "checks", y = 440.0)
        val frame = Frame("access", "Access", 0.0, 0.0, 900.0, 800.0, setOf(a.id, b.id), emptyList(), false)
        val wire = Link("access-wire", a.id, b.id, pin.key, pin.key, LinkKind.Wire, listOf("access-wire"),
            listOf(460.0 to 190.0, 600.0 to 190.0, 600.0 to 550.0, 40.0 to 550.0))
        val full = BlueprintLayout("summary-facts", listOf(a, b).associateBy { it.id }, listOf(frame), listOf(wire), 900.0, 800.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 })
        val folder = File("build/reports/blueprint-map-paint/summary-semantics").apply { mkdirs() }
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val fact = androidx.compose.runtime.mutableStateOf("2 stages · Pending")
            lateinit var scene: ImageComposeScene
            var surface = Color.Transparent
            var tick = 0L
            fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        surface = colors.surface
                        val current = levels.at(canvas.zoom)
                        BlueprintCanvas(current.layout, canvas, emptySet(), null, mapLevels = levels, mapLevel = current,
                            cardLook = { card -> full.cards[card.id]?.let {
                                BlueprintCardLook(colors.source, SurfaceGraphSymbol, if (card.id == a.id) "Roles" else "Checks", "Permission grants",
                                    pins = listOf(BlueprintPinLook(colors.source, "grant: Permission", colors.textStrong, true)))
                            } },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, fact.value, marks = listOf(BlueprintMark("Protected", colors.source))) },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle(); edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    painter.javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter, true)
                    canvas.restore(70.0, 90.0, .06)
                }; settle()
                val viewport = canvas.viewport()
                val before = raster()
                edt { fact.value = "2 stages · Running" }; settle()
                val after = raster()
                ImageIO.write(before, "png", File(folder, "before-${mode.name.lowercase()}-${density.toInt()}x.png"))
                ImageIO.write(after, "png", File(folder, "${mode.name.lowercase()}-${density.toInt()}x.png"))
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    val cards = (field(painter, "cards") as Array<*>).filterIsInstance<Card>()
                    val summary = cards.indexOfFirst { it.id == "summary:access" }
                    assertTrue(summary >= 0)
                    val card = cards[summary]
                    assertFalse(painter.rows(card.id, canvas.zoom), "The actual mounted CardMarker must take its far branch")
                    val look = (field(painter, "looks") as List<*>)[summary] as BlueprintCardLook
                    assertEquals("Access", look.title)
                    assertEquals("2 nodes", look.subtitle)
                    assertEquals(listOf("Protected"), look.marks.map { it.text })
                    assertEquals(listOf(frame.x, frame.y, frame.width, frame.height), listOf(card.x, card.y, card.width, card.height))
                    assertEquals(setOf(a.id, b.id), levels.at(canvas.zoom).frames.getValue(frame.key).members)
                    val miniatureWires = (field(painter, "summaryWires") as Map<*, *>)[card.id] as List<*>
                    assertSame(wire, (miniatureWires.single() as Pair<*, *>).first, "Collapsed structure retains the exact raw trace")
                    val wireX = ((viewport.x + 600.0 * viewport.zoom) * density).toInt()
                    val wireY = ((viewport.y + 370.0 * viewport.zoom) * density).toInt()
                    val background = after.getRGB(wireX + (4 * density).toInt(), wireY)
                    assertTrue((wireY - 2..wireY + 2).any { y -> (wireX - 2..wireX + 2).any { x ->
                        val ink = after.getRGB(x, y)
                        listOf(16, 8, 0).any { shift -> kotlin.math.abs(((ink ushr shift) and 255) - ((background ushr shift) and 255)) >= 24 }
                    } }, "A collapsed region must paint its internal raw wire at its actual position")
                    val texts = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                    assertEquals(1, texts.count { it == "2 nodes" }, "The mounted far summary exposes its one producer fact")
                    assertTrue(texts.none { it == fact.value }, "Rejected status prose must not become another summary line")
                    val labels = (field(painter, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                    assertTrue(labels.isNotEmpty(), "A complete visible summary caption is required before raster comparison")
                    val emitted = (field(painter, "summaryPaints") as List<*>).filterNotNull().filter { field(it, "id") == card.id }
                    assertEquals(1, emitted.count { (field(it, "layout") as androidx.compose.ui.text.TextLayoutResult).layoutInput.text.text == look.title })
                    assertEquals(1, emitted.size, "The canvas keeps only one name; counts and diagnostics remain in semantics")
                    val receipt = emitted.single()
                    val text = field(receipt, "layout") as androidx.compose.ui.text.TextLayoutResult
                    val x = field(receipt, "x") as Float; val y = field(receipt, "y") as Float
                    assertEquals(1f, field(receipt, "opacity"))
                    assertFalse(text.hasVisualOverflow)
                    assertEquals(1, text.lineCount)
                    assertTrue(text.size.width <= 320)
                    assertEquals(text.layoutInput.text.length, text.getLineEnd(text.lineCount - 1))
                    assertTrue(text.layoutInput.style.fontSize.value >= 11f)
                    assertTrue(text.layoutInput.style.fontSize.value * density >= 11f * density)
                    assertTrue(x >= 0f && y >= 0f && x + text.size.width <= 1100f && y + text.size.height <= 700f)
                    val ox = kotlin.math.floor(x * density).toInt(); val oy = kotlin.math.floor(y * density).toInt()
                    val mask = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(text.size.width * density).toInt() + 4,
                        kotlin.math.ceil(text.size.height * density).toInt() + 4)
                    val drawing = androidx.compose.ui.graphics.Canvas(mask)
                    drawing.translate(2f + x * density - ox, 2f + y * density - oy)
                    drawing.scale(density, density)
                    text.multiParagraph.paint(drawing, Color.White)
                    val pixels = mask.toPixelMap()
                    val ink = text.layoutInput.style.color.toArgb(); val base = surface.toArgb()
                    fun channel(value: Int, shift: Int) = (value ushr shift) and 255
                    for (offset in text.layoutInput.text.indices.filter { !text.layoutInput.text[it].isWhitespace() }) {
                        val bounds = text.getBoundingBox(offset)
                        val left = kotlin.math.floor(2 + x * density - ox + bounds.left * density).toInt().coerceAtLeast(0)
                        val top = kotlin.math.floor(2 + y * density - oy + bounds.top * density).toInt().coerceAtLeast(0)
                        val right = kotlin.math.ceil(2 + x * density - ox + bounds.right * density).toInt().coerceAtMost(mask.width)
                        val bottom = kotlin.math.ceil(2 + y * density - oy + bounds.bottom * density).toInt().coerceAtMost(mask.height)
                        var expected = 0; var supported = 0
                        for (py in top until bottom) for (px in left until right) if (pixels[px, py].alpha >= .65f) {
                            expected++
                            val actual = after.getRGB(ox + px - 2, oy + py - 2)
                            if (listOf(16, 8, 0).all { shift ->
                                val prediction = glyphChannel(channel(ink, shift), channel(base, shift), pixels[px, py].alpha)
                                kotlin.math.abs(channel(actual, shift) - prediction) <= 24f
                            }) supported++
                        }
                        assertTrue(expected > 0, "Every retained fact glyph must have rasterizable ink")
                        if (supported < expected * .7) {
                            val retained = java.awt.image.BufferedImage(mask.width, mask.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                            for (py in 0 until mask.height) for (px in 0 until mask.width)
                                retained.setRGB(px, py, ((pixels[px, py].alpha * 255).toInt() shl 24) or 0x00ffffff)
                            ImageIO.write(retained, "png", File(folder, "glyph-mask-${mode.name.lowercase()}-${density.toInt()}x.png"))
                            File(folder, "glyph-failure-${mode.name.lowercase()}-${density.toInt()}x.tsv").writeText(
                                "offset\texpected\tsupported\tx\ty\tox\toy\tink\tbase\n$offset\t$expected\t$supported\t$x\t$y\t$ox\t$oy\t$ink\t$base\n")
                            retained.flush()
                        }
                        assertTrue(supported >= expected * .7, "Every emitted fact glyph must have matching scene ink: offset=$offset expected=$expected supported=$supported")
                    }
                    var changedFact = 0
                    for (py in oy until kotlin.math.ceil((y + text.size.height) * density).toInt())
                        for (px in ox until kotlin.math.ceil((x + text.size.width) * density).toInt())
                            if (before.getRGB(px, py) != after.getRGB(px, py)) changedFact++
                    assertEquals(0, changedFact, "Rejected status prose must leave the one count fact unchanged")
                    var changed = 0
                    for (y in 0 until after.height) for (x in 0 until after.width) {
                        if (before.getRGB(x, y) != after.getRGB(x, y)) changed++
                    }
                    assertEquals(0, changed, "Rejected status prose must not repaint the collapsed summary")
                    assertSame(full.cards.getValue(a.id), a)
                    assertSame(pin, full.cards.getValue(a.id).pins.single())
                    assertEquals(viewport, canvas.viewport())
                }
                before.flush(); after.flush()
            } finally { edt { scene.close() } }
        }
    }


    @Test fun measuredOverlayKeepsPinnedGroupNameWithoutMovingTheMap() {
        val pin = Pin("state", "PaneState", true, 110.0, PortWiring.Connected, id = "source-row")
        val a = Card("state", 40.0, 80.0, 420.0, 250.0, listOf(pin), 0)
        val b = a.copy(id = "events", y = 390.0, pins = listOf(pin.copy(provides = false, id = "target-row")))
        val wire = Link("wire:state", a.id, b.id, pin.key, pin.key, LinkKind.Wire, listOf("wire:state"),
            listOf(a.x + a.width to a.y + pin.y, b.x to b.y + pin.y), family = "state")
        val frame = Frame("desktop", "Desktop", 0.0, 0.0, 900.0, 800.0, setOf(a.id, b.id), emptyList(), false)
        val full = BlueprintLayout("label-overlay", listOf(a, b).associateBy { it.id }, listOf(frame), listOf(wire), 900.0, 800.0)
        val levels = BlueprintMapLevels.prepare(full, { 4.0 })
        val folder = File("build/reports/blueprint-map-paint/label-overlay").apply { mkdirs() }
        val observations = arrayListOf("mode\tdensity\tcontrolOverlap\tcandidateOverlap\tchangedLabelPixels")
        val failures = ArrayList<String>()
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val overlay = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
            val measured = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
            val reserve = androidx.compose.runtime.mutableStateOf(false)
            val hits = ArrayList<Pair<Card, Pin>>()
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            fun labels(): List<androidx.compose.ui.geometry.Rect> {
                val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                return (field(painter, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>().toList()
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val current = levels.at(canvas.zoom)
                        Box(Modifier.fillMaxSize()) {
                            BlueprintGraph(current.layout, canvas, emptySet(), a.id, mapLevels = levels, mapLevel = current,
                                initialFit = {}, labelExclusions = if (reserve.value) listOfNotNull(measured.value) else emptyList(),
                                cardLook = { card -> full.cards[card.id]?.let { BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                    if (card.id == a.id) "Pane state" else "Recorded events", "Declared state",
                                    pins = card.pins.map { BlueprintPinLook(colors.source, "state: PaneState", colors.textStrong, it.provides) }) } },
                                frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 nodes") },
                                linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                                onPinClick = { card, hit -> hits += card to hit }, modifier = Modifier.fillMaxSize())
                            overlay.value?.let { at ->
                                Box(Modifier.absoluteOffset(at.left.dp, at.top.dp).requiredSize(at.width.dp, at.height.dp)
                                    .background(colors.surface).onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInRoot()
                                        val next = androidx.compose.ui.geometry.Rect(bounds.left / density, bounds.top / density, bounds.right / density, bounds.bottom / density)
                                        if (measured.value != next) measured.value = next
                                    }) { androidx.compose.material3.Text("Source", color = colors.textStrong, fontSize = MachineSignal.Type.body2) }
                            }
                        }
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(70.0, 90.0, .5) }; settle()
                val before = canvas.viewport()
                lateinit var unoccluded: List<androidx.compose.ui.geometry.Rect>
                edt {
                    unoccluded = labels()
                    assertEquals(1, unoccluded.size, "The full group caption and useful member body must be mounted first")
                    val box = unoccluded.single()
                    assertTrue(box.width > 0 && box.height > 0)
                    val height = MachineSignal.Editor.controlIconSize.value + MachineSignal.Space.s2.value * 2
                    overlay.value = androidx.compose.ui.geometry.Rect(box.left, box.top, box.right, box.top + height)
                }
                settle()
                val measuredBox = assertNotNull(measured.value)
                assertTrue(measuredBox.width > 0 && measuredBox.height > 0)
                val control = raster()
                edt {
                    assertEquals(unoccluded, labels())
                    assertTrue(labels().single().overlaps(measuredBox), "The actual measured Compose overlay must cover the painted group label before testing its exclusion")
                    assertEquals(before, canvas.viewport())
                    reserve.value = true
                }
                settle()
                val candidate = raster()
                ImageIO.write(control, "png", File(folder, "control-${mode.name.lowercase()}-${density.toInt()}x.png"))
                ImageIO.write(candidate, "png", File(folder, "candidate-${mode.name.lowercase()}-${density.toInt()}x.png"))
                edt {
                    val placed = labels()
                    assertEquals(1, placed.size, "The group name must remain painted")
                    val box = placed.single()
                    val overlap = box.overlaps(measuredBox)
                    val left = (box.left * density).toInt().coerceIn(0, candidate.width)
                    val top = (box.top * density).toInt().coerceIn(0, candidate.height)
                    val right = (box.right * density).toInt().coerceIn(left, candidate.width)
                    val bottom = (box.bottom * density).toInt().coerceIn(top, candidate.height)
                    assertTrue(right > left && bottom > top)
                    var changed = 0
                    for (y in top until bottom) for (x in left until right) if (control.getRGB(x, y) != candidate.getRGB(x, y)) changed++
                    assertTrue(changed > 0, "The name must paint at its new location, not merely update a rectangle")
                    assertEquals(before, canvas.viewport())
                    assertEquals(setOf(a.id, b.id), levels.at(canvas.zoom).frames.getValue(frame.key).members)
                    assertSame(a, full.cards.getValue(a.id)); assertSame(b, full.cards.getValue(b.id)); assertSame(wire, full.links.single())
                    observations += "${mode.name}\t${density.toInt()}\ttrue\t$overlap\t$changed"
                    if (overlap) failures += "${mode.name}/${density.toInt()}: the group caption still overlaps the measured overlay"
                    reserve.value = false
                }
                control.flush(); candidate.flush(); settle()
                edt { assertEquals(unoccluded, labels()); assertEquals(before, canvas.viewport()) }
                edt { canvas.restore(800.0 - (a.x + a.width) * 16.0, 350.0 - (a.y + pin.y) * 16.0, 16.0) }; settle()
                edt {
                    val point = Offset(800f * density, 350f * density)
                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                }; settle()
                assertEquals(1, hits.size)
                assertSame(a, hits.single().first)
                assertEquals(pin.key, hits.single().second.key)
                assertEquals(true, hits.single().second.provides)
            } finally { edt { scene.close() } }
        }
        File(folder, "observations.tsv").writeText(observations.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test fun distinctSummaryFactsKeepOnePaintedGroupName() {
        val a = Card("prepare", 40.0, 80.0, 420.0, 250.0, emptyList(), 0)
        val b = a.copy(id = "deliver", y = 390.0)
        val frame = Frame("pipeline", "Pipeline", 0.0, 0.0, 900.0, 800.0, setOf(a.id, b.id), emptyList(), false)
        val full = BlueprintLayout("distinct-summary-facts", listOf(a, b).associateBy { it.id }, listOf(frame), emptyList(), 900.0, 800.0)
        val levels = BlueprintMapLevels.prepare(full, { 4.0 })
        val folder = File("build/reports/blueprint-map-paint/distinct-summary-facts").apply { mkdirs() }
        val failures = ArrayList<String>()
        val observations = arrayListOf("mode\tdensity\treservations\tchangedBodyPixels")
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val fact = androidx.compose.runtime.mutableStateOf("2 stages · 1 dependency · 0 completed")
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val current = levels.at(canvas.zoom)
                        BlueprintCanvas(current.layout, canvas, emptySet(), null, mapLevels = levels, mapLevel = current,
                            cardLook = { card ->
                                if (card.id == "summary:pipeline") BlueprintCardLook(colors.source, SurfaceGraphSymbol, frame.label, fact.value)
                                else full.cards[card.id]?.let { BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                    if (card.id == a.id) "Prepare" else "Deliver", "Durable stage") }
                            },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 stages · definition") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(70.0, 90.0, .5) }; settle()
                val viewport = canvas.viewport()
                val before = raster()
                edt { fact.value = "2 stages · 1 dependency · 2 completed" }; settle()
                val after = raster()
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    val cards = (field(painter, "cards") as Array<*>).filterIsInstance<Card>()
                    val index = cards.indexOfFirst { it.id == "summary:pipeline" }
                    assertTrue(index >= 0, "The actual mounted summary is required")
                    assertEquals(listOf("summary:pipeline"), cards.map { it.id }, "Only the collapsed group may occupy this specimen")
                    val card = cards[index]
                    val look = (field(painter, "looks") as List<*>)[index] as BlueprintCardLook
                    assertEquals(frame.label, look.title)
                    assertEquals("2 nodes", look.subtitle)
                    assertEquals(setOf(a.id, b.id), levels.at(canvas.zoom).frames.getValue(frame.key).members)
                    assertEquals(listOf(frame.x, frame.y, frame.width, frame.height), listOf(card.x, card.y, card.width, card.height))
                    val labels = (field(painter, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                    assertTrue(labels.isNotEmpty(), "Missing captions are not a duplicate-heading reproduction")
                    val left = ((viewport.x + card.x * viewport.zoom) * density).toInt().coerceAtLeast(0)
                    val top = ((viewport.y + card.y * viewport.zoom) * density).toInt().coerceAtLeast(0)
                    val right = ((viewport.x + (card.x + card.width) * viewport.zoom) * density).toInt().coerceAtMost(after.width)
                    val bottom = ((viewport.y + (card.y + card.height) * viewport.zoom) * density).toInt().coerceAtMost(after.height)
                    assertTrue(right > left && bottom > top)
                    var changed = 0
                    for (y in top until bottom) for (x in left until right) if (before.getRGB(x, y) != after.getRGB(x, y)) changed++
                    assertEquals(0, changed, "A second supplied fact must not reintroduce a summary wall")
                    assertEquals(viewport, canvas.viewport())
                    assertSame(a, full.cards.getValue(a.id))
                    assertSame(b, full.cards.getValue(b.id))
                    observations += "${mode.name}\t${density.toInt()}\t${labels.size}\t$changed"
                    if (labels.size != 1) failures += "${mode.name}/${density.toInt()}: ${labels.size} reservations repeat the same group heading"
                }
                ImageIO.write(before, "png", File(folder, "before-fact-${mode.name.lowercase()}-${density.toInt()}x.png"))
                ImageIO.write(after, "png", File(folder, "${mode.name.lowercase()}-${density.toInt()}x.png"))
                before.flush(); after.flush()
            } finally { edt { scene.close() } }
        }
        File(folder, "observations.tsv").writeText(observations.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test fun wholeGroupNameStaysBoundedAcrossZoomAndMeasuredOverlay() {
        val title = "Analytics verification and declared contract dependency coverage across application services"
        val pin = Pin("state", "PaneState", true, 110.0, PortWiring.Connected, id = "source-row")
        val a = Card("state", 40.0, 80.0, 420.0, 250.0, listOf(pin), 0)
        val b = a.copy(id = "events", y = 390.0, pins = listOf(pin.copy(provides = false, id = "target-row")))
        val wire = Link("wire:state", a.id, b.id, pin.key, pin.key, LinkKind.Wire, listOf("wire:state"),
            listOf(a.x + a.width to a.y + pin.y, b.x to b.y + pin.y), family = "state")
        val frame = Frame("coverage", title, 0.0, 0.0, 900.0, 800.0, setOf(a.id, b.id), emptyList(), false)
        val full = BlueprintLayout("whole-caption", listOf(a, b).associateBy { it.id }, listOf(frame), listOf(wire), 900.0, 800.0)
        val levels = BlueprintMapLevels.prepare(full, { 4.0 })
        val frameLook = BlueprintFrameLook(SurfaceGraphSymbol, "2 nodes")
        val folder = File("build/reports/blueprint-map-paint/whole-caption").apply { mkdirs() }
        val observations = arrayListOf("mode\tdensity\tphase\tzoom\twholeText\tlines\tlogicalFloor\tphysicalFloor\toverlap\tchangedLabelPixels")
        val failures = ArrayList<String>()
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val overlay = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
            val measured = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
            val reserve = androidx.compose.runtime.mutableStateOf(false)
            val hits = ArrayList<Pair<Card, Pin>>()
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun labels() = (field(painter(), "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>().toList()
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val current = levels.at(canvas.zoom)
                        Box(Modifier.fillMaxSize()) {
                            BlueprintGraph(current.layout, canvas, emptySet(), a.id, mapLevels = levels, mapLevel = current,
                                initialFit = {}, labelExclusions = if (reserve.value) listOfNotNull(measured.value) else emptyList(),
                                cardLook = { card -> full.cards[card.id]?.let { BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                    if (card.id == a.id) "Pane state" else "Recorded events", "Declared state",
                                    pins = card.pins.map { BlueprintPinLook(colors.source, "state: PaneState", colors.textStrong, it.provides) }) } },
                                frameLook = { frameLook }, linkStyle = { Blueprint.link(colors.source, false, false) },
                                onCardClick = {}, onLinkClick = {}, onPaneClick = {}, onPinClick = { card, hit -> hits += card to hit }, modifier = Modifier.fillMaxSize())
                            overlay.value?.let { at ->
                                Box(Modifier.absoluteOffset(at.left.dp, at.top.dp).requiredSize(at.width.dp, at.height.dp)
                                    .background(colors.surface).onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInRoot()
                                        val next = androidx.compose.ui.geometry.Rect(bounds.left / density, bounds.top / density, bounds.right / density, bounds.bottom / density)
                                        if (measured.value != next) measured.value = next
                                    }) { androidx.compose.material3.Text("Source", color = colors.textStrong, fontSize = MachineSignal.Type.body2) }
                            }
                        }
                    }
                }
            }
            try {
                settle(); edt {
                    painter().javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter(), true)
                }; settle()
                for ((phase, zoom) in listOf("far" to .12, "middle" to 1.0, "near" to 16.0)) {
                    edt {
                        reserve.value = false; overlay.value = null; measured.value = null
                        if (phase == "near") canvas.restore(800.0 - (a.x + a.width) * zoom, 350.0 - (a.y + pin.y) * zoom, zoom)
                        else canvas.restore(70.0, 90.0, zoom)
                    }; settle()
                    val before = canvas.viewport()
                    if (phase == "near") {
                        edt {
                            assertTrue(painter().rows(a.id, zoom), "The typed card remains readable when its offscreen group header has no clear slot")
                            assertTrue(labels().isEmpty(), "A viewport filled by the raw card must not gain a floating group caption over its content")
                            val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                            assertTrue(nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { it.text.contains(title) },
                                "The complete group name remains accessible when its canvas header is offscreen")
                            assertSame(a, full.cards.getValue(a.id)); assertSame(b, full.cards.getValue(b.id)); assertSame(wire, full.links.single())
                            assertEquals(before, canvas.viewport())
                        }
                        val near = raster()
                        ImageIO.write(near, "png", File(folder, "caption-${mode.name.lowercase()}-${density.toInt()}x-$phase.png")); near.flush()
                        continue
                    }
                    lateinit var label: androidx.compose.ui.text.TextLayoutResult
                    var actualDensity = 0f
                    var expectedLines = 0
                    edt {
                        val engine = painter()
                        val inherited = generateSequence(engine.javaClass as Class<*>?) { it.superclass }
                            .first { it.declaredFields.any { field -> field.name == "density" } }
                        actualDensity = inherited.getDeclaredField("density").apply { isAccessible = true }.getFloat(engine)
                        assertEquals(density, actualDensity)
                        val viewportBits = inherited.getDeclaredField("viewport").apply { isAccessible = true }.getLong(engine)
                        val viewport = androidx.compose.ui.geometry.Size(Float.fromBits((viewportBits ushr 32).toInt()), Float.fromBits(viewportBits.toInt()))
                        assertEquals(1100f, viewport.width)
                        val cap = (field(engine, "BannerCap") as Number).toFloat()
                        val room = minOf(viewport.width - MachineSignal.Space.s2.value * 2, maxOf((frame.width * zoom).toFloat(), cap))
                        val space = engine.javaClass.getDeclaredMethod("bannerSpace", String::class.java, BlueprintFrameLook::class.java, java.lang.Float.TYPE)
                            .apply { isAccessible = true }.invoke(engine, title, frameLook, room)
                        assertNotNull(space, "A missing caption cannot reproduce a cut supplied name")
                        val measure = engine.javaClass.declaredMethods.single { it.name == "measureBanner" }.apply { isAccessible = true }.invoke(engine, title, frameLook, space)
                        label = field(measure, "label") as androidx.compose.ui.text.TextLayoutResult
                        assertNull(field(measure, "count"), "Counts remain in the frame semantics and do not widen its canvas name")
                        val frameOwner = dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java
                        val index = (frameOwner.getDeclaredField("frames").apply { isAccessible = true }.get(engine) as List<*>)
                            .filterIsInstance<Frame>().indexOfFirst { it.key == frame.key }
                        val height = if (index >= 0) {
                            val banner = engine.javaClass.getDeclaredMethod("banner", java.lang.Integer.TYPE, java.lang.Float.TYPE, java.lang.Double.TYPE)
                                .apply { isAccessible = true }
                            val layer = assertNotNull(banner.invoke(engine, index, room, zoom) as androidx.compose.ui.graphics.layer.GraphicsLayer?)
                            assertTrue(layer.size.width > 0 && layer.size.height > 0)
                            layer.size.height.toFloat()
                        } else {
                            val receipts = (field(engine, "summaryPaints") as List<*>).filterNotNull().filter { field(it, "id") == "summary:${frame.key}" }
                            val receipt = receipts.single {
                                val shown = (field(it, "layout") as androidx.compose.ui.text.TextLayoutResult).layoutInput.text.text
                                shown != frameLook.count && title.startsWith(shown.removeSuffix("…"))
                            }
                            label = field(receipt, "layout") as androidx.compose.ui.text.TextLayoutResult
                            assertEquals(1f, field(receipt, "opacity"))
                            assertEquals(0, receipts.count { (field(it, "layout") as androidx.compose.ui.text.TextLayoutResult).layoutInput.text.text == frameLook.count })
                            labels().single().height
                        }
                        val natural = (field(engine, "measurer") as androidx.compose.ui.text.TextMeasurer).measure(title, label.layoutInput.style, maxLines = 1)
                        val wraps = natural.size.width > label.layoutInput.constraints.maxWidth
                        if (label.lineCount != 1 || label.size.width > 320) {
                            File(folder, "wrap-failure-${mode.name.lowercase()}-${density.toInt()}x-$phase.tsv").writeText(
                                "density\tzoom\tframeIndex\tframeCount\tnaturalWidth\tmaxWidth\tlines\tfontSize\tlabelWidth\tlabelHeight\tlabels\n" +
                                    "$actualDensity\t$zoom\t$index\t${(frameOwner.getDeclaredField("frames").apply { isAccessible = true }.get(engine) as List<*>).size}\t${natural.size.width}\t${label.layoutInput.constraints.maxWidth}\t${label.lineCount}\t${label.layoutInput.style.fontSize.value}\t${label.size.width}\t${label.size.height}\t${labels().size}\n")
                            val failed = raster()
                            try { ImageIO.write(failed, "png", File(folder, "wrap-failure-${mode.name.lowercase()}-${density.toInt()}x-$phase.png")) }
                            finally { failed.flush() }
                        }
                        if (phase == "far") assertTrue(wraps, "The supplied name must actually exceed the bounded canvas slot")
                        expectedLines = 1
                        assertEquals(expectedLines, label.lineCount, "A canvas name keeps one bounded line while full text remains in semantics")
                        assertTrue(label.size.width <= 320)
                        assertEquals(1, labels().size, "The group caption must actually be painted before checking its complete text")
                        if (height < label.size.height) failures += "${mode.name}/${density.toInt()}/$phase: banner clips its measured text height"
                    }
                    val caption = raster()
                    ImageIO.write(caption, "png", File(folder, "caption-${mode.name.lowercase()}-${density.toInt()}x-$phase.png")); caption.flush()
                    edt {
                        val box = labels().single()
                        val height = MachineSignal.Editor.controlIconSize.value + MachineSignal.Space.s2.value * 2
                        overlay.value = androidx.compose.ui.geometry.Rect(box.left, box.top, box.right, box.top + height)
                    }; settle()
                    val exclusion = assertNotNull(measured.value)
                    assertTrue(exclusion.width > 0 && exclusion.height > 0)
                    edt { assertTrue(labels().single().overlaps(exclusion)); assertEquals(before, canvas.viewport()) }
                    val control = raster()
                    edt { reserve.value = true }; settle()
                    val candidate = raster()
                    ImageIO.write(control, "png", File(folder, "control-${mode.name.lowercase()}-${density.toInt()}x-$phase.png"))
                    ImageIO.write(candidate, "png", File(folder, "candidate-${mode.name.lowercase()}-${density.toInt()}x-$phase.png"))
                    edt {
                        val box = labels().single()
                        val overlap = box.overlaps(exclusion)
                        val x0 = (box.left * actualDensity).toInt().coerceIn(0, candidate.width)
                        val y0 = (box.top * actualDensity).toInt().coerceIn(0, candidate.height)
                        val x1 = (box.right * actualDensity).toInt().coerceIn(x0, candidate.width)
                        val y1 = (box.bottom * actualDensity).toInt().coerceIn(y0, candidate.height)
                        assertTrue(x1 > x0 && y1 > y0)
                        var changed = 0
                        for (y in y0 until y1) for (x in x0 until x1) if (control.getRGB(x, y) != candidate.getRGB(x, y)) changed++
                        assertTrue(changed > 0, "The reserved caption must paint outside the actual overlay")
                        val shown = label.layoutInput.text.text
                        val prefix = shown.removeSuffix("…")
                        val whole = title.startsWith(prefix) && (shown == title || shown.endsWith("…") && title[prefix.length].isWhitespace()) &&
                            !label.hasVisualOverflow && label.getLineEnd(label.lineCount - 1) == shown.length && label.lineCount == 1
                        val logical = label.layoutInput.style.fontSize.value
                        assertTrue(logical >= 11f); assertTrue(logical * actualDensity >= 11f * density)
                        assertEquals(before, canvas.viewport())
                        assertSame(a, full.cards.getValue(a.id)); assertSame(b, full.cards.getValue(b.id)); assertSame(wire, full.links.single())
                        assertEquals(setOf(a.id, b.id), levels.at(zoom).frames.getValue(frame.key).members)
                        observations += "${mode.name}\t${density.toInt()}\t$phase\t$zoom\t$whole\t${label.lineCount}\t$logical\t${logical * actualDensity}\t$overlap\t$changed"
                        if (!whole || label.lineCount != expectedLines) failures += "${mode.name}/${density.toInt()}/$phase: supplied group name does not fit its one bounded readable line"
                        if (overlap) failures += "${mode.name}/${density.toInt()}/$phase: caption still overlaps measured host bounds"
                    }
                    control.flush(); candidate.flush()
                }
                edt {
                    val point = Offset(800f * density, 350f * density)
                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                }; settle()
                assertEquals(1, hits.size); assertSame(a, hits.single().first)
                assertEquals(pin.key, hits.single().second.key); assertEquals(true, hits.single().second.provides)
            } finally { edt { scene.close() } }
        }
        File(folder, "observations.tsv").writeText(observations.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test fun replayOmissionRestoresTheFullRasterAndExactTypedGeometry() {
        val outgoing = Pin("value", "Value", true, 100.0, PortWiring.Connected)
        val incoming = outgoing.copy(provides = false)
        val source = Card("source", 30.0, 70.0, 300.0, 240.0, listOf(outgoing), 0)
        val target = Card("target", 440.0, 70.0, 300.0, 240.0, listOf(incoming), 0)
        val wire = Link("value-wire", source.id, target.id, outgoing.key, incoming.key, LinkKind.Wire,
            listOf("value-wire"), listOf(330.0 to 170.0, 440.0 to 170.0), family = "Value")
        val full = BlueprintLayout("replay-probe", linkedMapOf(source.id to source, target.id to target),
            listOf(Frame("area", "Area", 0.0, 0.0, 780.0, 360.0, setOf(source.id, target.id), emptyList(), false)),
            listOf(wire), 780.0, 360.0)
        val folder = File("build/reports/blueprint-map-paint/replay-fence").apply { mkdirs() }
        val metrics = mutableListOf("mode\tdensity\tphase\trenderNs\tfenceNs\tchangedPixels\tleft\ttop\tright\tbottom")
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            edt {
                scene = ImageComposeScene((900 * density).toInt(), (500 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                if (card.id == source.id) "Recorded source" else "Recorded target", "Typed value",
                                pins = card.pins.map { pin -> BlueprintPinLook(colors.source, "value: Value", colors.textStrong, pin.provides) }) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 cards") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            fun settle() { repeat(40) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            try {
                settle(); edt { canvas.restore(50.0, 70.0, 1.25) }; settle()
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    fun inherited(name: String) = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                        .first { type -> type.declaredFields.any { it.name == name } }.getDeclaredField(name).apply { isAccessible = true }
                    val omit = inherited("replayOmission").get(painter) as androidx.compose.runtime.MutableIntState
                    val countsField = inherited("replayCounts")
                    val originalView = canvas.viewport()
                    val originalCards = (field(painter, "cards") as Array<*>).toList()
                    assertEquals(listOf(source, target), originalCards)
                    assertSame(wire, full.links.single())
                    assertTrue(painter.rows(source.id, canvas.zoom))
                    assertTrue(painter.rows(target.id, canvas.zoom))
                    assertEquals(source to outgoing, painter.pinAt((source.x + source.width - 3).toFloat(), (source.y + outgoing.y).toFloat()))
                    assertEquals(target to incoming, painter.pinAt((target.x + 3).toFloat(), (target.y + incoming.y).toFloat()))
                    fun fenced(phase: String): Triple<IntArray, Long, Long> {
                        val start = System.nanoTime()
                        val image = scene.render(++tick * 16_666_667L)
                        val rendered = System.nanoTime()
                        try {
                            val bytes = image.encodeToData()!!.use { it.bytes }
                            val fenced = System.nanoTime()
                            val decoded = ImageIO.read(ByteArrayInputStream(bytes))
                            assertEquals((900 * density).toInt(), decoded.width)
                            assertEquals((500 * density).toInt(), decoded.height)
                            File(folder, "${mode.name.lowercase()}-${density.toInt()}x-$phase.png").writeBytes(bytes)
                            return Triple(decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width), rendered - start, fenced - rendered)
                        } finally { image.close() }
                    }
                    val counts = IntArray(15)
                    countsField.set(painter, counts)
                    omit.intValue = -2
                    Snapshot.sendApplyNotifications()
                    val baseline = fenced("full-before")
                    counts.fill(0)
                    omit.intValue = 10
                    Snapshot.sendApplyNotifications()
                    val omitted = fenced("omit-details-attribution-only")
                    assertTrue(counts[10] > 0)
                    omit.intValue = -1
                    Snapshot.sendApplyNotifications()
                    val returned = fenced("full-after")
                    assertContentEquals(baseline.first, returned.first)
                    val changed = baseline.first.indices.filter { baseline.first[it] != omitted.first[it] }
                    assertTrue(changed.isNotEmpty())
                    val physicalWidth = (900 * density).toInt()
                    val box = listOf(changed.minOf { it % physicalWidth }, changed.minOf { it / physicalWidth },
                        changed.maxOf { it % physicalWidth } + 1, changed.maxOf { it / physicalWidth } + 1)
                    for ((phase, sample) in listOf("full-before" to baseline, "omit-details-attribution-only" to omitted, "full-after" to returned)) {
                        metrics += (listOf(mode.name, density, phase, sample.second, sample.third, changed.size) + box).joinToString("\t")
                    }
                    assertEquals(originalView, canvas.viewport())
                    assertEquals(originalCards, (field(painter, "cards") as Array<*>).toList())
                    assertTrue(painter.rows(source.id, canvas.zoom))
                    assertTrue(painter.rows(target.id, canvas.zoom))
                    assertEquals(source to outgoing, painter.pinAt((source.x + source.width - 3).toFloat(), (source.y + outgoing.y).toFloat()))
                    assertEquals(target to incoming, painter.pinAt((target.x + 3).toFloat(), (target.y + incoming.y).toFloat()))
                    countsField.set(painter, null)
                }
            } finally { edt { scene.close() } }
        }
        File(folder, "measurements.tsv").writeText(metrics.joinToString("\n") + "\n")
    }

    private fun glyphChannel(ink: Int, base: Int, coverage: Float): Float {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        val value = linear(base) + (linear(ink) - linear(base)) * coverage
        return ((if (value <= .0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - .055) * 255).toFloat()
    }

    private fun glyphReference(text: androidx.compose.ui.text.TextLayoutResult, origin: Offset, density: Float, background: Color, scale: Float = 1f): androidx.compose.ui.graphics.PixelMap {
        val factor = density * scale
        val image = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(text.size.width * factor).toInt() + 4,
            kotlin.math.ceil(text.size.height * factor).toInt() + 4)
        val drawing = androidx.compose.ui.graphics.Canvas(image)
        drawing.drawRect(androidx.compose.ui.geometry.Rect(0f, 0f, image.width.toFloat(), image.height.toFloat()),
            androidx.compose.ui.graphics.Paint().apply { color = background })
        drawing.save()
        drawing.translate(2f + origin.x * density - kotlin.math.floor(origin.x * density),
            2f + origin.y * density - kotlin.math.floor(origin.y * density))
        // Keep the physical origin's phase before scaling: 234f / 1.2f * 1.2f falls below 234f.
        drawing.scale(factor, factor)
        text.multiParagraph.paint(drawing, text.layoutInput.style.color)
        drawing.restore()
        return image.toPixelMap()
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    @Test fun composedSummaryNameStaysPaintedAndFactsStayAccessibleAcrossMapLevels() {
        data class Emitted(val id: String, val layout: androidx.compose.ui.text.TextLayoutResult, val origin: Offset, val opacity: Float)
        data class Ink(val x: Int, val y: Int, val coverage: Float)
        fun <T> edtResult(block: () -> T): T {
            var result: Result<T>? = null
            edt { result = runCatching(block) }
            return assertNotNull(result).getOrThrow()
        }
        val out = Pin("state", "PaneState", true, 130.0, PortWiring.Connected, id = "state-out")
        val boundary = out.copy(key = "published", y = 200.0, id = "published-out")
        val input = out.copy(provides = false, id = "state-in")
        val external = boundary.copy(provides = false, y = 130.0, id = "published-in")
        val a = Card("state", 120.0, 260.0, 480.0, 320.0, listOf(out, boundary), 0)
        val b = Card("events", 120.0, 760.0, 480.0, 320.0, listOf(input), 0)
        val c = Card("consumer", 2000.0, 500.0, 480.0, 320.0, listOf(external), 0)
        val internal = Link("wire:internal", a.id, b.id, out.key, input.key, LinkKind.Wire, listOf("wire:internal"),
            listOf(a.x + a.width to a.y + out.y, b.x to b.y + input.y), family = "state")
        val crossing = Link("wire:boundary", a.id, c.id, boundary.key, external.key, LinkKind.Wire, listOf("wire:boundary"),
            listOf(a.x + a.width to a.y + boundary.y, c.x to c.y + external.y), family = "state")
        val frame = Frame("source", "Declared source", 0.0, 0.0, 1800.0, 1400.0, setOf(a.id, b.id), emptyList(), false)
        val full = BlueprintLayout("composed-summary-paint", listOf(a, b, c).associateBy { it.id }, listOf(frame), listOf(internal, crossing), 2480.0, 1400.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 })
        val summaryId = "summary:${frame.key}"
        val cuts = levels.at(.1).frames.getValue(frame.key)
        val blendZoom = (cuts.leaveZoom + cuts.enterZoom) / 2
        val hiddenCount = levels.at(.4).layout.foldedLinks.values.count { it == summaryId }
        assertEquals(1, hiddenCount)
        val domain = "${frame.nodes.size} nodes"
        val first = "$hiddenCount internal link · evidence Partial"
        val second = "$hiddenCount internal link · evidence Unknown"
        val folder = java.nio.file.Files.createTempDirectory("blueprint-composed-facts-").toFile()
        val observations = arrayListOf("mode\tdensity\tphase\tzoom\topacity\tdomainGlyphPixels\thiddenGlyphPixels\tchangedHiddenPixels")
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val hidden = androidx.compose.runtime.mutableStateOf(first)
            val measured = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
            val selections = ArrayList<String>()
            val hits = ArrayList<Pair<Card, Pin>>()
            var invoked = 0
            val action = BlueprintCardAction("details", "Inspect declared source", SurfaceGraphSymbol) { invoked++ }
            val agent = BlueprintAgent(null, "Supplied agent", BlueprintAgent.Activity.Stale, model = "Declared model", role = "Declared role")
            val mark = BlueprintMark("Partial", Color(0xffbc6d2b))
            val sourceLooks = LinkedHashMap<String, BlueprintCardLook>()
            var surface = Color.Transparent
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun observe(enabled: Boolean) = edt { painter().javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter(), enabled) }
            fun emitted(): List<Emitted> = (field(painter(), "summaryPaints") as List<*>).filterNotNull().map {
                Emitted(field(it, "id") as String, field(it, "layout") as androidx.compose.ui.text.TextLayoutResult,
                    Offset(field(it, "x") as Float, field(it, "y") as Float), field(it, "opacity") as Float)
            }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { result = ImageIO.read(ByteArrayInputStream(it.bytes)) } } }
                return assertNotNull(result)
            }
            fun glyphs(receipt: Emitted, from: Int, until: Int, image: java.awt.image.BufferedImage): Map<Int, List<Ink>> {
                val text = receipt.layout
                assertFalse(text.hasVisualOverflow)
                assertEquals(text.layoutInput.text.length, text.getLineEnd(text.lineCount - 1))
                assertTrue(text.layoutInput.style.fontSize.value >= 11f)
                assertTrue(text.layoutInput.style.fontSize.value * density >= 11f * density)
                val ox = kotlin.math.floor(receipt.origin.x * density).toInt()
                val oy = kotlin.math.floor(receipt.origin.y * density).toInt()
                val mask = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(text.size.width * density).toInt() + 4,
                    kotlin.math.ceil(text.size.height * density).toInt() + 4)
                val drawing = androidx.compose.ui.graphics.Canvas(mask)
                drawing.save()
                drawing.translate(2f + receipt.origin.x * density - ox, 2f + receipt.origin.y * density - oy)
                drawing.scale(density, density)
                text.multiParagraph.paint(drawing, Color.White)
                drawing.restore()
                val pixels = mask.toPixelMap()
                val exclusion = assertNotNull(measured.value)
                val engine = painter()
                val cards = (field(engine, "cards") as Array<*>).filterIsInstance<Card>()
                val card = cards.single { it.id == receipt.id }
                val view = canvas.viewport()
                val pinRooms = if (engine.rows(card.id, view.zoom)) card.pins.map { pin ->
                    val y = (view.y + (card.y + pin.y) * view.zoom).toFloat()
                    val radius = MachineSignal.Type.body2.value * maxOf(1f, view.zoom.toFloat())
                    androidx.compose.ui.geometry.Rect((view.x + card.x * view.zoom).toFloat(), y - radius,
                        (view.x + (card.x + card.width) * view.zoom).toFloat(), y + radius)
                } else emptyList()
                return (from until until).filter { !text.layoutInput.text[it].isWhitespace() }.associateWith { offset ->
                    val line = text.getLineForOffset(offset)
                    assertTrue(offset >= text.getLineStart(line) && offset < text.getLineEnd(line))
                    val box = text.getBoundingBox(offset)
                    val placed = androidx.compose.ui.geometry.Rect(receipt.origin.x + box.left, receipt.origin.y + box.top,
                        receipt.origin.x + box.right, receipt.origin.y + box.bottom)
                    assertFalse(placed.overlaps(exclusion), "An emitted fact glyph overlaps the measured host exclusion")
                    assertTrue(pinRooms.none { it.overlaps(placed) }, "An emitted fact glyph overlaps a painted typed row")
                    val ink = ArrayList<Ink>()
                    val left = kotlin.math.floor(2 + receipt.origin.x * density - ox + box.left * density).toInt().coerceAtLeast(0)
                    val top = kotlin.math.floor(2 + receipt.origin.y * density - oy + box.top * density).toInt().coerceAtLeast(0)
                    val right = kotlin.math.ceil(2 + receipt.origin.x * density - ox + box.right * density).toInt().coerceAtMost(mask.width)
                    val bottom = kotlin.math.ceil(2 + receipt.origin.y * density - oy + box.bottom * density).toInt().coerceAtMost(mask.height)
                    for (y in top until bottom) for (x in left until right) {
                        val coverage = pixels[x, y].alpha
                        if (coverage >= .65f) {
                            val px = ox + x - 2; val py = oy + y - 2
                            assertTrue(px in 0 until image.width && py in 0 until image.height, "An emitted glyph is clipped by the real viewport")
                            ink += Ink(px, py, coverage)
                        }
                    }
                    assertTrue(ink.isNotEmpty(), "Complete setup requires rasterizable ink for every non-space fact glyph")
                    ink
                }
            }
            fun supported(receipt: Emitted, chars: Map<Int, List<Ink>>, image: java.awt.image.BufferedImage) {
                val ink = receipt.layout.layoutInput.style.color.toArgb()
                val reference = glyphReference(receipt.layout, receipt.origin, density, surface)
                val ox = kotlin.math.floor(receipt.origin.x * density).toInt()
                val oy = kotlin.math.floor(receipt.origin.y * density).toInt()
                val base = surface.toArgb()
                for ((offset, points) in chars) {
                    val left = points.minOf { it.x }; val right = points.maxOf { it.x }
                    val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
                    val background = (maxOf(0, top - 2)..minOf(image.height - 1, bottom + 2)).flatMap { y ->
                        (maxOf(0, left - 2)..minOf(image.width - 1, right + 2)).map { x -> image.getRGB(x, y) }
                    }.groupingBy { it }.eachCount().maxBy { it.value }.key
                    fun channel(value: Int, shift: Int) = (value ushr shift) and 255
                    val matching = points.count { point ->
                        val actual = image.getRGB(point.x, point.y)
                        val glyph = reference[point.x - ox + 2, point.y - oy + 2].toArgb()
                        listOf(16, 8, 0).all { shift ->
                            val expected = (channel(background, shift) + (channel(glyph, shift) - channel(base, shift)) * receipt.opacity).toInt().coerceIn(0, 255)
                            kotlin.math.abs(channel(actual, shift) - expected) <= 18
                        } && listOf(16, 8, 0).any { kotlin.math.abs(channel(actual, it) - channel(background, it)) >= 12 }
                    }
                    if (matching < points.size * .8) {
                        val retained = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        points.forEach { retained.setRGB(it.x, it.y, ((it.coverage * 255).toInt() shl 24) or 0x00ffffff) }
                        ImageIO.write(retained, "png", File(folder, "glyph-mask-${mode.name.lowercase()}-${density.toInt()}x.png"))
                        File(folder, "glyph-failure-${mode.name.lowercase()}-${density.toInt()}x.tsv").writeText(
                            "offset\texpected\tsupported\tx\ty\topacity\tink\tbase\tbackground\n$offset\t${points.size}\t$matching\t${receipt.origin.x}\t${receipt.origin.y}\t${receipt.opacity}\t$ink\t$base\t$background\n")
                        retained.flush()
                    }
                    assertTrue(matching >= points.size * .8, "Measured fact glyph lacks matching ink in the actual scene raster: offset=$offset expected=${points.size} supported=$matching")
                }
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        surface = colors.surface
                        val current = levels.at(canvas.zoom)
                        Box(Modifier.fillMaxSize()) {
                            BlueprintGraph(current.layout, canvas, emptySet(), a.id, mapLevels = levels, mapLevel = current,
                                initialFit = {}, labelExclusions = listOfNotNull(measured.value),
                                cardLook = { card ->
                                    val look = BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                        when (card.id) { a.id -> "Declared state"; b.id -> "Recorded events"; c.id -> "Contract consumer"; else -> frame.label },
                                        if (card.id == summaryId) hidden.value else "Declared contract",
                                        marks = if (card.id == a.id || card.id == summaryId) listOf(mark) else emptyList(),
                                        pins = if (card.id == summaryId) emptyList() else card.pins.map { BlueprintPinLook(colors.source, "${it.key}: ${it.type}", colors.textStrong, it.provides) },
                                        agent = if (card.id == a.id || card.id == summaryId) agent else null,
                                        actions = if (card.id == a.id || card.id == summaryId) listOf(action) else emptyList(),
                                        tag = "fact-card:${card.id}", action = "Select declared card")
                                    sourceLooks[card.id] = look
                                    look
                                }, frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "", marks = listOf(mark)) },
                                linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = { selections += it },
                                onLinkClick = {}, onPaneClick = {}, onPinClick = { card, pin -> hits += card to pin }, modifier = Modifier.fillMaxSize())
                            Box(Modifier.absoluteOffset(30.dp, 80.dp).requiredSize(400.dp, 40.dp).background(colors.surface)
                                .onGloballyPositioned { coordinates ->
                                    val at = coordinates.boundsInRoot()
                                    val next = androidx.compose.ui.geometry.Rect(at.left / density, at.top / density, at.right / density, at.bottom / density)
                                    if (measured.value != next) measured.value = next
                                }) { androidx.compose.material3.Text("Source", color = colors.textStrong, fontSize = MachineSignal.Type.body2) }
                        }
                    }
                }
            }
            try {
                settle()
                edt { assertTrue(emitted().isEmpty()); assertFalse(field(painter(), "observeSummaryPaints") as Boolean) }
                observe(true)
                for ((phase, zoom) in listOf("far" to .4, "middle" to .8, "blend" to blendZoom, "near" to 1.8, "return" to .4)) {
                    edt {
                        hidden.value = first
                        if (phase == "near") canvas.restore(650.0 - (a.x + a.width) * zoom, 380.0 - (a.y + out.y) * zoom, zoom)
                        else canvas.restore(40.0, 70.0, zoom)
                    }; settle()
                    val beforeView = canvas.viewport()
                    val before = raster()
                    ImageIO.write(before, "png", File(folder, "${mode.name.lowercase()}-${density.toInt()}x-$phase-before.png"))
                    val receipts = edtResult { emitted() }
                    observations += "${mode.name}\t${density.toInt()}\t$phase\t$zoom\temitted=${receipts.size}"
                    receipts.forEach { observations += "emitted\topacity=${it.opacity}\torigin=${it.origin}\twidth=${it.layout.size.width}\theight=${it.layout.size.height}\tfont=${it.layout.layoutInput.style.fontSize.value}" }
                    val engine = painter()
                    val inherited = generateSequence(engine.javaClass as Class<*>?) { it.superclass }.first { it.declaredFields.any { field -> field.name == "density" } }
                    assertEquals(density, inherited.getDeclaredField("density").apply { isAccessible = true }.getFloat(engine))
                    val viewportBits = inherited.getDeclaredField("viewport").apply { isAccessible = true }.getLong(engine)
                    assertEquals(1100f, Float.fromBits((viewportBits ushr 32).toInt()))
                    assertEquals(700f, Float.fromBits(viewportBits.toInt()))
                    assertEquals(androidx.compose.ui.geometry.Rect(30f, 80f, 430f, 120f), measured.value)
                    val originalCards = full.cards.toMap(); val originalLinks = full.links.toList()
                    assertEquals(setOf(a.id, b.id), levels.at(zoom).frames.getValue(frame.key).members)
                    if (phase == "near") {
                        assertTrue(receipts.isEmpty(), "A dissolved summary must leave no stale paint receipts")
                        edt {
                            val cards = (field(engine, "cards") as Array<*>).filterIsInstance<Card>()
                            assertEquals(full.cards.keys, cards.map { it.id }.toSet())
                            cards.forEach { assertSame(full.cards.getValue(it.id), it) }
                            assertTrue(engine.rows(a.id, zoom))
                            assertEquals(full.links, (field(engine, "layout") as BlueprintLayout<*>).links)
                            val point = Offset(650f * density, 380f * density)
                            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                        }; settle()
                        assertSame(a, hits.single().first); assertSame(out, hits.single().second); assertEquals(out.key, hits.single().second.key); assertTrue(hits.single().second.provides)
                        ImageIO.write(before, "png", File(folder, "${mode.name.lowercase()}-${density.toInt()}x-$phase.png")); before.flush()
                        assertEquals(beforeView, canvas.viewport())
                        continue
                    }
                    val summary = edtResult {
                        val cards = (field(engine, "cards") as Array<*>).filterIsInstance<Card>()
                        val index = cards.indexOfFirst { it.id == summaryId }
                        assertTrue(index >= 0, "Complete mounted summary setup is required")
                        val look = (field(engine, "looks") as List<*>)[index] as BlueprintCardLook
                        assertEquals(frame.label, look.title)
                        assertEquals(first, sourceLooks.getValue(summaryId).subtitle)
                        assertEquals(listOf(mark), look.marks); assertEquals(agent, look.agent); assertSame(action, look.actions.single())
                        assertEquals(listOf(frame.x, frame.y, frame.width, frame.height), listOf(cards[index].x, cards[index].y, cards[index].width, cards[index].height))
                        assertEquals(1, cards[index].pins.size)
                        val forwarded = levels.at(zoom).forwardedPorts.entries.single { (ref, originals) ->
                            ref.card == summaryId && originals.singleOrNull()?.let {
                                it.card == a.id && it.key == boundary.key && it.provides == boundary.provides
                            } == true
                        }
                        assertTrue(forwarded.key.matches(summaryId, cards[index].pins.single()))
                        assertEquals(boundary.key, forwarded.value.single().key)
                        assertEquals(boundary.type, cards[index].pins.single().type)
                        assertEquals(boundary.provides, cards[index].pins.single().provides)
                        assertEquals("published: PaneState", look.pins.single()?.text)
                        assertNotNull(engine.agentBounds(summaryId, zoom))
                        assertTrue((field(engine, "mapLabels") as List<*>).isNotEmpty(), "An actually placed group label is required")
                        look
                    }
                    val setupReceipt = assertNotNull(receipts.firstOrNull { it.id == summaryId && it.opacity > 0f && it.layout.layoutInput.text.isNotEmpty() },
                        "Complete setup requires an actually painted summary body, not a missing label")
                    val setupInk = edtResult { glyphs(setupReceipt, 0, setupReceipt.layout.layoutInput.text.length, before) }
                    supported(setupReceipt, setupInk, before)
                    val domainReceipts = receipts.filter { it.id == summaryId && it.layout.layoutInput.text.text == frame.label }
                    assertEquals(1, domainReceipts.size, "The summary keeps exactly one concise name on the canvas")
                    val receipt = domainReceipts.single()
                    assertTrue(receipt.opacity > 0f && receipt.opacity <= 1f)
                    val retainedFrames = edtResult {
                        (dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java.getDeclaredField("frames")
                            .apply { isAccessible = true }.get(engine) as List<*>).filterIsInstance<Frame>()
                    }
                    val ownTitles = receipts.count { it.id == summaryId && it.layout.layoutInput.text.text == frame.label }
                    if (ownTitles != 1) {
                        val frameCounts = edtResult {
                            val retained = (dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java.getDeclaredField("frames")
                                .apply { isAccessible = true }.get(engine) as List<*>).filterIsInstance<Frame>()
                            listOf(retained.size, retained.count { it.key == frame.key }, (field(engine, "mapLabels") as List<*>).size)
                        }
                        val titleReceipts = receipts.filter { it.layout.layoutInput.text.text == frame.label }
                        File(folder, "title-failure-${mode.name.lowercase()}-${density.toInt()}x-$phase.tsv").writeText(
                            "density\tzoom\tretainedFrames\tmatchingFrames\tlabels\ttitleLayouts\tsummaryLayouts\tx\ty\twidth\theight\tlines\topacity\n" +
                                titleReceipts.joinToString("\n") { observed ->
                                    "$density\t$zoom\t${frameCounts[0]}\t${frameCounts[1]}\t${frameCounts[2]}\t${titleReceipts.size}\t${receipts.count { it.id == summaryId }}\t${observed.origin.x}\t${observed.origin.y}\t${observed.layout.size.width}\t${observed.layout.size.height}\t${observed.layout.lineCount}\t${observed.opacity}"
                                } + "\n")
                    }
                    if (retainedFrames.none { it.key == frame.key }) {
                        val title = receipts.filter { it.id == summaryId && it.layout.layoutInput.text.text == frame.label }.single()
                        val titleInk = edtResult { glyphs(title, 0, frame.label.length, before) }
                        supported(title, titleInk, before)
                    } else {
                        assertEquals(1, receipts.count { it.layout.layoutInput.text.text == frame.label }, "The header records the same single painted name")
                    }
                    assertEquals(domain, summary.subtitle)
                    assertEquals(frame.label, receipt.layout.layoutInput.text.text)
                    assertEquals(1, receipt.layout.lineCount)
                    assertTrue(receipt.layout.size.width <= 320)
                    assertTrue(receipts.all { it.layout.lineCount == 1 })
                    assertTrue(receipts.none { it.layout.layoutInput.text.text == domain }, "Producer facts stay out of the graph caption")
                    assertTrue(receipts.none { it.layout.layoutInput.text.text == first || it.layout.layoutInput.text.text == a.id || it.layout.layoutInput.text.text == b.id })
                    val domainInk = edtResult { glyphs(receipt, 0, frame.label.length, before) }
                    supported(receipt, domainInk, before)
                    edt { hidden.value = second }; settle()
                    val after = raster()
                    ImageIO.write(after, "png", File(folder, "${mode.name.lowercase()}-${density.toInt()}x-$phase-after.png"))
                    val changed = edtResult { emitted().single { it.id == summaryId && it.layout.layoutInput.text.text == frame.label } }
                    assertEquals(frame.label, changed.layout.layoutInput.text.text)
                    val nextDomain = edtResult { glyphs(changed, 0, frame.label.length, after) }
                    supported(changed, nextDomain, after)
                    val unchangedPixels = domainInk.values.flatten().map { it.x to it.y }.toSet()
                    assertEquals(unchangedPixels, nextDomain.values.flatten().map { it.x to it.y }.toSet())
                    assertTrue(unchangedPixels.all { (x, y) -> before.getRGB(x, y) == after.getRGB(x, y) }, "Rejected internal-link prose must not repaint the concise name")
                    val changedPixels = unchangedPixels.count { (x, y) -> before.getRGB(x, y) != after.getRGB(x, y) }
                    assertEquals(0, changedPixels)
                    edt {
                        val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        val button = nodes.singleOrNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == "blueprint-card-action:$summaryId:${action.id}" }
                        if (engine.headerScale(summaryId, zoom) != null) {
                            val beforeInvoke = invoked
                            assertTrue(assertNotNull(button).config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action?.invoke() == true)
                            assertEquals(beforeInvoke + 1, invoked)
                        }
                        assertTrue(selections.isEmpty())
                    }
                    assertEquals(originalCards, full.cards); assertEquals(originalLinks, full.links)
                    assertSame(internal, full.links[0]); assertSame(crossing, full.links[1]); assertSame(out, full.cards.getValue(a.id).pins[0])
                    assertEquals(beforeView, canvas.viewport())
                    observations += "${mode.name}\t${density.toInt()}\t$phase\t$zoom\t${receipt.opacity}\t${domainInk.values.sumOf { it.size }}\t0\t$changedPixels"
                    before.flush(); after.flush()
                }
                val observed = raster()
                observe(false)
                edt { hidden.value = first }; settle()
                edt { hidden.value = second }; settle()
                val disabled = raster()
                edt { assertTrue(emitted().isEmpty(), "Disabled observation must release the last frame's layouts") }
                assertTrue((0 until observed.height).all { y -> (0 until observed.width).all { x -> observed.getRGB(x, y) == disabled.getRGB(x, y) } })
                observed.flush(); disabled.flush()
            } finally { edt { scene.close() }; File(folder, "observations.tsv").writeText(observations.joinToString("\n") + "\n") }
        }
    }


    @Test fun partialSummaryScopesCountActualEntryWithoutGlyphsAndResetPerDraw() {
        fun <T> read(block: () -> T): T {
            var result: Result<T>? = null
            edt { result = runCatching(block) }
            return assertNotNull(result).getOrThrow()
        }
        val output = Pin("publish", "RecordedValue", true, 130.0, PortWiring.Connected, id = "raw-output")
        val input = output.copy(key = "consume", provides = false, id = "raw-input")
        val a = Card("first", 120.0, 260.0, 480.0, 320.0, listOf(output), 0)
        val b = a.copy(id = "second", y = 760.0, pins = listOf(input))
        val external = a.copy(id = "external", x = 3000.0, y = 500.0, pins = listOf(input))
        val frame = Frame("context", "Recorded context", 0.0, 0.0, 1800.0, 1400.0, setOf(a.id, b.id), emptyList(), false)
        val links = listOf(b, external).map { card -> Link("raw:${card.id}", a.id, card.id, output.key, input.key, LinkKind.Wire,
            listOf("raw:${card.id}"), listOf(a.x + a.width to a.y + output.y, card.x to card.y + input.y), family = output.type) }
        val full = BlueprintLayout("summary-alpha-entry", listOf(a, b, external).associateBy { it.id }, listOf(frame), links, 3480.0, 1400.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 })
        val cuts = levels.at(.1).frames.getValue(frame.key)
        assertTrue(cuts.enterZoom > cuts.leaveZoom)
        val blend = (cuts.enterZoom + cuts.leaveZoom) / 2
        val summaryId = "summary:${frame.key}"
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun enable(value: Boolean) = edt { painter().javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter(), value) }
            fun serial() = read { field(painter(), "summaryPaintFrame") as Long }
            fun assertPixels(expected: IntArray, actual: IntArray, message: String = "Every scene pixel must match") {
                if (expected.contentEquals(actual)) return
                val common = minOf(expected.size, actual.size)
                val first = (0 until common).firstOrNull { expected[it] != actual[it] } ?: common
                val changed = (0 until common).count { expected[it] != actual[it] } + kotlin.math.abs(expected.size - actual.size)
                val width = (1100 * density).toInt()
                kotlin.test.fail("$message; expected=${expected.size}, actual=${actual.size}, changed=$changed, first=(${first % width},${first / width})")
            }
            fun awaitExternalMarkers() {
                val deadline = System.nanoTime() + 1_000_000_000L
                var previous: List<androidx.compose.ui.geometry.Rect>? = null
                var stable = 0
                while (stable < 2 && System.nanoTime() < deadline) {
                    edt {
                        Snapshot.sendApplyNotifications()
                        scene.render(++tick * 16_666_667L).close()
                        val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        val bounds = listOf("blueprint-agent-${external.id}", "blueprint-card-action:${external.id}:facts").map { tag ->
                            nodes.singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }?.boundsInRoot
                        }
                        if (bounds.all { it != null && it.width > 0f && it.height > 0f }) {
                            val current = bounds.map { assertNotNull(it) }
                            stable = if (current == previous) stable + 1 else 1
                            previous = current
                        } else {
                            stable = 0
                            previous = null
                        }
                    }
                    if (stable < 2) Thread.sleep(8)
                }
                assertEquals(2, stable, "Both opaque rasters require committed external agent/action markers with stable positive bounds")
            }
            fun capture(zoom: Double, enabled: Boolean): IntArray {
                val engine = read { painter() }
                val x = 550.0 - (external.x + external.width / 2) * zoom
                val y = 270.0 - external.y * zoom
                edt { canvas.restore(x + 2.0, y, zoom) }; settle()
                val prior = serial()
                edt { canvas.restore(x, y, zoom) }; settle()
                if (zoom == cuts.leaveZoom / 2) awaitExternalMarkers()
                return read {
                    assertSame(engine, painter(), "Observation must belong to the same mounted painter")
                    assertEquals(x, canvas.viewport().x)
                    assertEquals(y, canvas.viewport().y)
                    assertEquals(zoom, canvas.viewport().zoom)
                    assertEquals(enabled, field(engine, "summaryPaintComplete"))
                    if (enabled) assertTrue((field(engine, "summaryPaintFrame") as Long) > prior, "A fresh completed painter draw is required")
                    else assertEquals(prior, field(engine, "summaryPaintFrame"))
                    scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { encoded ->
                        val pixels = ImageIO.read(ByteArrayInputStream(encoded.bytes))
                        val result = pixels.getRGB(0, 0, pixels.width, pixels.height, null, 0, pixels.width)
                        assertTrue(result.toSet().size > 8, "The visible external control must paint a nonempty scene")
                        pixels.flush()
                        result
                    } }
                }
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val current = levels.at(canvas.zoom)
                        BlueprintGraph(current.layout, canvas, emptySet(), null, mapLevels = levels, mapLevel = current, initialFit = {},
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                if (card.id == summaryId) frame.label else "Recorded ${card.id}",
                                if (card.id == summaryId) "Domain Partial\n1 internal link · evidence Unknown" else "Supplied body fact",
                                pins = card.pins.map { BlueprintPinLook(colors.source, "${it.key}: ${it.type}", colors.textStrong, it.provides) },
                                marks = listOf(BlueprintMark("Partial", colors.source)),
                                agent = BlueprintAgent(null, "Recorded agent", BlueprintAgent.Activity.Stale),
                                actions = listOf(BlueprintCardAction("facts", "Inspect supplied facts", SurfaceGraphSymbol) {})) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 actual members") },
                            linkStyle = { Blueprint.link(colors.source, false, false) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                assertEquals(0, read { field(painter(), "partialSummaryAlphaScopes") })
                assertEquals(0L, serial())
                assertFalse(read { field(painter(), "summaryPaintComplete") as Boolean })
                enable(true)
                val opaque = capture(cuts.leaveZoom / 2, true)
                assertEquals(0, read { field(painter(), "partialSummaryAlphaScopes") })
                val observed = capture(blend, true)
                read {
                    val engine = painter()
                    val cards = (field(engine, "cards") as Array<*>).filterIsInstance<Card>()
                    assertEquals(1, cards.count { it.id !in full.cards }, "Complete partial-summary setup is required")
                    assertTrue(cards.any { it.id == summaryId })
                    val opacity = (field(engine, "cardOpacity") as Map<*, *>)[summaryId] as Float
                    assertTrue(opacity > 0f && opacity < 1f)
                    assertEquals(1, field(engine, "partialSummaryAlphaScopes"), "The real alpha scope must count even without later summary glyphs")
                    assertTrue((field(engine, "summaryPaints") as List<*>).isEmpty())
                }
                assertPixels(observed, capture(blend, true))
                assertEquals(1, read { field(painter(), "partialSummaryAlphaScopes") }, "Scope counts must reset, never accumulate across draws")
                enable(false)
                assertPixels(observed, capture(blend, false), "Default-off observation must preserve every scene pixel")
                assertEquals(0, read { field(painter(), "partialSummaryAlphaScopes") })
                assertTrue(read { (field(painter(), "summaryPaints") as List<*>).isEmpty() })
                enable(true)
                capture(cuts.enterZoom * 1.5, true)
                assertEquals(0, read { field(painter(), "partialSummaryAlphaScopes") })
                assertTrue(read { (field(painter(), "cards") as Array<*>).filterIsInstance<Card>().none { it.id == summaryId } })
                assertPixels(observed, capture(blend, true))
                assertEquals(1, read { field(painter(), "partialSummaryAlphaScopes") })
                val returned = capture(cuts.leaveZoom / 2, true)
                if (!opaque.contentEquals(returned)) {
                    val folder = File("build/reports/blueprint-map-paint/summary-alpha").apply { mkdirs() }
                    val width = (1100 * density).toInt()
                    val height = (700 * density).toInt()
                    listOf("opaque-before" to opaque, "opaque-return" to returned).forEach { (phase, values) ->
                        val retained = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        retained.setRGB(0, 0, width, height, values, 0, width)
                        ImageIO.write(retained, "png", File(folder, "$phase-${mode.name.lowercase()}-${density.toInt()}x.png"))
                        retained.flush()
                    }
                    val changed = opaque.indices.filter { opaque[it] != returned[it] }
                    File(folder, "return-${mode.name.lowercase()}-${density.toInt()}x.tsv").writeText(
                        "changed\tleft\ttop\tright\tbottom\tzoom\tframe\tscopes\n" +
                            "${changed.size}\t${changed.minOf { it % width }}\t${changed.minOf { it / width }}\t" +
                            "${changed.maxOf { it % width }}\t${changed.maxOf { it / width }}\t${canvas.zoom}\t${serial()}\t" +
                            "${read { field(painter(), "partialSummaryAlphaScopes") }}\n")
                }
                assertPixels(opaque, returned)
                assertEquals(0, read { field(painter(), "partialSummaryAlphaScopes") })
                assertSame(full, levels.full)
                assertEquals(listOf(a, b, external).associateBy { it.id }, full.cards)
                assertEquals(links, full.links)
                assertEquals(listOf(frame), full.frames)
            } finally { edt { scene.close() } }
        }
    }

    @Test fun standaloneFarNameStaysBoundedWithinMeasuredViewportAndKeepsTypedIdentity() {
        fun <T> read(block: () -> T): T {
            var result: Result<T>? = null
            edt { result = runCatching(block) }
            return assertNotNull(result).getOrThrow()
        }
        val output = Pin("publish", "RecordedContract", true, 140.0, PortWiring.Connected, id = "original-output")
        val input = output.copy(key = "consume", provides = false, id = "original-input")
        val source = Card("original-service", 80.0, 80.0, 1500.0, 360.0, listOf(output), 0)
        val target = source.copy(id = "original-consumer", x = 30000.0, pins = listOf(input))
        val wire = Link("original-wire", source.id, target.id, output.key, input.key, LinkKind.Wire, listOf("original-wire"),
            listOf(source.x + source.width to source.y + output.y, target.x to target.y + input.y), family = output.type)
        val full = BlueprintLayout("standalone-name", listOf(source, target).associateBy { it.id }, emptyList(), listOf(wire), 31500.0, 440.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 })
        val supplied = "Declared service and worker contract with independent recorded source facts and retained original provider identity"
        fun File.deleteTreeSafely(within: File) {
            val target = canonicalFile.toPath()
            require(target.parent == within.canonicalFile.toPath() && !java.nio.file.Files.isSymbolicLink(toPath()))
            java.nio.file.Files.walkFileTree(target, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    java.nio.file.Files.delete(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (exc != null) throw exc
                    java.nio.file.Files.delete(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
        val folder = java.nio.file.Files.createTempDirectory("blueprint-standalone-name-").toFile()
        val parent = folder.parentFile
        try {
            for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
                val canvas = GraphCanvasState(.002..16.0)
                val exclusion = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
                val hits = ArrayList<Pair<Card, Pin>>()
                val selections = ArrayList<String>()
                lateinit var scene: ImageComposeScene
                var textColor = Color.Unspecified
                var surface = Color.Unspecified
                var tick = 0L
                fun settle() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
                fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                fun raster(): java.awt.image.BufferedImage = read {
                    scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) } }
                }
                edt {
                    scene = ImageComposeScene((360 * density).toInt(), (520 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                        MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                            val colors = MachineSignal.colors
                            textColor = colors.textStrong
                            surface = colors.surface
                            Box(Modifier.fillMaxSize()) {
                                BlueprintGraph(full, canvas, emptySet(), null, initialFit = {}, mapLevels = levels, mapLevel = levels.at(canvas.zoom),
                                    labelExclusions = listOfNotNull(exclusion.value),
                                    cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                        if (card.id == source.id) supplied else "Consumer", "Declared code · evidence Unknown",
                                        marks = listOf(BlueprintMark("Stale", colors.source)),
                                        pins = card.pins.map { BlueprintPinLook(colors.source, "${it.key}: ${it.type}", colors.textStrong, it.provides) }) },
                                    frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") }, linkStyle = { Blueprint.link(colors.source, false, false) },
                                    onCardClick = { selections += it }, onLinkClick = {}, onPaneClick = {}, onPinClick = { card, pin -> hits += card to pin },
                                    modifier = Modifier.fillMaxSize())
                                Box(Modifier.absoluteOffset(24.dp, 80.dp).requiredSize(312.dp, 34.dp).background(colors.surface)
                                    .onGloballyPositioned {
                                        val bounds = it.boundsInRoot()
                                        val next = androidx.compose.ui.geometry.Rect(bounds.left / density, bounds.top / density, bounds.right / density, bounds.bottom / density)
                                        if (exclusion.value != next) exclusion.value = next
                                    })
                            }
                        }
                    }
                }
                try {
                    settle()
                    for ((phase, zoom) in listOf("far" to .04, "middle" to .08, "return" to .04)) {
                        edt { canvas.restore(180.0 - (source.x + source.width / 2) * zoom, 72.0 - source.y * zoom, zoom) }; settle()
                        val view = canvas.viewport()
                        val image = raster()
                        read {
                            val engine = painter()
                            val index = (field(engine, "cards") as Array<*>).filterIsInstance<Card>().indexOfFirst { it.id == source.id }
                            assertTrue(index >= 0)
                            val paint = assertNotNull((field(engine, "paints") as Array<*>)[index])
                            assertEquals(PaintDetail.Tier.Summary, (field(paint, "detail") as PaintDetail).tier, "The mounted card must actually exercise the fallback label")
                            val labels = (field(engine, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                            val box = labels.single()
                            val gap = MachineSignal.Space.s2.value
                            val measurer = field(engine, "measurer") as androidx.compose.ui.text.TextMeasurer
                            val style = (field(engine, "type") as BlueprintType).style(MachineSignal.Type.label, textColor)
                            assertTrue(measurer.measure(supplied, style, maxLines = 1).size.width > 360, "The old unbounded label must be too wide; missing setup cannot reproduce the defect")
                            val layout = assertNotNull(engine.javaClass.getDeclaredMethod("name", String::class.java, androidx.compose.ui.text.TextStyle::class.java,
                                java.lang.Float.TYPE).apply { isAccessible = true }
                                .invoke(engine, supplied, style, minOf(320f, 360 - gap * 4)) as androidx.compose.ui.text.TextLayoutResult?)
                            assertEquals(1, layout.lineCount)
                            assertTrue(layout.size.width <= 320)
                            assertFalse(layout.hasVisualOverflow)
                            val shown = layout.layoutInput.text.text
                            assertTrue(supplied.startsWith(shown.removeSuffix("…")))
                            assertTrue(shown == supplied || supplied.getOrNull(shown.removeSuffix("…").length)?.isWhitespace() == true)
                            assertEquals(shown.length, layout.getLineEnd(layout.lineCount - 1))
                            assertTrue(style.fontSize.value >= 11f)
                            assertTrue(style.fontSize.value * density >= 11f * density)
                            assertEquals(layout.size.width + gap * 2, box.width)
                            assertEquals(layout.size.height.toFloat(), box.height)
                            assertTrue(box.left >= gap && box.right <= 360 - gap && box.bottom <= 520 - gap)
                            assertFalse(box.overlaps(assertNotNull(exclusion.value)))
                            assertTrue(box.top >= assertNotNull(exclusion.value).bottom + gap)
                            val origin = Offset(box.left + gap, box.top)
                            val ox = kotlin.math.floor(origin.x * density).toInt()
                            val oy = kotlin.math.floor(origin.y * density).toInt()
                            val mask = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(layout.size.width * density).toInt() + 4,
                                kotlin.math.ceil(layout.size.height * density).toInt() + 4)
                            val drawing = androidx.compose.ui.graphics.Canvas(mask)
                            drawing.save()
                            drawing.translate(2f + origin.x * density - ox, 2f + origin.y * density - oy)
                            drawing.scale(density, density)
                            layout.multiParagraph.paint(drawing, Color.White)
                            drawing.restore()
                            val pixels = mask.toPixelMap()
                            for (offset in shown.indices.filter { !shown[it].isWhitespace() }) {
                                val glyph = layout.getBoundingBox(offset)
                                val left = kotlin.math.floor(2 + origin.x * density - ox + glyph.left * density).toInt().coerceAtLeast(0)
                                val top = kotlin.math.floor(2 + origin.y * density - oy + glyph.top * density).toInt().coerceAtLeast(0)
                                val right = kotlin.math.ceil(2 + origin.x * density - ox + glyph.right * density).toInt().coerceAtMost(mask.width)
                                val bottom = kotlin.math.ceil(2 + origin.y * density - oy + glyph.bottom * density).toInt().coerceAtMost(mask.height)
                                var ink = 0
                                var supported = 0
                                for (y in top until bottom) for (x in left until right) if (pixels[x, y].alpha >= .5f) {
                                    val px = ox + x - 2
                                    val py = oy + y - 2
                                    assertTrue(px in 0 until image.width && py in 0 until image.height)
                                    val actual = image.getRGB(px, py)
                                    if (listOf(16, 8, 0).any { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((surface.toArgb() ushr shift) and 255)) >= 20 }) supported++
                                    ink++
                                }
                                assertTrue(ink > 0 && supported >= ink * .7, "Every complete-name glyph must have ink in the actual mounted raster")
                            }
                            assertEquals(view, canvas.viewport())
                            assertSame(full, levels.full)
                            assertEquals(listOf(source, target).associateBy { it.id }, full.cards)
                            assertEquals(listOf(wire), full.links)
                        }
                        ImageIO.write(image, "png", File(folder, "${mode.name.lowercase()}-${density.toInt()}x-$phase.png"))
                        image.flush()
                    }
                    edt { canvas.restore(180.0 - (source.x + source.width) * 2.0, 200.0 - (source.y + output.y) * 2.0, 2.0) }; settle()
                    val near = canvas.viewport()
                    read {
                        val engine = painter()
                        assertTrue(engine.rows(source.id, near.zoom))
                        val recovered = assertNotNull(engine.pinAt((source.x + source.width - 10).toFloat(), (source.y + output.y).toFloat()))
                        assertEquals(source, recovered.first)
                        assertSame(output, recovered.second)
                        val look = (field(engine, "looks") as List<*>).filterIsInstance<BlueprintCardLook>().first { it.title == supplied }
                        assertEquals("Declared code · evidence Unknown", look.subtitle)
                        assertEquals(listOf("Stale"), look.marks.map { it.text })
                        assertEquals(listOf("publish: RecordedContract"), look.pins.map { it?.text })
                        scene.sendPointerEvent(PointerEventType.Press, Offset(160f * density, 200f * density), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                        scene.sendPointerEvent(PointerEventType.Release, Offset(160f * density, 200f * density), button = PointerButton.Primary)
                    }
                    settle()
                    assertEquals(source, hits.single().first)
                    assertSame(output, hits.single().second)
                    assertTrue(selections.isEmpty(), "A pin callback must retain its own action identity")
                    assertEquals(near, canvas.viewport())
                } finally { edt { scene.close() } }
            }
        } finally { folder.deleteTreeSafely(within = parent) }
    }

    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
    @Test fun summaryDiagnosticsStayAccessibleWithoutPaintingOverClearOrBlockedGraph() {
        val pin = Pin("grant", "Permission", true, 110.0, PortWiring.Connected, id = "raw-grant")
        val a = Card("roles", 40.0, 80.0, 420.0, 320.0, listOf(pin), 0)
        val b = a.copy(id = "checks", y = 440.0)
        val frame = Frame("access", "Access controls across the whole desktop with independent permission grants and declared service boundaries across every workspace ".repeat(4).trim(), 0.0, 0.0, 900.0, 800.0,
            setOf(a.id, b.id), emptyList(), false)
        val full = BlueprintLayout("short-summary-mark", listOf(a, b).associateBy { it.id }, listOf(frame), emptyList(), 900.0, 800.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 })
        val markColor = Color(0xFFD30BE7)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val blocked = androidx.compose.runtime.mutableStateOf(false)
            val marked = androidx.compose.runtime.mutableStateOf(true)
            val measured = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            fun markInk(image: java.awt.image.BufferedImage): List<androidx.compose.ui.geometry.Rect> {
                val pixels = HashSet<Int>()
                val expected = markColor.toArgb()
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val actual = image.getRGB(x, y)
                    if (listOf(16, 8, 0).all { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 2 })
                        pixels += y * image.width + x
                }
                val result = ArrayList<androidx.compose.ui.geometry.Rect>()
                while (pixels.isNotEmpty()) {
                    val todo = java.util.ArrayDeque<Int>()
                    val first = pixels.first(); pixels.remove(first); todo.add(first)
                    var left = image.width; var top = image.height; var right = 0; var bottom = 0; var count = 0
                    while (todo.isNotEmpty()) {
                        val at = todo.removeFirst(); val x = at % image.width; val y = at / image.width
                        left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1); count++
                        for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                            val nx = x + dx; val ny = y + dy
                            if (nx in 0 until image.width && ny in 0 until image.height) {
                                val next = ny * image.width + nx
                                if (pixels.remove(next)) todo.add(next)
                            }
                        }
                    }
                    if (count >= 4 * density * density) result += androidx.compose.ui.geometry.Rect(left / density, top / density, right / density, bottom / density)
                }
                return result
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val level = levels.at(canvas.zoom)
                        Box(Modifier.fillMaxSize()) {
                            BlueprintGraph(level.layout, canvas, emptySet(), null, mapLevels = levels, mapLevel = level, initialFit = {},
                                labelExclusions = if (blocked.value) listOfNotNull(measured.value) else emptyList(),
                                cardLook = { card -> full.cards[card.id]?.let { BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                    if (card.id == a.id) "Roles" else "Checks", "Permission grants",
                                    pins = listOf(BlueprintPinLook(colors.source, "grant: Permission", colors.textStrong, true))) } },
                                frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 stages · Pending",
                                    marks = if (marked.value) listOf(BlueprintMark("!", markColor)) else emptyList()) },
                                linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                                modifier = Modifier.fillMaxSize())
                            if (blocked.value) Box(Modifier.absoluteOffset(0.dp, 140.dp).requiredSize(1100.dp, 560.dp)
                                .background(colors.surface).onGloballyPositioned { coordinates ->
                                    val bounds = coordinates.boundsInRoot()
                                    val next = androidx.compose.ui.geometry.Rect(bounds.left / density, bounds.top / density, bounds.right / density, bounds.bottom / density)
                                    if (measured.value != next) measured.value = next
                                })
                        }
                    }
                }
            }
            try {
                settle(); edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    painter.javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter, true)
                    canvas.restore(70.0, 90.0, .06)
                }; settle()
                val camera = canvas.viewport()
                val summary = assertNotNull(levels.at(camera.zoom).frames.getValue(frame.key).summary)
                val body = androidx.compose.ui.geometry.Rect((camera.x + summary.x * camera.zoom).toFloat(),
                    (camera.y + summary.y * camera.zoom).toFloat(), (camera.x + (summary.x + summary.width) * camera.zoom).toFloat(),
                    (camera.y + (summary.y + summary.height) * camera.zoom).toFloat())
                val outside = raster()
                try {
                    val ink = markInk(outside)
                    assertTrue(ink.isEmpty(), "Diagnostic mark metadata must not paint over the graph")
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val receipts = (field(painter, "summaryPaints") as List<*>).filterNotNull().filter { field(it, "id") == summary.id }
                        val texts = receipts.map { field(it, "layout") as androidx.compose.ui.text.TextLayoutResult }
                        assertEquals(1, texts.size, "The tiny body must recover one bounded name outside")
                        val title = texts.single()
                        val shown = title.layoutInput.text.text
                        val prefix = shown.removeSuffix("…")
                        assertEquals(1, title.lineCount)
                        assertTrue(title.size.width <= 320)
                        assertTrue(shown.endsWith("…"), "This supplied name must exceed the one-line outside slot")
                        assertTrue(frame.label.startsWith(prefix) && frame.label[prefix.length].isWhitespace())
                        texts.forEach { text ->
                            assertFalse(text.hasVisualOverflow)
                            assertEquals(text.layoutInput.text.length, text.getLineEnd(text.lineCount - 1))
                            assertTrue(text.layoutInput.style.fontSize.value >= 11f)
                        }
                        val labels = (field(painter, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                        assertEquals(1, labels.size)
                        assertFalse(labels.single().overlaps(body))
                        assertEquals(listOf("!"), (field(painter, "looks") as List<*>).filterIsInstance<BlueprintCardLook>()
                            .single { it.title == frame.label }.marks.map { it.text })
                        blocked.value = true
                    }; settle()
                    assertNotNull(measured.value)
                    val blockedImage = raster()
                    try {
                        val innerInk = markInk(blockedImage)
                        assertTrue(innerInk.isEmpty(), "A measured overlay must not force diagnostic metadata onto the graph")
                        edt {
                            val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                            val exclusion = assertNotNull(measured.value)
                            val labels = (field(painter, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                            assertTrue(labels.none { it.overlaps(exclusion) }, "A bounded name may use available space above the overlay")
                            val receipts = (field(painter, "summaryPaints") as List<*>).filterNotNull()
                            receipts.forEach { receipt ->
                                val text = field(receipt, "layout") as androidx.compose.ui.text.TextLayoutResult
                                assertEquals(1, text.lineCount)
                                assertTrue(text.size.width <= 320)
                            }
                            marked.value = false
                        }; settle()
                        val control = raster()
                        try {
                            assertTrue(markInk(control).isEmpty())
                            assertContentEquals(blockedImage.getRGB(0, 0, blockedImage.width, blockedImage.height, null, 0, blockedImage.width),
                                control.getRGB(0, 0, control.width, control.height, null, 0, control.width),
                                "Changing hidden diagnostic metadata must leave the canvas unchanged")
                        } finally { control.flush() }
                    } finally { blockedImage.flush() }
                } finally { outside.flush() }
                assertSame(full, levels.full)
                assertSame(a, full.cards.getValue(a.id)); assertSame(pin, full.cards.getValue(a.id).pins.single())
                assertEquals("raw-grant", pin.id); assertEquals("Permission", pin.type); assertTrue(pin.provides)
                assertEquals(setOf(a.id, b.id), levels.at(camera.zoom).frames.getValue(frame.key).members)
                assertEquals(camera, canvas.viewport())
            } finally { edt { scene.close() } }
        }
    }

    @Test fun hiddenRawReadabilityUsesFullMeasuredRowsAndPreparedExposureWithoutMovingTheMap() {
        val pin = Pin("value", "Record", true, 110.0, PortWiring.Connected)
        val first = Card("raw-source", 60.0, 90.0, 420.0, 320.0, listOf(pin), 0)
        val second = first.copy(id = "raw-target", x = 540.0, pins = listOf(pin.copy(provides = false)))
        val incomplete = first.copy(id = "incomplete", x = 1020.0, width = 70.0, height = 80.0)
        val nested = Frame("inner", "Typed members", 20.0, 50.0, 1120.0, 420.0,
            setOf(first.id, second.id, incomplete.id), emptyList(), false, parent = "outer")
        val outer = Frame("outer", "Full source", 0.0, 0.0, 1160.0, 500.0, emptySet(), emptyList(), false)
        val link = Link("raw-wire", first.id, second.id, pin.key, pin.key, LinkKind.Wire, listOf("raw-wire"),
            listOf(first.x + first.width to first.y + pin.y, second.x to second.y + pin.y), family = "record")
        val full = BlueprintLayout("readable-source", listOf(first, second, incomplete).associateBy { it.id },
            listOf(outer, nested), listOf(link), 1160.0, 500.0)
        val levels = BlueprintMapLevels.prepare(full, { 2.0 })
        val far = .06
        assertFalse(levels.at(far).layout.cards.containsKey(first.id))
        var logical: Double? = null
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            edt {
                scene = ImageComposeScene((1000 * density).toInt(), (740 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(full, canvas, emptySet(), null, mapLevels = levels, mapLevel = levels.at(canvas.zoom),
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol, "Typed member", "Original independent fact",
                                pins = card.pins.map { row -> BlueprintPinLook(colors.source, "value: Record", colors.textStrong, row.provides) }) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "3 original members") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                edt { canvas.restore(80.0, 90.0, far) }; settle()
                var required = 0.0
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    assertFalse(painter.rows(first.id, canvas.zoom))
                    val before = canvas.viewport()
                    val retained = painter.stats
                    required = assertNotNull(painter.readableZoom(first.x, first.y, first.width, first.height))
                    assertTrue(required.isFinite() && required > far)
                    val exposed = levels.at(required).frames.values.filter { first.id in it.members }
                    assertTrue(exposed.isNotEmpty())
                    assertTrue(exposed.all { it.expanded && it.blend == 1.0 })
                    assertTrue(levels.at(required).layout.cards.containsKey(first.id))
                    assertNull(painter.readableZoom(incomplete.x, incomplete.y, incomplete.width, incomplete.height))
                    assertEquals(before, canvas.viewport())
                    assertEquals(retained, painter.stats)
                    logical?.let { assertEquals(it, required, .0000001) } ?: run { logical = required }
                }
                edt { canvas.restore(100.0 - first.x * required, 100.0 - first.y * required, required) }; settle()
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    assertTrue(painter.rows(first.id, canvas.zoom))
                    val hit = assertNotNull(painter.pinAt((first.x + first.width - 2).toFloat(), (first.y + pin.y).toFloat()))
                    assertEquals(first.id, hit.first.id)
                    assertSame(pin, hit.second)
                    assertEquals("Record", hit.second.type)
                    assertTrue(hit.second.provides)
                    assertEquals(required, assertNotNull(painter.readableZoom(first.x, first.y, first.width, first.height)), .0000001)
                    assertSame(full, levels.full)
                    assertSame(link, full.links.single())
                    assertEquals(listOf(first, second, incomplete), full.cards.values.toList())
                }
                edt { canvas.restore(80.0, 90.0, far) }; settle()
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    assertFalse(painter.rows(first.id, canvas.zoom))
                    assertEquals(required, assertNotNull(painter.readableZoom(first.x, first.y, first.width, first.height)), .0000001)
                    assertNull(painter.readableZoom(incomplete.x, incomplete.y, incomplete.width, incomplete.height))
                }
            } finally { edt { scene.close() } }
        }
    }


    @Test fun regionPinsUseRetainedReadableInkAtUnitZoomWithIndependentCaptionFallback() {
        val out = Pin("published", "Record", true, 130.0, PortWiring.Connected, id = "record-out")
        val input = out.copy(provides = false, id = "record-in")
        val source = Card("producer", 80.0, 240.0, 420.0, 280.0, listOf(out), 0)
        val target = Card("consumer", 900.0, 240.0, 300.0, 280.0, listOf(input), 0)
        val frame = Frame("contracts", "Declared contracts", 0.0, 0.0, 650.0, 620.0, setOf(source.id), emptyList(), false)
        val wire = Link("record-wire", source.id, target.id, out.key, input.key, LinkKind.Wire, listOf("record-wire"),
            listOf(source.x + source.width to source.y + out.y, target.x to target.y + input.y), family = "record")
        val full = BlueprintLayout("region-pin-ink", listOf(source, target).associateBy { it.id }, listOf(frame), listOf(wire), 1200.0, 620.0)
        val levels = BlueprintMapLevels.prepare(full, { 4.0 })
        val summaryId = "summary:${frame.key}"
        val fact = "1 node"
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val text = androidx.compose.runtime.mutableStateOf("published: Record")
            val marks = androidx.compose.runtime.mutableStateOf(emptyList<BlueprintMark>())
            val hits = arrayListOf<Pair<Card, Pin>>()
            val shape = dev.shibasis.reaktor.graph.core.GraphShape(
                listOf(dev.shibasis.reaktor.graph.core.ScopeShape(frame.key, frame.label),
                    dev.shibasis.reaktor.graph.core.ScopeShape("outside", "Outside")),
                full.cards.values.map { card -> dev.shibasis.reaktor.graph.core.NodeShape(card.id,
                    if (card.id in frame.nodes) frame.key else "outside", card.id, "Record", dev.shibasis.reaktor.graph.core.NodeKind.Service,
                    ports = card.pins.map { dev.shibasis.reaktor.graph.core.PortShape(it.key, it.type, it.provides, it.wiring) }) },
                listOf(dev.shibasis.reaktor.graph.core.WireShape(target.id, input.key, source.id, out.key, out.type, sourceId = wire.id)), emptyList())
            val editor = dev.shibasis.reaktor.blueprint.editor.GraphEditor(shape,
                dev.shibasis.reaktor.blueprint.editor.Placement(full.cards.mapValues { (_, card) -> dev.shibasis.reaktor.blueprint.editor.Spot(card.x, card.y) }),
                pins = { node -> full.cards.getValue(node.id).pins })
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val current = levels.at(canvas.zoom)
                        BlueprintGraph(current.layout, canvas, emptySet(), null, mapLevels = levels, mapLevel = current,
                            editor = editor, editable = false, initialFit = {}, cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                if (card.id == summaryId) frame.label else card.id,
                                if (card.id == summaryId) "$fact\nRetained evidence" else "Declared boundary",
                                pins = if (card.id == summaryId) emptyList() else card.pins.map {
                                    BlueprintPinLook(colors.source, text.value, colors.textStrong, it.provides, marks = marks.value)
                                }) }, frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "1 original member") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            onPinClick = { card, pin -> hits += card to pin }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                val engine = painter()
                val previousFrame = field(engine, "summaryPaintFrame") as Long
                edt {
                    engine.javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(engine, true)
                    canvas.restore(60.0, 80.0, 1.0)
                }; settle()
                edt {
                    assertTrue(field(engine, "summaryPaintComplete") as Boolean, "Setup requires a completed observed painter frame")
                    assertTrue((field(engine, "summaryPaintFrame") as Long) > previousFrame)
                }
                val originalView = canvas.viewport()
                fun caption(phase: String) {
                    val receipts = (field(engine, "summaryPaints") as List<*>).filterNotNull()
                    val layouts = receipts.map { field(it, "layout") as androidx.compose.ui.text.TextLayoutResult }
                    if (layouts.count { it.layoutInput.text.text == frame.label } != 1) {
                        val folder = File("build/reports/blueprint-map-paint/region-pin-ink").apply { mkdirs() }
                        val name = "${mode.name.lowercase()}-${density.toInt()}x-$phase"
                        val view = canvas.viewport()
                        File(folder, "$name.tsv").writeText("density\tzoom\tx\ty\tobservedFrame\tcomplete\treceipts\tfactLayouts\tmapLabels\trows\n" +
                            listOf(density, view.zoom, view.x, view.y, field(engine, "summaryPaintFrame"), field(engine, "summaryPaintComplete"),
                                receipts.size, layouts.count { it.layoutInput.text.text.contains(fact) }, (field(engine, "mapLabels") as List<*>).size,
                                engine.rows(summaryId, view.zoom)).joinToString("\t") + "\n")
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { encoded ->
                            val raster = ImageIO.read(ByteArrayInputStream(encoded.bytes))
                            try { ImageIO.write(raster, "png", File(folder, "$name.png")) } finally { raster.flush() }
                        } }
                    }
                    val body = layouts.single { it.layoutInput.text.text == frame.label }
                    assertEquals(frame.label, body.layoutInput.text.text)
                    assertEquals(1, body.lineCount)
                    assertTrue(body.size.width <= 320)
                    assertTrue(layouts.none { it.layoutInput.text.text == fact }, "The count remains accessible without another canvas line")
                    assertTrue(layouts.all { it.lineCount == 1 })
                    assertFalse(body.hasVisualOverflow)
                    assertTrue(body.layoutInput.style.fontSize.value >= 11f)
                }
                edt {
                    assertEquals(1.0, canvas.zoom)
                    assertTrue(engine.rows(summaryId, canvas.zoom), "Actual readable Region pin ink must commit at the original unit camera")
                    val cards = (field(engine, "cards") as Array<*>).filterIsInstance<Card>()
                    val index = cards.indexOfFirst { it.id == summaryId }
                    val card = cards[index]
                    val look = (field(engine, "looks") as List<*>)[index] as BlueprintCardLook
                    assertEquals(BlueprintFace.Region, look.face)
                    assertEquals("published: Record", look.pins.single()?.text)
                    val pin = card.pins.single()
                    assertEquals(out.type, pin.type); assertEquals(out.provides, pin.provides)
                    val refs = levels.at(1.0).forwardedPorts.entries.single { it.key.matches(summaryId, pin) }.value
                    assertEquals(listOf(PinRef(source.id, out.key, out.provides)), refs)
                    val paint = assertNotNull((field(engine, "paints") as Array<*>)[index])
                    val measured = assertNotNull(field(paint, "measured"))
                    val row = assertNotNull((field(measured, "pins") as List<*>).single())
                    val layout = field(row, "text") as androidx.compose.ui.text.TextLayoutResult
                    assertTrue(field(row, "complete") as Boolean)
                    assertEquals(MachineSignal.Type.data, layout.layoutInput.style.fontSize)
                    assertTrue((field(row, "smallest") as Double) >= 11.0)
                    assertFalse(layout.hasVisualOverflow)
                    assertEquals(text.value.length, layout.getLineEnd(layout.lineCount - 1))
                    val point = Offset(((canvas.viewport().x + card.x + card.width - 2) * density).toFloat(),
                        ((canvas.viewport().y + card.y + pin.y) * density).toFloat())
                    assertEquals(card to pin, engine.pinAt((card.x + card.width - 2).toFloat(), (card.y + pin.y).toFloat()))
                    val origin = Offset((canvas.viewport().x + card.x + (field(row, "textLeft") as Float)).toFloat(),
                        (canvas.viewport().y + card.y + pin.y - layout.size.height / 2f).toFloat())
                    assertTrue(origin.x >= (canvas.viewport().x + card.x).toFloat())
                    assertTrue(origin.x + layout.size.width <= canvas.viewport().x + card.x + card.width)
                    val ox = kotlin.math.floor(origin.x * density).toInt(); val oy = kotlin.math.floor(origin.y * density).toInt()
                    val mask = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(layout.size.width * density).toInt() + 4,
                        kotlin.math.ceil(layout.size.height * density).toInt() + 4)
                    val drawing = androidx.compose.ui.graphics.Canvas(mask)
                    drawing.save(); drawing.translate(2f + origin.x * density - ox, 2f + origin.y * density - oy)
                    drawing.scale(density, density); layout.multiParagraph.paint(drawing, Color.White); drawing.restore()
                    val pixels = mask.toPixelMap()
                    scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { encoded ->
                        val raster = ImageIO.read(ByteArrayInputStream(encoded.bytes))
                        try {
                            for (offset in text.value.indices.filter { !text.value[it].isWhitespace() }) {
                                val box = layout.getBoundingBox(offset)
                                val left = kotlin.math.floor(2 + origin.x * density - ox + box.left * density).toInt().coerceAtLeast(0)
                                val right = kotlin.math.ceil(2 + origin.x * density - ox + box.right * density).toInt().coerceAtMost(mask.width)
                                val top = kotlin.math.floor(2 + origin.y * density - oy + box.top * density).toInt().coerceAtLeast(0)
                                val bottom = kotlin.math.ceil(2 + origin.y * density - oy + box.bottom * density).toInt().coerceAtMost(mask.height)
                                val points = (top until bottom).flatMap { y -> (left until right).filter { x -> pixels[x, y].alpha >= .65f }.map { x -> x to y } }
                                assertTrue(points.isNotEmpty(), "Every retained named-pin glyph requires actual raster support")
                                val base = ((top - 2).coerceAtLeast(0) until (bottom + 2).coerceAtMost(mask.height)).flatMap { y ->
                                    ((left - 2).coerceAtLeast(0) until (right + 2).coerceAtMost(mask.width)).map { x -> raster.getRGB(ox + x - 2, oy + y - 2) } }
                                    .groupingBy { it }.eachCount().maxBy { it.value }.key
                                val reference = glyphReference(layout, origin, density, Color(base))
                                val matching = points.count { (x, y) ->
                                    val actual = raster.getRGB(ox + x - 2, oy + y - 2)
                                    val expected = reference[x, y].toArgb()
                                    listOf(16, 8, 0).all { shift ->
                                        kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 18
                                    } && listOf(16, 8, 0).any { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((base ushr shift) and 255)) >= 12 }
                                }
                                assertTrue(matching >= points.size * .8, "Retained Region pin layout must match emitted glyph ink at the readable floor")
                            }
                        } finally { raster.flush() }
                    } }
                    caption("unit")
                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                }; settle()
                assertSame(source, hits.single().first); assertSame(out, hits.single().second)
                edt { text.value = "published: Record ".repeat(80) }; settle()
                edt {
                    assertFalse(engine.rows(summaryId, 1.0), "An overflowing row must not become a hidden ellipsis or forced Rows")
                    caption("overflow"); assertEquals(originalView, canvas.viewport())
                }
                edt { text.value = "published: Record"; marks.value = listOf(BlueprintMark("T", Color.Blue, BlueprintMark.Kind.Tag)) }; settle()
                edt {
                    assertFalse(engine.rows(summaryId, 1.0), "Undersized emitted marks retain the original readable floor")
                    caption("undersized-mark"); assertEquals(originalView, canvas.viewport())
                }
                edt { marks.value = emptyList(); canvas.restore(600.0 - (source.x + source.width - 2) * 8, 400.0 - (source.y + out.y) * 8, 8.0) }; settle()
                edt {
                    assertTrue(engine.rows(source.id, 8.0))
                    assertEquals(source to out, engine.pinAt((source.x + source.width - 2).toFloat(), (source.y + out.y).toFloat()))
                    scene.sendPointerEvent(PointerEventType.Press, Offset(600f * density, 400f * density), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, Offset(600f * density, 400f * density), button = PointerButton.Primary)
                }; settle()
                assertSame(source, hits.last().first); assertSame(out, hits.last().second)
                edt { canvas.restore(originalView.x, originalView.y, 1.0) }; settle()
                edt {
                    assertTrue(engine.rows(summaryId, 1.0)); caption("return")
                    assertEquals(originalView, canvas.viewport()); assertSame(full, levels.full)
                    assertSame(source, full.cards.getValue(source.id)); assertSame(target, full.cards.getValue(target.id))
                    assertSame(out, full.cards.getValue(source.id).pins.single()); assertSame(input, full.cards.getValue(target.id).pins.single())
                    assertSame(wire, full.links.single()); assertEquals(listOf(wire.id), wire.members)
                    assertEquals(source.id, wire.from); assertEquals(target.id, wire.to)
                    assertEquals(out.key, wire.fromPort); assertEquals(input.key, wire.toPort)
                }
            } finally { edt { scene.close() } }
        }
    }

    @Test fun preparedWideFrameKeepsOneBoundedNameAndAccessibleFactsAcrossDensities() {
        data class Emitted(val layout: androidx.compose.ui.text.TextLayoutResult, val origin: Offset, val opacity: Float)
        val input = Pin("record", "Record", false, 55.0, PortWiring.Connected, id = "record-in")
        val output = Pin("state", "Record", true, 55.0, PortWiring.Connected, id = "state-out")
        val members = (0 until 12).map { index -> Card("record-$index", 100.0 + index * 800.0, 120.0, 740.0, 72.0,
            listOf(input.copy(id = "record-in-$index"), output.copy(id = "state-out-$index")), 0) }
        val peers = (0 until 2).map { index -> Card("delivery-$index", 10300.0, 120.0 + index * 240.0, 740.0, 72.0,
            listOf(input.copy(id = "delivery-in-$index"), output.copy(id = "delivery-out-$index")), 0) }
        fun wire(id: String, from: Card, to: Card) = Link(id, from.id, to.id, output.key, input.key, LinkKind.Wire, listOf(id),
            listOf(from.x + from.width to from.y + output.y, to.x to to.y + input.y), family = "Record")
        val wires = members.zipWithNext().mapIndexed { index, (from, to) -> wire("wire:record-$index", from, to) } +
            listOf(wire("wire:delivery-first", members.first(), peers.first()), wire("wire:delivery-last", members.last(), peers.first()),
                wire("wire:delivery-second", members.last(), peers.last()))
        val frame = Frame("feature:records", "Records", 47.0, 70.0, 9838.0, 238.0, members.map { it.id }.toSet(), emptyList(), false)
        val full = BlueprintLayout("prepared-wide-frame", (members + peers).associateBy { it.id }, listOf(frame), wires, 11040.0, 520.0)
        val levels = BlueprintMapLevels.prepare(full, { 2.0 / 3 })
        val summaryId = "summary:${frame.key}"
        val cut = levels.at(1.0).frames.getValue(frame.key)
        val phases = listOf("middle" to .5, "blend" to (cut.leaveZoom + cut.enterZoom) / 2, "expanded" to cut.enterZoom, "return" to .5)
        val kinds = "12 nodes"
        val facts = "12 nodes"
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            var surface = Color.Transparent
            val frameLook = BlueprintFrameLook(SurfaceGraphSymbol, kinds, tag = "prepared-frame:${frame.key}")
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(40) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(4) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun receipts() = (field(painter(), "summaryPaints") as List<*>).filterNotNull().filter { field(it, "id") == summaryId }.map {
                Emitted(field(it, "layout") as androidx.compose.ui.text.TextLayoutResult,
                    Offset(field(it, "x") as Float, field(it, "y") as Float), field(it, "opacity") as Float)
            }
            fun supported(receipt: Emitted, image: java.awt.image.BufferedImage) {
                val text = receipt.layout
                assertFalse(text.hasVisualOverflow)
                assertEquals(text.layoutInput.text.length, text.getLineEnd(text.lineCount - 1))
                assertTrue(text.layoutInput.style.fontSize.value >= 11f)
                assertTrue(text.layoutInput.style.fontSize.value * density >= 11f * density)
                val ox = kotlin.math.floor(receipt.origin.x * density).toInt()
                val oy = kotlin.math.floor(receipt.origin.y * density).toInt()
                val mask = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(text.size.width * density).toInt() + 4,
                    kotlin.math.ceil(text.size.height * density).toInt() + 4)
                val drawing = androidx.compose.ui.graphics.Canvas(mask)
                drawing.save()
                drawing.translate(2f + receipt.origin.x * density - ox, 2f + receipt.origin.y * density - oy)
                drawing.scale(density, density)
                text.multiParagraph.paint(drawing, Color.White)
                drawing.restore()
                val pixels = mask.toPixelMap()
                val reference = glyphReference(text, receipt.origin, density, surface)
                fun channel(value: Int, shift: Int) = (value ushr shift) and 255
                val base = surface.toArgb()
                for (offset in text.layoutInput.text.indices.filter { !text.layoutInput.text[it].isWhitespace() }) {
                    val box = text.getBoundingBox(offset)
                    val left = kotlin.math.floor(2 + receipt.origin.x * density - ox + box.left * density).toInt().coerceAtLeast(0)
                    val top = kotlin.math.floor(2 + receipt.origin.y * density - oy + box.top * density).toInt().coerceAtLeast(0)
                    val right = kotlin.math.ceil(2 + receipt.origin.x * density - ox + box.right * density).toInt().coerceAtMost(mask.width)
                    val bottom = kotlin.math.ceil(2 + receipt.origin.y * density - oy + box.bottom * density).toInt().coerceAtMost(mask.height)
                    val points = (top until bottom).flatMap { y -> (left until right).filter { x -> pixels[x, y].alpha >= .65f }.map { x -> x to y } }
                    assertTrue(points.isNotEmpty(), "The complete supplied glyph must have rasterizable support")
                    points.forEach { (x, y) -> assertTrue(ox + x - 2 in 0 until image.width && oy + y - 2 in 0 until image.height) }
                    val background = (maxOf(0, oy + top - 4)..minOf(image.height - 1, oy + bottom)).flatMap { y ->
                        (maxOf(0, ox + left - 4)..minOf(image.width - 1, ox + right)).map { x -> image.getRGB(x, y) }
                    }.groupingBy { it }.eachCount().maxBy { it.value }.key
                    val matching = points.count { (x, y) ->
                        val actual = image.getRGB(ox + x - 2, oy + y - 2)
                        val expected = reference[x, y].toArgb()
                        listOf(16, 8, 0).all { shift ->
                            val value = (channel(background, shift) + (channel(expected, shift) - channel(base, shift)) * receipt.opacity).toInt().coerceIn(0, 255)
                            kotlin.math.abs(channel(actual, shift) - value) <= 18
                        } && listOf(16, 8, 0).any { kotlin.math.abs(channel(actual, it) - channel(background, it)) >= 12 }
                    }
                    assertTrue(matching >= points.size * .8, "A complete transition glyph must be painted in the actual scene raster")
                }
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        surface = colors.surface
                        val current = levels.at(canvas.zoom)
                        BlueprintGraph(current.layout, canvas, emptySet(), members.first().id, mapLevels = levels, mapLevel = current, initialFit = {},
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                if (card.id == summaryId) frame.label else "Declared ${card.id}", if (card.id == summaryId) facts else "Record contract",
                                pins = card.pins.map { BlueprintPinLook(colors.source, "${it.key}: ${it.type}", colors.textStrong, it.provides) },
                                tag = "prepared-card:${card.id}") },
                            frameLook = { frameLook }, linkStyle = { Blueprint.link(colors.source, false, false) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                edt { painter().javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter(), true) }
                for ((phase, zoom) in phases) {
                    edt { canvas.restore(-1907.0, if (zoom == .5) 394.5 else 90.0 - frame.y * zoom, zoom) }; settle()
                    edt {
                        val before = canvas.viewport()
                        val inherited = generateSequence(painter().javaClass as Class<*>?) { it.superclass }
                            .first { it.declaredFields.any { member -> member.name == "density" } }
                        assertEquals(density, inherited.getDeclaredField("density").apply { isAccessible = true }.getFloat(painter()))
                        val current = levels.at(zoom)
                        val painted = paintMap(levels, current)
                        val retained = (dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java.getDeclaredField("frames")
                            .apply { isAccessible = true }.get(painter()) as List<*>).filterIsInstance<Frame>()
                        assertEquals(painted.layout.frames.map { it.key }, retained.map { it.key })
                        assertEquals(zoom >= cut.enterZoom, current.frames.getValue(frame.key).expanded)
                        if (zoom == .5) {
                            assertTrue(current.layout.frames.isEmpty())
                            assertEquals(summaryId, current.layout.cards.keys.single { it !in full.cards })
                            assertTrue(retained.isEmpty(), "A collapsed projection has no expanded frame for FrameMarker")
                        }
                        val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        val headers = nodes.filter { it.config.getOrNull(SemanticsProperties.TestTag) == frameLook.tag }
                        val expanded = current.frames.getValue(frame.key).expanded
                        assertEquals(painted.layout.frames.count { it.key == frame.key }, headers.size, "The retained painted frame owns its marker throughout the measured blend")
                        val emitted = receipts()
                        val titles = emitted.filter { it.layout.layoutInput.text.text == frame.label }
                        val committed = (field(painter(), "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                        assertTrue(committed.isNotEmpty())
                        val ink = ArrayList<Emitted>()
                        ink += emitted
                        if (retained.isEmpty()) {
                            assertEquals(1, titles.size)
                            assertEquals(0, emitted.count { it.layout.layoutInput.text.text == facts }, "Counts stay in semantics, away from graph ink")
                        } else {
                            assertEquals(if (expanded) 0 else 1, titles.size,
                                "Expanded headers paint through their frame layer, without retaining synthetic-summary receipts")
                            if (expanded) {
                                val headerBounds = headers.single().boundsInRoot
                                assertTrue(headerBounds.width > 0f && headerBounds.height > 0f, "A marker tag without committed physical bounds is not a delivered header")
                                val text = walk(headers.single()).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
                                assertTrue(text.contains(frame.label)); assertTrue(text.contains(kinds))
                            }
                            val box = committed.first()
                            val cap = (field(painter(), "BannerCap") as Number).toFloat()
                            val room = minOf(1100f - MachineSignal.Space.s2.value * 2, maxOf((frame.width * zoom).toFloat(), cap))
                            val space = assertNotNull(painter().javaClass.getDeclaredMethod("bannerSpace", String::class.java, BlueprintFrameLook::class.java, java.lang.Float.TYPE)
                                .apply { isAccessible = true }.invoke(painter(), frame.label, frameLook, room))
                            val measured = painter().javaClass.declaredMethods.single { it.name == "measureBanner" }.apply { isAccessible = true }
                                .invoke(painter(), frame.label, frameLook, space)
                            val layer = assertNotNull(painter().javaClass.getDeclaredMethod("banner", java.lang.Integer.TYPE, java.lang.Float.TYPE, java.lang.Double.TYPE)
                                .apply { isAccessible = true }.invoke(painter(), 0, room, zoom) as androidx.compose.ui.graphics.layer.GraphicsLayer?)
                            val label = field(measured, "label") as androidx.compose.ui.text.TextLayoutResult
                            assertNull(field(measured, "count"))
                            assertEquals(frame.label, label.layoutInput.text.text)
                            assertEquals(1, label.lineCount); assertTrue(label.size.width <= 320)
                            assertTrue(layer.size.width <= 320)
                            if (expanded) ink += Emitted(label, Offset(box.left + 31f, box.top + (layer.size.height - label.size.height) / 2f), 1f)
                            assertTrue(committed.none { candidate -> committed.any { other -> candidate !== other && candidate.overlaps(other) } })
                            if (phase == "blend") assertEquals(0, emitted.count { it.layout.layoutInput.text.text == facts }, "The committed banner already owns the only count fact")
                        }
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { data ->
                            val raster = ImageIO.read(ByteArrayInputStream(data.bytes))
                            try { ink.forEach { supported(it, raster) } } finally { raster.flush() }
                        } }
                        assertEquals(before, canvas.viewport())
                        assertSame(full, levels.full); assertSame(frame, full.frames.single())
                        (members + peers).forEach { card -> assertSame(card, full.cards.getValue(card.id)); card.pins.forEachIndexed { index, pin -> assertSame(pin, full.cards.getValue(card.id).pins[index]) } }
                        wires.forEachIndexed { index, wire -> assertSame(wire, full.links[index]); assertEquals(listOf(wire.id), wire.members)
                            assertEquals(output.key, wire.fromPort); assertEquals(input.key, wire.toPort) }
                        assertEquals(28, full.cards.values.sumOf { it.pins.size }); assertEquals(14, full.links.size)
                        assertEquals(frame.nodes, current.frames.getValue(frame.key).members)
                    }
                }
            } finally { edt { scene.close() } }
        }
    }

    @Test fun hoveredRawRouteRestylesAtTheSamePointerAndRestoresExactInk() {
        val output = Pin("value", "Value", true, 100.0, PortWiring.Connected, id = "value-out")
        val input = output.copy(provides = false, id = "value-in")
        val source = Card("source", 30.0, 70.0, 300.0, 240.0, listOf(output), 0)
        val target = Card("target", 440.0, 70.0, 300.0, 240.0, listOf(input), 0)
        val wire = Link("value-wire", source.id, target.id, output.key, input.key, LinkKind.Wire,
            listOf("value-wire"), listOf(330.0 to 170.0, 440.0 to 170.0), family = "Value")
        val route = Link("raw-hop", source.id, target.id, null, null, LinkKind.Route,
            listOf("raw-hop"), listOf(330.0 to 250.0, 440.0 to 250.0))
        val full = BlueprintLayout("hover-restyle", linkedMapOf(source.id to source, target.id to target),
            emptyList(), listOf(wire, route), 780.0, 360.0)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val excluded = androidx.compose.runtime.mutableStateOf(false)
            val chosen = mutableListOf<Link>()
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun inherited(name: String): Any = generateSequence(painter().javaClass as Class<*>?) { it.superclass }
                .first { type -> type.declaredFields.any { it.name == name } }.getDeclaredField(name).apply { isAccessible = true }.get(painter())
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            edt {
                scene = ImageComposeScene((900 * density).toInt(), (500 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                if (card.id == source.id) "Recorded source" else "Recorded target", "Value contract",
                                pins = card.pins.map { pin -> BlueprintPinLook(colors.source, "value: Value", colors.textStrong, pin.provides) }) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            linkStyle = { link -> if (link.id == route.id) LinkStyle(Color.Blue, width = 2.4f,
                                alpha = if (excluded.value) .1f else 1f, glow = Color.Blue, lifted = true)
                                else LinkStyle(Color.Green, alpha = 1f) },
                            onCardClick = {}, onLinkClick = { chosen += it }, onPaneClick = {},
                            initialFit = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(50.0, 70.0, 1.25) }; settle()
                val camera = canvas.viewport()
                val point = Offset(((camera.x + 385.0 * camera.zoom) * density).toFloat(),
                    ((camera.y + 250.0 * camera.zoom) * density).toFloat())
                edt {
                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Move, point)
                }; settle()
                assertSame(route, chosen.single())
                fun committed(alpha: Float) = edt {
                    assertEquals(1, inherited("hoveredLink"))
                    assertEquals(alpha, (inherited("alphas") as FloatArray)[1])
                    assertEquals(camera, canvas.viewport())
                    assertSame(source, full.cards.getValue(source.id)); assertSame(target, full.cards.getValue(target.id))
                    assertEquals(listOf(wire, route), full.links)
                    assertEquals(listOf("raw-hop"), route.members)
                    assertEquals(output.key, wire.fromPort); assertEquals(input.key, wire.toPort)
                    assertSame(output, full.cards.getValue(source.id).pins.single())
                    assertSame(input, full.cards.getValue(target.id).pins.single())
                    assertEquals("value-out", output.id); assertEquals("value-in", input.id)
                    assertEquals("Value", output.type); assertEquals(output.type, input.type)
                    assertTrue(output.provides); assertFalse(input.provides)
                }
                val probeX = point.x.toInt()
                val probeTop = (point.y - 3 * density).toInt()
                val probeBottom = (point.y + 3 * density).toInt()
                val backgroundY = (point.y + 16 * density).toInt()
                fun contrast(image: java.awt.image.BufferedImage): Int {
                    val background = image.getRGB(probeX, backgroundY)
                    return (probeTop..probeBottom).maxOf { y ->
                        val ink = image.getRGB(probeX, y)
                        listOf(16, 8, 0).sumOf { shift -> kotlin.math.abs(((ink ushr shift) and 255) - ((background ushr shift) and 255)) }
                    }
                }
                committed(1f)
                val included = raster()
                try {
                    val before = contrast(included)
                    assertTrue(before > 100, "The fixed probe must contain actual included route ink")
                    edt { excluded.value = true }; settle()
                    committed(.1f)
                    val muted = raster()
                    try {
                        assertTrue(contrast(muted) < before * .7,
                            "Changing only the supplied style must mute actual hover stroke ink without moving the pointer")
                    } finally { muted.flush() }
                    edt { excluded.value = false }; settle()
                    committed(1f)
                    val restored = raster()
                    try {
                        assertContentEquals(included.getRGB(0, 0, included.width, included.height, null, 0, included.width),
                            restored.getRGB(0, 0, restored.width, restored.height, null, 0, restored.width))
                    } finally { restored.flush() }
                    assertSame(route, chosen.single())
                } finally { included.flush() }
            } finally { edt { scene.close() } }
        }
    }

    @Test fun mutedRawRouteRemainsSelectableAfterMountWithoutExposingFadedRawLinks() {
        val output = Pin("value", "Value", true, 90.0, PortWiring.Connected, id = "value-out")
        val input = output.copy(provides = false, id = "value-in")
        val source = Card("source", 80.0, 160.0, 380.0, 280.0, listOf(output), 0)
        val target = Card("target", 1360.0, 160.0, 380.0, 280.0, listOf(input), 0)
        val wire = Link("value-wire", source.id, target.id, output.key, input.key, LinkKind.Wire,
            listOf("value-wire"), listOf(460.0 to 250.0, 1360.0 to 250.0), family = "Value")
        val route = Link("raw-hop", source.id, target.id, null, null, LinkKind.Route, listOf("raw-hop"),
            listOf(460.0 to 400.0, 520.0 to 550.0, 1300.0 to 550.0, 1360.0 to 400.0))
        val frame = Frame("area", "Recorded area", 40.0, 120.0, 1120.0, 460.0, setOf(source.id), emptyList(), false)
        val full = BlueprintLayout("muted-route-hit", linkedMapOf(source.id to source, target.id to target),
            listOf(frame), listOf(wire, route), 1840.0, 670.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 })
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            for (opacity in listOf(.06f, 0f)) {
                val canvas = GraphCanvasState(.002..16.0)
                val chosen = mutableListOf<Link>()
                lateinit var scene: ImageComposeScene
                var tick = 0L
                fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
                fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                fun hoveredLink() = dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java
                    .getDeclaredField("hoveredLink").apply { isAccessible = true }.getInt(painter())
                edt {
                    scene = ImageComposeScene((1512 * density).toInt(), (982 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                        MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                            val colors = MachineSignal.colors
                            val current = levels.at(canvas.zoom)
                            BlueprintCanvas(current.layout, canvas, emptySet(), null, mapLevels = levels, mapLevel = current,
                                cardLook = { card -> full.cards[card.id]?.let { BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                    if (card.id == source.id) "Recorded source" else "Recorded target", "Value contract",
                                    pins = card.pins.map { pin -> BlueprintPinLook(colors.source, "value: Value", colors.textStrong, pin.provides) }) } },
                                frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "Value contract") },
                                linkStyle = { link -> LinkStyle(Color.Blue, alpha = if (route.id in link.members) opacity else 1f) },
                                onCardClick = {}, onLinkClick = { chosen += it }, onPaneClick = {}, initialFit = {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                try {
                    settle(); edt { canvas.restore(-1300.0, -720.0, 2.0) }; settle()
                    val camera = canvas.viewport()
                    edt {
                        val owner = dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java
                        val committed = (owner.getDeclaredField("links").apply { isAccessible = true }.get(painter()) as List<*>).filterIsInstance<Link>()
                        val index = committed.indexOf(route)
                        assertTrue(index >= 0)
                        assertEquals(opacity, (owner.getDeclaredField("alphas").apply { isAccessible = true }.get(painter()) as FloatArray)[index])
                        val point = Offset(700f * density, 380f * density)
                        scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                    }; settle()
                    edt {
                        assertEquals(if (opacity > 0f) full.links.indexOf(route) else -1, hoveredLink(),
                            "Fresh muted-route hover must use the same exposure authority as its mounted press")
                    }
                    assertEquals(if (opacity > 0f) listOf(route) else emptyList(), chosen,
                        "A retained muted route stays selectable after mount; a transparent route does not")
                    chosen.singleOrNull()?.let { assertSame(route, it) }
                    assertEquals(camera, canvas.viewport())
                    assertSame(source, full.cards.getValue(source.id)); assertSame(target, full.cards.getValue(target.id))
                    assertEquals(listOf(wire, route), full.links)
                    assertEquals(listOf("raw-hop"), route.members)
                    assertEquals(output.key, wire.fromPort); assertEquals(input.key, wire.toPort)
                    assertSame(output, source.pins.single()); assertSame(input, target.pins.single())
                    assertEquals("value-out", output.id); assertEquals("value-in", input.id)
                    assertEquals("Value", output.type); assertEquals(output.type, input.type)
                    assertTrue(output.provides); assertFalse(input.provides)
                    if (opacity > 0f) {
                        val band = levels.at(1.0).frames.getValue(frame.key)
                        val zoom = band.leaveZoom + (band.enterZoom - band.leaveZoom) * .1
                        edt { canvas.restore(700.0 - 1000.0 * zoom, 380.0 - 550.0 * zoom, zoom) }; settle()
                        edt {
                            val paint = paintMap(levels, levels.at(zoom))
                            val index = paint.layout.links.indexOf(route)
                            assertTrue(index >= 0)
                            assertTrue(paint.links[index] > 0f && paint.links[index] < .2f)
                            assertNotEquals(route, painter().paintedLinkAt(1000f, 550f, 4f),
                                "Semantic muting must not expose a raw link before its map transition reaches the existing hit floor")
                            painter().hover(1000f, 550f, 4f)
                            assertEquals(-1, hoveredLink(), "Transition-faded raw links must stay unavailable to hover")
                            assertEquals(listOf(wire, route), full.links)
                        }
                    }
                } finally { edt { scene.close() } }
            }
        }
    }

    @Test fun farDirectedCorridorsKeepVisibleWeightedInkAndOneAttachedCount() {
        val out = Pin("raw|out", "Record", true, 40.0, PortWiring.Connected)
        val input = out.copy(key = "raw|in", provides = false)
        val left = Card("left-node", 1000.0, 2000.0, 400.0, 800.0, listOf(out, input), 0)
        val right = left.copy(id = "right-node", x = 15000.0)
        val frames = listOf(
            Frame("left", "Left", 0.0, 0.0, 8000.0, 8000.0, setOf(left.id), emptyList(), false),
            Frame("right", "Right", 12000.0, 0.0, 8000.0, 8000.0, setOf(right.id), emptyList(), false))
        val forward = Link("forward", left.id, right.id, out.key, input.key, LinkKind.Wire,
            (1..9).map { "forward:$it" }, listOf(1200.0 to 2000.0, 15200.0 to 2000.0), family = "record")
        val reverse = Link("reverse", right.id, left.id, out.key, input.key, LinkKind.Wire,
            listOf("return"), listOf(15200.0 to 6000.0, 1200.0 to 6000.0), family = "record")
        val full = BlueprintLayout("far-ink", listOf(left, right).associateBy { it.id }, frames, listOf(forward, reverse), 20000.0, 8000.0)
        val levels = BlueprintMapLevels.prepare(full, { 1.0 })
        val folder = File("build/reports/blueprint-map-paint/far-corridor-ink").apply { mkdirs() }
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val opacity = androidx.compose.runtime.mutableStateOf(.22f)
            var miniatureFill = 0
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            edt {
                scene = ImageComposeScene((900 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val level = levels.at(canvas.zoom)
                        miniatureFill = lerp(colors.surface, colors.source, .10f).toArgb()
                        BlueprintCanvas(level.layout, canvas, emptySet(), null, mapLevels = levels, mapLevel = level,
                            cardLook = { card -> full.cards[card.id]?.let { BlueprintCardLook(colors.source, SurfaceGraphSymbol, card.id, "") } },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "1 node") },
                            labels = mapOf(forward.id to "×9"),
                            linkStyle = { LinkStyle(colors.source, width = 1.3f, alpha = opacity.value) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(50.0, 80.0, .03) }; settle()
                val camera = canvas.viewport()
                val actual = raster()
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    val painted = (dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java.getDeclaredField("links").let { it.isAccessible = true; it.get(painter) } as List<*>).filterIsInstance<Link>()
                    assertEquals(listOf(9, 1), painted.map { it.members.size })
                    val labels = dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java.getDeclaredField("labels").let { it.isAccessible = true; it.get(painter) } as Map<*, *>
                    assertEquals("×9", labels[forward.id])
                    assertEquals("", labels[reverse.id], "A single raw route does not need a count badge")
                }
                edt { opacity.value = 1f }; settle()
                val strong = raster()
                edt { opacity.value = .05f }; settle()
                val dimmed = raster()
                edt { opacity.value = 0f }; settle()
                val control = raster()
                fun contrast(image: java.awt.image.BufferedImage, y: Int): Int =
                    ((y - 2) * density).toInt().rangeTo(((y + 2) * density).toInt()).maxOf { py ->
                        val x = (300 * density).toInt()
                        listOf(16, 8, 0).maxOf { shift ->
                            kotlin.math.abs(((image.getRGB(x, py) ushr shift) and 255) - ((control.getRGB(x, py) ushr shift) and 255))
                        }
                    }
                val opaqueContrast = contrast(strong, 140)
                assertTrue(opaqueContrast >= 20, "The opaque control must have measurable source-color ink")
                assertTrue(contrast(actual, 140) >= opaqueContrast * .45,
                    "An ordinary .22-alpha cross-scope corridor remains legible at 3%")
                assertTrue(contrast(dimmed, 140) <= opaqueContrast * .12,
                    "Intentional .05 selection dimming must remain dim")
                val miniX = ((camera.x + (left.x + left.width / 2) * camera.zoom) * density).toInt()
                val miniY = ((camera.y + (left.y + left.height * .75) * camera.zoom) * density).toInt()
                listOf(16, 8, 0).forEach { shift ->
                    assertTrue(kotlin.math.abs(((actual.getRGB(miniX, miniY) ushr shift) and 255) - ((miniatureFill ushr shift) and 255)) <= 2,
                        "The original miniature retains a subtle role-colored body at its actual scaled position")
                }
                fun band(y: Int): Int = ((y - 8) * density).toInt().rangeTo(((y + 8) * density).toInt()).count { py ->
                    val x = (300 * density).toInt()
                    actual.getRGB(x, py) != control.getRGB(x, py)
                }
                assertTrue(band(140) >= (2 * density).toInt(), "The forward corridor has visible screen ink at 3%")
                assertTrue(band(260) >= density.toInt(), "The reverse corridor remains independently visible")
                assertTrue(band(140) > band(260), "Nine directed members paint thicker than one reverse member")
                fun arrow(tip: Int, y: Int, direction: Int, length: Float, stroke: Float): Boolean {
                    // Far arrows are bounded to 4–6 screen pixels. Sample their actual upper wing,
                    // behind the attached tip, rather than the previous ten-pixel arrow footprint.
                    val half = Math.PI / 7
                    val back = length * kotlin.math.cos(half)
                    val wing = length * kotlin.math.sin(half)
                    val xs = listOf(tip - direction * back, tip - direction * back * .65)
                    return (kotlin.math.floor(xs.min() * density).toInt()..kotlin.math.ceil(xs.max() * density).toInt()).any { px ->
                        (kotlin.math.floor((y - wing) * density).toInt()..kotlin.math.floor((y - stroke / 2 - .25) * density).toInt()).any { py ->
                            actual.getRGB(px, py) != control.getRGB(px, py)
                        }
                    }
                }
                assertTrue(arrow(410, 140, 1, 6f, 3f), "Forward arrow stays attached to the right boundary")
                assertTrue(arrow(290, 260, -1, 4f, 1f), "Reverse arrow stays attached to the left boundary")
                ImageIO.write(actual, "png", File(folder, "${mode.name}-${density.toInt()}.png"))
                actual.flush(); strong.flush(); dimmed.flush(); control.flush()
                assertEquals(camera, canvas.viewport())
                assertSame(full, levels.at(2.0).layout)
                assertEquals(listOf(forward, reverse), full.links)
                assertEquals(frames, full.frames)
                assertSame(left, full.cards[left.id]); assertSame(right, full.cards[right.id])
                assertEquals(setOf("forward:1", "forward:2", "forward:3", "forward:4", "forward:5", "forward:6", "forward:7", "forward:8", "forward:9", "return"),
                    levels.at(.03).layout.links.flatMap { it.members }.toSet())
            } finally { edt { scene.close() } }
        }
    }

    @Test fun collapsedWorkMembersKeepBoundedReadableNamesAtTwentyThreeAndFortyNinePercent() {
        val cards = (0 until 6).map { k -> Card("task-$k", 40.0 + k % 3 * 380.0, 90.0 + k / 3 * 280.0,
            340.0, 240.0, emptyList(), 0) }
        val frame = Frame("work", "Repair Hangar blueprints", 0.0, 0.0, 1180.0, 660.0,
            cards.mapTo(LinkedHashSet()) { it.id }, emptyList(), false)
        val full = BlueprintLayout("work", cards.associateBy { it.id }, listOf(frame), emptyList(), 1180.0, 660.0)
        var levels by mutableStateOf(BlueprintMapLevels.prepare(full, { .8 }))
        val folder = File("build/reports/blueprint-map-paint/work-members").apply { mkdirs() }
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            var surface = Color.Transparent
            var foreground = Color.Transparent
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        surface = colors.surface
                        foreground = colors.textStrong
                        val current = levels.at(canvas.zoom)
                        BlueprintCanvas(current.layout, canvas, emptySet(), null, initialFit = {},
                            mapLevels = levels, mapLevel = current,
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                "Review typed dependencies and preserve the daily workflow ${card.id}", "Ready", face = BlueprintFace.Prose) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "6 tasks") },
                            linkStyle = { Blueprint.link(colors.source, false, false) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                edt { painter().javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter(), true) }
                for ((zoom, blend) in listOf(.23 to 0.0, .49 to 0.0, .23 to .25, .23 to .75, .49 to .25, .49 to .75)) {
                    val rawOwns = blend >= .5
                    edt {
                        levels = BlueprintMapLevels.prepare(full, { if (blend == 0.0) .8 else zoom / (1 + .12 * blend) })
                        canvas.restore(30.0, 45.0, zoom)
                    }; settle()
                    edt {
                        val view = canvas.viewport()
                        val entries = (field(painter(), "memberPaints") as List<*>).filterNotNull()
                        val memberEntries = entries.filter { field(it, "id") in full.cards }
                        assertEquals(if (rawOwns) emptySet() else full.cards.keys, memberEntries.map { field(it, "id") }.toSet(),
                            "Exactly the dominant miniature owner paints each real member name at $zoom / $blend")
                        val visual = field(painter(), "cardOpacity") as Map<*, *>
                        if (blend > 0.0) {
                            val projected = paintMap(levels, levels.at(zoom))
                            assertTrue(full.cards.keys.all { (projected.cards[it] ?: 0f) > 0f })
                            assertTrue(projected.cards.getValue("summary:work") > 0f)
                            assertTrue(full.cards.keys.all { visual[it] == if (rawOwns) 1f else 0f })
                        }
                        val memberBounds = (field(painter(), "memberLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                        val captions = (field(painter(), "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                        assertEquals(if (rawOwns) 0 else cards.size, memberBounds.size)
                        memberBounds.forEachIndexed { index, box ->
                            assertTrue(captions.none { it.overlaps(box) })
                            assertTrue(memberBounds.drop(index + 1).none { it.overlaps(box) })
                        }
                        assertEquals(setOf("summary:work"), levels.at(zoom).layout.cards.keys)
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { data ->
                            val raster = ImageIO.read(ByteArrayInputStream(data.bytes))
                            try {
                                ImageIO.write(raster, "png", File(folder, "${mode.name.lowercase()}-${density.toInt()}x-$zoom-blend-$blend.png"))
                                for (card in cards) {
                                    val left = ((view.x + card.x * zoom) * density).toInt() + 3
                                    val top = ((view.y + card.y * zoom) * density).toInt() + 3
                                    val right = ((view.x + (card.x + card.width) * zoom) * density).toInt() - 3
                                    val bottom = ((view.y + (card.y + card.height) * zoom) * density).toInt() - 3
                                    val ink = (top until bottom).sumOf { py -> (left until right).count { px ->
                                        val actual = raster.getRGB(px, py)
                                        listOf(16, 8, 0).all { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((foreground.toArgb() ushr shift) and 255)) <= 24 }
                                    } }
                                    assertTrue(ink >= 10 * density, "The winning owner paints a readable member name without a blank interval: ${card.id} at $zoom / $blend")
                                }
                                for (entry in memberEntries) {
                                    val card = full.cards.getValue(field(entry, "id") as String)
                                    val text = field(entry, "layout") as androidx.compose.ui.text.TextLayoutResult
                                    val x = field(entry, "x") as Float; val y = field(entry, "y") as Float
                                    val bounds = androidx.compose.ui.geometry.Rect(x, y, x + text.size.width, y + text.size.height)
                                    val body = androidx.compose.ui.geometry.Rect((view.x + card.x * zoom).toFloat(), (view.y + card.y * zoom).toFloat(),
                                        (view.x + (card.x + card.width) * zoom).toFloat(), (view.y + (card.y + card.height) * zoom).toFloat())
                                    assertTrue(bounds.left >= body.left && bounds.top >= body.top && bounds.right <= body.right && bounds.bottom <= body.bottom)
                                    assertTrue(text.lineCount in 1..2)
                                    assertTrue(text.layoutInput.style.fontSize.value >= 11f)
                                    val reference = glyphReference(text, Offset(x, y), density, surface)
                                    val base = surface.toArgb()
                                    val ox = kotlin.math.floor(x * density).toInt(); val oy = kotlin.math.floor(y * density).toInt()
                                    val ink = (0 until reference.height).flatMap { py -> (0 until reference.width).mapNotNull { px ->
                                        val expected = reference[px, py].toArgb()
                                        if (listOf(16, 8, 0).any { shift -> kotlin.math.abs(((expected ushr shift) and 255) - ((base ushr shift) and 255)) >= 20 }) Triple(px, py, expected) else null
                                    } }
                                    assertTrue(ink.isNotEmpty())
                                    val painted = ink.count { (px, py, expected) ->
                                        val actual = raster.getRGB(ox + px - 2, oy + py - 2)
                                        listOf(16, 8, 0).all { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 18 }
                                    }
                                    assertTrue(painted >= ink.size * .8, "Each bounded member name is really painted at $zoom: ${card.id}")
                                }
                            } finally { raster.flush() }
                        } }
                        assertEquals(view, canvas.viewport())
                        assertEquals(cards.associateBy { it.id }, levels.full.cards)
                    }
                }
            } finally { edt { scene.close() } }
        }
    }

    @Test fun denseWorkNamesAndCodeRowsStayReadableAtUsefulZooms() {
        val folder = File("build/reports/blueprint-map-paint/useful-zoom").apply { mkdirs() }
        for (work in listOf(true, false)) for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val pins = (0 until 22).map { k -> Pin("pin-$k", "PaneState", k % 2 == 1, 55.0 + k / 2 * 18.0, PortWiring.Connected, id = "row-$k") }
            var cards = if (work) (0 until 4).map { k -> Card("task-$k", 40.0 + k % 2 * 440, 40.0 + k / 2 * 310,
                400.0, 300.0, emptyList(), 0) } else listOf(Card("narrow", 40.0, 40.0, 270.0, 300.0, pins, 0),
                Card("wide", 350.0, 40.0, 500.0, 300.0, pins.map { it.copy(id = "wide-${it.id}") }, 0))
            var full = BlueprintLayout("useful-zoom", cards.associateBy { it.id }, emptyList(), emptyList(), 900.0, 660.0)
            val measuredLayout = androidx.compose.runtime.mutableStateOf(full)
            val canvas = GraphCanvasState(.002..16.0)
            val agent = BlueprintAgent(null, "Codex", BlueprintAgent.Activity.Assigned)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            var surface = Color.Transparent
            var textColor = Color.Transparent
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun supported(image: java.awt.image.BufferedImage, text: androidx.compose.ui.text.TextLayoutResult, origin: Offset, scale: Float) {
                assertFalse(text.hasVisualOverflow)
                val reference = glyphReference(text, origin, density, surface, scale)
                val factor = density * scale
                val ox = kotlin.math.floor(origin.x * density).toInt(); val oy = kotlin.math.floor(origin.y * density).toInt()
                val dx = origin.x * density - ox; val dy = origin.y * density - oy
                val base = surface.toArgb()
                for (offset in text.layoutInput.text.indices.filter { !text.layoutInput.text[it].isWhitespace() }) {
                    val box = text.getBoundingBox(offset)
                    val left = kotlin.math.floor(2 + dx + box.left * factor).toInt().coerceAtLeast(0)
                    val right = kotlin.math.ceil(2 + dx + box.right * factor).toInt().coerceAtMost(reference.width)
                    val top = kotlin.math.floor(2 + dy + box.top * factor).toInt().coerceAtLeast(0)
                    val bottom = kotlin.math.ceil(2 + dy + box.bottom * factor).toInt().coerceAtMost(reference.height)
                    val points = (top until bottom).flatMap { y -> (left until right).filter { x ->
                        val expected = reference[x, y].toArgb()
                        listOf(16, 8, 0).any { shift -> kotlin.math.abs(((expected ushr shift) and 255) - ((base ushr shift) and 255)) >= 20 }
                    }.map { x -> x to y } }
                    assertTrue(points.isNotEmpty(), "Each readable glyph keeps contrast: ${text.layoutInput.text[offset]}")
                    val matching = points.count { (x, y) ->
                        val expected = reference[x, y].toArgb()
                        val actual = image.getRGB(ox + x - 2, oy + y - 2)
                        listOf(16, 8, 0).all { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 18 }
                    }
                    assertTrue(matching >= points.size * .8, "Useful zoom paints '${text.layoutInput.text}' glyph '${text.layoutInput.text[offset]}' at $origin")
                }
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (700 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        surface = colors.surface; textColor = colors.textStrong
                        BlueprintCanvas(measuredLayout.value, canvas, emptySet(), null, initialFit = {},
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                if (work) "Review measured task geometry and preserve every original typed dependency while making the daily workflow readable ${card.id}" else "Service ${card.id}",
                                if (work) "Ready" else "Worker", face = if (work) BlueprintFace.Prose else BlueprintFace.Code,
                                agent = if (work) agent else null,
                                pins = card.pins.map { pin -> BlueprintPinLook(colors.source,
                                    if (card.id == "narrow") "state: PaneState" else "binding: NotificationDispatcher", colors.textStrong, pin.provides) }) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") }, linkStyle = { Blueprint.link(colors.source, false, false) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                if (!work) {
                    edt {
                        val engine = painter()
                        val looks = field(engine, "looks") as List<*>
                        cards = cards.mapIndexed { index, card ->
                            val node = dev.shibasis.reaktor.blueprint.BlueprintNode(card.id, 0,
                                inputs = card.pins.filterNot { it.provides }.map { dev.shibasis.reaktor.blueprint.PinSpec(it.key, it.type, it.wiring) },
                                outputs = card.pins.filter { it.provides }.map { dev.shibasis.reaktor.blueprint.PinSpec(it.key, it.type, it.wiring) })
                            val body = engine.body(node, looks[index] as BlueprintCardLook)
                            card.copy(width = maxOf(card.width, body.width), height = maxOf(card.height, body.height)).also {
                                assertEquals(maxOf(card.width, body.width), it.width, "Use the actual shared body measurement, with the original lower bound")
                                assertEquals(card.pins, it.pins)
                            }
                        }
                        full = full.copy(cards = cards.associateBy { it.id })
                        measuredLayout.value = full
                    }
                    settle()
                }
                for (zoom in if (work) listOf(.49) else listOf(.95, 1.0, 1.05, 1.2)) {
                    edt { canvas.restore(30.0, 25.0, zoom) }; settle()
                    edt {
                        val engine = painter(); val view = canvas.viewport()
                        val looks = field(engine, "looks") as List<*>
                        val paints = field(engine, "paints") as Array<*>
                        val labels = (field(engine, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                        val measurer = field(engine, "measurer") as androidx.compose.ui.text.TextMeasurer
                        val type = field(engine, "type") as BlueprintType
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { data ->
                            val raster = ImageIO.read(ByteArrayInputStream(data.bytes))
                            try {
                                ImageIO.write(raster, "png", File(folder, "${if (work) "work" else "code"}-${mode.name.lowercase()}-${density.toInt()}x-$zoom.png"))
                                for ((i, card) in cards.withIndex()) {
                                    val look = looks[i] as BlueprintCardLook; val paint = assertNotNull(paints[i])
                                    assertSame(card, (field(engine, "cards") as Array<*>)[i])
                                    if (work) {
                                        assertEquals(PaintDetail.Tier.Summary, (field(paint, "detail") as PaintDetail).tier)
                                        val body = androidx.compose.ui.geometry.Rect((view.x + card.x * zoom).toFloat(), (view.y + card.y * zoom).toFloat(),
                                            (view.x + (card.x + card.width) * zoom).toFloat(), (view.y + (card.y + card.height) * zoom).toFloat())
                                        val label = labels.single { body.contains(it.center) }
                                        assertTrue(label.left >= body.left && label.right <= body.right && label.top >= body.top && label.bottom <= body.bottom)
                                        assertTrue(label.width <= 320f)
                                        val style = type.style(MachineSignal.Type.label, textColor)
                                        val text = engine.javaClass.declaredMethods.single { it.name == "name" }.apply { isAccessible = true }
                                            .invoke(engine, look.title, style, minOf(304f, body.width - 16f)) as androidx.compose.ui.text.TextLayoutResult
                                        assertTrue(text.layoutInput.style.fontSize.value >= 11f)
                                        supported(raster, text, Offset(label.left + 8f, label.top), 1f)
                                        engine.agentBounds(card.id, zoom)?.let { at ->
                                            val badge = androidx.compose.ui.geometry.Rect((view.x + at.x * zoom).toFloat(), (view.y + at.y * zoom).toFloat(),
                                                (view.x + (at.x + at.width) * zoom).toFloat(), (view.y + (at.y + at.height) * zoom).toFloat())
                                            assertFalse(label.overlaps(badge))
                                        }
                                    } else if (zoom >= 1.0) {
                                        assertTrue(engine.rows(card.id, zoom), "All original typed rows are available at 100%: ${card.id}, density=$density")
                                        assertTrue((field(field(paint, "measured")!!, "rows") as PaintDetail.Content).complete)
                                        val previous = field(paint, "previous")
                                        val alpha = if (previous == null) 1f else field(paint, "blend") as Float
                                        val style = type.style(MachineSignal.Type.data, textColor.copy(alpha = alpha), mono = true)
                                        for ((k, pin) in card.pins.withIndex()) {
                                            val row = assertNotNull(look.pins[k])
                                            val text = measurer.measure(row.text, style, maxLines = 1,
                                                constraints = androidx.compose.ui.unit.Constraints(maxWidth = (card.width / 2 - 22).toInt()))
                                            assertFalse(text.hasVisualOverflow)
                                            val x = card.x + if (pin.provides) card.width - 20 - text.size.width else 20.0
                                            val y = card.y + pin.y - text.size.height / 2.0
                                            supported(raster, text, Offset((view.x + x * zoom).toFloat(), (view.y + y * zoom).toFloat()), zoom.toFloat())
                                            assertSame(pin, full.cards.getValue(card.id).pins[k])
                                        }
                                    }
                                }
                                if (work) assertEquals(cards.size, labels.size)
                                assertEquals(view, canvas.viewport())
                            } finally { raster.flush() }
                        } }
                    }
                }
            } finally { edt { scene.close() } }
        }
    }

    @Test fun assignedMarkerKeepsOneWholeNameAtTheSummaryTitleEntry() {
        val pins = listOf(Pin("event", "Event", false, 80.0, PortWiring.Connected, id = "raw-event"),
            Pin("state", "PaneState", true, 104.0, PortWiring.Connected, id = "raw-state"))
        val card = Card("task", 0.0, 0.0, 270.0, 366.0, pins, 0)
        val full = BlueprintLayout("task-title-entry", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
        val agent = BlueprintAgent(null, "Assigned designer", BlueprintAgent.Activity.Assigned)
        val folder = File("build/reports/blueprint-map-paint/work-title-entry").apply { mkdirs() }
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val subject = androidx.compose.runtime.mutableStateOf("Read onboarding messages")
            val hits = mutableListOf<Pair<Card, Pin>>()
            lateinit var scene: ImageComposeScene
            var textColor = Color.Unspecified
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun readyAgent() {
                var ready = 0
                val deadline = System.nanoTime() + 1_000_000_000L
                while (ready < 2 && System.nanoTime() < deadline) {
                    edt {
                        Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close()
                        val view = canvas.viewport()
                        val committed = painter().agentBounds(card.id, view.zoom)
                        val actual = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                            .singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-agent-${card.id}" }?.boundsInRoot
                        val expected = committed?.let { androidx.compose.ui.geometry.Rect(
                            ((view.x + it.x * view.zoom) * density).toFloat(), ((view.y + it.y * view.zoom) * density).toFloat(),
                            ((view.x + (it.x + it.width) * view.zoom) * density).toFloat(),
                            ((view.y + (it.y + it.height) * view.zoom) * density).toFloat()) }
                        val matches = actual != null && expected != null && actual.width > 0 && actual.height > 0 &&
                            listOf(actual.left - expected.left, actual.top - expected.top, actual.right - expected.right, actual.bottom - expected.bottom)
                                .all { kotlin.math.abs(it) <= 2f * density }
                        ready = if (matches) ready + 1 else 0
                    }
                    if (ready < 2) Thread.sleep(8)
                }
                assertEquals(2, ready, "The existing assigned marker must commit before title ink is sampled")
            }
            fun support(image: java.awt.image.BufferedImage, text: androidx.compose.ui.text.TextLayoutResult, origin: Offset, scale: Float) {
                val factor = density * scale
                val ox = kotlin.math.floor(origin.x * density).toInt()
                val oy = kotlin.math.floor(origin.y * density).toInt()
                val dx = origin.x * density - ox
                val dy = origin.y * density - oy
                val mask = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(text.size.width * factor).toInt() + 4,
                    kotlin.math.ceil(text.size.height * factor).toInt() + 4)
                val drawing = androidx.compose.ui.graphics.Canvas(mask)
                drawing.save(); drawing.translate(2f + dx, 2f + dy); drawing.scale(factor, factor)
                text.multiParagraph.paint(drawing, Color.White); drawing.restore()
                val pixels = mask.toPixelMap()
                for (offset in text.layoutInput.text.indices.filter { !text.layoutInput.text[it].isWhitespace() }) {
                    val box = text.getBoundingBox(offset)
                    val left = kotlin.math.floor(2 + dx + box.left * factor).toInt().coerceAtLeast(0)
                    val right = kotlin.math.ceil(2 + dx + box.right * factor).toInt().coerceAtMost(mask.width)
                    val top = kotlin.math.floor(2 + dy + box.top * factor).toInt().coerceAtLeast(0)
                    val bottom = kotlin.math.ceil(2 + dy + box.bottom * factor).toInt().coerceAtMost(mask.height)
                    val points = (top until bottom).flatMap { y -> (left until right).filter { x -> pixels[x, y].alpha >= .65f }.map { x -> x to y } }
                    assertTrue(points.isNotEmpty(), "Every whole-name glyph needs a retained mask")
                    val base = ((top - 2).coerceAtLeast(0) until (bottom + 2).coerceAtMost(mask.height)).flatMap { y ->
                        ((left - 2).coerceAtLeast(0) until (right + 2).coerceAtMost(mask.width)).map { x -> image.getRGB(ox + x - 2, oy + y - 2) } }
                        .groupingBy { it }.eachCount().maxBy { it.value }.key
                    val reference = glyphReference(text, origin, density, Color(base), scale)
                    val matching = points.count { (x, y) ->
                        val actual = image.getRGB(ox + x - 2, oy + y - 2)
                        val expected = reference[x, y].toArgb()
                        listOf(16, 8, 0).all { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 18 } &&
                            listOf(16, 8, 0).any { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((base ushr shift) and 255)) >= 12 }
                    }
                    assertTrue(matching >= points.size * .8, "Assigned-marker placement must retain actual whole-name glyph ink")
                }
            }
            edt {
                scene = ImageComposeScene((1100 * density).toInt(), (850 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        textColor = colors.textStrong
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { BlueprintCardLook(colors.source, SurfaceGraphSymbol, subject.value, "Ready",
                                pins = listOf(BlueprintPinLook(colors.source, "event: Event", colors.textStrong, false),
                                    BlueprintPinLook(colors.source, "state: PaneState", colors.textStrong, true)),
                                marks = listOf(BlueprintMark("Doing", colors.source), BlueprintMark("2", colors.source)), agent = agent) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            onPinClick = { at, pin -> hits += at to pin }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                var middle: IntArray? = null
                for ((phase, zoom) in listOf("middle" to .5, "entry" to .5, "near" to 2.0, "return" to .5)) {
                    edt { subject.value = if (phase == "entry" || phase == "near") "Read onboarding" else "Read onboarding messages"; canvas.restore(70.0, 90.0, zoom) }
                    settle(); readyAgent()
                    val view = canvas.viewport()
                    edt {
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { encoded ->
                            val raster = ImageIO.read(ByteArrayInputStream(encoded.bytes))
                            try {
                                ImageIO.write(raster, "png", File(folder, "$phase-${mode.name.lowercase()}-${density.toInt()}x.png"))
                                val engine = painter()
                                val paint = assertNotNull((field(engine, "paints") as Array<*>).single())
                                val measured = assertNotNull(field(paint, "measured"))
                                val look = (field(engine, "looks") as List<*>).single() as BlueprintCardLook
                                val detail = field(paint, "detail") as PaintDetail
                                val actualAgent = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                                    .single { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-agent-${card.id}" }.boundsInRoot
                                assertSame(full, field(engine, "layout"))
                                assertSame(card, (field(engine, "cards") as Array<*>).single())
                                pins.forEachIndexed { k, pin -> assertSame(pin, card.pins[k]) }
                                assertEquals(listOf("event: Event", "state: PaneState"), look.pins.map { it?.text })
                                assertEquals("Ready", look.subtitle); assertEquals(listOf("Doing", "2"), look.marks.map { it.text })
                                assertEquals(agent, look.agent)
                                val labels = (field(engine, "mapLabels") as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                                if (phase == "near") {
                                    val rowMeasure = field(measured, "rows")
                                    val textMeasurer = field(engine, "measurer") as androidx.compose.ui.text.TextMeasurer
                                    val pinStyle = field(engine, "chipStyle") as androidx.compose.ui.text.TextStyle
                                    val pinInk = look.pins.mapIndexedNotNull { index, row -> row?.let {
                                        val pin = card.pins[index]
                                        val columns = if (card.pins.any { other -> other.provides != pin.provides && kotlin.math.abs(other.y - pin.y) < BlueprintEngine.RowHeight }) 2 else 1
                                        textMeasurer.measure(row.text, pinStyle, maxLines = 1,
                                            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (card.width / columns - 22).toInt()))
                                            .let { row.text to (it.size to it.hasVisualOverflow) }
                                    } }
                                    assertEquals(PaintDetail.Tier.Rows, detail.tier, "density=$density rows=$rowMeasure body=${field(measured, "bodyHeight")} pinInk=$pinInk")
                                    assertTrue(engine.rows(card.id, zoom))
                                    assertTrue((field(measured, "rows") as PaintDetail.Content).complete)
                                    assertTrue((field(measured, "rows") as PaintDetail.Content).smallest * zoom >= 11.0)
                                    val point = Offset(((view.x + (card.width - 2) * zoom) * density).toFloat(), ((view.y + pins[1].y * zoom) * density).toFloat())
                                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                                } else {
                                    val title: androidx.compose.ui.text.TextLayoutResult
                                    val origin: Offset
                                    val scale: Float
                                    if (phase == "entry") {
                                        assertEquals(PaintDetail.Tier.Title, detail.tier)
                                        assertEquals(0f, field(paint, "blend"))
                                        assertEquals(PaintDetail.Tier.Summary, (field(assertNotNull(field(paint, "previous")), "detail") as PaintDetail).tier)
                                        title = field(paint, "title") as androidx.compose.ui.text.TextLayoutResult
                                        val bounds = engine.javaClass.declaredMethods.single { it.name == "wholeTitleBounds" }.apply { isAccessible = true }
                                            .invoke(engine, card, look, title, measured, card.x.toFloat(), card.y.toFloat()) as androidx.compose.ui.geometry.Rect
                                        origin = Offset((view.x + bounds.left * zoom).toFloat(), (view.y + bounds.top * zoom).toFloat())
                                        scale = zoom.toFloat()
                                        assertTrue(labels.isEmpty(), "A painted entering title must not also acquire an outside caption")
                                        assertEquals(1, canvas.reading()!![BlueprintTitle.Whole])
                                    } else {
                                        assertEquals(PaintDetail.Tier.Summary, detail.tier, "An incomplete rich title keeps one bounded outside name")
                                        val box = labels.single()
                                        val center = (view.x + card.width / 2 * zoom).toFloat()
                                        val gap = MachineSignal.Space.s2.value
                                        val body = androidx.compose.ui.geometry.Rect((view.x + card.x * zoom).toFloat(), (view.y + card.y * zoom).toFloat(),
                                            (view.x + (card.x + card.width) * zoom).toFloat(), (view.y + (card.y + card.height) * zoom).toFloat())
                                        val room = if (body.contains(box.center)) minOf(304f, body.width - gap * 2)
                                            else minOf(320f, minOf(center - gap * 2, 1100f - gap * 2 - center) * 2)
                                        title = engine.javaClass.declaredMethods.single { it.name == "name" }.apply { isAccessible = true }
                                            .invoke(engine, subject.value, (field(engine, "type") as BlueprintType).style(MachineSignal.Type.label, textColor), room) as androidx.compose.ui.text.TextLayoutResult
                                        assertEquals(1, title.lineCount)
                                        assertTrue(title.size.width <= 320)
                                        origin = Offset(box.left + gap, box.top)
                                        scale = 1f
                                    }
                                    val shown = title.layoutInput.text.text
                                    if (phase == "entry") assertEquals(subject.value, shown)
                                    else {
                                        val prefix = shown.removeSuffix("…")
                                        assertTrue(subject.value.startsWith(prefix) && prefix.isNotBlank())
                                        assertTrue(shown == subject.value || subject.value.getOrNull(prefix.length)?.isWhitespace() == true)
                                        val words = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                                            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                                        assertTrue(words.any { it.text.contains(subject.value) }, "The complete subject remains accessible")
                                    }
                                    assertFalse(title.hasVisualOverflow); assertTrue(title.lineCount in 1..2)
                                    assertEquals(shown.length, title.getLineEnd(title.lineCount - 1))
                                    assertTrue(title.layoutInput.style.fontSize.value * scale >= 11f)
                                    val titleBox = androidx.compose.ui.geometry.Rect(origin * density, androidx.compose.ui.geometry.Size(title.size.width * scale * density, title.size.height * scale * density))
                                    assertFalse(titleBox.overlaps(actualAgent), "The actual assigned icon must not intersect the painted name")
                                    support(raster, title, origin, scale)
                                }
                                val values = raster.getRGB(0, 0, raster.width, raster.height, null, 0, raster.width)
                                if (phase == "middle") middle = values
                                if (phase == "return") assertTrue(assertNotNull(middle).contentEquals(values), "Middle → title → typed rows → middle must return every pixel exactly")
                                assertEquals(view, canvas.viewport())
                            } finally { raster.flush() }
                        } }
                    }
                    settle()
                    assertEquals(view, canvas.viewport())
                }
                assertEquals(1, hits.size); assertSame(card, hits.single().first); assertSame(pins[1], hits.single().second)
                assertEquals("raw-state", hits.single().second.id); assertEquals("PaneState", hits.single().second.type); assertTrue(hits.single().second.provides)
            } finally { edt { scene.close() } }
        }
    }

    @Test fun shortSummaryCorridorsPaintBothCountsBesideTheirOriginalDirectedInk() {
        val cards = listOf("a" to 20.0, "b" to 120.0, "c" to 700.0).associate { (id, x) ->
            id to Card(id, x, 20.0, 40.0, 140.0, listOf(Pin("raw|out", "Record", true, 30.0, PortWiring.Connected),
                Pin("raw|in", "Record", false, 120.0, PortWiring.Connected)), 0)
        }
        val frames = listOf(Frame("root", "Map", 0.0, 0.0, 1000.0, 400.0, emptySet(), emptyList(), false),
            Frame("left", "Left", 0.0, 0.0, 500.0, 400.0, setOf("b"), emptyList(), false, "root"),
            Frame("leaf", "Leaf", 10.0, 10.0, 90.0, 180.0, setOf("a"), emptyList(), false, "left"),
            Frame("right", "Right", 650.0, 0.0, 350.0, 400.0, setOf("c"), emptyList(), false, "root"))
        fun wire(id: String, from: String, to: String, y: Double, members: List<String>): Link {
            val start = cards.getValue(from); val end = cards.getValue(to)
            val points = listOf(start.x + start.width / 2 to y, end.x + end.width / 2 to y)
            return Link(id, from, to, "raw|out", "raw|in", LinkKind.Wire, members,
                if (id == "reverse") listOf(points.first(), 650.0 to y, 500.0 to y, points.last()) else points, family = "record")
        }
        val wires = listOf(wire("forward-a", "a", "c", 50.0, listOf("raw-a", "raw-b")),
            wire("forward-b", "b", "c", 80.0, (1..7).map { "raw-forward-$it" }), wire("reverse", "c", "a", 140.0, listOf("raw-return")))
        val full = BlueprintLayout("short-count-ink", cards, frames, wires, 1000.0, 400.0)
        val prepared = BlueprintMapLevels.prepare(full, { if (it.id.startsWith("summary:") || it.id == "b") .8 else 1.6 },
            summary = { if (it.key == "root") null else "summary:${it.key}" })
        val folder = File("build/reports/blueprint-map-paint/short-corridor-counts").apply { mkdirs() }
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val mounted = androidx.compose.runtime.mutableStateOf(prepared.at(.5))
            val visible = androidx.compose.runtime.mutableStateOf(true)
            val first = mounted.value
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun inherited(name: String) = generateSequence(painter().javaClass as Class<*>?) { it.superclass }
                .first { type -> type.declaredFields.any { it.name == name } }.getDeclaredField(name).apply { isAccessible = true }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { result = ImageIO.read(ByteArrayInputStream(it.bytes)) } }
                return assertNotNull(result)
            }
            edt {
                scene = ImageComposeScene((2100 * density).toInt(), (900 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val level = mounted.value
                        BlueprintCanvas(level.layout, canvas, emptySet(), null,
                            cardLook = { card -> full.cards[card.id]?.let { raw -> BlueprintCardLook(colors.source, SurfaceGraphSymbol, raw.id, "",
                                pins = raw.pins.map { pin -> BlueprintPinLook(colors.source, if (pin.provides) "Record out" else "Record in", colors.source, pin.provides) }) } },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "Record") },
                            linkStyle = { LinkStyle(colors.source, width = 1.3f, alpha = if (visible.value) .5f else 0f) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(),
                            fitKey = 0, fitScene = full.key, initialFit = {}, mapLevels = prepared, mapLevel = level)
                    }
                }
            }
            try {
                settle()
                var far: IntArray? = null
                for ((phase, zoom) in listOf("far" to .5, "mid" to 1.0, "near" to 2.0, "return" to .5)) {
                    edt { mounted.value = prepared.at(zoom, mounted.value.expanded); canvas.restore(40.0, 40.0, zoom) }; settle()
                    val view = canvas.viewport()
                    edt {
                        val engine = painter()
                        val counts = IntArray(16)
                        inherited("replayCounts").set(engine, counts)
                        val omit = inherited("replayOmission").get(engine) as androidx.compose.runtime.MutableIntState
                        omit.intValue = if (omit.intValue == -2) -1 else -2; Snapshot.sendApplyNotifications()
                        val image = raster()
                        try {
                            ImageIO.write(image, "png", File(folder, "$phase-${mode.name.lowercase()}-${density.toInt()}x.png"))
                            val values = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                            if (phase == "far") far = values
                            if (phase == "return") assertContentEquals(assertNotNull(far), values)
                            assertSame(full, prepared.full); assertEquals(cards, full.cards); assertEquals(frames, full.frames); assertEquals(wires, full.links)
                            cards.values.forEach { raw -> raw.pins.forEachIndexed { k, pin -> assertSame(pin, full.cards.getValue(raw.id).pins[k]) } }
                            if (zoom == 2.0) assertSame(full, mounted.value.layout)
                            if (zoom == .5) {
                                val links = (inherited("links").get(engine) as List<*>).filterIsInstance<Link>()
                                val labels = inherited("labels").get(engine) as Map<*, *>
                                val placed = (inherited("placed").get(engine) as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>().toList()
                                assertEquals(listOf(9, 1), links.map { it.members.size })
                                assertEquals(listOf("×9", ""), links.map { labels[it.id] })
                                assertEquals(1, counts[12], "Only an aggregate needs a count; both directed corridors remain exposed")
                                assertEquals(1, placed.size)
                                val header = androidx.compose.ui.geometry.Rect(40f, 40f, 540f, 60f)
                                val strokes = links.map { link ->
                                    val start = link.points.first(); val end = link.points.last()
                                    assertEquals(75.0, kotlin.math.abs(end.first - start.first) * zoom)
                                    val half = BlueprintFarProjection.corridor(link.members.size, 1.3f, zoom).width * zoom.toFloat() / 2 + 1f
                                    val x0 = (view.x + minOf(start.first, end.first) * zoom).toFloat()
                                    val x1 = (view.x + maxOf(start.first, end.first) * zoom).toFloat()
                                    val y = (view.y + start.second * zoom).toFloat()
                                    androidx.compose.ui.geometry.Rect(x0, y - half, x1, y + half)
                                }
                                assertTrue(placed.all { box -> !box.overlaps(header) && strokes.none { it.overlaps(box) } })
                                counts.fill(0); omit.intValue = 12; Snapshot.sendApplyNotifications()
                                val omitted = raster()
                                try {
                                    assertEquals(1, counts[12])
                                    for (stroke in strokes) for (y in kotlin.math.floor(stroke.top * density).toInt() until kotlin.math.ceil(stroke.bottom * density).toInt())
                                        for (x in kotlin.math.ceil(stroke.left * density).toInt() until kotlin.math.floor(stroke.right * density).toInt())
                                            assertEquals(omitted.getRGB(x, y), image.getRGB(x, y), "Count labels must leave both directional corridors exposed")
                                    val measurer = field(engine, "measurer") as androidx.compose.ui.text.TextMeasurer
                                    val type = field(engine, "type") as BlueprintType
                                    val tints = inherited("tints").get(engine) as IntArray
                                    links.forEachIndexed { index, link ->
                                        if ((labels[link.id] as String).isBlank()) return@forEachIndexed
                                        val text = measurer.measure(labels[link.id] as String,
                                            type.label(Color(tints[index] or 0xFF000000.toInt()).copy(alpha = .96f)), maxLines = 1)
                                        assertFalse(text.hasVisualOverflow); assertTrue(text.layoutInput.style.fontSize.value >= 11f)
                                        val box = placed[index]
                                        val origin = Offset(box.left + 10f, box.top + 4f)
                                        val ox = kotlin.math.floor(origin.x * density).toInt(); val oy = kotlin.math.floor(origin.y * density).toInt()
                                        val dx = origin.x * density - ox; val dy = origin.y * density - oy
                                        val mask = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(text.size.width * density).toInt() + 4,
                                            kotlin.math.ceil(text.size.height * density).toInt() + 4)
                                        val drawing = androidx.compose.ui.graphics.Canvas(mask)
                                        drawing.save(); drawing.translate(2f + dx, 2f + dy); drawing.scale(density, density)
                                        text.multiParagraph.paint(drawing, Color.White); drawing.restore()
                                        val pixels = mask.toPixelMap()
                                        for (offset in text.layoutInput.text.indices) {
                                            val glyph = text.getBoundingBox(offset)
                                            val left = kotlin.math.floor(2 + dx + glyph.left * density).toInt().coerceAtLeast(0)
                                            val right = kotlin.math.ceil(2 + dx + glyph.right * density).toInt().coerceAtMost(mask.width)
                                            val top = kotlin.math.floor(2 + dy + glyph.top * density).toInt().coerceAtLeast(0)
                                            val bottom = kotlin.math.ceil(2 + dy + glyph.bottom * density).toInt().coerceAtMost(mask.height)
                                            val points = (top until bottom).flatMap { y -> (left until right).filter { x -> pixels[x, y].alpha >= .65f }.map { x -> x to y } }
                                            assertTrue(points.isNotEmpty())
                                            val base = ((top - 2).coerceAtLeast(0) until (bottom + 2).coerceAtMost(mask.height)).flatMap { y ->
                                                ((left - 2).coerceAtLeast(0) until (right + 2).coerceAtMost(mask.width)).map { x -> image.getRGB(ox + x - 2, oy + y - 2) } }
                                                .groupingBy { it }.eachCount().maxBy { it.value }.key
                                            val reference = glyphReference(text, origin, density, Color(base))
                                            val supported = points.count { (x, y) ->
                                                val actual = image.getRGB(ox + x - 2, oy + y - 2)
                                                val expected = reference[x, y].toArgb()
                                                actual != omitted.getRGB(ox + x - 2, oy + y - 2) &&
                                                    listOf(16, 8, 0).all { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 18 } &&
                                                    listOf(16, 8, 0).any { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((base ushr shift) and 255)) >= 12 }
                                            }
                                            assertTrue(supported >= points.size * .8, "Each original directed count needs actual readable glyph ink")
                                        }
                                    }
                                } finally { omitted.flush(); omit.intValue = -1; Snapshot.sendApplyNotifications() }
                                val restored = raster()
                                try { assertContentEquals(values, restored.getRGB(0, 0, restored.width, restored.height, null, 0, restored.width)) }
                                finally { restored.flush() }
                                assertEquals(first.layout, mounted.value.layout); assertEquals(first.forwardedPorts, mounted.value.forwardedPorts)
                            }
                            assertEquals(view, canvas.viewport())
                        } finally { image.flush(); inherited("replayCounts").set(engine, null) }
                    }
                }
                edt { visible.value = false }; settle()
                edt {
                    val counts = IntArray(16)
                    inherited("replayCounts").set(painter(), counts)
                    try { raster().flush(); assertEquals(0, counts[12]); assertTrue((inherited("placed").get(painter()) as List<*>).isEmpty()) }
                    finally { inherited("replayCounts").set(painter(), null) }
                }
            } finally { edt { scene.close() } }
        }
    }

    @Test fun coherentOwnershipKeepsPaintedContextTypedRowsAndExactOpeningReturn() {
        val originals = listOf(
            Card("read", 40.0, 140.0, 240.0, 160.0, listOf(
                Pin("in", "Record", false, 100.0, PortWiring.Connected, id = "read-in"),
                Pin("out", "Record", true, 140.0, PortWiring.Connected, id = "read-out")), 0),
            Card("publish", 350.0, 140.0, 240.0, 160.0, listOf(
                Pin("in", "Record", false, 100.0, PortWiring.Connected, id = "publish-in"),
                Pin("out", "Record", true, 140.0, PortWiring.Connected, id = "publish-out")), 0),
            Card("dispatch", 350.0, 440.0, 240.0, 160.0, listOf(
                Pin("in", "Record", false, 100.0, PortWiring.Connected, id = "dispatch-in"),
                Pin("out", "Record", true, 140.0, PortWiring.Connected, id = "dispatch-out")), 0))
        val group = Frame("delivery", "Delivery", 20.0, 20.0, 620.0, 360.0,
            originals.take(2).map { it.id }.toSet(), emptyList(), false)
        val wires = originals.take(2).map { from -> Link("wire:${from.id}", from.id, originals[2].id, "out", "in", LinkKind.Wire,
            listOf("wire:${from.id}"), listOf(from.x + from.width to from.y + 140.0,
                from.x + from.width to 400.0, originals[2].x to 400.0, originals[2].x to originals[2].y + 100.0), family = "Record") }
        val full = BlueprintLayout("independent-middle", originals.associateBy { it.id }, listOf(group), wires, 660.0, 620.0,
            cardOf = originals.associate { it.id to it.id })
        val levels = BlueprintMapLevels.prepare(full, { when (it.id) { "read" -> .6; "publish" -> 1.5; else -> .2 } })
        val summary = "summary:${group.key}"
        val titles = mapOf("read" to "Read policy", "publish" to "Publish state", "dispatch" to "Dispatch event")
        val below = levels.at(1.49)
        assertFalse(below.frames.getValue(group.key).expanded)
        assertEquals(setOf(summary, "dispatch"), below.layout.cards.keys)
        val cut = levels.at(1.8)
        assertEquals(1.5, cut.frames.getValue(group.key).leaveZoom)
        assertEquals(1.5 * 1.12, cut.frames.getValue(group.key).enterZoom)
        assertTrue(cut.frames.getValue(group.key).expanded)
        assertEquals(1.0, cut.frames.getValue(group.key).blend)
        assertTrue(cut.memberBlends.isEmpty())
        assertEquals(originals.map { it.id }.toSet(), paintMap(levels, cut).layout.cards.keys)
        originals.forEach { assertEquals(1f, paintMap(levels, cut).cards[it.id]) }
        assertFalse(paintMap(levels, cut).cards.containsKey(summary), "Readable members cannot coexist with an enclosing summary")
        assertEquals(1f, paintMap(levels, cut).frames[group.key])
        wires.forEachIndexed { k, wire -> assertSame(wire, cut.layout.links[k]) }
        val fractional = levels.at(1.5 * 1.06)
        val partial = paintMap(levels, fractional)
        assertTrue(fractional.memberBlends.isEmpty())
        assertEquals(.5, fractional.frames.getValue(group.key).blend, 1e-9)
        assertEquals(.5f, partial.cards.getValue("read"), .000001f)
        assertEquals(.5f, partial.cards.getValue("publish"), .000001f)
        assertEquals(.5f, partial.frames.getValue(group.key), .000001f)
        assertEquals(.5f, partial.cards.getValue(summary), .000001f)
        partial.layout.links.forEachIndexed { index, link ->
            assertEquals(minOf(partial.cards[link.from] ?: partial.frames[link.from] ?: 1f,
                partial.cards[link.to] ?: partial.frames[link.to] ?: 1f), partial.links[index])
        }
        val midpoint = Card("midpoint", 800.0, 80.0, 80.0, 60.0, emptyList(), 0)
        val nested = midpoint.copy(id = "nested", y = 280.0)
        val midpointFrame = Frame("midpoint-frame", "Midpoint", 760.0, 20.0, 300.0, 180.0,
            setOf(midpoint.id), emptyList(), false)
        val nestedOuter = midpointFrame.copy(key = "nested-outer", label = "Nested", y = 220.0, nodes = emptySet())
        val nestedInner = nestedOuter.copy(key = "nested-inner", label = "Inner", nodes = setOf(nested.id), parent = nestedOuter.key)
        val mixedFull = full.copy(cards = full.cards + listOf(midpoint, nested).associateBy { it.id },
            frames = full.frames + listOf(midpointFrame, nestedOuter, nestedInner), width = 1080.0)
        val mixedLevels = BlueprintMapLevels.prepare(mixedFull, { when (it.id) {
            "read" -> .6; "publish" -> 1.5; "dispatch" -> .2; else -> 1.0
        } })
        val mixed = mixedLevels.at(1.06)
        val mixedPaint = paintMap(mixedLevels, mixed)
        assertTrue(mixed.memberBlends.isEmpty())
        assertFalse(mixed.frames.getValue(group.key).expanded)
        assertEquals(1f, mixedPaint.cards.getValue("summary:${group.key}"))
        assertFalse("read" in mixedPaint.cards)
        assertFalse("publish" in mixedPaint.cards)
        assertEquals(.5f, mixedPaint.cards.getValue(midpoint.id), .00001f)
        assertEquals(.5f, mixedPaint.cards.getValue("summary:${midpointFrame.key}"), .00001f)
        assertEquals(.5f, mixedPaint.frames.getValue(midpointFrame.key), .00001f)
        assertEquals(.25f, mixedPaint.cards.getValue(nested.id), .00001f)
        assertEquals(.25f, mixedPaint.cards.getValue("summary:${nestedInner.key}"), .00001f)
        assertEquals(.25f, mixedPaint.frames.getValue(nestedInner.key), .00001f)
        mixedFull.cards.forEach { (id, card) -> assertSame(card, mixedLevels.full.cards[id]) }
        mixedPaint.layout.cards.filterKeys(mixedFull.cards::containsKey).forEach { (id, card) -> assertSame(mixedFull.cards[id], card) }
        val legacyFull = mixedFull.copy(cards = listOf(midpoint, nested).associateBy { it.id },
            frames = listOf(midpointFrame, nestedOuter, nestedInner), links = emptyList(),
            cardOf = listOf(midpoint, nested).associate { it.id to it.id })
        val legacyLevels = BlueprintMapLevels.prepare(legacyFull, { 1.0 })
        val legacy = legacyLevels.at(1.06)
        val legacyPaint = paintMap(legacyLevels, legacy)
        assertTrue(legacy.memberBlends.isEmpty())
        assertEquals(.5f, legacyPaint.cards.getValue(midpoint.id), .00001f)
        assertEquals(.5f, legacyPaint.cards.getValue("summary:${midpointFrame.key}"), .00001f)
        assertEquals(.5f, legacyPaint.frames.getValue(midpointFrame.key), .00001f)
        assertEquals(.25f, legacyPaint.cards.getValue(nested.id), .00001f)
        assertEquals(.25f, legacyPaint.cards.getValue("summary:${nestedInner.key}"), .00001f)
        assertEquals(.25f, legacyPaint.frames.getValue(nestedInner.key), .00001f)
        assertEquals(84.8, midpoint.width * 1.06, .00001)
        assertEquals(1.6, 128.0 / midpoint.width)
        val folder = File("build/reports/blueprint-map-paint/independent-middle").apply { mkdirs() }
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val mounted = androidx.compose.runtime.mutableStateOf(levels.at(.35))
            val hits = mutableListOf<Pair<Card, Pin>>()
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(40) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(4) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun inherited(name: String): Any = dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java
                .getDeclaredField(name).apply { isAccessible = true }.get(painter())
            fun supported(image: java.awt.image.BufferedImage, text: androidx.compose.ui.text.TextLayoutResult, origin: Offset, scale: Float) {
                assertFalse(text.hasVisualOverflow)
                assertEquals(text.layoutInput.text.length, text.getLineEnd(text.lineCount - 1))
                assertTrue(text.layoutInput.style.fontSize.value * scale >= 11f)
                val factor = density * scale
                val ox = kotlin.math.floor(origin.x * density).toInt(); val oy = kotlin.math.floor(origin.y * density).toInt()
                val dx = origin.x * density - ox; val dy = origin.y * density - oy
                val mask = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(text.size.width * factor).toInt() + 4,
                    kotlin.math.ceil(text.size.height * factor).toInt() + 4)
                val drawing = androidx.compose.ui.graphics.Canvas(mask)
                drawing.save(); drawing.translate(2f + dx, 2f + dy); drawing.scale(factor, factor)
                text.multiParagraph.paint(drawing, Color.White); drawing.restore()
                val pixels = mask.toPixelMap()
                for (offset in text.layoutInput.text.indices.filter { !text.layoutInput.text[it].isWhitespace() }) {
                    val box = text.getBoundingBox(offset)
                    val left = kotlin.math.floor(2 + dx + box.left * factor).toInt().coerceAtLeast(0)
                    val right = kotlin.math.ceil(2 + dx + box.right * factor).toInt().coerceAtMost(mask.width)
                    val top = kotlin.math.floor(2 + dy + box.top * factor).toInt().coerceAtLeast(0)
                    val bottom = kotlin.math.ceil(2 + dy + box.bottom * factor).toInt().coerceAtMost(mask.height)
                    val points = (top until bottom).flatMap { y -> (left until right).filter { x -> pixels[x, y].alpha >= .7f }.map { x -> x to y } }
                    assertTrue(points.isNotEmpty(), "Every retained glyph needs actual support")
                    points.forEach { (x, y) -> assertTrue(ox + x - 2 in 0 until image.width && oy + y - 2 in 0 until image.height) }
                    val base = ((top - 2).coerceAtLeast(0) until (bottom + 2).coerceAtMost(mask.height)).flatMap { y ->
                        ((left - 2).coerceAtLeast(0) until (right + 2).coerceAtMost(mask.width)).map { x -> image.getRGB(ox + x - 2, oy + y - 2) } }
                        .groupingBy { it }.eachCount().maxBy { it.value }.key
                    val reference = glyphReference(text, origin, density, Color(base), scale)
                    val matches = points.count { (x, y) ->
                        val actual = image.getRGB(ox + x - 2, oy + y - 2); val expected = reference[x, y].toArgb()
                        listOf(16, 8, 0).all { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 18 } &&
                            listOf(16, 8, 0).any { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((base ushr shift) and 255)) >= 12 }
                    }
                    if (matches < points.size * .8) {
                        val expected = java.awt.image.BufferedImage(mask.width, mask.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        for (y in 0 until mask.height) for (x in 0 until mask.width) expected.setRGB(x, y, reference[x, y].toArgb())
                        ImageIO.write(expected, "png", File(folder, "unsupported-reference-${mode.name.lowercase()}-${density.toInt()}x.png"))
                        expected.flush()
                    }
                    assertTrue(matches >= points.size * .8, "Each subject, fact and typed row must appear in the mounted raster: " +
                        "\'${text.layoutInput.text.text}\' glyph \'${text.layoutInput.text[offset]}\' at $origin scale=$scale support=$matches/${points.size}")
                }
            }
            edt {
                scene = ImageComposeScene((1400 * density).toInt(), (1360 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val current = mounted.value
                        BlueprintCanvas(current.layout, canvas, emptySet(), originals[0].id, mapLevels = levels, mapLevel = current,
                            cardLook = { card -> full.cards[card.id]?.let { raw -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                titles.getValue(raw.id), "Ready", pins = raw.pins.map { pin ->
                                    BlueprintPinLook(colors.source, "${pin.key}: ${pin.type}", colors.textMuted, pin.provides) },
                                marks = listOf(BlueprintMark("Live", colors.source)), tag = "independent-card:${raw.id}") } },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 nodes", tag = "independent-frame:${group.key}") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            onPinClick = { card, pin -> hits += card to pin }, modifier = Modifier.fillMaxSize(),
                            fitKey = 0, fitScene = full.key, initialFit = {})
                    }
                }
            }
            try {
                settle()
                edt { painter().javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter(), true) }
                var opening: IntArray? = null
                for ((phase, zoom) in listOf("opening" to .35, "middle" to 1.8, "near" to 2.0, "return" to .35)) {
                    edt { mounted.value = levels.at(zoom, mounted.value.expanded); canvas.restore(50.0, 60.0, zoom) }; settle()
                    edt {
                        val engine = painter(); val view = canvas.viewport(); val current = mounted.value
                        val drawn = (field(engine, "cards") as Array<*>).filterIsInstance<Card>()
                        val looks = field(engine, "looks") as List<*>
                        val paints = field(engine, "paints") as Array<*>
                        val expected = paintMap(levels, current)
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { data ->
                            val raster = ImageIO.read(ByteArrayInputStream(data.bytes))
                            try {
                                ImageIO.write(raster, "png", File(folder, "$phase-${mode.name.lowercase()}-${density.toInt()}x.png"))
                                assertEquals(expected.layout.cards.keys, drawn.map { it.id }.toSet())
                                assertEquals(expected.layout.frames.map { it.key }, (inherited("frames") as List<*>).filterIsInstance<Frame>().map { it.key })
                                assertEquals(group.nodes, current.frames.getValue(group.key).members)
                                assertSame(full, levels.full); assertSame(group, full.frames.single())
                                originals.forEach { card -> assertSame(card, full.cards.getValue(card.id)); card.pins.forEachIndexed { k, pin -> assertSame(pin, full.cards.getValue(card.id).pins[k]) } }
                                wires.forEachIndexed { k, wire -> assertSame(wire, full.links[k]); assertEquals("out", wire.fromPort); assertEquals("in", wire.toPort); assertEquals("Record", wire.family) }
                                assertEquals(wires.map { it.id }.toSet(), expected.layout.links.flatMap { it.members }.toSet() + expected.layout.foldedLinks.keys)
                                val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                                if (phase == "opening" || phase == "return") {
                                    assertTrue(current.memberBlends.isEmpty())
                                    assertTrue(originals[0].width * zoom < 128.0)
                                    assertEquals(setOf(summary, "dispatch"), drawn.map { it.id }.toSet())
                                    val emitted = (field(engine, "summaryPaints") as List<*>).filterNotNull().filter { field(it, "id") == summary }
                                    assertEquals(listOf(group.label), emitted.map { (field(it, "layout") as androidx.compose.ui.text.TextLayoutResult).layoutInput.text.text })
                                    emitted.forEach { receipt ->
                                        val text = field(receipt, "layout") as androidx.compose.ui.text.TextLayoutResult
                                        assertEquals(1, text.lineCount); assertTrue(text.size.width <= 320)
                                        supported(raster, text, Offset(field(receipt, "x") as Float, field(receipt, "y") as Float), 1f)
                                    }
                                    val members = field(engine, "summaryMembers") as Map<*, *>
                                    assertEquals(group.nodes, (assertNotNull(members[summary]) as List<*>).map { ((it as Pair<*, *>).first as Card).id }.toSet())
                                    val corridor = expected.layout.links.single()
                                    assertEquals(2, corridor.members.size); assertEquals(summary, corridor.from); assertEquals("dispatch", corridor.to)
                                    assertTrue(expected.links.single() > 0f)
                                    assertEquals(group.nodes.map { PinRef(it, "out", true) }.toSet(), current.forwardedPorts.values.flatten().toSet())
                                    for (card in originals.take(2)) {
                                        val x = ((view.x + card.x * zoom) * density).toInt()
                                        val y = ((view.y + (card.y + card.height / 2) * zoom) * density).toInt()
                                        val center = ((view.x + (card.x + card.width / 2) * zoom) * density).toInt()
                                        val surface = raster.getRGB(center, y)
                                        assertTrue((-2..2).any { dx -> listOf(16, 8, 0).any { shift ->
                                            kotlin.math.abs(((raster.getRGB(x + dx, y) ushr shift) and 255) - ((surface ushr shift) and 255)) >= 20
                                        } }, "Each hidden member retains its actual scaled rectangle outline")
                                    }
                                } else {
                                    val shown = originals
                                    assertEquals(shown.map { it.id }.toSet(), drawn.filter { it.id in full.cards }.map { it.id }.toSet())
                                    if (phase == "middle") {
                                        assertTrue(current.frames.getValue(group.key).expanded); assertEquals(1.0, current.frames.getValue(group.key).blend)
                                        originals.forEach { assertEquals(1f, expected.cards.getValue(it.id)) }
                                        assertFalse(expected.cards.containsKey(summary))
                                        assertEquals(1f, (field(engine, "cardOpacity") as Map<*, *>)["read"] ?: 1f, "Absent fade overrides retain the fully opaque raw card")
                                        assertEquals("read", current.layout.cardOf["read"])
                                        assertEquals("publish", current.layout.cardOf["publish"])
                                        assertTrue(current.forwardedPorts.isEmpty())
                                        wires.forEachIndexed { k, wire -> assertSame(wire, current.layout.links[k]) }
                                    } else assertSame(full, field(engine, "layout"))
                                    val header = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "independent-frame:${group.key}" }
                                    assertTrue(header.boundsInRoot.width > 0f && header.boundsInRoot.height > 0f)
                                    assertTrue(walk(header).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { it.text.contains(group.label) })
                                    for (card in shown) {
                                        val index = drawn.indexOfFirst { it.id == card.id }; assertSame(card, drawn[index])
                                        val look = looks[index] as BlueprintCardLook; val paint = assertNotNull(paints[index])
                                        assertEquals(titles.getValue(card.id), look.title); assertEquals("Ready", look.subtitle)
                                        assertEquals(listOf("Live"), look.marks.map { it.text })
                                        assertEquals(card.pins.map { "${it.key}: ${it.type}" }, look.pins.map { it?.text })
                                        assertEquals(card.pins.map { it.provides }, look.pins.map { it?.filled })
                                        assertTrue(engine.rows(card.id, zoom), "The measured near-row envelope is retained without forcing a tier")
                                        for ((methodName, value) in listOf("cardTitle" to look.title, "cardFact" to look.subtitle)) {
                                            val method = engine.javaClass.declaredMethods.single { it.name == methodName && it.parameterCount == 5 }.apply { isAccessible = true }
                                            val emitted = assertNotNull(method.invoke(engine, card, look, paint, card.x.toFloat(), card.y.toFloat()) as? Pair<*, *>)
                                            val text = emitted.first as androidx.compose.ui.text.TextLayoutResult; val box = emitted.second as androidx.compose.ui.geometry.Rect
                                            assertEquals(value, text.layoutInput.text.text); assertTrue(text.lineCount in 1..(if (methodName == "cardTitle") 2 else 1))
                                            for (line in 0 until text.lineCount - 1) {
                                                val end = text.getLineEnd(line, visibleEnd = true)
                                                assertTrue(end == value.length || value[end].isWhitespace() || value[end - 1].isWhitespace())
                                            }
                                            supported(raster, text, Offset((view.x + box.left * zoom).toFloat(), (view.y + box.top * zoom).toFloat()), zoom.toFloat())
                                        }
                                        val style = (field(engine, "subtitleStyles") as Array<*>)[1] as androidx.compose.ui.text.TextStyle
                                        val measure = engine.javaClass.declaredMethods.single { it.name == "measure" && it.parameterCount == 4 }.apply { isAccessible = true }
                                        for ((k, pin) in card.pins.withIndex()) {
                                            val row = assertNotNull(look.pins[k])
                                            val text = measure.invoke(engine, row.text, style, card.width.toFloat() / 2 - 26f, 1) as androidx.compose.ui.text.TextLayoutResult
                                            val x = card.x + if (pin.provides) card.width - 20f - text.size.width else 20.0
                                            val y = card.y + pin.y - text.size.height / 2f
                                            assertEquals(row.text, text.layoutInput.text.text)
                                            supported(raster, text, Offset((view.x + x * zoom).toFloat(), (view.y + y * zoom).toFloat()), zoom.toFloat())
                                        }
                                    }
                                }
                                val values = raster.getRGB(0, 0, raster.width, raster.height, null, 0, raster.width)
                                if (phase == "opening") opening = values
                                if (phase == "return") assertContentEquals(assertNotNull(opening), values, "Opening → useful middle → typed near → opening retains every pixel")
                                assertEquals(view, canvas.viewport())
                            } finally { raster.flush() }
                        } }
                        assertEquals(50.0, canvas.viewport().x); assertEquals(60.0, canvas.viewport().y); assertEquals(zoom, canvas.zoom)
                        if (phase == "near") {
                            val card = originals[0]; val pin = card.pins[1]
                            val target = assertNotNull(engine.pinAt((card.x + card.width - 10).toFloat(), (card.y + pin.y).toFloat()))
                            assertSame(card, target.first); assertSame(pin, target.second)
                            val point = Offset(((view.x + (card.x + card.width - 10) * zoom) * density).toFloat(), ((view.y + (card.y + pin.y) * zoom) * density).toFloat())
                            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                        }
                    }
                    settle()
                }
                assertEquals(1, hits.size); assertSame(originals[0], hits.single().first); assertSame(originals[0].pins[1], hits.single().second)
                assertEquals("read-out", hits.single().second.id); assertEquals("Record", hits.single().second.type); assertTrue(hits.single().second.provides)
            } finally { edt { scene.close() } }
        }
    }

    @Test fun unavailableSummaryBodyNamesAttachToTheirOwnTypedBoundaryBeforeGenericFallback() {
        data class Emitted(val id: String, val layout: androidx.compose.ui.text.TextLayoutResult, val origin: Offset)
        val cards = listOf("a" to 205.0, "b" to 553.0).associate { (id, x) ->
            id to Card(id, x, 310.0, 80.0, 140.0, listOf(
                Pin("out", "Event", true, 30.0, PortWiring.Connected), Pin("in", "Event", false, 120.0, PortWiring.Connected)), 0)
        }
        val frames = listOf(Frame("root", "Delivery", 0.0, 0.0, 800.0, 500.0, emptySet(), emptyList(), false),
            Frame("left", "Read Inputs", 200.0, 300.0, 90.0, 160.0, setOf("a"), emptyList(), false, "root"),
            Frame("right", "Ship Results", 548.0, 300.0, 90.0, 160.0, setOf("b"), emptyList(), false, "root"))
        fun wire(id: String, from: String, to: String, y: Double, count: Int): Link {
            val start = cards.getValue(from); val end = cards.getValue(to)
            return Link(id, from, to, "out", "in", LinkKind.Wire, (1..count).map { "$id:$it" },
                listOf(start.x + start.width / 2 to y, end.x + end.width / 2 to y), family = "Event")
        }
        val wires = listOf(wire("forward", "a", "b", 340.0, 9), wire("reverse", "b", "a", 430.0, 1))
        val full = BlueprintLayout("endpoint-captions", cards, frames, wires, 800.0, 500.0)
        val prepared = BlueprintMapLevels.prepare(full, { if (it.id.startsWith("summary:")) .8 else 1.6 },
            summary = { if (it.key == "root") null else "summary:${it.key}" })
        val folder = File("build/blueprint-map-paint/endpoint-captions").apply { mkdirs() }
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val mounted = androidx.compose.runtime.mutableStateOf(prepared.at(.5))
            lateinit var scene: ImageComposeScene
            var surface = Color.Transparent
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun baseField(name: String) = dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine::class.java
                .getDeclaredField(name).apply { isAccessible = true }
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { result = ImageIO.read(ByteArrayInputStream(it.bytes)) } }
                return assertNotNull(result)
            }
            edt {
                scene = ImageComposeScene((1400 * density).toInt(), (1100 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        surface = colors.surface
                        val level = mounted.value
                        BlueprintCanvas(level.layout, canvas, emptySet(), null,
                            cardLook = { card -> full.cards[card.id]?.let { raw -> BlueprintCardLook(colors.source, SurfaceGraphSymbol, "Stage ${raw.id}", "Ready",
                                pins = raw.pins.map { pin -> BlueprintPinLook(colors.source, if (pin.provides) "out" else "in", colors.source, pin.provides) }) } },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") }, linkStyle = { LinkStyle(colors.source, width = 1.3f, alpha = .5f) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(),
                            fitKey = 0, fitScene = full.key, initialFit = {}, mapLevels = prepared, mapLevel = level)
                    }
                }
            }
            try {
                settle()
                edt { painter().javaClass.getDeclaredField("observeSummaryPaints").apply { isAccessible = true }.setBoolean(painter(), true) }
                var far: IntArray? = null
                for ((phase, zoom, x) in listOf(Triple("far", .5, 40.0), Triple("mid", 1.0, 40.0), Triple("near", 2.0, 40.0),
                    Triple("clip", .5, -160.0), Triple("return", .5, 40.0))) {
                    edt { mounted.value = prepared.at(zoom, mounted.value.expanded); canvas.restore(x, 40.0, zoom) }; settle()
                    edt {
                        val engine = painter()
                        val omit = baseField("replayOmission").get(engine) as androidx.compose.runtime.MutableIntState
                        omit.intValue = if (omit.intValue == -2) -1 else -2; Snapshot.sendApplyNotifications()
                        val view = canvas.viewport()
                        val image = raster()
                        try {
                            ImageIO.write(image, "png", File(folder, "$phase-${mode.name.lowercase()}-${density.toInt()}x.png"))
                            val values = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                            if (phase == "far") far = values
                            if (phase == "return") assertContentEquals(assertNotNull(far), values)
                            assertSame(full, prepared.full); assertEquals(cards, full.cards); assertEquals(frames, full.frames); assertEquals(wires, full.links)
                            cards.values.forEach { raw -> raw.pins.forEachIndexed { k, pin -> assertSame(pin, full.cards.getValue(raw.id).pins[k]) } }
                            if (zoom == 2.0) assertSame(full, mounted.value.layout)
                            if (zoom == .5) {
                                val entries = (field(engine, "summaryPaints") as List<*>).filterNotNull().map { value ->
                                    Emitted(field(value, "id") as String, field(value, "layout") as androidx.compose.ui.text.TextLayoutResult,
                                        Offset(field(value, "x") as Float, field(value, "y") as Float))
                                }
                                val labels = (baseField("placed").get(engine) as List<*>).filterIsInstance<androidx.compose.ui.geometry.Rect>()
                                val active = mounted.value.layout
                                val gap = MachineSignal.Space.s2.value
                                val glyph = MachineSignal.Editor.controlIconSize.value + MachineSignal.Editor.controlGap.value
                                val boxes = ArrayList<androidx.compose.ui.geometry.Rect>()
                                var anchored = 0
                                val evidence = ArrayList<String>()
                                for (card in active.cards.values) {
                                    val frame = frames.singleOrNull { "summary:${it.key}" == card.id } ?: continue
                                    val right = (view.x + (card.x + card.width) * zoom).toFloat()
                                    if (right <= gap) {
                                        assertTrue(entries.none { it.id == card.id })
                                        continue
                                    }
                                    val owned = entries.filter { it.id == card.id }
                                    val title = owned.single { it.layout.layoutInput.text.text == frame.label }
                                    assertEquals(1, owned.size, "An owner-bound caption paints only its name")
                                    assertEquals(1, title.layout.lineCount); assertFalse(title.layout.hasVisualOverflow)
                                    assertTrue(title.layout.layoutInput.style.fontSize.value >= 11f)
                                    for (line in 0 until title.layout.lineCount - 1) {
                                        val end = title.layout.getLineEnd(line, visibleEnd = true)
                                        val value = title.layout.layoutInput.text.text
                                        assertTrue(end == value.length || value[end].isWhitespace() || value[end - 1].isWhitespace())
                                    }
                                    val width = title.layout.size.width + glyph + gap * 2
                                    val height = title.layout.size.height.toFloat() + gap * 2
                                    assertTrue(width <= 129f, "The measured complete subject caption must fit the demonstrated corridor gap")
                                    val box = androidx.compose.ui.geometry.Rect(title.origin.x - glyph - gap, title.origin.y - gap,
                                        title.origin.x - glyph - gap + width, title.origin.y - gap + height)
                                    assertTrue(labels.none { it.overlaps(box) }); assertTrue(boxes.none { it.overlaps(box) }); boxes += box
                                    assertTrue(active.cards.values.none { other ->
                                        box.overlaps(androidx.compose.ui.geometry.Rect((view.x+ other.x * zoom).toFloat(), (view.y+ other.y * zoom).toFloat(),
                                            (view.x+ (other.x+ other.width) * zoom).toFloat(), (view.y+ (other.y+ other.height) * zoom).toFloat()))
                                    })
                                    val endpoints = active.links.filter { it.from == card.id || it.to == card.id }.flatMap { link ->
                                        BlueprintFarProjection.boundaryLabels(link, view, 1400.0, 1100.0, width / 2.0, height / 2.0,
                                            (BlueprintFarProjection.corridor(link.members.size, 1.3f, zoom).width * zoom).toDouble())
                                    }.filter { it.owner == card.id }
                                    endpoints.forEach { endpoint ->
                                        assertSame(active.links.single { it.id == endpoint.link.id }, endpoint.link)
                                        assertEquals(if (endpoint.provides) endpoint.link.fromPort else endpoint.link.toPort, endpoint.port)
                                        assertEquals(if (endpoint.provides) endpoint.link.trace.first() else endpoint.link.trace.last(), endpoint.boundary)
                                        val ref = PinRef(endpoint.owner, assertNotNull(endpoint.port), endpoint.provides)
                                        val forwarded = mounted.value.forwardedPorts[ref].orEmpty()
                                        assertTrue(forwarded.isNotEmpty())
                                        forwarded.forEach { raw ->
                                            val pin = full.cards.getValue(raw.card).pins.single { raw.matches(raw.card, it) }
                                            assertEquals("Event", pin.type); assertEquals(endpoint.provides, pin.provides)
                                        }
                                    }
                                    val matching = endpoints.filter { endpoint -> !endpoint.clipped && endpoint.anchor?.let { (x, y) ->
                                        kotlin.math.abs(x - box.center.x) < .01 && kotlin.math.abs(y - box.center.y) < .01
                                    } == true }
                                    if (matching.isNotEmpty()) anchored++
                                    evidence += "owner=${card.id}; titleOrigin=${title.origin}; caption=$box; typedEndpoints=${endpoints.size}; anchored=${matching.size}"
                                    for (emitted in owned) {
                                        val text = emitted.layout; val origin = emitted.origin
                                        val ox = kotlin.math.floor(origin.x * density).toInt(); val oy = kotlin.math.floor(origin.y * density).toInt()
                                        val reference = glyphReference(text, origin, density, surface)
                                        val mask = androidx.compose.ui.graphics.ImageBitmap(reference.width, reference.height)
                                        val drawing = androidx.compose.ui.graphics.Canvas(mask)
                                        drawing.save(); drawing.translate(2f + origin.x * density - ox, 2f + origin.y * density - oy); drawing.scale(density, density)
                                        text.multiParagraph.paint(drawing, Color.White); drawing.restore()
                                        val pixels = mask.toPixelMap()
                                        for (offset in text.layoutInput.text.indices.filter { !text.layoutInput.text[it].isWhitespace() }) {
                                            val bounds = text.getBoundingBox(offset)
                                            val dx = origin.x * density - ox; val dy = origin.y * density - oy
                                            val points = (kotlin.math.floor(2 + dy + bounds.top * density).toInt().coerceAtLeast(0) until
                                                kotlin.math.ceil(2 + dy + bounds.bottom * density).toInt().coerceAtMost(mask.height)).flatMap { y ->
                                                (kotlin.math.floor(2 + dx + bounds.left * density).toInt().coerceAtLeast(0) until
                                                    kotlin.math.ceil(2 + dx + bounds.right * density).toInt().coerceAtMost(mask.width))
                                                    .filter { x -> pixels[x, y].alpha >= .7f }.map { x -> x to y }
                                            }
                                            assertTrue(points.isNotEmpty())
                                            val support = points.count { (x, y) ->
                                                val actual = image.getRGB(ox + x - 2, oy + y - 2); val expected = reference[x, y].toArgb()
                                                listOf(16, 8, 0).all { shift -> kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 18 }
                                            }
                                            assertTrue(support >= points.size * .8, "Each owner-bound name needs readable glyph ink")
                                        }
                                    }
                                }
                                File(folder, "$phase-${mode.name.lowercase()}-${density.toInt()}x.txt").writeText(evidence.joinToString("\n"))
                                if (phase != "clip") { assertEquals(2, boxes.size); assertTrue(anchored > 0, "At least one complete subject caption must anchor to its exact typed incident boundary") }
                                else assertTrue(active.links.flatMap { BlueprintFarProjection.boundaryLabels(it, view, 1400.0, 1100.0, 50.0, 20.0, 3.0) }.any { it.clipped })
                            }
                            assertEquals(view, canvas.viewport())
                        } finally { image.flush() }
                    }
                }
            } finally { edt { scene.close() } }
        }
    }
}
