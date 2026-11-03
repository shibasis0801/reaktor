package dev.shibasis.reaktor.blueprint.product

import kotlin.test.Test
import androidx.compose.foundation.layout.fillMaxSize
import dev.shibasis.reaktor.ui.machinesignal.colors
import kotlinx.coroutines.swing.Swing
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.random.Random
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState

private fun card(id: String, x: Double, y: Double, height: Double, rows: Int = 0) =
    Card(id, x, y, 272.0, height, SurfaceGraphNode(id, id, "", rows = List(rows) { SurfaceGraphRow("$id-$it", "Row $it") }).pins(), 0)

class SurfaceGraphDetailTest {
    @Test fun explorerBoundaryClickKeepsTheExactOriginalRowAndDirection() {
        val provider = dev.shibasis.reaktor.blueprint.Pin("record", "Record", true, 70.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected, "source-unit")
        val consumer = provider.copy(provides = false, id = "destination-unit")
        val original = Card("source", 0.0, 0.0, 272.0, 180.0, listOf(provider, consumer), 0)
        val layout = BlueprintLayout("pins", mapOf(original.id to original), emptyList(), emptyList(), 272.0, 180.0)
        val boundary = provider.copy(key = "boundary", id = "presentation-only")
        val summary = original.copy(id = "summary:domain", pins = listOf(boundary))
        val ref = dev.shibasis.reaktor.blueprint.PinRef(summary.id, boundary.key, true)
        val source = dev.shibasis.reaktor.blueprint.PinRef(original.id, provider.key, true)
        assertEquals(original to provider, surfaceGraphPinTarget(layout, mapOf(ref to listOf(source)), summary, boundary))
        assertEquals(original to consumer, surfaceGraphPinTarget(layout, emptyMap(), original, consumer))
        kotlin.test.assertNull(surfaceGraphPinTarget(layout, mapOf(ref to listOf(source, source.copy(provides = false))), summary, boundary))
        kotlin.test.assertNull(surfaceGraphPinTarget(layout, mapOf(ref to listOf(source.copy(key = "absent"))), summary, boundary))
    }

