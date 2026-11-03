@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import dev.shibasis.reaktor.ui.machinesignal.colors
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

class BlueprintAgentSlotTest {
    private val glyph = ImageVector.Builder("Work", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Blue)) { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close() }
    }.build()
    private val node = BlueprintNode("bpo-agent-slot", 0, inputs = listOf(
        PinSpec("checks", "Focused checks", PortWiring.Connected), PinSpec("pictures", "Reviewed pictures", PortWiring.Connected)))
    private val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Claimed,
        effort = "xhigh", model = "sol 6.1", role = "designer")

    @Test fun claimStateAndAgentCountPreserveCardsCameraAndGalleryHitBounds() {
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) probe(mode) { p ->
            val before = p.layout!!.cards.toMap()
            val body = p.body
            val camera = p.canvas.viewport()
            val bounds = p.tag("blueprint-agent-bpo-agent-slot").boundsInRoot
            val icon = p.tag("blueprint-agent-bpo-agent-slot-icon").boundsInRoot
            val tuple = p.tag("blueprint-agent-bpo-agent-slot-tuple").boundsInRoot
            assertTrue(icon.right <= tuple.left)
            assertTrue(tuple.bottom <= bounds.bottom + 1f)
            assertTrue(p.canvas.reading()!![BlueprintTier.Detail] == 1)
            for (activity in BlueprintAgent.Activity.entries) for (count in listOf(0, 2, 12)) {
                p.set(agent.copy(image = p.state.value.agent!!.image, activity = activity, otherAgents = count))
                assertEquals(body, p.body, "$activity / $count keeps the measured body")
                assertEquals(before, p.layout!!.cards)
                assertEquals(camera, p.canvas.viewport())
                assertEquals(bounds, p.tag("blueprint-agent-bpo-agent-slot").boundsInRoot)
                assertEquals(icon, p.tag("blueprint-agent-bpo-agent-slot-icon").boundsInRoot)
                assertEquals(tuple, p.tag("blueprint-agent-bpo-agent-slot-tuple").boundsInRoot)
                val label = activity.label + if (count > 0) " · +$count" else ""
                val state = p.tag("blueprint-agent-bpo-agent-slot-state")
                assertTrue(state.config[SemanticsProperties.Text].any { it.text == label })
                val texts = arrayListOf<TextLayoutResult>()
                edt { assertTrue(state.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(texts)) }
                assertTrue(texts.isNotEmpty() && texts.none { it.hasVisualOverflow }, "$label: ${texts.map { listOf(it.size, it.lineCount, it.didOverflowWidth, it.didOverflowHeight, it.layoutInput.style) }}; bounds=${state.boundsInRoot}")
                for (target in listOf("icon", "tuple")) {
                    edt { assertTrue(p.tag("blueprint-agent-bpo-agent-slot-$target").config[SemanticsActions.OnClick].action!!.invoke()) }
                    assertEquals(p.state.value.agent, p.opened)
                }
                if (count == 2 && activity in listOf(BlueprintAgent.Activity.Claimed, BlueprintAgent.Activity.Live,
                        BlueprintAgent.Activity.Stale, BlueprintAgent.Activity.Completed)) p.picture("${mode.name.lowercase()}-${activity.name.lowercase()}")
            }
        }
    }

    @Test fun absentAgentsHaveNoReservedFooterOrGalleryHit() {
        probe(MachineSignalMode.Dark) { p ->
            val withAgent = p.body!!
            p.set(null)
            assertTrue(p.body!!.height < withAgent.height)
            assertEquals(BlueprintEngine.cardHeight(node), p.layout!!.cards.getValue(node.id).height)
            assertTrue(p.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("blueprint-agent-") == true })
        }
    }

    @Test fun aLongTupleFitsItsMeasuredSlotAndMiddleZoomKeepsOnlyTheGalleryIcon() {
        probe(MachineSignalMode.Light) { p ->
            p.set(p.state.value.agent!!.copy(role = "designer for graph presentation and interaction acceptance"))
            val tuple = p.tag("blueprint-agent-bpo-agent-slot-tuple")
            val bounds = p.tag("blueprint-agent-bpo-agent-slot").boundsInRoot
            assertTrue(tuple.boundsInRoot.bottom <= bounds.bottom + 1f)
            val card = p.layout!!.cards.getValue(node.id)
            edt { p.canvas.restore(40.0 - card.x * .8, 70.0 - card.y * .8, .8) }; p.frame()
            assertTrue(p.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-agent-bpo-agent-slot-tuple" })
            assertTrue(p.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-agent-bpo-agent-slot-state" })
            edt { assertTrue(p.tag("blueprint-agent-bpo-agent-slot-icon").config[SemanticsActions.OnClick].action!!.invoke()) }
            assertEquals(p.state.value.agent, p.opened)
        }
    }

    private inner class Probe(val mode: MachineSignalMode) {
        val canvas = GraphCanvasState(.01..4.0)
        val state = mutableStateOf(BlueprintCardLook(Color.Blue, glyph, "The agent slot fits without moving cards", "Blueprint presentation",
            pins = node.inputs.map { BlueprintPinLook(Color.Blue, it.type, Color.White, true) }, agent = agent.copy(image = familyImage(mode))))
        var body: BlueprintBody? = null
        var layout: BlueprintLayout<String>? = null
        var opened: BlueprintAgent? = null
        lateinit var scene: ImageComposeScene
        var time = 0L

        fun frame() { repeat(12) { edt { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close() }; Thread.sleep(20) } }
        fun set(agent: BlueprintAgent?) { edt { state.value = state.value.copy(agent = agent) }; frame() }
        fun nodes(): List<SemanticsNode> {
            var result = emptyList<SemanticsNode>()
            edt {
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                result = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            return result
        }
        fun tag(tag: String) = nodes().singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            ?: error("Missing $tag at ${canvas.viewport()}; ${canvas.reading()}; tags=${nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }}")
        fun picture(name: String) = edt {
            val folder = File("build/reports/blueprint-agent-slot").apply { mkdirs() }
            scene.render(++time * 16_666_667L).use { image -> image.encodeToData()!!.use { data -> File(folder, "$name.png").writeBytes(data.bytes) } }
        }
    }

    private fun probe(mode: MachineSignalMode, inspect: (Probe) -> Unit) {
        val p = Probe(mode)
        edt {
            p.scene = ImageComposeScene(1200, 700, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                    val colors = MachineSignal.colors
                    val look = p.state.value.copy(pins = p.state.value.pins.map { it?.copy(textColor = colors.textStrong) })
                    val body = measureBlueprintBody(node, look)
                    p.body = body
                    val layout = remember(body) { BlueprintEngine.layout("slot", listOf(BlueprintGroup("paint", "Presentation", listOf(node.copy(body = body)))), emptyList(), 1.6) }
                    p.layout = layout
                    BlueprintCanvas(layout, p.canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(colors.controlAccent, false, false) }, frameLook = { BlueprintFrameLook(glyph, "") },
                        cardLook = { look }, onCardClick = {}, onLinkClick = {}, onPaneClick = {}, onPinClick = { _, _ -> },
                        modifier = Modifier.fillMaxSize(), onAgentOpen = { p.opened = it })
                }
            }
        }
        try {
            p.frame()
            val card = p.layout!!.cards.getValue(node.id)
            edt { p.canvas.restore(40.0 - card.x * 1.5, 70.0 - card.y * 1.5, 1.5) }; p.frame()
            inspect(p)
        } finally { edt { p.scene.close() } }
    }

    private fun familyImage(mode: MachineSignalMode) = System.getenv("ORG_GRADLE_PROJECT_blueprintBadgeIcons")?.let { folder ->
        val file = File(folder, if (mode == MachineSignalMode.Light) "codex-light.png" else "codex-dark.png")
        org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    }
    private fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
}
