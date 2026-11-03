@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

class BlueprintFarProjectionDiagnosticTest {
    @Test fun nestedTypedPreparedPhasesRenderThroughOrdinaryCanvas() {
        val cards = listOf("a" to 20.0, "b" to 120.0, "c" to 700.0).associate { (id, x) ->
            id to Card(id, x, 20.0, 40.0, 140.0, listOf(
                Pin("raw|out", "Record", true, 30.0, PortWiring.Connected),
                Pin("raw|in", "Record", false, 120.0, PortWiring.Connected)), 0)
        }
        val frames = listOf(
            Frame("root", "Map", 0.0, 0.0, 1000.0, 400.0, emptySet(), emptyList(), false),
            Frame("left", "Left", 0.0, 0.0, 500.0, 400.0, setOf("b"), emptyList(), false, "root"),
            Frame("leaf", "Leaf", 10.0, 10.0, 90.0, 180.0, setOf("a"), emptyList(), false, "left"),
            Frame("right", "Right", 650.0, 0.0, 350.0, 400.0, setOf("c"), emptyList(), false, "root"))
        fun wire(id: String, from: String, to: String, y: Double, members: List<String>): Link {
            val start = cards.getValue(from)
            val end = cards.getValue(to)
            val points = listOf(start.x + start.width / 2 to y, end.x + end.width / 2 to y)
            return Link(id, from, to, "raw|out", "raw|in", LinkKind.Wire, members,
                if (id == "reverse") listOf(points.first(), 650.0 to y, 500.0 to y, points.last()) else points,
                family = "record")
        }
        val wires = listOf(
            wire("forward-a", "a", "c", 50.0, listOf("raw-a", "raw-b")),
            wire("forward-b", "b", "c", 80.0, listOf("raw-c", "raw-d", "raw-e", "raw-f", "raw-g", "raw-h", "raw-i")),
            wire("reverse", "c", "a", 140.0, listOf("raw-return")))
        val full = BlueprintLayout("far-phases", cards, frames, wires, 1000.0, 400.0)
        val prepared = BlueprintMapLevels.prepare(full,
            { if (it.id.startsWith("summary:") || it.id == "b") .8 else 1.6 },
            summary = { if (it.key == "root") null else "summary:${it.key}" })
        val folder = Files.createTempDirectory("blueprint-far-phases-").toFile()
        File(folder, "geometry.txt").writeText(full.toString())
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val mounted = mutableStateOf(prepared.at(.5))
            val first = mounted.value
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun edt(block: () -> Unit) {
                if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
            }
            fun settle() {
                repeat(16) {
                    edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }
                    Thread.sleep(3)
                }
            }
            edt {
                scene = ImageComposeScene((2100 * density).toInt(), (900 * density).toInt(), Density(density),
                    coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val level = mounted.value
                        BlueprintCanvas(level.layout, canvas, emptySet(), null,
                            linkStyle = { LinkStyle(colors.source, width = 1.3f) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "Record") },
                            cardLook = { card -> full.cards[card.id]?.let { raw ->
                                BlueprintCardLook(colors.source, SurfaceGraphSymbol, raw.id, "",
                                    pins = raw.pins.map { pin -> BlueprintPinLook(colors.source,
                                        if (pin.provides) "Record out" else "Record in", colors.source, true) })
                            } },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize(), fitKey = 0, fitScene = full.key,
                            initialFit = {}, mapLevels = prepared, mapLevel = level)
                    }
                }
            }
            try {
                settle()
                for ((phase, zoom) in listOf("far" to .5, "mid" to 1.0, "near" to 2.0, "return" to .5)) {
                    edt {
                        mounted.value = prepared.at(zoom, mounted.value.expanded)
                        canvas.restore(40.0, 40.0, zoom)
                    }
                    settle()
                    val level = mounted.value
                    assertSame(full, prepared.full)
                    if (phase == "near") assertSame(full, level.layout)
                    if (phase == "return") {
                        assertEquals(first.layout, level.layout)
                        assertEquals(first.forwardedPorts, level.forwardedPorts)
                    }
                    val stem = "${mode.name}-${density.toInt()}-$phase"
                    File(folder, "$stem.txt").writeText("camera=${canvas.viewport()}\nlevel=$level\n")
                    edt {
                        scene.render(++tick * 16_666_667L).use { image ->
                            image.encodeToData()!!.use { data ->
                                val raster = assertNotNull(ImageIO.read(ByteArrayInputStream(data.bytes)))
                                try { assertTrue(ImageIO.write(raster, "png", File(folder, "$stem.png"))) }
                                finally { raster.flush() }
                            }
                        }
                    }
                }
            } finally { edt { scene.close() } }
        }
        println("BlueprintFarProjectionDiagnosticTest images: ${folder.absolutePath}")
    }

    @Test fun denseNestedMembersUseDeliveredFullFitAndTypedDirectedCorridors() {
        val cards = (0 until 121).associate { index ->
            val local = if (index < 60) index else index - 60
            val x = (if (index < 60) 200.0 else 3800.0) + local % 6 * 400.0
            val y = 200.0 + local / 6 * 220.0
            val id = "member:$index"
            id to Card(id, x, y, 120.0, 80.0, listOf(
                Pin("record|out", "Record", true, 16.0, PortWiring.Connected),
                Pin("record|in", "Record", false, 32.0, PortWiring.Connected),
                Pin("event|out", "Event", true, 48.0, PortWiring.Connected),
                Pin("event|in", "Event", false, 64.0, PortWiring.Connected)), 0)
        }
        fun ids(range: IntRange) = range.map { "member:$it" }.toSet()
        val frames = listOf(
            Frame("root", "Dense map", 0.0, 0.0, 6600.0, 2800.0, emptySet(), emptyList(), false),
            Frame("left", "Producers", 0.0, 0.0, 3000.0, 2600.0, ids(30..59), emptyList(), false, "root"),
            Frame("left-inner", "Record producers", 100.0, 100.0, 2600.0, 1100.0, ids(0..29), emptyList(), false, "left"),
            Frame("right", "Consumers", 3600.0, 0.0, 3000.0, 2600.0, ids(90..120), emptyList(), false, "root"),
            Frame("right-inner", "Record consumers", 3700.0, 100.0, 2600.0, 1100.0, ids(60..89), emptyList(), false, "right"))
        fun wire(index: Int, from: Int, to: Int, event: Boolean, reversed: Boolean): Link {
            val start = cards.getValue("member:$from")
            val end = cards.getValue("member:$to")
            val key = if (event) "event" else "record"
            val sy = start.y + if (event) 48.0 else 16.0
            val ey = end.y + if (event) 64.0 else 32.0
            val sx = start.x + start.width
            val ex = end.x
            val middle = (sx + ex) / 2
            return Link("wire:$index", start.id, end.id, "$key|out", "$key|in", LinkKind.Wire,
                listOf("raw:$index"), listOf(sx to sy, middle to sy, middle to ey, ex to ey),
                reversed = reversed, family = "typed")
        }
        val wires = (0 until 60).map { wire(it, it, it + 60, it % 2 == 1, it % 3 == 0) } +
            (0 until 11).map { wire(it + 60, it + 60, it, it % 2 == 1, false) }
        val full = BlueprintLayout("dense-far", cards, frames, wires, 6600.0, 2800.0)
        val prepared = full.blueprintMapLevels()
        val folder = Files.createTempDirectory("blueprint-far-dense-").toFile()
        println("BlueprintFarProjectionDiagnosticTest dense images: ${folder.absolutePath}")
        File(folder, "geometry.txt").writeText(full.toString())
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            var observed: BlueprintLayout<String>? = null
            var paintedBounds = emptyList<Bounds>()
            var observedZoom = 0.0
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun edt(block: () -> Unit) {
                if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
            }
            edt {
                scene = ImageComposeScene((1512 * density).toInt(), (982 * density).toInt(), Density(density),
                    coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val level = prepared.at(canvas.zoom)
                        BlueprintGraph(full, canvas, emptySet(), null,
                            linkStyle = { LinkStyle(colors.source, alpha = 1f) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "Typed flow") },
                            cardLook = { card -> full.cards[card.id]?.let { raw ->
                                BlueprintCardLook(colors.source, SurfaceGraphSymbol, raw.id, "",
                                    pins = raw.pins.map { pin -> BlueprintPinLook(colors.source,
                                        pin.type, colors.source, true) })
                            } },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize(), mapLevels = prepared, mapLevel = level,
                            onGeometry = { layout, zoom, bounds ->
                                observed = layout; observedZoom = zoom; paintedBounds = bounds
                            })
                    }
                }
            }
            try {
                repeat(32) {
                    edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }
                    Thread.sleep(3)
                }
                val level = prepared.at(canvas.zoom)
                assertEquals(121, prepared.full.cards.size)
                assertSame(full, prepared.full)
                assertNotNull(observed)
                assertTrue(paintedBounds.isNotEmpty())
                assertEquals(wires.flatMap { it.members }.toSet(),
                    level.layout.links.flatMap { it.members }.toSet() + level.layout.foldedLinks.keys)
                level.layout.links.forEach { link ->
                    val raw = wires.filter { original -> original.members.any(link.members::contains) }
                    assertEquals(setOf(link.reversed), raw.map { it.reversed }.toSet())
                    for (provides in listOf(true, false)) {
                        val ref = PinRef(if (provides) link.from else link.to,
                            assertNotNull(if (provides) link.fromPort else link.toPort), provides)
                        val attached = level.layout.links.filter { projected ->
                            (if (provides) projected.from else projected.to) == ref.card &&
                                (if (provides) projected.fromPort else projected.toPort) == ref.key
                        }.flatMap { it.members }.toSet()
                        val expected = wires.filter { original -> original.members.any(attached::contains) }
                            .map { original -> PinRef(if (provides) original.from else original.to,
                                assertNotNull(if (provides) original.fromPort else original.toPort), provides) }.toSet()
                        assertEquals(expected, level.forwardedPorts[ref]?.toSet() ?: setOf(ref))
                        val pin = level.layout.cards.getValue(ref.card).pins.single { ref.matches(ref.card, it) }
                        expected.forEach { endpoint ->
                            assertEquals(pin.type, cards.getValue(endpoint.card).pins.single {
                                endpoint.matches(endpoint.card, it)
                            }.type)
                        }
                    }
                }
                val stem = "${mode.name}-${density.toInt()}-fit"
                File(folder, "$stem.txt").writeText("camera=${canvas.viewport()}\nobservedZoom=$observedZoom\n" +
                    "reading=${canvas.reading()}\npaintedBounds=$paintedBounds\npainted=$observed\nprepared=$level\n")
                edt {
                    scene.render(++tick * 16_666_667L).use { image ->
                        image.encodeToData()!!.use { data ->
                            val raster = assertNotNull(ImageIO.read(ByteArrayInputStream(data.bytes)))
                            try { assertTrue(ImageIO.write(raster, "png", File(folder, "$stem.png"))) }
                            finally { raster.flush() }
                        }
                    }
                }
                assertEquals(cards, full.cards)
                assertEquals(frames, full.frames)
                assertEquals(wires, full.links)
                val fitCamera = canvas.viewport()
                val fitLevel = level
                fun boundaryReceipt(current: BlueprintMapLevel<String>, view: dev.shibasis.composeflow.model.Viewport,
                                    target: File, sourceRaster: String) {
                    target.writeText(buildString {
                        appendLine("sourceRaster=$sourceRaster; camera=$view; viewport=1512x982")
                        appendLine("slotHalfSize=18x12; slotIsGlyphMeasurement=false; paintedOwnerAssociation=Unknown")
                        appendLine("members=${full.cards.size}; rawLinks=${full.links.size}; corridors=${current.layout.links.size}")
                        current.layout.links.forEach { link ->
                            val ink = BlueprintFarProjection.corridor(link.members.size, 1.3f, view.zoom)
                            val labels = BlueprintFarProjection.boundaryLabels(link, view, 1512.0, 982.0,
                                18.0, 12.0, ink.width * view.zoom)
                            assertEquals(2, labels.size)
                            labels.forEach { label ->
                                assertSame(link, label.link)
                                val owner = current.layout.cards.getValue(label.owner)
                                val ref = PinRef(label.owner, assertNotNull(label.port), label.provides)
                                val pin = owner.pins.single { ref.matches(owner.id, it) }
                                val raw = current.forwardedPorts[ref] ?: listOf(ref)
                                assertTrue(raw.isNotEmpty())
                                raw.forEach { endpoint ->
                                    assertEquals(pin.type, full.cards.getValue(endpoint.card).pins.single {
                                        endpoint.matches(endpoint.card, it)
                                    }.type)
                                }
                                label.anchor?.let { (x, y) ->
                                    assertTrue(x in 18.0..1494.0 && y in 12.0..970.0)
                                }
                                appendLine("corridor=${link.id}; members=${link.members}; reversed=${link.reversed}; family=${link.family}; owner=${label.owner}; port=${label.port}; provides=${label.provides}; type=${pin.type}; raw=$raw")
                                appendLine("ownerBounds=${listOf(owner.x, owner.y, owner.width, owner.height)}; boundary=${label.boundary}; screen=${label.screen}; anchor=${label.anchor}; clipped=${label.clipped}")
                            }
                        }
                    })
                }
                boundaryReceipt(fitLevel, fitCamera, File(folder, "$stem-endpoints.txt"), "$stem.png")
                val ladderFolder = File(folder, "ladder").apply { mkdirs() }
                val bands = fitLevel.frames.values.filter { it.summary != null }
                val midZoom = (bands.minOf { it.leaveZoom } + bands.maxOf { it.enterZoom }) / 2
                val nearZoom = maxOf(2.0, bands.maxOf { it.enterZoom })
                val focus = cards.getValue(wires.first { !it.reversed && it.fromPort == "event|out" }.from)
                val anchorX = fitCamera.x + (focus.x + focus.width / 2) * fitCamera.zoom
                val anchorY = fitCamera.y + (focus.y + focus.height / 2) * fitCamera.zoom
                File(ladderFolder, "$stem.txt").writeText("sourceRaster=../$stem.png\ncamera=$fitCamera\n" +
                    "observedZoom=$observedZoom\nreading=${canvas.reading()}\n" +
                    "paintedBounds=${paintedBounds.map { listOf(it.x, it.y, it.width, it.height) }}\n" +
                    "rawPortInk=Unknown\npainted=$observed\nprepared=$fitLevel\n")
                val fitRaster = assertNotNull(ImageIO.read(File(folder, "$stem.png")))
                File(folder, "$stem-count-evidence.txt").writeText(buildString {
                    appendLine("qualification=Unknown")
                    appendLine("failure=No existing corridor-identity-bound completed glyph evidence")
                    appendLine("sourceRaster=$stem.png")
                    appendLine("rasterPixels=${fitRaster.width}x${fitRaster.height}")
                    appendLine("camera=$fitCamera")
                    appendLine("members=${full.cards.size}; rawLinks=${full.links.size}")
                    appendLine("structuralCorridors=${fitLevel.layout.links.size}")
                    appendLine("paintedCountGlyphs=Unknown; rawPortInk=Unknown")
                    appendLine("missing=completed corridor id to label layout, paint origin and replayed glyph coverage")
                    appendLine("existing=onGeometry layout and bounds; source raster; camera reading")
                    appendLine("limit=ordered placement rectangles and aggregate label replay counts do not identify skipped corridors")
                    fitLevel.layout.links.forEach { corridor ->
                        appendLine("structuralCorridor=$corridor")
                    }
                    appendLine("forwardedPorts=${fitLevel.forwardedPorts}")
                })
                try {
                    for ((phase, requestedZoom) in listOf("mid" to midZoom, "near" to nearZoom, "return-fit" to fitCamera.zoom)) {
                        edt {
                            if (phase == "return-fit") canvas.restore(fitCamera.x, fitCamera.y, fitCamera.zoom)
                            else canvas.zoomAt(requestedZoom / canvas.zoom, anchorX, anchorY)
                        }
                        repeat(32) {
                            edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }
                            Thread.sleep(3)
                        }
                        val view = canvas.viewport()
                        val current = prepared.at(view.zoom)
                        assertSame(full, prepared.full)
                        assertEquals(cards, full.cards); assertEquals(frames, full.frames); assertEquals(wires, full.links)
                        assertEquals(wires.flatMap { it.members }.sorted(),
                            (current.layout.links.flatMap { it.members } + current.layout.foldedLinks.keys).sorted())
                        current.layout.links.forEach { link ->
                            val raw = wires.filter { original -> original.members.any(link.members::contains) }
                            assertTrue(raw.any { it.id == link.id })
                            assertEquals(setOf(link.reversed), raw.map { it.reversed }.toSet())
                            assertEquals(setOf(link.family), raw.map { it.family }.toSet())
                            for (provides in listOf(true, false)) {
                                val ref = PinRef(if (provides) link.from else link.to,
                                    assertNotNull(if (provides) link.fromPort else link.toPort), provides)
                                val attached = current.layout.links.filter { projected ->
                                    (if (provides) projected.from else projected.to) == ref.card &&
                                        (if (provides) projected.fromPort else projected.toPort) == ref.key
                                }.flatMap { it.members }.toSet()
                                val expected = wires.filter { original -> original.members.any(attached::contains) }
                                    .map { original -> PinRef(if (provides) original.from else original.to,
                                        assertNotNull(if (provides) original.fromPort else original.toPort), provides) }.toSet()
                                assertEquals(expected, current.forwardedPorts[ref]?.toSet() ?: setOf(ref))
                                val pin = current.layout.cards.getValue(ref.card).pins.single { ref.matches(ref.card, it) }
                                expected.forEach { endpoint ->
                                    assertEquals(pin.type, cards.getValue(endpoint.card).pins.single { endpoint.matches(endpoint.card, it) }.type)
                                }
                            }
                        }
                        if (phase == "mid") assertTrue(current.frames.values.any { it.summary != null && it.blend > 0 && it.blend < 1 })
                        if (phase == "near") {
                            assertSame(full, current.layout)
                            assertTrue(current.forwardedPorts.isEmpty())
                            cards.forEach { (id, card) -> assertSame(card, current.layout.cards[id]) }
                            wires.forEachIndexed { index, wire -> assertSame(wire, current.layout.links[index]) }
                        }
                        if (phase == "return-fit") {
                            assertEquals(fitCamera, view)
                            assertEquals(fitLevel.layout, current.layout)
                            assertEquals(fitLevel.forwardedPorts, current.forwardedPorts)
                        }
                        val structuralPortsInViewport = current.layout.cards.values.flatMap { card -> card.pins.mapNotNull { pin ->
                            val worldX = card.x + if (pin.provides) card.width else 0.0
                            val worldY = card.y + pin.y
                            val screenX = view.x + worldX * view.zoom; val screenY = view.y + worldY * view.zoom
                            if (screenX in 0.0..1512.0 && screenY in 0.0..982.0)
                                "${PinRef(card.id, pin.key, pin.provides)} type=${pin.type} world=($worldX,$worldY) screen=($screenX,$screenY)"
                            else null
                        } }
                        val phaseStem = "${mode.name}-${density.toInt()}-$phase"
                        boundaryReceipt(current, view, File(ladderFolder, "$phaseStem-endpoints.txt"), "$phaseStem.png")
                        File(ladderFolder, "$phaseStem.txt").writeText("camera=$view\nrequestedZoom=$requestedZoom\n" +
                            "anchor=($anchorX,$anchorY)\nobservedZoom=$observedZoom\nreading=${canvas.reading()}\n" +
                            "paintedBounds=${paintedBounds.map { listOf(it.x, it.y, it.width, it.height) }}\n" +
                            "structuralPortsInViewport=$structuralPortsInViewport\nrawPortInk=Unknown\npainted=$observed\nprepared=$current\n")
                        edt {
                            scene.render(++tick * 16_666_667L).use { image ->
                                image.encodeToData()!!.use { data ->
                                    val raster = assertNotNull(ImageIO.read(ByteArrayInputStream(data.bytes)))
                                    try {
                                        assertTrue(ImageIO.write(raster, "png", File(ladderFolder, "$phaseStem.png")))
                                        if (phase == "return-fit") assertContentEquals(
                                            fitRaster.getRGB(0, 0, fitRaster.width, fitRaster.height, null, 0, fitRaster.width),
                                            raster.getRGB(0, 0, raster.width, raster.height, null, 0, raster.width))
                                    } finally { raster.flush() }
                                }
                            }
                        }
                    }
                } finally { fitRaster.flush() }
            } finally { edt { scene.close() } }
        }
    }
}
