@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlueprintZoomTest {
    private val card = Card("bpo-zoom-tiers", 0.0, 0.0, 420.0, 220.0, emptyList(), 0)
    private val layout = BlueprintLayout("zoom", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
    private val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Claimed, effort = "xhigh", model = "sol 6.1", role = "designer")

    @Test fun middleIsWholeTitleAndCompactBadgeDetailAddsTheTupleAndClaimChangesNeverMoveAnything() {
        for (mode in listOf(MachineSignalMode.Dark, MachineSignalMode.Light)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.01..4.0)
            val assets = generateSequence(File(".").canonicalFile) { it.parentFile }
                .map { File(it, "bestbuds/modules/engine/src/jvmMain/resources/agent-icons") }.firstOrNull { it.isDirectory }
            val familyImage = assets?.resolve(if (mode == MachineSignalMode.Light) "codex-light.png" else "codex-dark.png")
                ?.let { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
            val activity = mutableStateOf(agent.activity)
            var opened: BlueprintAgent? = null
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
            fun frame() = edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }
            fun settle() { repeat(8) { frame() }; Thread.sleep(200); repeat(24) { frame(); Thread.sleep(5) } }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun semantics() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun tagged(tag: String) = semantics().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            edt {
                scene = ImageComposeScene((900 * density).toInt(), (600 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        BlueprintCanvas(layout, canvas, emptySet(), null,
                            linkStyle = { Blueprint.link(Color.Blue, false, false) }, frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                            cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, "The middle zoom shows simpler nodes with just the agent's icon", "Ready · Urgent",
                                face = BlueprintFace.Prose, eyebrow = "Blueprint", marks = listOf(BlueprintMark("High", Color.Red)), progress = .7f,
                                agent = agent.copy(image = familyImage, activity = activity.value)) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, onAgentOpen = { opened = it }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle()
                for ((zoom, tier) in listOf(.08 to BlueprintTier.Overview, .55 to BlueprintTier.Middle, 1.2 to BlueprintTier.Middle, 1.4 to BlueprintTier.Detail, .55 to BlueprintTier.Middle)) {
                    edt { canvas.restore(40.0, 70.0, zoom) }; settle()
                    val camera = canvas.viewport()
                    edt {
                        assertEquals(1, canvas.reading()!![tier], "mode=$mode density=$density zoom=$zoom reading=${canvas.reading()}")
                        assertEquals(if (tier == BlueprintTier.Overview) 0 else 1, canvas.reading()!![BlueprintTitle.Whole])
                        if (tier != BlueprintTier.Overview) {
                            val icon = assertNotNull(tagged("blueprint-agent-${card.id}-icon"))
                            assertTrue(icon.config[SemanticsActions.OnClick].action!!.invoke())
                            assertEquals(agent.label, opened?.label)
                            assertEquals(tier == BlueprintTier.Detail, tagged("blueprint-agent-${card.id}-tuple") != null)
                            assertTrue(canvas.reading()!!.smallest >= 11 - .001)
                        } else assertEquals(null, tagged("blueprint-agent-${card.id}-icon"))
                        val folder = File("build/reports/blueprint-zoom").apply { mkdirs() }
                        scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { data ->
                            File(folder, "${mode.name.lowercase()}-${density.toInt()}x-${tier.name.lowercase()}.png").writeBytes(data.bytes)
                        } }
                    }
                    activity.value = BlueprintAgent.Activity.Live; settle()
                    assertEquals(camera, canvas.viewport())
                    assertEquals(card, layout.cards.getValue(card.id))
                    activity.value = BlueprintAgent.Activity.Claimed; settle()
                    assertEquals(camera, canvas.viewport())
                }
            } finally { edt { scene.close() } }
        }
    }
}
