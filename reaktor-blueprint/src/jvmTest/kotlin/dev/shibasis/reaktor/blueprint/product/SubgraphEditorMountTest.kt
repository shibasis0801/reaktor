@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.graph.selection.*
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class SubgraphEditorMountTest {
    @Test fun suppliedTypedGeometrySurvivesZoomAndSavedViewEditReopenWithLegacyCallIntact() = runBlocking(Dispatchers.Swing) {
        val type = "PayloadValue"
        val producer = NodeShape("producer", "inner", "Producer", "ProducerKind", NodeKind.Service,
            ports = listOf(PortShape("supply", type, true, PortWiring.Connected)), attributes = mapOf("fact" to "Recorded source"))
        val member = NodeShape("member", "inner", "Member", "MemberKind", NodeKind.Interactor,
            ports = listOf(PortShape("receive", type, false, PortWiring.Connected), PortShape("forward", type, true, PortWiring.Connected)),
            attributes = mapOf("fact" to "Recorded processing"))
        val sink = NodeShape("sink", "outer", "Sink", "SinkKind", NodeKind.Store,
            ports = listOf(PortShape("store", type, false, PortWiring.Connected), PortShape("declared", type, false, PortWiring.Connected)), attributes = mapOf("fact" to "Recorded storage"))
        val first = WireShape(member.id, "receive", producer.id, "supply", type, "first-definition", "first-wire")
        val second = WireShape(sink.id, "store", member.id, "forward", type, "second-definition", "second-wire")
        val source = GraphShape(listOf(ScopeShape("outer", "Outer context"), ScopeShape("inner", "Inner context", "outer")),
            listOf(producer, member, sink), listOf(first, second), emptyList(), "complete-source")
        fun card(node: NodeShape, x: Double) = Card(node.id, x, 160.0, 380.0, 280.0,
            node.ports.mapIndexed { i, port -> Pin(port.key, port.type, port.provides, 90.0 + i * 44.0, port.wiring, "row:${node.id}:${port.key}") }, 0)
        val cards = listOf(card(producer, 80.0), card(member, 720.0), card(sink, 1360.0))
        fun link(wire: WireShape, x: Double, y: Double, end: Double) = Link(wire.id, wire.provider, wire.consumer,
            wire.providerPort, wire.consumerPort, LinkKind.Wire, listOf(wire.id), listOf(x to y, end to y), family = wire.type)
        val full = BlueprintLayout("supplied-source", cards.associateBy { it.id }, listOf(
            Frame("outer", "Outer context", 0.0, 70.0, 1840.0, 600.0, setOf(sink.id), emptyList(), false),
            Frame("inner", "Inner context", 40.0, 120.0, 1120.0, 460.0, setOf(producer.id, member.id), emptyList(), false, parent = "outer")),
            listOf(link(first, 460.0, 250.0, 720.0), link(second, 1100.0, 294.0, 1360.0).copy(
                points = listOf(1100.0 to 294.0, 1240.0 to 294.0, 1240.0 to 250.0, 1360.0 to 250.0))), 1840.0, 670.0)
        val search = SearchGraph("bound-search", "source-2", source.nodes.map { SearchNode(it.id, it.label, it.type) }, listOf(
            SearchEdge(first.id, producer.id, member.id, "wire", "Receives payload"),
            SearchEdge(second.id, member.id, sink.id, "wire", "Stores payload")))
        val original = Subgraph("saved-view", "Saved context", "Retained intent", search.id, "source-1",
            source.nodes.map { it.id }, source.wires.map { it.id }, status = "saved", revision = 4)
        val palette = SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.LightGray, Color.Blue)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            var documents by mutableStateOf(listOf(original))
            val writes = mutableListOf<Pair<Subgraph, Int>>()
            val canvas = GraphCanvasState(.002..16.0)
            var latestSelection = Selection()
            val suppliedNodes = linkedMapOf<String, NodeShape>()
            val suppliedLinks = linkedSetOf<Link>()
            var openedEvidence = 0
            var tick = 0L
            fun scene(typed: Boolean) = ImageComposeScene((1512 * density).toInt(), (982 * density).toInt(), Density(density), coroutineContext = Dispatchers.Swing) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                    val colors = MachineSignal.colors
                    val appearance = palette.copy(canvas = colors.canvas, card = colors.surface, text = colors.textStrong, muted = colors.textMuted, line = colors.line)
                    val save: (Subgraph, Int) -> Unit = { next, expected -> writes += next to expected; documents = listOf(next) }
                    if (typed) SubgraphEditor(search, documents, appearance, { "new-view" }, save, Modifier.fillMaxSize(),
                        sourceGraph = source, sourceLayout = full,
                        sourceCardLook = { node ->
                            suppliedNodes[node.id] = node
                            BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, node.label, "${node.type} · ${node.attributes.getValue("fact")}",
                                action = node.label, marks = listOf(BlueprintMark("Recorded mark", Color.Blue)),
                                agent = BlueprintAgent(null, "Recorded agent", BlueprintAgent.Activity.Unavailable),
                                actions = listOf(BlueprintCardAction("evidence", "Open recorded evidence", SurfaceGraphSymbol) { openedEvidence++ }),
                                pins = full.cards.getValue(node.id).pins.map { pin -> BlueprintPinLook(Color.Blue, "${pin.key}: ${pin.type}", appearance.text, true) })
                        },
                        sourceFrameLook = { frame -> BlueprintFrameLook(SurfaceGraphSymbol, if (frame.key == "outer") "3 actual members · retained source" else "2 actual members · processing") },
                        sourceLinkStyle = { wire, selection ->
                            latestSelection = selection
                            suppliedLinks += wire
                            Blueprint.link(Color.Blue, !selection.empty, wire.members.any { id ->
                                id in selection.wires || search.edges.any { it.id == id && (it.from in selection.nodes || it.to in selection.nodes) }
                            })
                        },
                        sourceLinkLabel = { wire -> search.edges.firstOrNull { it.id in wire.members }?.label }, canvas = canvas)
                    else SubgraphEditor(search, documents, appearance, { "new-view" }, save, Modifier.fillMaxSize())
                }
            }
            suspend fun settle(scene: ImageComposeScene) { repeat(24) { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close(); delay(4) } }
            fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            fun contains(node: SemanticsNode, value: String): Boolean = node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value == it } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { value == it.text } == true || node.children.any { contains(it, value) }
            fun click(scene: ImageComposeScene, value: String) {
                val candidates = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) && contains(it, value) }
                val target = assertNotNull(candidates.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }, "Missing mounted action")
                assertTrue(assertNotNull(target.config[SemanticsActions.OnClick].action).invoke())
            }
            suspend fun open(scene: ImageComposeScene) { settle(scene); click(scene, "Views"); settle(scene); click(scene, original.title); settle(scene) }
            fun assertGeometry() {
                val expected = full
                assertEquals(expected.cards.values.toList(), canvas.extent.filterIsInstance<Card>())
                assertEquals(expected.frames, canvas.extent.filterIsInstance<Frame>())
                assertEquals(cards.associateBy { it.id }, full.cards)
                assertEquals(source.wires.map { it.id }, full.links.flatMap { it.members })
                assertEquals(source.nodes.toSet(), suppliedNodes.values.toSet())
                for (wire in full.links) assertTrue(wire in suppliedLinks, "Full raw link must reach the supplied style")
                for (node in source.nodes) assertEquals(node.ports.map { Triple(it.key, it.type, it.provides) },
                    full.cards.getValue(node.id).pins.map { Triple(it.key, it.type, it.provides) })
            }
            var mounted = scene(true)
            try {
                open(mounted)
                assertTrue(nodes(mounted).any { contains(it, "Source updated") })
                for (zoom in listOf(.2, .7, 2.0, .2, 2.0)) {
                    canvas.restore(600.0 - 920.0 * zoom, 450.0 - 330.0 * zoom, zoom); settle(mounted)
                    assertGeometry()
                    assertTrue(nodes(mounted).any { contains(it, "Outer context") })
                    mounted.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { encoded ->
                        val pixels = ImageIO.read(ByteArrayInputStream(encoded.bytes))
                        val colors = mutableSetOf<Int>()
                        for (y in 180 until minOf(pixels.height, (800 * density).toInt()) step 4) for (x in 40 until pixels.width - 40 step 4) colors += pixels.getRGB(x, y)
                        assertTrue(colors.size > 8, "Complete mounted scene must paint nonempty content")
                    } }
                }
                canvas.restore(700.0 - (cards[1].x + 190.0) * 2.0, 380.0 - 240.0 * 2.0, 2.0); settle(mounted)
                val beforeSelection = canvas.viewport()
                click(mounted, member.label); settle(mounted)
                assertEquals(setOf(member.id), latestSelection.nodes)
                assertEquals(beforeSelection, canvas.viewport())
                assertTrue(nodes(mounted).any { contains(it, "receive: PayloadValue") })
                assertTrue(nodes(mounted).any { contains(it, "forward: PayloadValue") })
                assertTrue(nodes(mounted).any { contains(it, "MemberKind · Recorded processing") })
                assertTrue(nodes(mounted).any { contains(it, "RECORDED MARK") })
                val evidence = nodes(mounted).single { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-card-action:${member.id}:evidence" }
                assertTrue(assertNotNull(evidence.config[SemanticsActions.OnClick].action).invoke()); settle(mounted)
                assertEquals(1, openedEvidence)
                val pin = nodes(mounted).single { it.config.getOrNull(SemanticsProperties.TestTag) == "subgraph-node:${member.id}-needs-receive" }
                assertTrue(assertNotNull(pin.config[SemanticsActions.OnClick].action).invoke()); settle(mounted)
                assertEquals(setOf(member.id), latestSelection.nodes)
                assertEquals(beforeSelection, canvas.viewport())
                click(mounted, "Exclude Receives payload · ${producer.id} → ${member.id}"); settle(mounted)
                click(mounted, "Save subgraph"); settle(mounted)
                val write = writes.single()
                assertEquals(4, write.second)
                assertEquals(original.nodes, write.first.nodes)
                assertEquals(listOf(second.id), write.first.edges)
                assertEquals(original.intent, write.first.intent)
                assertEquals(original.title, write.first.title)
                assertEquals(5, write.first.revision)
                assertEquals(search.revision, write.first.sourceRevision)
                assertGeometry()
                val beforeReopen = canvas.viewport()
                mounted.close(); mounted = scene(true); open(mounted)
                assertEquals(beforeReopen, canvas.viewport())
                assertGeometry()
                click(mounted, member.label); settle(mounted)
                assertEquals(setOf(member.id), latestSelection.nodes)
                assertTrue(nodes(mounted).any { contains(it, "Include Receives payload · ${producer.id} → ${member.id}") })
                assertEquals(listOf(second.id), documents.single().edges)
                canvas.restore(700.0 - 1180.0 * 2.0, 380.0 - 294.0 * 2.0, 2.0); settle(mounted)
                val beforeWire = canvas.viewport()
                mounted.sendPointerEvent(PointerEventType.Press, Offset(700f * density, 380f * density), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                mounted.sendPointerEvent(PointerEventType.Release, Offset(700f * density, 380f * density), button = PointerButton.Primary)
                settle(mounted)
                assertEquals(setOf(second.id), latestSelection.wires)
                assertEquals(beforeWire, canvas.viewport())
                assertTrue(nodes(mounted).any { contains(it, "Remove connection") })
                assertGeometry()
                assertEquals(listOf(first, second), source.wires)
                assertEquals(original.nodes, documents.single().nodes)
            } finally { mounted.close() }
            val legacy = scene(false)
            try {
                open(legacy)
                assertEquals(source.nodes.size, nodes(legacy).count { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("subgraph-node:") == true })
                click(legacy, member.label); settle(legacy)
                assertTrue(nodes(legacy).any { contains(it, "Remove Member") })
                assertEquals(listOf(second.id), documents.single().edges)
                assertEquals(1, writes.size)
            } finally { legacy.close() }
        }
    }

    @Test fun authoritativeRouteSelectionAndSavedExclusionKeepRawTypedGeometry() = runBlocking(Dispatchers.Swing) {
        val type = "PayloadValue"
        val producer = NodeShape("producer", "inner", "Producer", "ProducerKind", NodeKind.Service, attachedTo = "start-route",
            ports = listOf(PortShape("supply", type, true, PortWiring.Connected)), attributes = mapOf("fact" to "Recorded source"))
        val member = NodeShape("member", "inner", "Member", "MemberKind", NodeKind.Interactor,
            ports = listOf(PortShape("receive", type, false, PortWiring.Connected), PortShape("forward", type, true, PortWiring.Connected)),
            attributes = mapOf("fact" to "Recorded processing"))
        val sink = NodeShape("sink", "outer", "Sink", "SinkKind", NodeKind.Store, attachedTo = "end-route",
            ports = listOf(PortShape("store", type, false, PortWiring.Connected)), attributes = mapOf("fact" to "Recorded storage"))
        val first = WireShape(member.id, "receive", producer.id, "supply", type, "first-definition", "first-wire")
        val second = WireShape(sink.id, "store", member.id, "forward", type, "second-definition", "second-wire")
        val start = NodeShape("start-route", "inner", "Start route", "StartRoute", NodeKind.Route,
            ports = listOf(PortShape("supply", type, true, PortWiring.Connected)), attributes = mapOf("fact" to "Recorded route source"))
        val end = NodeShape("end-route", "outer", "End route", "EndRoute", NodeKind.Route, attributes = mapOf("fact" to "Recorded route destination"))
        val projected = WireShape(sink.id, "store", start.id, "supply", type, "projected-definition", "projected-wire")
        val binding = WireShape(producer.id, "routeBinding", start.id, "navBinding", "RouteSignal", "binding-definition", "binding-wire")
        val source = GraphShape(listOf(ScopeShape("outer", "Outer context"), ScopeShape("inner", "Inner context", "outer")),
            listOf(producer, member, sink, start, end), listOf(first, second, projected, binding),
            listOf(RouteShape(start.id, end.id, "continue:next#step")), "complete-source")
        val view = dev.shibasis.reaktor.blueprint.graph.AppGraph(source)
        assertSame(source, view.shape)
        assertEquals(setOf(producer.id, member.id, sink.id), view.nodes.keys)
        assertEquals(listOf(first.id, second.id, projected.id), view.wires.map { it.id })
        assertEquals(start.id, projected.provider)
        assertEquals(producer.id, view.wires.single { it.id == projected.id }.provider)
        assertEquals(projected.providerPort, view.wires.single { it.id == projected.id }.providerPort)
        assertEquals(projected.consumer, view.wires.single { it.id == projected.id }.consumer)
        val hop = view.hops.single()
        assertEquals(producer.id, hop.from)
        assertEquals(sink.id, hop.to)
        assertEquals(source.routes.single().exit, hop.exit)
        val routeId = "route:${hop.id}"
        assertNotEquals(routeId, hop.id)
        fun card(node: NodeShape, x: Double) = Card(node.id, x, 160.0, 380.0, 280.0,
            node.ports.mapIndexed { i, port -> Pin(port.key, port.type, port.provides, 90.0 + i * 44.0, port.wiring, "row:${node.id}:${port.key}") }, 0)
        val cards = listOf(card(producer, 80.0), card(member, 720.0), card(sink, 1360.0))
        val firstLink = Link(first.id, producer.id, member.id, first.providerPort, first.consumerPort, LinkKind.Wire,
            listOf(first.id), listOf(460.0 to 250.0, 720.0 to 250.0), family = type)
        val secondLink = Link(second.id, member.id, sink.id, second.providerPort, second.consumerPort, LinkKind.Wire,
            listOf(second.id), listOf(1100.0 to 294.0, 1240.0 to 294.0, 1240.0 to 250.0, 1360.0 to 250.0), family = type)
        val projectedLink = Link(projected.id, producer.id, sink.id, projected.providerPort, projected.consumerPort, LinkKind.Wire,
            listOf(projected.id), listOf(460.0 to 250.0, 500.0 to 480.0, 1320.0 to 480.0, 1360.0 to 250.0), family = type)
        val route = Link(hop.id, hop.from, hop.to, null, null, LinkKind.Route, listOf(hop.id),
            listOf(460.0 to 400.0, 520.0 to 550.0, 1300.0 to 550.0, 1360.0 to 400.0), family = hop.exit)
        val full = BlueprintLayout("supplied-source", cards.associateBy { it.id }, listOf(
            Frame("outer", "Outer context", 0.0, 70.0, 1840.0, 600.0, setOf(sink.id), emptyList(), false),
            Frame("inner", "Inner context", 40.0, 120.0, 1120.0, 460.0, setOf(producer.id, member.id), emptyList(), false, parent = "outer")),
            listOf(firstLink, secondLink, projectedLink, route), 1840.0, 670.0)
        val search = SearchGraph("bound-search", "source-2", view.nodes.values.map { SearchNode(it.id, it.label, it.type) }, listOf(
            SearchEdge(first.id, producer.id, member.id, "wire", "Receives payload"),
            SearchEdge(second.id, member.id, sink.id, "wire", "Stores payload"),
            SearchEdge(projected.id, producer.id, sink.id, "wire", "Projected source supply"),
            SearchEdge(routeId, hop.from, hop.to, "route", "Continue to recorded destination")))
        val original = Subgraph("saved-view", "Saved context", "Retained intent", search.id, "source-1",
            search.nodes.map { it.id }, search.edges.map { it.id }, status = "saved", revision = 4)
        val palette = SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.LightGray, Color.Blue)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            var documents by mutableStateOf(listOf(original))
            val writes = mutableListOf<Pair<Subgraph, Int>>()
            val canvas = GraphCanvasState(.002..16.0)
            var latestSelection = Selection()
            val suppliedNodes = linkedMapOf<String, NodeShape>()
            val suppliedLinks = linkedSetOf<Link>()
            var tick = 0L
            fun scene(input: GraphShape = source, searchInput: SearchGraph = search, prepared: BlueprintLayout<String> = full,
                failureContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext) =
                ImageComposeScene((1512 * density).toInt(), (982 * density).toInt(), Density(density), coroutineContext = Dispatchers.Swing + failureContext) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        val appearance = palette.copy(canvas = colors.canvas, card = colors.surface, text = colors.textStrong, muted = colors.textMuted, line = colors.line)
                        SubgraphEditor(searchInput, documents, appearance, { "new-view" },
                            { next, expected -> writes += next to expected; documents = listOf(next) }, Modifier.fillMaxSize(),
                            sourceGraph = input, sourceLayout = prepared,
                            sourceCardLook = { node ->
                                suppliedNodes[node.id] = node
                                BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, node.label, "${node.type} · ${node.attributes.getValue("fact")}",
                                    marks = listOf(BlueprintMark("Recorded mark", Color.Blue)),
                                    pins = full.cards[node.id]?.pins.orEmpty().map { pin -> BlueprintPinLook(Color.Blue, "${pin.key}: ${pin.type}", appearance.text, true) })
                            },
                            sourceFrameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "Actual members · retained source") },
                            sourceLinkStyle = { link, selection ->
                                latestSelection = selection; suppliedLinks += link
                                Blueprint.link(Color.Blue, !selection.empty, link.members.any { it in selection.wires })
                            },
                            sourceLinkLabel = { link -> if (hop.id in link.members) "Continue to recorded destination" else "Recorded typed wire" },
                            canvas = canvas)
                    }
                }
            suspend fun settle(scene: ImageComposeScene) { repeat(24) { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close(); delay(4) } }
            fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            fun contains(node: SemanticsNode, value: String): Boolean = node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value == it } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { value == it.text } == true || node.children.any { contains(it, value) }
            fun click(scene: ImageComposeScene, value: String) {
                val candidates = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) && contains(it, value) }
                val target = assertNotNull(candidates.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }, "Missing mounted action")
                assertTrue(assertNotNull(target.config[SemanticsActions.OnClick].action).invoke())
            }
            suspend fun open(scene: ImageComposeScene) { settle(scene); click(scene, "Views"); settle(scene); click(scene, original.title); settle(scene) }
            suspend fun press(scene: ImageComposeScene) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(700f * density, 380f * density), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, Offset(700f * density, 380f * density), button = PointerButton.Primary)
                settle(scene)
            }
            fun assertGeometry() {
                val expected = full
                assertEquals(expected.cards.values.toList(), canvas.extent.filterIsInstance<Card>())
                assertEquals(expected.frames, canvas.extent.filterIsInstance<Frame>())
                assertEquals(cards.associateBy { it.id }, full.cards)
                assertEquals(listOf(firstLink, secondLink, projectedLink, route), full.links)
                assertEquals(listOf(first.id, second.id, projected.id, hop.id), full.links.flatMap { it.members })
                assertFalse(full.links.any { routeId in it.members })
                assertEquals(view.nodes.values.toSet(), suppliedNodes.filterKeys { it in view.nodes }.values.toSet())
                for ((id, node) in suppliedNodes) assertEquals(source.nodes.single { it.id == id }, node)
                for (link in full.links) assertTrue(link in suppliedLinks, "Full raw relationship must reach the supplied style")
                for (node in view.nodes.values) assertEquals(node.ports.map { Triple(it.key, it.type, it.provides) },
                    full.cards.getValue(node.id).pins.map { Triple(it.key, it.type, it.provides) })
            }
            var routeProbe: Offset? = null
            val routeFrames = mutableListOf<java.awt.image.BufferedImage>()
            val routeReceipts = mutableListOf<String>()
            fun routeInk(scene: ImageComposeScene): Int {
                val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver
                fun field(name: String): Any? = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                    .first { owner -> owner.declaredFields.any { it.name == name } }
                    .getDeclaredField(name).apply { isAccessible = true }.get(painter)
                val routeIndex = (field("links") as List<*>).indexOfFirst { it is Link && hop.id in it.members }
                val styleAlpha = ((field("styles") as List<*>).getOrNull(routeIndex) as? dev.shibasis.reaktor.blueprint.canvas.LinkStyle)?.alpha
                val committedAlpha = (field("alphas") as FloatArray).getOrNull(routeIndex)
                val hoverIndex = field("hoveredLink") as Int
                val stateTick = tick
                return scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { encoded ->
                val pixels = ImageIO.read(ByteArrayInputStream(encoded.bytes))
                if (routeProbe == null) {
                    val pills = (field("placed") as List<*>)
                        .filterIsInstance<androidx.compose.ui.geometry.Rect>()
                    assertTrue(pills.isNotEmpty(), "The selected route label must be mounted before choosing a stroke probe")
                    val view = canvas.viewport()
                    routeProbe = (540..1280 step 20).map { world ->
                        Offset((view.x + world * view.zoom).toFloat(), (view.y + 550.0 * view.zoom).toFloat())
                    }.firstOrNull { point ->
                        point.x > 8f && point.x < pixels.width / density - 8f && point.y > 8f && point.y < pixels.height / density - 24f &&
                            pills.none { it.overlaps(androidx.compose.ui.geometry.Rect(point.x - 4f, point.y - 4f, point.x + 4f, point.y + 20f)) }
                    }
                    assertNotNull(routeProbe, "The real route needs a visible stroke segment clear of all actual label pills")
                }
                val point = assertNotNull(routeProbe)
                val x = (point.x * density).toInt(); val y = (point.y * density).toInt()
                val background = pixels.getRGB(x, y + (16 * density).toInt())
                fun distance(value: Int) = listOf(16, 8, 0).sumOf { shift -> kotlin.math.abs((value shr shift and 255) - (background shr shift and 255)) }
                val ink = ((y - 4 * density).toInt()..(y + 4 * density).toInt()).maxOf { distance(pixels.getRGB(x, it)) }
                if (routeFrames.size < 2) {
                    routeFrames += pixels
                    val view = canvas.viewport()
                    routeReceipts += listOf(routeFrames.size - 1, density, pixels.width, pixels.height, stateTick, tick,
                        x, (y - 4 * density).toInt(), x + 1, (y + 4 * density).toInt() + 1,
                        x, y + (16 * density).toInt(), x + 1, y + (16 * density).toInt() + 1,
                        background, ink, routeIndex, styleAlpha, committedAlpha, hoverIndex,
                        routeIndex >= 0 && hoverIndex == routeIndex, view.x, view.y, view.zoom).joinToString("\t")
                }
                ink
            } }
            }
            var mounted = scene()
            try {
                open(mounted)
                for (zoom in listOf(.2, .7, 2.0, .2, 2.0)) {
                    canvas.restore(600.0 - 920.0 * zoom, 450.0 - 330.0 * zoom, zoom); settle(mounted)
                    assertGeometry()
                }
                canvas.restore(700.0 - 1000.0 * 2.0, 380.0 - 550.0 * 2.0, 2.0); settle(mounted)
                val beforeRoute = canvas.viewport()
                press(mounted)
                assertEquals(setOf(hop.id), latestSelection.wires)
                assertEquals(beforeRoute, canvas.viewport())
                assertTrue(nodes(mounted).any { contains(it, "Remove connection") })
                assertTrue(nodes(mounted).any { contains(it, "Continue to recorded destination") })
                val includedInk = routeInk(mounted)
                assertTrue(includedInk > 12, "Actual route stroke must be present before exclusion")
                click(mounted, "Remove connection"); settle(mounted)
                assertEquals(setOf(hop.id), latestSelection.wires)
                assertTrue(nodes(mounted).any { contains(it, "Restore connection") })
                val excludedInk = routeInk(mounted)
                if (!(excludedInk < includedInk * .7)) {
                    val folder = java.io.File(System.getProperty("java.io.tmpdir"), "blueprint-subgraph-exclusion").apply { mkdirs() }
                    val appearance = if (mode == MachineSignalMode.Dark) "dark" else "light"
                    val suffix = "$appearance-${density.toInt()}x"
                    routeFrames.forEachIndexed { index, pixels ->
                        val phase = if (index == 0) "included" else "excluded"
                        ImageIO.write(pixels, "png", java.io.File(folder, "$phase-$suffix.png"))
                    }
                    java.io.File(folder, "receipt-$suffix.tsv").writeText(
                        "phaseIndex\tdensity\twidth\theight\tstateTick\trasterTick\tprobeLeft\tprobeTop\tprobeRightExclusive\tprobeBottomExclusive\tbackgroundLeft\tbackgroundTop\tbackgroundRightExclusive\tbackgroundBottomExclusive\tbackgroundArgb\tcontrast\trawRouteIndex\tsuppliedStyleAlpha\tcommittedAlpha\thoverIndex\thoversRawRoute\tcameraX\tcameraY\tcameraZoom\n" +
                            routeReceipts.joinToString("\n", postfix = "\n"))
                }
                assertTrue(excludedInk < includedInk * .7, "Saved/search exclusion must mute the actual raw route stroke")
                assertEquals(beforeRoute, canvas.viewport())
                click(mounted, "Save subgraph"); settle(mounted)
                val write = writes.single()
                assertEquals(4, write.second)
                assertEquals(original.id, write.first.id)
                assertEquals(search.id, write.first.graphId)
                assertEquals(original.nodes, write.first.nodes)
                assertEquals(listOf(first.id, second.id, projected.id), write.first.edges)
                assertEquals(original.title, write.first.title)
                assertEquals(original.intent, write.first.intent)
                assertEquals(5, write.first.revision)
                assertEquals(search.revision, write.first.sourceRevision)
                val beforeReopen = canvas.viewport()
                mounted.close(); mounted = scene(); open(mounted)
                assertEquals(beforeReopen, canvas.viewport())
                assertGeometry()
                fun reopenedPressState(phase: String): List<String> = runCatching {
                    buildList<String> {
                        fun row(key: String, value: Any?) {
                            val text = value?.toString()?.replace("\t", "\\t")?.replace("\r", "\\r")?.replace("\n", "\\n") ?: "<null>"
                            add("$phase\t$key\t$text")
                        }
                        val view = canvas.viewport()
                        row("stateTick", tick)
                        row("density", density)
                        row("expectedRawWireIds", setOf(hop.id).sorted())
                        row("actualRawWireIds", latestSelection.wires.sorted())
                        row("expectedDocumentId", original.id)
                        row("savedDocumentId", write.first.id)
                        row("reopenedDocumentId", documents.single().id)
                        row("reopenedGraphId", documents.single().graphId)
                        row("expectedSearchRouteId", routeId)
                        row("savedEdges", write.first.edges)
                        row("reopenedEdges", documents.single().edges)
                        row("pressScenePixelX", 700f * density)
                        row("pressScenePixelY", 380f * density)
                        row("cameraBeforeClose", beforeReopen)
                        row("cameraAtPhase", view)
                        row("sameCameraAsBeforeClose", view == beforeReopen)
                        row("pressHitWinner", "Unknown: the existing seam retains no press-hit winner")
                        row("reopenedRaster", "Unavailable: existing settle frames were closed, not retained")
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver
                        fun field(name: String): Any? = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                            .first { owner -> owner.declaredFields.any { it.name == name } }
                            .getDeclaredField(name).apply { isAccessible = true }.get(painter)
                        val committed = (field("links") as List<*>).filterIsInstance<Link>()
                        val rawIndex = committed.indexOfFirst { hop.id in it.members }
                        val fullRoute = full.links.firstOrNull { hop.id in it.members }
                        val projectedRoute = committed.getOrNull(rawIndex)
                        val hoverIndex = field("hoveredLink") as Int
                        row("originalFullRawRoute", fullRoute)
                        row("committedProjectedRoute", projectedRoute)
                        row("committedRouteEqualsFull", projectedRoute == fullRoute)
                        row("committedRawRouteIndex", rawIndex)
                        row("committedRawRouteMatches", committed.count { hop.id in it.members })
                        row("committedSearchRouteMatches", committed.count { routeId in it.members })
                        row("suppliedStyleAlpha", ((field("styles") as List<*>).getOrNull(rawIndex) as? dev.shibasis.reaktor.blueprint.canvas.LinkStyle)?.alpha)
                        row("committedAlpha", (field("alphas") as FloatArray).getOrNull(rawIndex))
                        row("hoveredLinkIndex", hoverIndex)
                        row("hoveredLinkId", committed.getOrNull(hoverIndex)?.id)
                        row("hoveredRawWireMembers", committed.getOrNull(hoverIndex)?.members)
                        row("hoveredCardIndex", field("hovered"))
                        row("hoveredPinKey", field("hoveredPin"))
                        if (rawIndex >= 0) {
                            row("committedWorldRouteBounds", (field("linkBox") as FloatArray).slice(rawIndex * 4 until rawIndex * 4 + 4))
                            val starts = field("start") as IntArray
                            row("committedWorldRoutePoints", (field("points") as FloatArray).slice(starts[rawIndex] until starts[rawIndex + 1]))
                        }
                    }
                }.getOrElse { listOf("$phase\tcaptureError\t${it.javaClass.name}") }
                val beforeReopenedPress = reopenedPressState("before-reopened-press")
                press(mounted)
                if (latestSelection.wires != setOf(hop.id)) {
                    runCatching {
                        val afterReopenedPress = reopenedPressState("after-reopened-release-and-existing-settle")
                        val folder = java.io.File(System.getProperty("java.io.tmpdir"), "blueprint-subgraph-exclusion").apply { mkdirs() }
                        val appearance = if (mode == MachineSignalMode.Dark) "dark" else "light"
                        val suffix = "$appearance-${density.toInt()}x"
                        val rows = beforeReopenedPress + afterReopenedPress + listOf(
                            "pre-save\tincludedContrast\t$includedInk",
                            "pre-save\texcludedContrast\t$excludedInk",
                            "pre-save\tretainedRasterCount\t${routeFrames.size}",
                            "pre-save\treceiptSchema\tphaseIndex,density,width,height,stateTick,rasterTick,probeLeft,probeTop,probeRightExclusive,probeBottomExclusive,backgroundLeft,backgroundTop,backgroundRightExclusive,backgroundBottomExclusive,backgroundArgb,contrast,rawRouteIndex,suppliedStyleAlpha,committedAlpha,hoverIndex,hoversRawRoute,cameraX,cameraY,cameraZoom"
                        ) + routeReceipts.mapIndexed { index, receipt -> "pre-save\tframe$index\t${receipt.replace("\t", "\\t")}" }
                        java.io.File(folder, "reopened-press-$suffix.tsv").writeText("phase\tfield\tvalue\n" + rows.joinToString("\n", postfix = "\n"))
                        routeFrames.forEachIndexed { index, pixels ->
                            val phase = if (index == 0) "included" else "excluded"
                            ImageIO.write(pixels, "png", java.io.File(folder, "pre-save-$phase-for-reopened-press-$suffix.png"))
                        }
                    }.onFailure { System.err.println("Reopened route press receipt failed: ${it.javaClass.name}") }
                }
                assertEquals(setOf(hop.id), latestSelection.wires)
                assertTrue(nodes(mounted).any { contains(it, "Restore connection") })
                assertEquals(excludedInk, routeInk(mounted))
                assertEquals(listOf(first.id, second.id, projected.id), documents.single().edges)
                canvas.restore(700.0 - 1000.0 * 2.0, 380.0 - 480.0 * 2.0, 2.0); settle(mounted)
                val beforeProjected = canvas.viewport()
                press(mounted)
                assertEquals(setOf(projected.id), latestSelection.wires)
                assertEquals(beforeProjected, canvas.viewport())
                assertTrue(nodes(mounted).any { contains(it, "Projected source supply") })
                canvas.restore(700.0 - 1180.0 * 2.0, 380.0 - 294.0 * 2.0, 2.0); settle(mounted)
                val beforeWire = canvas.viewport()
                press(mounted)
                assertEquals(setOf(second.id), latestSelection.wires)
                assertEquals(beforeWire, canvas.viewport())
                assertTrue(nodes(mounted).any { contains(it, "Remove connection") })
                canvas.restore(700.0 - (cards[1].x + 190.0) * 2.0, 380.0 - 240.0 * 2.0, 2.0); settle(mounted)
                val memberActionNodes = nodes(mounted)
                if (memberActionNodes.none { it.config.contains(SemanticsActions.OnClick) && contains(it, member.label) }) {
                    runCatching {
                        val phase = "before-original-member-action"
                        val rows = mutableListOf<String>()
                        fun row(key: String, value: Any?) {
                            val text = value?.toString()?.replace("\t", "\\t")?.replace("\r", "\\r")?.replace("\n", "\\n") ?: "<null>"
                            rows += "$phase\t$key\t${text.take(4096)}"
                            if (text.length > 4096) rows += "$phase\t${key}UntruncatedLength\t${text.length}"
                        }
                        row("stateTick", tick)
                        row("density", density)
                        row("appearance", mode)
                        row("expectedMemberId", member.id)
                        row("expectedMemberLabel", member.label)
                        row("nextAction", "Original click helper semantics action; not delivered at this phase")
                        row("actualSelectedNodesBeforeAction", latestSelection.nodes.sorted())
                        row("actualSelectedRawWiresBeforeAction", latestSelection.wires.sorted())
                        row("expectedDocumentId", original.id)
                        row("savedDocumentId", write.first.id)
                        row("reopenedDocumentId", documents.single().id)
                        row("reopenedGraphId", documents.single().graphId)
                        row("savedEdges", write.first.edges)
                        row("reopenedEdges", documents.single().edges)
                        row("cameraAtPhase", canvas.viewport())
                        row("expectedCameraX", 700.0 - (cards[1].x + 190.0) * 2.0)
                        row("expectedCameraY", 380.0 - 240.0 * 2.0)
                        row("expectedCameraZoom", 2.0)
                        row("originalFullMemberCard", full.cards[member.id])
                        row("canvasExtentMemberCard", canvas.extent.filterIsInstance<Card>().firstOrNull { it.id == member.id })
                        row("semanticsNodeCount", memberActionNodes.size)
                        val matching = memberActionNodes.filter { contains(it, member.label) || it.config.getOrNull(SemanticsProperties.TestTag)?.contains(member.id) == true }
                        row("matchingSemanticsNodeCount", matching.size)
                        matching.sortedBy { it.boundsInRoot.width * it.boundsInRoot.height }.take(32).forEachIndexed { index, node ->
                            row("memberSemantics${index}Id", node.id)
                            row("memberSemantics${index}Bounds", node.boundsInRoot)
                            row("memberSemantics${index}Tag", node.config.getOrNull(SemanticsProperties.TestTag))
                            row("memberSemantics${index}Text", node.config.getOrNull(SemanticsProperties.Text)?.take(6)?.map { it.text })
                            row("memberSemantics${index}Descriptions", node.config.getOrNull(SemanticsProperties.ContentDescription)?.take(6))
                            row("memberSemantics${index}HasClick", node.config.contains(SemanticsActions.OnClick))
                            row("memberSemantics${index}HasClickAction", node.config.getOrNull(SemanticsActions.OnClick)?.action != null)
                        }
                        val clickable = memberActionNodes.filter { it.config.contains(SemanticsActions.OnClick) }
                        row("clickableSemanticsNodeCount", clickable.size)
                        clickable.take(32).forEachIndexed { index, node ->
                            row("clickableSemantics${index}Tag", node.config.getOrNull(SemanticsProperties.TestTag))
                            row("clickableSemantics${index}Text", node.config.getOrNull(SemanticsProperties.Text)?.take(6)?.map { it.text })
                            row("clickableSemantics${index}Descriptions", node.config.getOrNull(SemanticsProperties.ContentDescription)?.take(6))
                        }
                        runCatching {
                            val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver
                            fun field(name: String): Any? = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                                .first { owner -> owner.declaredFields.any { it.name == name } }
                                .getDeclaredField(name).apply { isAccessible = true }.get(painter)
                            row("hoveredCardIndex", field("hovered"))
                            row("hoveredPinKey", field("hoveredPin"))
                            row("hoveredLinkIndex", field("hoveredLink"))
                            val slot = (field("cardSlots") as Map<*, *>)[member.id] as? Int
                            row("committedMemberSlot", slot)
                            row("committedCardCount", (field("cards") as Array<*>).size)
                            if (slot != null) {
                                row("committedMemberCard", (field("cards") as Array<*>).getOrNull(slot))
                                row("committedMarkerKey", (field("markerKeys") as Array<*>).getOrNull(slot))
                                row("committedPaintPresent", (field("paints") as Array<*>).getOrNull(slot) != null)
                                row("committedLayerPresent", (field("layers") as Array<*>).getOrNull(slot) != null)
                                val boxes = field("cardBox") as FloatArray
                                if (slot >= 0 && slot < boxes.size / 4) row("committedMemberWorldBounds", boxes.slice(slot * 4 until slot * 4 + 4))
                            }
                        }.onFailure { row("committedStateCaptureError", it.javaClass.name) }
                        row("phaseRaster", "Unavailable: existing settle frames were closed, not retained; no render performed")
                        row("glyphSupport", "Unknown: semantics and marker metadata do not establish delivered ink")
                        row("pressHitWinner", "Unknown: no pointer press at this semantics-action phase; existing seam retains no winner")
                        val folder = java.io.File(System.getProperty("java.io.tmpdir"), "blueprint-subgraph-exclusion").apply { mkdirs() }
                        val appearance = if (mode == MachineSignalMode.Dark) "dark" else "light"
                        java.io.File(folder, "missing-member-action-$appearance-${density.toInt()}x.tsv")
                            .writeText("phase\tfield\tvalue\n" + rows.joinToString("\n", postfix = "\n"))
                    }.onFailure { System.err.println("Missing member action receipt failed: ${it.javaClass.name}") }
                }
                val memberMarker = nodes(mounted).single { it.config.getOrNull(SemanticsProperties.TestTag) == "subgraph-node:${member.id}" }
                assertTrue(contains(memberMarker, member.label))
                val memberAction = assertNotNull(memberMarker.config.getOrNull(SemanticsActions.OnClick), "The exact supplied raw card must expose its selection action")
                assertEquals(member.label, memberAction.label)
                assertNotNull(memberAction.action, "The supplied raw-card action must be callable")
                val beforeMember = canvas.viewport()
                click(mounted, member.label); settle(mounted)
                assertEquals(setOf(member.id), latestSelection.nodes)
                assertEquals(beforeMember, canvas.viewport())
                assertTrue(nodes(mounted).any { contains(it, "MemberKind · Recorded processing") })
                assertTrue(nodes(mounted).any { contains(it, "RECORDED MARK") })
                assertTrue(nodes(mounted).any { contains(it, "receive: PayloadValue") })
                assertTrue(nodes(mounted).any { contains(it, "forward: PayloadValue") })
                val beforePin = canvas.viewport()
                val pin = nodes(mounted).single { it.config.getOrNull(SemanticsProperties.TestTag) == "subgraph-node:${member.id}-needs-receive" }
                assertTrue(assertNotNull(pin.config[SemanticsActions.OnClick].action).invoke()); settle(mounted)
                assertEquals(setOf(member.id), latestSelection.nodes)
                assertEquals(beforePin, canvas.viewport())
                assertGeometry()
                assertEquals(listOf(first, second, projected, binding), source.wires)
                assertEquals(listOf(producer, member, sink, start, end), source.nodes)
                assertEquals(listOf(RouteShape("start-route", "end-route", "continue:next#step")), source.routes)
                assertEquals("complete-source", source.definitionDigest)
                assertEquals(1, writes.size)
            } finally { mounted.close() }
            if (mode == MachineSignalMode.Light && density == 1f) {
                val collision = source.copy(wires = listOf(first.copy(sourceId = hop.id), second))
                val cases = listOf(
                    Triple(source.copy(routes = emptyList()), search, "A route relationship requires supplied route authority"),
                    Triple(source.copy(nodes = source.nodes.map { if (it.id == member.id) it.copy(attachedTo = "start-route") else it }), search,
                        "A supplied route requires one authoritative member per endpoint"),
                    Triple(source, search.copy(edges = search.edges.map { if (it.id == routeId) it.copy(from = sink.id, to = producer.id) else it }),
                        "Directed route endpoints must match the source relationship"),
                    Triple(collision, search.copy(edges = search.edges.map { if (it.id == first.id) it.copy(id = hop.id) else it }),
                        "Source relationships must resolve to distinct raw members"),
                    Triple(source, search.copy(nodes = search.nodes + SearchNode(start.id, start.label, start.type)),
                        "The search members and directed relationships must match one complete supplied source projection"),
                    Triple(source, search.copy(edges = search.edges + SearchEdge(binding.id, binding.provider, binding.consumer, "wire")),
                        "The search members and directed relationships must match one complete supplied source projection"))
                for ((input, searchInput, expected) in cases) {
                    val writesBefore = writes.size
                    val documentsBefore = documents.toList()
                    val failures = mutableListOf<Throwable>()
                    val refused = scene(input, searchInput, failureContext = kotlinx.coroutines.CoroutineExceptionHandler { _, failure -> failures += failure })
                    try {
                        val failure = assertNotNull(runCatching { open(refused) }.exceptionOrNull() ?: failures.singleOrNull(), "Invalid route authority must refuse the mounted supplied map")
                        assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains(expected) == true }, "Refusal must identify the authority boundary")
                        assertEquals(writesBefore, writes.size)
                        assertEquals(documentsBefore, documents)
                    } finally { refused.close() }
                }
            }
        }
    }
}
