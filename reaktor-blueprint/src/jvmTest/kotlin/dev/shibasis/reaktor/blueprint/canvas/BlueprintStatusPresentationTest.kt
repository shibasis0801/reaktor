@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.jvm.internal.CallableReference
import kotlin.test.*

class BlueprintStatusPresentationTest {
    @Test fun aNonInteractiveProseFacePlacesItsThirdBadge() {
        withPainter { painter, look, _ ->
            assertEquals(setOf("To do", "Waiting", "High"), placed(painter, look).map { field(it, "mark") as BlueprintMark }.map { it.text }.toSet())
        }
    }

    @Test fun badgeTextContrastsWithItsPaintedChipInEveryTheme() {
        for (mode in MachineSignalMode.entries) withPainter(mode) { painter, look, card ->
            placed(painter, look).forEach { value ->
                val mark = field(value, "mark") as BlueprintMark
                val layout = field(field(value, "measured"), "text") as TextLayoutResult
                val background = mark.color.copy(alpha = BlueprintBadgeStyle.Tint).compositeOver(card)
                assertTrue(contrast(layout.layoutInput.style.color, background) >= 4.5, "$mode/${mark.text} chip contrast is ${contrast(layout.layoutInput.style.color, background)}")
            }
        }
    }

    @Test fun secondaryProseTextContrastsWithItsStatusFill() {
        for (mode in MachineSignalMode.entries) withPainter(mode) { painter, _, card ->
            for (name in listOf("footerStyle", "eyebrowStyle")) {
                val style = field(painter, name) as androidx.compose.ui.text.TextStyle
                assertTrue(contrast(style.color, card) >= 4.5, "$mode/$name contrast is ${contrast(style.color, card)}")
            }
        }
    }

    @Test fun aCutSubtitleIsReportedAsOverflowAndALongFirstWordStillDraws() {
        withPainter { painter, look, _ ->
            val method = painter.javaClass.getDeclaredMethod("proseSubtitle", BlueprintCardLook::class.java, Float::class.javaPrimitiveType).apply { isAccessible = true }
            val clipped = assertNotNull(method.invoke(painter, look.copy(subtitle = "This evidence continues across many delivery stages ".repeat(12)), 170f)) as TextLayoutResult
            assertTrue(clipped.hasVisualOverflow, "A truncated subtitle must not be reported as complete")
            val unbroken = assertNotNull(method.invoke(painter, look.copy(subtitle = "VerificationEvidenceForDelivery".repeat(8)), 170f), "A first word wider than the line keeps a visible subtitle") as TextLayoutResult
            assertTrue(unbroken.layoutInput.text.isNotEmpty())
        }
    }

    private fun placed(painter: BlueprintPainter, look: BlueprintCardLook): List<Any> =
        painter.javaClass.getDeclaredMethod("proseMarks", BlueprintCardLook::class.java, Float::class.javaPrimitiveType).let { method ->
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (method.invoke(painter, look, 428f) as List<Any>)
        }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.compositeOver(background).luminance().toDouble()
        val b = background.luminance().toDouble()
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    private fun withPainter(mode: MachineSignalMode = MachineSignalMode.Dark, action: (BlueprintPainter, BlueprintCardLook, Color) -> Unit) {
        SwingUtilities.invokeAndWait {
            val card = Card("item", 20.0, 20.0, 440.0, 210.0, emptyList(), 0)
            val layout = BlueprintLayout("marks", mapOf(card.id to card), emptyList(), emptyList(), 520.0, 300.0)
            val canvas = GraphCanvasState(.01..4.0)
            var look: BlueprintCardLook? = null
            var body = Color.Unspecified
            val scene = ImageComposeScene(600, 400, coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                    val tone = Ink.Warn.color
                    body = lerp(MachineSignal.colors.card, tone, .24f)
                    val current = BlueprintCardLook(Ink.Warn.color, BlueprintBench.icon, "A waiting task", "Evidence remains readable", face = BlueprintFace.Prose,
                        fill = body, outline = Ink.Warn.color, marks = listOf(BlueprintMark("To do", Ink.Warn.color, BlueprintMark.Kind.Badge),
                            BlueprintMark("Waiting", Ink.Accent.color, BlueprintMark.Kind.Badge), BlueprintMark("High", Ink.Danger.color, BlueprintMark.Kind.Badge)))
                    look = current
                    BlueprintCanvas(layout, canvas, emptySet(), null, linkStyle = { Blueprint.link(tone, false, false) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") }, cardLook = { current },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                }
            }
            try {
                repeat(12) { Snapshot.sendApplyNotifications(); scene.render(it * 16666667L).close() }
                val painter = assertNotNull((canvas.reading as? CallableReference)?.boundReceiver) as BlueprintPainter
                action(painter, assertNotNull(look), body)
            } finally { scene.close() }
        }
    }
}