    @Test fun explorerModelKeepsNestedSceneRowsFamiliesAndOneOwnerPerCard() {
        val a = SurfaceGraphNode("a", "Sheet", "Book", scope = "chapter", rows = listOf(SurfaceGraphRow("unit", "Unit", "unit-key", aliases = listOf("unit-alias"))))
        val b = SurfaceGraphNode("b", "Route", "Path", scope = "island")
        val groups = listOf(SurfaceGraphGroup("island", "Island", owner = "source-owner"), SurfaceGraphGroup("chapter", "Chapter", parent = "island"))
        val cards = mapOf("a" to Card("a", 80.0, 120.0, 272.0, 160.0, a.pins(), 0), "b" to Card("b", 500.0, 120.0, 272.0, 160.0, b.pins(), 0))
        val frames = listOf(Frame("island", "Island", 0.0, 0.0, 850.0, 400.0, setOf("a", "b"), emptyList(), false),
            Frame("chapter", "Chapter", 40.0, 70.0, 360.0, 250.0, setOf("a"), emptyList(), false, parent = "island"))
        val route = Link("edge", "a", "b", null, null, LinkKind.Wire, listOf("edge"), listOf(352.0 to 160.0, 500.0 to 160.0), family = "chapter")
        val scene = BlueprintLayout(Unit, cards, frames, listOf(route), 850.0, 400.0)
        val model = SurfaceGraphSimple(listOf(a, b), listOf(SurfaceGraphLink("edge", "unit-alias", "b")),
            SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.Gray, Color.Blue), groups = groups, initialLayout = scene)
        val laid = model.editor.layout
        assertEquals(cards.mapValues { it.value.x to it.value.y }, laid.cards.mapValues { it.value.x to it.value.y })
        assertEquals(setOf("a", "b"), laid.frames.flatMap { it.nodes }.toSet())
        assertTrue(laid.frames.flatMap { it.nodes }.groupingBy { it }.eachCount().values.all { it == 1 })
        assertEquals("chapter", laid.frames.single { "a" in it.nodes }.key)
        assertEquals("chapter", laid.links.single().family)
        assertEquals("unit", laid.cards.getValue("a").pins.first { it.key == "unit-key" }.id)
        val before = laid.cards
        model.editor.select(dev.shibasis.reaktor.blueprint.editor.Selection(nodes = setOf("a")))
        assertEquals(before, model.editor.layout.cards)
    }

    @Test fun simpleAdapterKeepsRowsAliasesAndParallelLinkIdentity() {
        val a = SurfaceGraphNode("a", "Source", "Two rows", rows = listOf(SurfaceGraphRow("unit-a", "First row", "first", aliases = listOf("alias-a")), SurfaceGraphRow("unit-b", "Second row", "second")))
        val b = SurfaceGraphNode("b", "Destination", "One row", rows = listOf(SurfaceGraphRow("unit-c", "Third row", "third")))
        val edges = listOf(SurfaceGraphLink("one", "alias-a", "unit-c"), SurfaceGraphLink("two", "alias-a", "unit-c"), SurfaceGraphLink("three", "a", "b"))
        val model = SurfaceGraphSimple(listOf(a, b), edges, SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.Gray, Color.Blue))
        assertEquals(3, model.editor.shape.wires.size)
        assertEquals(edges.map { it.id }.toSet(), model.editor.shape.wires.map { it.definitionId }.toSet())
        val layout = model.editor.layout
        assertEquals(3, layout.links.size)
        assertEquals(edges.toSet(), layout.links.mapNotNull { model.link(it.id) }.toSet())
        val row = layout.cards.getValue("a").pins.first { it.key == "first" }
        assertEquals("unit-a", row.id)
        assertEquals(listOf("alias-a"), row.aliases)
        assertEquals("First row", model.document.row("a", "first")?.title)
        val before = layout.cards
        model.editor.select(dev.shibasis.reaktor.blueprint.editor.Selection(nodes = setOf("b")))
        assertEquals(before, model.editor.layout.cards)
        assertEquals(layout.links, model.editor.layout.links)
    }

    @Test fun straightStrokeFadesOnlyAcrossOneDevicePixel() {
        val mesh = SurfaceGraphMesh(false)
        SurfaceGraphStroker().apply { point(0f, 0f); point(10f, 0f); stroke(mesh, 1f, 1f, 0x204060, 1f) }
        mesh.close()
        assertEquals(16, mesh.vertexCount)
        assertEquals(54, mesh.indexCount)
        assertEquals(listOf(0, 0, 0, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 0, 0, 0), (0 until 16).map { mesh.colors[it] ushr 24 })
        assertEquals(listOf(.5f, 1.5f, .5f, .5f, .5f, -.5f, .5f, -1.5f), (8 until 16).map { mesh.positions[it] })
        assertEquals(0x80123456.toInt(), surfaceGraphArgb(0x123456, .5f))
    }

    @Test fun mixMatchesComposeLerpWithinTwoSteps() {
        val random = Random(7)
        repeat(20_000) {
            val start = Color(random.nextInt()); val stop = Color(random.nextInt()); val t = random.nextFloat()
            val fast = mix(start, stop, t).toArgb(); val exact = lerp(start, stop, t).toArgb()
            for (shift in listOf(0, 8, 16, 24)) assertTrue(abs((fast ushr shift and 255) - (exact ushr shift and 255)) <= 2, "$start $stop $t")
        }
    }

    @Test fun hairlineStrokesKeepTheirWeightAsCoverage() {
        val mesh = SurfaceGraphMesh(false)
        SurfaceGraphStroker().apply { point(0f, 0f); point(10f, 0f); stroke(mesh, .1f, 1f, 0, 1f) }
        assertEquals(51, mesh.colors[5] ushr 24)
    }

    @Test fun largeMeshesSplitIntoShortIndexedBatches() {
        val mesh = SurfaceGraphMesh(false)
        val stroker = SurfaceGraphStroker()
        repeat(5000) { stroker.clear(); stroker.point(0f, it.toFloat()); stroker.point(10f, it.toFloat()); stroker.stroke(mesh, 1f, 1f, 0, 1f) }
        mesh.close()
        assertEquals(80000, mesh.vertexCount)
        assertEquals(2, mesh.batchCount)
        for (b in 0 until mesh.batchCount) {
            val count = mesh.batches[b * 4 + 1] - mesh.batches[b * 4]
            assertTrue(count <= 65_535)
            for (i in mesh.batches[b * 4 + 2] until mesh.batches[b * 4 + 3]) assertTrue(mesh.indices[i].toInt() and 0xFFFF < count)
        }
    }

    @Test fun roundedRectsFillInsideAndFadeOutsideTheEdge() {
        val mesh = SurfaceGraphMesh(false)
        val stroker = SurfaceGraphStroker()
        stroker.roundRect(0f, 0f, 100f, 50f, 0f, .1f)
        assertEquals(4, stroker.size)
        stroker.fill(mesh, 1f, 0xFF102030.toInt(), 50f)
        assertEquals(9, mesh.vertexCount)
        assertEquals(36, mesh.indexCount)
        assertEquals(listOf(99.5f, .5f, 100.5f, -.5f), (2 until 6).map { (mesh.positions[it] * 1000).roundToInt() / 1000f })
        stroker.outline(mesh, .5f, 1f, 0x102030, 1f)
        assertEquals(25, mesh.vertexCount)
        assertEquals(36 + 72, mesh.indexCount)
        stroker.roundRect(0f, 0f, 100f, 50f, 16f, .1f)
        assertTrue(stroker.size > 12)
    }

    @Test fun detailDoesNotFlickerWhenFittingNearThresholds() {
        val state=SurfaceGraphExplorerState()
        assertEquals(0,state.level(.07))
        assertEquals(2,state.level(.9))
        assertEquals(2,state.level(.41))
        assertEquals(1,state.level(.38))
        assertEquals(1,state.level(.43))
        assertEquals(2,state.level(.46))
        assertEquals(0,state.level(.15))
        assertEquals(0,state.level(.19))
        assertEquals(1,state.level(.21))
    }

    @Test fun cameraUsesTheSameWorldUnitsAtRetinaDensity() {
        val geometry = listOf(card("card", 200.0, 300.0, 400.0, rows = 5))
        fun state(pixelRatio: Double): SurfaceGraphExplorerState = SurfaceGraphExplorerState().apply {
            density(pixelRatio); resize(IntSize((1440*pixelRatio).toInt(),(900*pixelRatio).toInt()))
            insetStart=364.0; insetBottom=100.0; place(geometry)
        }
        val standard=state(1.0); val retina=state(2.0)
        assertEquals(standard.viewport(),retina.viewport())
        standard.centre("card",3);retina.centre("card",3)
        assertEquals(standard.viewport(),retina.viewport())
        standard.zoomBy(.8);retina.zoomBy(.8)
        assertEquals(standard.viewport(),retina.viewport())
        standard.back();retina.back()
        assertEquals(standard.viewport(),retina.viewport())
        retina.restore(12.0,34.0,.7)
        retina.density(1.0)
        assertEquals(12.0,retina.viewport().x)
        assertEquals(34.0,retina.viewport().y)
        assertEquals(.7,retina.viewport().zoom)
    }

    @Test fun revealMovesOnlyToUncoverTheTarget() {
        val geometry = listOf(card("small", 100.0, 100.0, 100.0), card("right", 900.0, 100.0, 100.0, rows = 3), card("tall", 500.0, 0.0, 2000.0))
        val state = SurfaceGraphExplorerState().apply { resize(IntSize(1440,900)); place(geometry, key = "scene") }
        state.flow.setViewport(Viewport(0.0,0.0,1.0))
        state.reveal("small"); state.reveal("tall"); state.reveal("right", 2)
        assertEquals(Viewport(0.0,0.0,1.0),state.viewport())
        state.insetEnd=360.0
        state.reveal("right")
        assertEquals(Viewport(-108.0,0.0,1.0),state.viewport())
        state.flow.setViewport(Viewport(0.0,0.0,.1))
        state.reveal("small")
        assertEquals(.1,state.viewport().zoom)
        state.reveal("small",readable=true)
        assertEquals(.9,state.viewport().zoom)
    }

    @Test fun placeFitsOnlyWhenTheSceneKeyChanges() {
        val geometry = listOf(card("card", 0.0, 0.0, 100.0))
        val state = SurfaceGraphExplorerState().apply { resize(IntSize(1440,900)); place(geometry, key = "a") }
        state.flow.setViewport(Viewport(10.0,20.0,.5))
        state.place(geometry.map { it.copy() }, key = "a")
        assertEquals(Viewport(10.0,20.0,.5),state.viewport())
        state.place(geometry.map { it.copy() }, key = "b")
        assertTrue(state.viewport() != Viewport(10.0,20.0,.5))
    }

    @Test fun canvasKeepsItsZoomAtRetinaDensity() {
        fun canvas(ratio: Double) = GraphCanvasState().apply { density(ratio); flow.setCanvasSize(IntSize((1440*ratio).toInt(),(900*ratio).toInt())); insetTop=48.0; insetStart=200.0 }
        val standard=canvas(1.0); val retina=canvas(2.0)
        listOf(standard,retina).forEach { it.fit(0.0,0.0,2000.0,1000.0) }
        assertEquals(standard.viewport(),retina.viewport())
        listOf(standard,retina).forEach { it.zoomTo(1.0); it.centre(500.0,300.0,272.0,100.0) }
        assertEquals(1.0,retina.zoom)
        assertEquals(standard.viewport(),retina.viewport())
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SurfaceGraphExplorerInteractionTest {
    @Test fun physicalRowsAliasesAndOpenKeepTheCameraAndSavedPositionsStill() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Swing) {
        for (mode in listOf(dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode.Light, dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode.Dark)) {
            val nodes = listOf(SurfaceGraphNode("sheet", "Atlas sheet", "Book", scope = "atlas", opens = true,
                rows = listOf(SurfaceGraphRow("unit", "Unit graph", "unit-key", aliases = listOf("unit-alias")))),
                SurfaceGraphNode("route", "Learning route", "Path", scope = "atlas"))
            val selected = androidx.compose.runtime.mutableStateOf<String?>(null)
            val state = SurfaceGraphExplorerState()
            var opened: String? = null
            val scene = androidx.compose.ui.ImageComposeScene(1100, 700, androidx.compose.ui.unit.Density(1f), coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {
                dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface(dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant.Editor,
                    dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity.Compact, mode = mode) {
                    val colors = dev.shibasis.reaktor.ui.machinesignal.MachineSignal.colors
                    SurfaceGraphExplorer(nodes, listOf(SurfaceGraphLink("related", "unit-alias", "route")), selected.value,
                        SurfaceGraphAppearance(colors.canvas, colors.surface, colors.textStrong, colors.textMuted, colors.line, colors.controlAccent),
                        state, { selected.value = it }, androidx.compose.ui.Modifier.fillMaxSize(), key = "same-scene", onClear = { selected.value = null },
                        onOpen = { opened = it; true })
                }
            }
            var time = 0L
            suspend fun frames() { repeat(24) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close(); kotlinx.coroutines.delay(10) } }
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun semantics() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            try {
                frames()
                assertEquals(nodes, state.showing)
                val positions = state.cards.associate { it.id to (it.x to it.y) }
                state.centre("sheet", level = 2.0); frames()
                val camera = state.viewport()
                val card = state.cards.single { it.id == "sheet" }
                val pin = card.pins.first { it.id == "unit" }
                val point = androidx.compose.ui.geometry.Offset((camera.x + (card.x + card.width / 4) * camera.zoom).toFloat(), (camera.y + (card.y + pin.y) * camera.zoom).toFloat())
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, point,
                    buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, point, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                frames()
                assertEquals("unit", selected.value)
                selected.value = "unit-alias"; frames()
                assertTrue(semantics().any { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false } &&
                    walk(node).any { child -> child.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { it.text == "Unit graph" } } })
                val open = semantics().single { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } == "blueprint-card-action:sheet:open" }
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, open.boundsInRoot.center,
                    buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, open.boundsInRoot.center, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                frames()
                assertEquals("sheet", opened)
                assertEquals("unit-alias", selected.value)
                assertEquals(camera, state.viewport())
                assertEquals(positions, state.cards.associate { it.id to (it.x to it.y) })
                val folder = java.io.File("build/reports/blueprint-explorer").apply { mkdirs() }
                scene.render(++time * 16_666_667L).use { image -> image.encodeToData()!!.use { data -> java.io.File(folder, "${mode.name.lowercase()}-sheet-open.png").writeBytes(data.bytes) } }
            } finally { scene.close() }
        }
    }

    @Test fun areaCorridorsChangesOnlyRoutingAndKeepsTheCaptureFoldFlag() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Swing) {
        val appearance = SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.Gray, Color.Blue)
        val nodes = listOf(SurfaceGraphNode("a", "Source", "Book", scope = "atlas"), SurfaceGraphNode("b", "Destination", "Route", scope = "atlas"))
        val edges = listOf(SurfaceGraphLink("one", "a", "b"), SurfaceGraphLink("two", "a", "b"))
        val model = SurfaceGraphSimple(nodes, edges, appearance)
        val state = SurfaceGraphExplorerState()
        val corridors = androidx.compose.runtime.mutableStateOf(true)
        val editor = dev.shibasis.reaktor.blueprint.editor.GraphEditor(model.editor.base, model.editor.placement, shape = model.editor.shape,
            cardWidth = appearance.width, single = false, pins = model.editor.pins, terms = model.document, bundles = true)
        val scene = androidx.compose.ui.ImageComposeScene(1100, 700, androidx.compose.ui.unit.Density(1f), coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {
            SurfaceGraphExplorer(nodes, edges, null, appearance, state, {}, androidx.compose.ui.Modifier.fillMaxSize(), editor = editor, areaCorridors = corridors.value, key = "same-scene")
        }
        var time = 0L
        suspend fun frames() { repeat(24) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close(); kotlinx.coroutines.delay(10) } }
        try {
            frames()
            assertTrue(editor.routing.bundles)
            val positions = editor.layout.cards
            val placement = editor.placement
            val shape = editor.shape
            val history = editor.history
            val camera = state.viewport()
            corridors.value = false; frames()
            assertFalse(editor.routing.bundles)
            assertTrue(editor.bundles)
            assertEquals(positions, editor.layout.cards)
            assertEquals(placement, editor.placement)
            assertEquals(shape, editor.shape)
            assertEquals(history, editor.history)
            assertEquals(camera, state.viewport())
        } finally { scene.close() }
    }
}
