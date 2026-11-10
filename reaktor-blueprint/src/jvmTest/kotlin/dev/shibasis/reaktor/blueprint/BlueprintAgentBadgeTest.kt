@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.blueprint.canvas.BlueprintAgentBadge
import dev.shibasis.reaktor.blueprint.canvas.agentBadgeFrames
import dev.shibasis.reaktor.blueprint.canvas.agentBadgeHeat
import dev.shibasis.reaktor.blueprint.canvas.agentBadgeTuple
import dev.shibasis.reaktor.blueprint.canvas.agentBadgeState
import dev.shibasis.reaktor.blueprint.canvas.drawBlueprintAgentIcon
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.colors
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BlueprintAgentBadgeTest {
    private val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Claimed,
        effort = "xhigh", model = "sol 6.1", role = "designer")

    @Test fun mixedParticipantsHaveSeparateSideBySideIconsAndActions() {
        val companions = listOf(BlueprintAgent(null, "Claude", BlueprintAgent.Activity.Assigned, key = "claude"),
            BlueprintAgent(null, "Ada", BlueprintAgent.Activity.Completed, key = "ada", person = true))
        val people = agent.copy(key = "codex", companions = companions, otherAgents = 2)
        val opened = mutableListOf<String?>()
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(360, 100, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    BlueprintAgentBadge(people, {}, Modifier.fillMaxWidth(), onParticipantOpen = { opened += it.key })
                }
            }
        }
        try {
            repeat(4) { tick -> SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); scene.render((tick + 1L) * 16_666_667L).close() } }
            SwingUtilities.invokeAndWait {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                val icons = (0..2).map { i -> nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-agent-participant-$i-icon" } }
                assertTrue(icons.zipWithNext().all { (a, b) -> a.boundsInRoot.right <= b.boundsInRoot.left && a.boundsInRoot.top == b.boundsInRoot.top })
                icons.forEach { assertTrue(it.boundsInRoot.height > 0); it.config[SemanticsActions.OnClick].action!!.invoke() }
                assertEquals(listOf<String?>("codex", "claude", "ada"), opened.toList())
            }
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }

    @Test fun modelAndRoleComeFromStructuredHostValuesWithoutParsingTheLabel() {
        assertEquals("(sol 6.1, designer)", agentBadgeTuple(agent))
        assertEquals("", agentBadgeTuple(agent.copy(model = null, role = null)))
        assertEquals("", agentBadgeTuple(agent.copy(label = "(claude, max, opus 5.5, hidden role)", model = null, role = null)))
        assertEquals("(sol 6.1)", agentBadgeTuple(agent.copy(role = null)))
        assertEquals("(designer)", agentBadgeTuple(agent.copy(model = null)))
        assertEquals("Codex", agentBadgeState(agent.copy(label = "Codex", activity = BlueprintAgent.Activity.Unavailable)))
        assertEquals("Codex · Live", agentBadgeState(agent.copy(label = "Codex", activity = BlueprintAgent.Activity.Live)))
    }

    @Test fun missingMetadataUsesOneVisibleLineAndKeepsTheGalleryAction() {
        for (density in listOf(1f, 2f)) for (known in listOf(false, true)) {
            var opens = 0
            val current = if (known) agent.copy(label = "Codex")
                else agent.copy(label = "Codex", model = null, role = null, activity = BlueprintAgent.Activity.Unavailable)
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((260 * density).toInt(), (160 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                        Column { BlueprintAgentBadge(current, { opens++ }, Modifier.width(240.dp)) }
                    }
                }
            }
            var tick = 0L
            fun settle() { repeat(16) {
                SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }
                Thread.sleep(2)
            } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            fun node(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            try {
                settle()
                SwingUtilities.invokeAndWait {
                val badge = node("blueprint-agent").boundsInRoot
                val height = badge.height / density
                val texts = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                if (known) {
                    val rows = nodes().filter { it.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty() }.map { it.boundsInRoot }
                    assertEquals(2, rows.size)
                    val stackHeight = rows.sumOf { it.height.toDouble() }.toFloat() / density + MachineSignal.Space.s1_5.value
                    assertEquals(maxOf(MachineSignal.AgentBadge.size.value, stackHeight), height, 1f / density,
                        "The badge fits its measured metadata stack and gallery icon: density=$density texts=$texts")
                    assertTrue(rows.all { it.height > 0f && it.top >= badge.top && it.bottom <= badge.bottom })
                    assertFalse(rows[0].overlaps(rows[1]), "Known state and tuple must remain separately readable")
                } else assertEquals(MachineSignal.AgentBadge.size.value, height, .01f)
                assertEquals(if (known) listOf("Codex · Claimed", "(sol 6.1, designer)") else listOf("Codex"), texts)
                assertTrue(node("blueprint-agent-tuple").config[SemanticsActions.OnClick].action?.invoke() == true)
                assertTrue(node("blueprint-agent-icon").config[SemanticsActions.OnClick].action?.invoke() == true)
                assertEquals(2, opens)
                assertEquals(if (known) "sol 6.1" else null, current.model)
                assertEquals(if (known) "designer" else null, current.role)
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    @Test fun everyReasoningLevelHasASeparateHeatTokenInBothThemes() {
        val levels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
        for (light in listOf(false, true)) {
            val colors = levels.map { agentBadgeHeat(it, light, Color.Magenta) }
            assertEquals(levels.size, colors.toSet().size)
            for (i in colors.indices) for (j in i + 1 until colors.size) assertTrue(abs(colors[i].luminance() - colors[j].luminance()) > .003f)
            assertTrue(colors.all { if (light) (1.05f / (it.luminance() + .05f)) >= 3f else (it.luminance() + .05f) / .058f >= 3f })
            assertEquals(colors[5], agentBadgeHeat("extra_high", light, Color.Magenta))
            assertEquals(Color.Magenta, agentBadgeHeat("adaptive", light, Color.Magenta))
            assertEquals(Color.Magenta, agentBadgeHeat(null, light, Color.Magenta))
        }
    }

    private fun pixels(activity: BlueprintAgent.Activity, phase: Float, density: Float): List<Color> {
        val side = (MachineSignal.AgentBadge.size.value * density).toInt()
        val bitmap = ImageBitmap(side, side)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
            drawBlueprintAgentIcon(agent.copy(activity = activity), Color.Red, phase)
        }
        return bitmap.toPixelMap().let { map -> (0 until side).flatMap { y -> (0 until side).map { x -> map[x, y] } } }
    }

    @Test fun liveSweepsWhileAssignedAndCompletedHaveStaticRings() {
        for (density in listOf(1f, 2f)) {
            val assigned = pixels(BlueprintAgent.Activity.Claimed, 0f, density)
            assertEquals(assigned, pixels(BlueprintAgent.Activity.Claimed, .5f, density))
            assertEquals(assigned, pixels(BlueprintAgent.Activity.Assigned, .5f, density))
            assertNotEquals(assigned, pixels(BlueprintAgent.Activity.Stale, 0f, density))
            assertNotEquals(pixels(BlueprintAgent.Activity.Live, 0f, density), pixels(BlueprintAgent.Activity.Live, .5f, density))
            val completed = pixels(BlueprintAgent.Activity.Completed, 0f, density)
            assertEquals(assigned, completed)
            assertEquals(completed, pixels(BlueprintAgent.Activity.Completed, .5f, density))
            assertNotEquals(completed, pixels(BlueprintAgent.Activity.Unassigned, 0f, density))
        }
    }

    @Test fun onlyVisibleLiveAgentsRequestFramesAndCancellationStopsTheClock(): Unit = runBlocking {
        val clock = BroadcastFrameClock()
        for (activity in BlueprintAgent.Activity.entries) for (visible in listOf(false, true)) {
            val phases = mutableListOf<Float>()
            val job = launch(clock, start = CoroutineStart.UNDISPATCHED) { agentBadgeFrames(activity, visible, phases::add) }
            if (activity == BlueprintAgent.Activity.Live && visible) {
                assertTrue(clock.hasAwaiters)
                clock.sendFrame(1_000_000L)
                kotlinx.coroutines.yield()
                clock.sendFrame(401_000_000L)
                kotlinx.coroutines.yield()
                assertEquals(.25f, phases.single())
                job.cancelAndJoin()
                assertFalse(clock.hasAwaiters)
            } else {
                assertTrue(job.isCompleted)
                assertFalse(clock.hasAwaiters)
                assertTrue(phases.isEmpty())
            }
        }
    }

    @Test fun aPaneOfAssignedAgentsStopsInvalidatingAfterItSettles() {
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(660, 520, coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    Column { repeat(12) { BlueprintAgentBadge(agent, {}, tag = "idle-agent-$it") } }
                }
            }
            try {
                repeat(12) { Snapshot.sendApplyNotifications(); scene.render((it + 1L) * 16_666_667L).close() }
                var invalidations = 0
                repeat(120) {
                    Snapshot.sendApplyNotifications()
                    if (scene.hasInvalidations()) invalidations++
                }
                assertEquals(0, invalidations)
                File("build/reports/blueprint-agent-badge").apply { mkdirs() }
                    .resolve("idle-probe.json").writeText("{\"renderer\":\"ImageComposeScene\",\"agents\":12,\"activity\":\"Claimed\",\"settleFrames\":12,\"idlePolls\":120,\"invalidations\":$invalidations}")
            } finally { scene.close() }
        }
    }

    @Test fun theFamilyImageRetainsItsOwnColoursInsideTheHeatRing() {
        val image = ImageBitmap(24, 24)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(24f, 24f)) { drawRect(Color.Blue) }
        val output = ImageBitmap(32, 32)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output), Size(32f, 32f)) {
            drawBlueprintAgentIcon(agent.copy(image = image), Color.Red, 0f)
        }
        assertEquals(Color.Blue, output.toPixelMap()[16, 16])
    }

    private fun renderApprovedFamilyAssets() {
        val assetRoot = System.getenv("ORG_GRADLE_PROJECT_blueprintBadgeIcons") ?: return
        require(File(assetRoot).isDirectory)
        val output = File("build/reports/blueprint-agent-badge").apply { mkdirs() }
        val activities = listOf(BlueprintAgent.Activity.Claimed, BlueprintAgent.Activity.Live, BlueprintAgent.Activity.Stale, BlueprintAgent.Activity.Completed)
        for (mode in listOf(MachineSignalMode.Dark, MachineSignalMode.Light)) for (density in listOf(1f, 2f)) {
            val light = mode == MachineSignalMode.Light
            fun icon(name: String) = org.jetbrains.skia.Image.makeFromEncoded(File(assetRoot, name).readBytes()).toComposeImageBitmap()
            val families = listOf("claude" to icon("claude.png"), "codex" to icon(if (light) "codex-light.png" else "codex-dark.png"))
            SwingUtilities.invokeAndWait {
                val scene = ImageComposeScene((660 * density).toInt(), (520 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        Column(Modifier.fillMaxSize().background(MachineSignal.colors.canvas).padding(MachineSignal.Space.s4), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
                            Text("Agent badges · $mode · density $density", role = dev.shibasis.reaktor.surface.Type.Body)
                            for ((family, bitmap) in families) for (activity in activities) {
                                Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s4)) {
                                    BlueprintAgentBadge(agent.copy(image = bitmap, label = "($family, xhigh, ${if (family == "claude") "opus 5.5" else "sol 6.1"}, designer)",
                                        model = if (family == "claude") "opus 5.5" else "sol 6.1", activity = activity), {}, Modifier.width(400.dp))
                                    Text(activity.label, role = dev.shibasis.reaktor.surface.Type.Meta)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
                                for (level in listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")) {
                                    Column {
                                        BlueprintAgentBadge(agent.copy(image = families.last().second, effort = level), {}, compact = true)
                                        Text(level, role = dev.shibasis.reaktor.surface.Type.Meta)
                                    }
                                }
                            }
                        }
                    }
                }
                try {
                    repeat(8) { Snapshot.sendApplyNotifications(); scene.render((it + 1L) * 16_666_667L).close() }
                    scene.render(150_000_000L).use { image -> File(output, "badge-${mode.name.lowercase()}-${density.toInt()}x.png").writeBytes(requireNotNull(image.encodeToData()).bytes) }
                } finally { scene.close() }
            }
        }
    }

    @Test fun iconAndTupleOpenTheSameGalleryAndCompactKeepsOnlyTheIcon() {
        for (density in listOf(1f, 2f)) for (mode in listOf(MachineSignalMode.Dark, MachineSignalMode.Light)) {
            val compact = mutableStateOf(false)
            var opens = 0
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((420 * density).toInt(), (120 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        Column(Modifier.fillMaxWidth().background(if (mode == MachineSignalMode.Dark) MachineSignal.Editor.Canvas else Color.White)) {
                            BlueprintAgentBadge(agent, { opens++ }, compact = compact.value)
                        }
                    }
                }
            }
            var frame = 0L
            fun settle() = repeat(6) { SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); scene.render(++frame * 16_666_667L).close() } }
            fun nodes(): List<SemanticsNode> {
                var result = emptyList<SemanticsNode>()
                val collect = {
                    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                    result = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                }
                if (SwingUtilities.isEventDispatchThread()) collect() else SwingUtilities.invokeAndWait(collect)
                return result
            }
            fun node(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            try {
                settle()
                val before = node("blueprint-agent-icon").boundsInRoot
                for (target in listOf("blueprint-agent-icon", "blueprint-agent-tuple")) SwingUtilities.invokeAndWait {
                    assertTrue(node(target).config[SemanticsActions.OnClick].action?.invoke() == true)
                }
                assertEquals(2, opens)
                assertTrue(nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "(sol 6.1, designer)" } == true })
                SwingUtilities.invokeAndWait { compact.value = true }; settle()
                assertEquals(before, node("blueprint-agent-icon").boundsInRoot)
                assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "blueprint-agent-tuple" })
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
        renderApprovedFamilyAssets()
    }
}
