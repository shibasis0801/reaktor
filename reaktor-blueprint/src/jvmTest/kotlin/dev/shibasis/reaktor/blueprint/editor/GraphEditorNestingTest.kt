@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.Blueprint
import dev.shibasis.reaktor.blueprint.BlueprintBench
import dev.shibasis.reaktor.blueprint.BlueprintCanvas
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.BlueprintFace
import dev.shibasis.reaktor.blueprint.BlueprintFrameLook
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintOpening
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.LayoutOptions
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.composeflow.model.Viewport
import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertIs

class GraphEditorNestingTest {
    @Test fun routingBundlesChangeOnlyTheProjectedRoutesAndRetainEveryMemberIdentity() {
        val wires = listOf(WireShape("b", "in", "a", "out", "Int"), WireShape("b", "second", "a", "other", "Int"))
        val graph = GraphShape(listOf(ScopeShape("app", "App")),
            listOf("a", "b").map { NodeShape(it, "app", it, "Node", NodeKind.Service) }, wires, emptyList())
        val placement = Placement(mapOf("a" to Spot(60.0, 80.0), "b" to Spot(1400.0, 80.0)),
            notes = listOf(Note("area", "Area", 0.0, 0.0, 600.0, 600.0)))
        val editor = GraphEditor(graph, placement).apply { showChanges = false }
        editor.fold("area")
        editor.select(Selection(setOf("b")))
        val before = listOf(editor.shape, editor.placement, editor.selection, editor.editRevision, editor.history, editor.future)
        val separate = editor.layout
        assertEquals(2, separate.links.size)
        editor.routing = LayoutOptions(bundles = true)
        val joined = editor.layout
        assertEquals(1, joined.links.size)
        assertEquals(wires.map { it.id }.toSet(), joined.links.single().members.toSet())
        assertEquals(separate.cards, joined.cards)
        assertEquals(before, listOf(editor.shape, editor.placement, editor.selection, editor.editRevision, editor.history, editor.future))
        assertFalse(editor.bundles)
        editor.routing = LayoutOptions(bundles = false)
        assertEquals(separate, editor.layout)
    }

    @Test fun threeNestedFramesOwnOnlyDirectSemanticCardsAndOneInnermostFallback() {
        val graph = GraphShape(listOf(ScopeShape("root", "Root"), ScopeShape("middle", "Middle", "root"),
            ScopeShape("leaf", "Leaf", "middle")), listOf(
            NodeShape("root-card", "root", "Root", "Node", NodeKind.Service),
            NodeShape("middle-card", "middle", "Middle", "Node", NodeKind.Service),
            NodeShape("leaf-card", "leaf", "Leaf", "Node", NodeKind.Service),
            NodeShape("unscoped", "elsewhere", "Fallback", "Node", NodeKind.Service)), emptyList(), emptyList())
        val notes = listOf(Note("feature:root", "Root", 0.0, 0.0, 2000.0, 2000.0),
            Note("feature:middle", "Middle", 50.0, 50.0, 1500.0, 1500.0, parent = "feature:root"),
            Note("feature:leaf", "Leaf", 100.0, 100.0, 1000.0, 1000.0, parent = "feature:middle"))
        val editor = GraphEditor(graph, Placement(graph.nodes.associate { it.id to Spot(200.0, 200.0) }, notes = notes))
        val scene = editor.layout
        assertEquals(setOf("root-card"), scene.frames.single { it.key == "feature:root" }.nodes)
        assertEquals(setOf("middle-card"), scene.frames.single { it.key == "feature:middle" }.nodes)
        assertEquals(setOf("leaf-card", "unscoped"), scene.frames.single { it.key == "feature:leaf" }.nodes)
        assertTrue(scene.frames.flatMap { it.nodes }.groupingBy { it }.eachCount().values.all { it == 1 })
        assertEquals(scene.cards.keys, scene.frames.flatMap { it.nodes }.toSet())
        assertEquals(scene, editor.layout)
    }

