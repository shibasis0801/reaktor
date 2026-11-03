@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.graph.selection.*
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.File
import kotlin.test.*

class SubgraphEditorTest {
    private val graph = SearchGraph("catalog", "source-2", listOf(
        SearchNode("source:one", "First source", "source"), SearchNode("unit:one", "First unit", "unit", parent = "source:one"),
        SearchNode("source:two", "Second source", "source"), SearchNode("unit:two", "Second unit", "unit", parent = "source:two")),
        listOf(SearchEdge("contains-one", "source:one", "unit:one", "contains"), SearchEdge("builds-on", "unit:one", "unit:two", "builds on"),
            SearchEdge("contains-two", "source:two", "unit:two", "contains")))
    private val saved = Subgraph("view", "Pinned sources", "Trace two units", graph.id, graph.revision,
        graph.nodes.map { it.id }, graph.edges.map { it.id }, status = "saved", revision = 3)
    private val palette = SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.LightGray, Color.Blue)

    @Test fun adapterKeepsExactSourceUnitAndEdgeMembershipWithStillSelection() {
        val model = subgraphBlueprint(graph.nodes, graph.edges, palette)
        assertEquals(graph.nodes.map { it.id }.toSet(), model.editor.layout.cards.keys)
        assertEquals(graph.edges.map { it.id }.toSet(), model.editor.shape.wires.map { it.definitionId }.toSet())
        val camera = GraphCanvasState().apply { restore(-90.0, -20.0, .8) }
        val before = camera.viewport()
        val cards = model.editor.layout.cards
        val links = model.editor.layout.links
        model.editor.select(Selection(nodes = setOf("unit:one")))
        assertEquals(cards, model.editor.layout.cards)
        assertEquals(links, model.editor.layout.links)
        assertEquals(before, camera.viewport())
        val wire = assertNotNull(model.wire("builds-on"))
        assertEquals("builds-on", model.link(wire)?.id)
    }

    @Test fun savedViewUsesExpectedRevisionAndKeepsTheExactMembership() = runBlocking(Dispatchers.Swing) {
        Host().use { host ->
            host.open()
            host.rename("Pinned sources revised")
            host.click("Save subgraph")
            host.settle()
            val write = host.writes.single()
            assertEquals(3, write.second)
            assertEquals(4, write.first.revision)
            assertEquals(saved.nodes, write.first.nodes)
            assertEquals(saved.edges, write.first.edges)
            assertEquals("Pinned sources revised", write.first.title)
            assertEquals(graph.revision, write.first.sourceRevision)
        }
    }

    @Test fun refusedSaveKeepsTheDraftForRetry() = runBlocking(Dispatchers.Swing) {
        Host().use { host ->
            host.open(); host.rename("Preserved draft")
            host.refuse = true
            host.click("Save subgraph"); host.settle()
            assertTrue(host.hasText("Save refused"))
            assertTrue(host.writes.isEmpty())
            host.refuse = false
            host.click("Save subgraph"); host.settle()
            assertEquals("Preserved draft", host.writes.single().first.title)
            assertEquals(saved.nodes, host.writes.single().first.nodes)
            assertEquals(3, host.writes.single().second)
        }
    }

    @Test fun conflictingRevisionKeepsTheLocalDraftAndDoesNotWrite() = runBlocking(Dispatchers.Swing) {
        Host().use { host ->
            host.open(); host.rename("Local draft")
            host.documents = listOf(saved.copy(revision = 4)); host.settle()
            host.click("Save subgraph"); host.settle()
            assertTrue(host.hasText("This view changed elsewhere. Reopen its latest version before saving."))
            assertTrue(host.writes.isEmpty())
            host.documents = listOf(saved); host.settle()
            host.click("Save subgraph"); host.settle()
            assertEquals("Local draft", host.writes.single().first.title)
            assertEquals(3, host.writes.single().second)
        }
    }

    @Test fun realEditorRendersEveryMemberThroughBlueprintInBothThemes() = runBlocking(Dispatchers.Swing) {
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) Host(mode).use { host ->
            host.open()
            assertTrue(host.hasText("First source"))
            assertTrue(host.hasText("First unit"))
            assertEquals(graph.nodes.size, host.nodes().count { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("subgraph-node:") == true })
            host.click("First unit")
            host.settle()
            assertTrue(host.hasText("Remove First unit"))
            host.picture("${mode.name.lowercase()}-saved-view")
        }
    }

    @Test fun proposalOpensByPointerAtDesktopAndPhoneWidths() = runBlocking(Dispatchers.Swing) {
        for (width in listOf(1440, 390)) Host(width = width).use { host ->
            host.settle()
            host.pointerClick("Views")
            host.settle()
            host.pointerClick(saved.title)
            host.settle()
            assertTrue(host.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "subgraph-node:${graph.nodes.first().id}" }, "The proposal opens at $width px")
        }
    }

    private inner class Host(mode: MachineSignalMode = MachineSignalMode.Light, width: Int = 1200) : AutoCloseable {
        var documents by mutableStateOf(listOf(saved))
        var refuse = false
        val writes = ArrayList<Pair<Subgraph, Int>>()
        private var clock = 0L
        private val appearance = if (mode == MachineSignalMode.Light) palette else palette.copy(canvas = Color(0xFF14161B), card = Color(0xFF1C1F26), text = Color.White)
        val scene = ImageComposeScene(width, 800, coroutineContext = Dispatchers.Swing) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                SubgraphEditor(graph, documents, appearance, { "new-view" }, { next, revision ->
                    check(!refuse) { "Save refused" }
                    writes += next to revision
                    documents = listOf(next)
                }, Modifier.fillMaxSize())
            }
        }
        suspend fun settle() {
            repeat(16) { Snapshot.sendApplyNotifications(); scene.render(++clock * 16_666_667L).close(); delay(10) }
        }
        fun nodes(): List<SemanticsNode> {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        }
        private fun contains(node: SemanticsNode, value: String): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true || node.children.any { contains(it, value) }
        fun hasText(value: String) = nodes().any { contains(it, value) }
        fun click(value: String) {
            val node = nodes().firstOrNull { it.config.contains(SemanticsActions.OnClick) && contains(it, value) }
            assertNotNull(node, "Missing action: $value").config[SemanticsActions.OnClick].action!!.invoke()
        }
        fun pointerClick(value: String) {
            val node = assertNotNull(nodes().firstOrNull { it.config.contains(SemanticsActions.OnClick) && contains(it, value) }, "Missing pointer target: $value")
            val point = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
        }
        fun text(tag: String, value: String) {
            val root = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            assertNotNull(root, "Missing field: $tag")
            fun target(node: SemanticsNode): SemanticsNode? = if (node.config.contains(SemanticsActions.SetText)) node else node.children.firstNotNullOfOrNull(::target)
            assertNotNull(target(root)).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))
        }
        suspend fun open() { settle(); click("Views"); settle(); click(saved.title); settle() }
        suspend fun rename(value: String) { click("Views"); settle(); text("subgraph-name", value); settle() }
        fun picture(name: String) {
            val directory = System.getenv("BPO_SUBGRAPH_PICTURES") ?: "build/reports/bpo-subgraph-shim"
            scene.render(++clock * 16_666_667L).use { image -> image.encodeToData()!!.use { data -> File(directory, "$name.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) } } }
        }
        override fun close() = scene.close()
    }
}
