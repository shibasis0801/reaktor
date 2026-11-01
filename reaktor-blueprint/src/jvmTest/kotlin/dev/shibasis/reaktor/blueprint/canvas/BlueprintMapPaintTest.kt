@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
                            assertEquals("2 cards", look.subtitle)
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

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
}