    @Test fun editorBackRestoresSixLevelsOfCameraSelectionAndFolds() {
        val scopes = listOf(ScopeShape("app", "App")) + (0 until 6).map { ScopeShape("level-$it", "Level $it", if (it == 0) "app" else "level-${it - 1}") }
        val nodes = (0 until 6).map { NodeShape("node-$it", "level-$it", "Node $it", "Node", NodeKind.Service) } + NodeShape("outside", "app", "Outside", "Node", NodeKind.Service)
        val notes = (0 until 6).map { Note("level-$it", "Level $it", 0.0, 0.0, 2400.0 - it * 100, 2000.0 - it * 100, parent = if (it == 0) null else "level-${it - 1}") }
        val placement = Placement(nodes.associate { it.id to Spot(if (it.id == "outside") 3600.0 else 80.0, if (it.id == "outside") 50.0 else 100.0 + it.id.last().digitToInt() * 180) }, notes = notes)
        val editor = GraphEditor(GraphShape(scopes, nodes, emptyList(), emptyList()), placement)
        val canvas = GraphCanvasState(.01..4.0)
        canvas.resize(IntSize(1512, 982))
        editor.navigation(canvas::viewport, canvas::restore)
        editor.fold("level-5")
        val states = ArrayList<List<Any?>>()
        fun state() = listOf(editor.focus, editor.folded.toSet(), editor.selection, canvas.viewport())
        val outside = editor.layout.cards.getValue("outside")
        for (level in 0 until 6) {
            editor.select(Selection(setOf("outside")))
            canvas.restore(Viewport(level * 17.0 - 40, level * 13.0 + 30, .0001 * (level + 1)))
            states += state()
            val camera = canvas.viewport()
            editor.open("level-$level")
            assertEquals(outside, editor.layout.cards.getValue("outside"), "Outside card at level $level")
            assertEquals(camera, canvas.viewport(), "Camera at level $level")
            assertNull(editor.aim)
            editor.select(Selection(setOf("node-$level")))
            assertEquals(placement, editor.placement)
        }
        for (level in 5 downTo 0) {
            assertTrue(editor.back())
            assertEquals(states[level], state(), "Back from level $level")
        }
        assertEquals(outside, editor.layout.cards.getValue("outside"))
    }

