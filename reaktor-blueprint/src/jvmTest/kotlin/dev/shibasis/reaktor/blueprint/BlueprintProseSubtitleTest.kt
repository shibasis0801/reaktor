@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.blueprint.canvas.BlueprintPainter
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.graph.core.PortWiring
import kotlin.jvm.internal.CallableReference
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintProseSubtitleTest {
    private val evidence = "Verified 519 of 519 tests, 251 of 251 flows and 47 of 47 parity checks; production release receipt 5a3b76fb"

    @Test fun aLongSubtitleWrapsToASecondLineInsteadOfWideningTheCard() {
        val node = BlueprintNode("item", 0)
        val look = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Release the learning pages", "Done", face = BlueprintFace.Prose)
        lateinit var scene: ImageComposeScene
        var short: BlueprintBody? = null
        var long: BlueprintBody? = null
        edt {
            scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    short = measureBlueprintBody(node, look)
                    long = measureBlueprintBody(node, look.copy(subtitle = evidence))
                }
            }
            repeat(8) { Snapshot.sendApplyNotifications(); scene.render(it * 16_666_667L).close() }
        }
        try {
            val one = requireNotNull(short)
            val two = requireNotNull(long)
            assertEquals(BlueprintEngine.CardWidth, one.width)
            assertEquals(one.width, two.width)
            assertTrue(two.height - one.height in 12.0..BlueprintEngine.RowHeight, "second subtitle line adds ${two.height - one.height}")
        } finally { edt { scene.close() } }
    }

    @Test fun aDrawnSubtitleStaysBetweenItsTitlePinsAndAgentFooter() {
        val pins = listOf(Pin("wait", "Waits on", false, 100.0, PortWiring.Connected), Pin("connect", "Connect", true, 128.0, PortWiring.Connected))
        val card = Card("item", 20.0, 20.0, 360.0, 340.0, pins, 0)
        val layout = BlueprintLayout("drawn-subtitle", mapOf(card.id to card), emptyList(), emptyList(), 450.0, 430.0)
        val canvas = GraphCanvasState(.01..4.0)
        edt {
            val look = BlueprintCardLook(Color.Blue, BlueprintBench.icon, "Release the learning pages", evidence,
                face = BlueprintFace.Prose, eyebrow = "Check", agent = BlueprintAgent(null, "Fixture builder", BlueprintAgent.Activity.Live),
                pins = pins.map { BlueprintPinLook(Color.Blue, it.type, Color.White, false) })
            val scene = ImageComposeScene(600, 500, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    BlueprintCanvas(layout, canvas, emptySet(), null, { Blueprint.link(Color.Blue, false, false) },
                        { BlueprintFrameLook(BlueprintBench.icon, "") }, { look }, {}, {}, {}, Modifier.fillMaxSize())
                }
            }
            try {
                repeat(12) { Snapshot.sendApplyNotifications(); scene.render(it * 16_666_667L).close() }
                val painter = (canvas.reading as CallableReference).boundReceiver as BlueprintPainter
                fun call(name: String, vararg args: Any): Any? {
                    val method = painter.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
                    method.isAccessible = true
                    return method.invoke(painter, *args)
                }
                val measure = requireNotNull(call("worldMeasure", card, look))
                val body = measure.javaClass.getDeclaredField("bodyHeight").let { it.isAccessible = true; it.getFloat(measure) }
                val room = card.width.toFloat() - 24f
                val subtitle = requireNotNull(call("proseSubtitle", look, room)) as TextLayoutResult
                val title = requireNotNull(call("proseTitle", card, look, room, body)) as TextLayoutResult
                val top = body - 28f - (subtitle.size.height - subtitle.getLineBottom(0))
                assertTrue(34f + title.size.height <= top, "The drawn title ends before the subtitle")
                assertTrue(top + subtitle.size.height <= body, "Both drawn subtitle lines remain inside the body")
                assertTrue(pins.maxOf { it.y } < top, "The subtitle follows both port rows")
                val cache = painter.javaClass.getDeclaredField("measurer").let { it.isAccessible = true; it.get(painter) }
                fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
                val textCache = field(field(cache, "textLayoutCache"), "cache")
                fun calls() = listOf("hitCount", "missCount").sumOf { name -> textCache.javaClass.methods.single { it.name == name && it.parameterCount == 0 }.invoke(textCache) as Int }
                val before = calls()
                repeat(16) { call("proseSubtitle", look, room) }
                assertEquals(before, calls(), "Repeated body, record and draw reads reuse the subtitle layout")
            } finally { scene.close() }
        }
    }

    private fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
}