    @Test fun foldedOpenPreservesOutsideRoutesAndMovesIntroducedCardsByTheirPlacementDelta() = runBlocking {
        val wires = listOf(WireShape("b", "in", "a", "out", "Int"), WireShape("c", "in", "b", "out", "Int"), WireShape("d", "in", "c", "out", "Int"))
        val graph = GraphShape(listOf(ScopeShape("app", "App"), ScopeShape("area", "Area", "app")),
            listOf("a", "b", "c", "d").map { id -> NodeShape(id, if (id < "c") "area" else "app", id, "Node", NodeKind.Service,
                ports = listOf(PortShape("in", "Int", false, PortWiring.Connected), PortShape("out", "Int", true, PortWiring.Connected))) }, wires, emptyList())
        val placement = Placement(mapOf("a" to Spot(80.0, 100.0), "b" to Spot(600.0, 120.0), "c" to Spot(3600.0, 60.0), "d" to Spot(4200.0, 60.0)),
            notes = listOf(Note("area", "Area", 0.0, 0.0, 1200.0, 800.0)))
        val editor = GraphEditor(graph, placement).apply {
            showChanges = false
            permits = { _, _ -> true }
            validate = { Verdict(it.changes.isEmpty(), "Presentation only") }
            savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = expected.presentation + "1")) }
        }
        val canvas = GraphCanvasState()
        editor.navigation(canvas::viewport, canvas::restore)
        editor.fold("area")
        val before = editor.layout
        val route = before.links.single { it.members == listOf(wires.last().id) }
        canvas.restore(Viewport(-120.0, 42.0, .0001))
        val camera = canvas.viewport()
        editor.open("area")
        val opened = editor.layout
        assertNotNull(editor.opening)
        assertEquals(camera, canvas.viewport())
        assertNull(editor.aim)
        assertEquals(placement, editor.placement)
        assertEquals(before.cards.getValue("c"), opened.cards.getValue("c"))
        assertEquals(before.cards.getValue("d"), opened.cards.getValue("d"))
        assertEquals(route, opened.links.single { it.id == route.id })
        assertEquals(graph.nodes.map { it.id }.toSet(), opened.cardOf.keys)
        assertEquals(wires.map { it.id }.toSet(), opened.links.flatMap { it.members }.toSet())
        assertTrue(opened.cards.getValue("b").y > placement.cards.getValue("b").y)
        val beforeMove = opened.cards.getValue("b")
        val frame = opened.frames.single { it.key == "area" }
        assertIs<EditReceipt.Accepted>(editor.propose(GraphPart(changes = emptyList(), layout = Placement(cards = mapOf("b" to Spot(856.0, 248.0))))))
        val moved = editor.layout
        assertEquals(beforeMove.x + 256.0, moved.cards.getValue("b").x)
        assertEquals(beforeMove.y + 128.0, moved.cards.getValue("b").y)
        assertEquals(opened.cards.getValue("a"), moved.cards.getValue("a"))
        assertEquals(route, moved.links.single { it.id == route.id })
        assertEquals(frame, moved.frames.single { it.key == "area" })
        assertEquals(moved, editor.opening?.layout)
        assertTrue(editor.back())
        assertEquals(setOf("area"), editor.folded)
        assertNull(editor.opening)
        assertEquals(before.cards.getValue("c"), editor.layout.cards.getValue("c"))
    }

    @Test fun hiddenWireIdsSelectAndFrameTheirFoldedCardWithoutUnfoldingOrEditing() {
        val wires = listOf(WireShape("b", "in", "a", "out", "Int"), WireShape("b", "other", "a", "second", "Int"))
        val graph = GraphShape(listOf(ScopeShape("app", "App"), ScopeShape("area", "Area", "app")),
            listOf("a", "b").map { NodeShape(it, "area", it, "Node", NodeKind.Service) }, wires, emptyList())
        val placement = Placement(mapOf("a" to Spot(80.0, 100.0), "b" to Spot(600.0, 120.0)),
            notes = listOf(Note("area", "Area", 0.0, 0.0, 1200.0, 800.0)))
        val editor = GraphEditor(graph, placement).apply { showChanges = false }
        editor.fold("area")
        val folded = editor.layout
        assertEquals(wires.associate { it.id to "area" }, folded.foldedLinks)
        assertTrue(folded.links.isEmpty())
        assertEquals(setOf("a", "b"), folded.cardOf.keys)
        val before = listOf(editor.shape, editor.placement, editor.editRevision, editor.history.toList(), editor.future.toList())
        wires.forEach { wire ->
            editor.select(Selection(wires = setOf(wire.id)))
            assertEquals(Selection(notes = setOf("area")), editor.selection)
            editor.reveal(wire.id)
            assertEquals(Selection(notes = setOf("area")), editor.selection)
            assertEquals(listOf(folded.cards.getValue("area")), editor.aim?.boxes)
            assertEquals(setOf("area"), editor.folded)
        }
        assertEquals(before, listOf(editor.shape, editor.placement, editor.editRevision, editor.history.toList(), editor.future.toList()))
        editor.unfold("area")
        assertEquals(emptySet(), editor.folded)
        assertTrue(editor.layout.foldedLinks.isEmpty(), editor.layout.foldedLinks.toString())
        editor.reveal(wires.first().id)
        assertEquals(Selection(wires = setOf(wires.first().id)), editor.selection)
    }

    @Test fun foldAndUnfoldBackRestoreThePriorViewWithoutAnEdit() {
        val graph = GraphShape(listOf(ScopeShape("app", "App"), ScopeShape("area", "Area", "app")),
            listOf(NodeShape("node", "area", "Node", "Node", NodeKind.Service)), emptyList(), emptyList())
        val placement = Placement(mapOf("node" to Spot(100.0, 100.0)), notes = listOf(Note("area", "Area", 0.0, 0.0, 600.0, 400.0)))
        val editor = GraphEditor(graph, placement)
        val canvas = GraphCanvasState()
        editor.navigation(canvas::viewport, canvas::restore)
        editor.select(Selection(setOf("node")))
        val before = listOf(editor.focus, editor.folded, editor.selection, canvas.viewport())
        editor.fold("area")
        val folded = listOf(editor.focus, editor.folded, editor.selection, canvas.viewport())
        editor.unfold("area")
        canvas.restore(100.0, 50.0, 1.5)
        assertTrue(editor.back())
        assertEquals(folded, listOf(editor.focus, editor.folded, editor.selection, canvas.viewport()))
        assertTrue(editor.back())
        assertEquals(before, listOf(editor.focus, editor.folded, editor.selection, canvas.viewport()))
        assertFalse(editor.back())
        assertTrue(editor.history.isEmpty())
        assertTrue(editor.future.isEmpty())
        assertEquals(graph, editor.shape)
        assertEquals(placement, editor.placement)
    }

    @Test fun coveredEditorCardsPinsSelectedEndpointsAndBendsCannotReceiveInput() {
        val wire = WireShape("b", "in", "a", "out", "Int")
        val graph = GraphShape(listOf(ScopeShape("app", "App")), listOf(
            NodeShape("a", "app", "A", "A", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Connected))),
            NodeShape("b", "app", "B", "B", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Connected)))), listOf(wire), emptyList())
        val editor = GraphEditor(graph, Placement(mapOf("a" to Spot(0.0, 0.0), "b" to Spot(500.0, 0.0)), bends = mapOf(wire.id to listOf(Spot(390.0, 200.0)))))
        val card = editor.layout.cards.getValue("a")
        val pin = editor.anchor(PinEnd("a", "out", true))!!
        editor.select(Selection(wires = setOf(wire.id)))
        editor.cover(setOf("a"), setOf(wire.id))
        assertNull(editor.cardAt(Spot(card.x + 10, card.y + 10)))
        assertNull(editor.pinAt(Spot(pin.first, pin.second)))
        editor.press(pin.first.toFloat(), pin.second.toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
        assertFalse(editor.gesture is Gesture.Wiring)
        editor.invoke(CommandId("edit-escape"))
        editor.press(390f, 200f, Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
        assertFalse(editor.gesture is Gesture.Bend)
        assertTrue(editor.selection.wires.isEmpty())
        editor.invoke(CommandId("edit-escape"))
        editor.selectAll()
        assertEquals(setOf("b"), editor.selection.nodes)
        editor.cover(emptySet(), emptySet())
        assertEquals("a", editor.cardAt(Spot(card.x + 10, card.y + 10))?.id)
        assertEquals(PinEnd("a", "out", true), editor.pinAt(Spot(pin.first, pin.second)))
    }
    @Test fun aLensShieldsCoveredCardAndRouteHitsUntilItCloses() = specimen { host ->
        val before = host.canvas.viewport()
        host.opening.value = BlueprintOpening(layout, layout.frames.first(), mapOf("covered" to 18_000.0), setOf("covered-route"))
        host.frame()
        host.click(350.0, 158.0)
        host.click(570.0, 350.0)
        assertTrue(host.cards.isEmpty())
        assertTrue(host.links.isEmpty())
        host.click(680.0, 158.0)
        host.click(570.0, 390.0)
        assertEquals(listOf("far"), host.cards)
        assertEquals(listOf("visible-route"), host.links)
        assertEquals(before, host.canvas.viewport())
        host.opening.value = null
        host.frame()
        host.click(350.0, 158.0)
        host.click(570.0, 350.0)
        assertEquals(listOf("far", "covered"), host.cards)
        assertEquals(listOf("visible-route", "covered-route"), host.links)
        assertEquals(before, host.canvas.viewport())
    }

    @Test fun sixOpeningLevelsAndZoomPreserveGeometryAndSelection() = specimen { host ->
        host.click(680.0, 158.0)
        val before = host.canvas.viewport()
        val geometry = layout.cards.toMap()
        val routes = layout.links.toList()
        val frames = layout.frames.toList()
        repeat(6) { level ->
            host.opening.value = BlueprintOpening(layout, layout.frames[level], mapOf("covered" to 18_000.0), setOf("covered-route"))
            host.frame()
            assertEquals(before, host.canvas.viewport(), "Opening level $level")
            assertEquals(listOf("far"), host.cards)
            assertEquals(geometry, layout.cards)
            assertEquals(routes, layout.links)
            assertEquals(frames, layout.frames)
        }
        val opening = host.opening.value
        host.edt { host.canvas.zoomBy(1.05) }
        host.frame()
        assertEquals(opening, host.opening.value)
        assertEquals(listOf("far"), host.cards)
        host.edt { host.canvas.back() }
        host.frame()
        assertEquals(before, host.canvas.viewport())
        for (level in 5 downTo 0) {
            host.opening.value = if (level == 0) null else BlueprintOpening(layout, layout.frames[level - 1], mapOf("covered" to 18_000.0), setOf("covered-route"))
            host.frame()
            assertEquals(before, host.canvas.viewport(), "Closing level $level")
        }
        assertEquals(geometry, layout.cards)
        assertEquals(listOf("far"), host.cards)
    }

    private val layout = BlueprintLayout("nesting", linkedMapOf(
        "inner" to Card("inner", 80.0, 90.0, 180.0, 80.0, emptyList(), 0),
        "covered" to Card("covered", 330.0, 140.0, 200.0, 144.0, emptyList(), 0),
        "far" to Card("far", 650.0, 140.0, 200.0, 144.0, emptyList(), 0)),
        (0 until 6).map { level -> Frame("scope-$level", "Scope $level", 40.0 + level, 40.0 + level, 380.0 - level * 2, 260.0 - level * 2,
            setOf("inner"), emptyList(), false, parent = if (level == 0) null else "scope-${level - 1}") },
        listOf(
            Link("covered-route", "covered", "far", null, null, LinkKind.Wire, listOf("covered-route"), listOf(530.0 to 350.0, 620.0 to 350.0)),
            Link("visible-route", "inner", "far", null, null, LinkKind.Wire, listOf("visible-route"), listOf(530.0 to 390.0, 620.0 to 390.0))),
        900.0, 450.0)

    private fun specimen(action: (Host) -> Unit) {
        val host = Host()
        host.edt {
            host.scene = ImageComposeScene(1000, 650, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Dark) {
                    BlueprintCanvas(layout, host.canvas, emptySet(), host.cards.lastOrNull(),
                        linkStyle = { Blueprint.link(Color.Red, false, false) }, frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                        cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, it.id, "", face = BlueprintFace.Prose) },
                        onCardClick = { host.cards += it }, onLinkClick = { host.links += it.id }, onPaneClick = {},
                        modifier = Modifier.fillMaxSize(), opening = host.opening.value, fitKey = 1)
                }
            }
        }
        try {
            host.frame()
            host.edt { host.canvas.restore(30.0, 70.0, 1.0) }
            host.frame()
            action(host)
        } finally { host.edt { host.scene.close() } }
    }

    private class Host {
        val canvas = GraphCanvasState(.01..4.0)
        val opening = mutableStateOf<BlueprintOpening<String>?>(null)
        val cards = mutableListOf<String>()
        val links = mutableListOf<String>()
        lateinit var scene: ImageComposeScene
        private var tick = 0L
        fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
        fun frame() { repeat(20) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(10) } }
        fun click(x: Double, y: Double) {
            edt {
                val camera = canvas.viewport()
                val at = Offset((camera.x + x * camera.zoom).toFloat(), (camera.y + y * camera.zoom).toFloat())
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            frame()
        }
    }
}
