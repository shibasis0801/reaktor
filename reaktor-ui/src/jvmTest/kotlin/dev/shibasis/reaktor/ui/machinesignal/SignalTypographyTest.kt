package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.core.truth.TruthClass
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.TextRole
import dev.shibasis.reaktor.surface.ThemeMismatch
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.TypeFace
import dev.shibasis.reaktor.surface.TypeScale
import dev.shibasis.reaktor.surface.TypeWeight
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.ui.machinesignal.surface.toneButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import androidx.compose.material3.Text as MaterialText

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTextApi::class)
class SignalTypographyTest {
    private val sample = "Deploy target · 42 ms"
    private val hangarBody = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.sp)
    private val chrome = PlatformTextStyle(
        spanStyle = null,
        paragraphStyle = PlatformParagraphStyle(
            fontRasterizationSettings = FontRasterizationSettings(FontSmoothing.AntiAlias, FontHinting.None, subpixelPositioning = true, autoHintingForced = false),
        ),
    )
    private val failures = mutableListOf<String>()

    private val sizes = mapOf(
        TypeScale.Display to 24.sp, TypeScale.Title to 17.sp, TypeScale.Title2 to 16.sp, TypeScale.Title3 to 15.sp,
        TypeScale.Title4 to 14.sp, TypeScale.Body to 13.sp, TypeScale.Label to 12.sp, TypeScale.Body2 to 11.5.sp,
        TypeScale.Meta to 11.sp, TypeScale.Data to 10.5.sp, TypeScale.Caption to 10.sp, TypeScale.Micro to 9.5.sp,
    )
    private val weights = mapOf(
        TypeWeight.Regular to FontWeight.Normal, TypeWeight.Medium to FontWeight.Medium,
        TypeWeight.Strong to FontWeight.SemiBold, TypeWeight.Bold to FontWeight.Bold,
    )
    private val inks: Map<InkRole, Color> = mapOf(
        Ink.Strong to MachineSignal.Editor.Text, Ink.Text to MachineSignal.Editor.Text, Ink.Muted to MachineSignal.Editor.Muted,
        Ink.Unknown to MachineSignal.Editor.Unknown, Ink.Accent to MachineSignal.Editor.Accent, Ink.Source to MachineSignal.Editor.Source,
        Ink.Busy to MachineSignal.Accent, Ink.Ok to MachineSignal.Status.Ok, Ink.Warn to MachineSignal.Status.Warn,
        Ink.Danger to MachineSignal.Status.Error, Ink.Inverse to MachineSignal.Editor.Canvas, Ink.OnAccent to Color.White,
        EntityInk.Route to MachineSignal.Entity.Route, EntityInk.Screen to MachineSignal.Entity.Screen,
        EntityInk.Container to MachineSignal.Entity.Container, EntityInk.Service to MachineSignal.Entity.Service,
        EntityInk.Cloud to MachineSignal.Entity.Cloud, EntityInk.Actor to MachineSignal.Entity.Actor,
        EntityInk.Auth to MachineSignal.Entity.Auth, EntityInk.Infra to MachineSignal.Entity.Infra,
        EntityInk.Agent to MachineSignal.Entity.Agent, EntityInk.Topic to MachineSignal.Entity.Topic,
        EntityInk.Repo to MachineSignal.Entity.Repo, EntityInk.Interactor to MachineSignal.Entity.Interactor,
        EdgeInk.Exec to MachineSignal.Edge.Exec, EdgeInk.Navigation to MachineSignal.Edge.Navigation,
        EdgeInk.Data to MachineSignal.Edge.Data, EdgeInk.Attachment to MachineSignal.Edge.Attachment,
        EdgeInk.Containment to MachineSignal.Edge.Containment, EdgeInk.PortOff to MachineSignal.Edge.PortOff,
        ProvenanceInk(TruthClass.Imported) to MachineSignal.provenance(TruthClass.Imported).base,
    )

    @Test
    fun everyRoleDrawsExactlyWhatSignalTextDrew() {
        listOf(true, false).forEach { hangar ->
            sizes.forEach { (scale, size) ->
                weights.forEach { (weight, font) ->
                    listOf(false, true).forEach { mono ->
                        val role = TextRole(scale, weight, if (mono) TypeFace.Code else TypeFace.Text)
                        same("$role/hangar=$hangar", hangar,
                            { SignalText(sample, color = MachineSignal.Text2, size = size, weight = font, mono = mono) },
                            { Text(sample, role = role, ink = Ink.Text) })
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun everyInkDrawsTheColourItsCallSitePassed() {
        inks.forEach { (ink, legacy) ->
            same("$ink", true, { MaterialText(sample, color = legacy, fontSize = 12.sp, maxLines = 1) }, { Text(sample, role = Type.Label, ink = ink) })
        }
        same("text1 remapped", true, { SignalText(sample, color = MachineSignal.Text1) }, { Text(sample, role = Type.Body, ink = Ink.Strong) })
        same("text3 remapped", true, { SignalText(sample, color = MachineSignal.Entity.Infra) }, { Text(sample, role = Type.Body, ink = Ink.Muted) })
        same("text4 remapped", true, { SignalText(sample, color = MachineSignal.provenance(TruthClass.Fixture).base) }, { Text(sample, role = Type.Body, ink = Ink.Unknown) })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun eyebrowsChromeAndInheritedLabelsMatchTheirLegacyText() {
        same("eyebrow", true, { Eyebrow("RESULT") }, { Text("RESULT", role = Type.Eyebrow, ink = Ink.Unknown) })
        listOf(TypeScale.Label, TypeScale.Meta, TypeScale.Body).forEach { scale ->
            listOf(TypeWeight.Regular, TypeWeight.Strong).forEach { weight ->
                val size = sizes.getValue(scale)
                same("chrome/$scale/$weight", true, {
                    MaterialText(sample, color = MachineSignal.Editor.Muted, fontFamily = MachineSignalFonts().ui, fontSize = size,
                        fontWeight = weights.getValue(weight), lineHeight = size * MachineSignal.Editor.lineHeight,
                        style = TextStyle(platformStyle = chrome), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, { Text(sample, role = TextRole(scale, weight, TypeFace.Chrome), ink = Ink.Muted) })
            }
        }
        SignalTone.entries.forEach { tone ->
            same("inherited/$tone", true, {
                Button({}, appearance = toneButton(tone)) { MaterialText("Deploy", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }, { Button({}, appearance = toneButton(tone)) { Text("Deploy") } })
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun aBaseInTheFontsReplacesTheRootTextStyle() {
        listOf(Type.Body, Type.Meta.code, Type.Title.strong).forEach { role ->
            val legacy = render(true) { SignalText(sample, color = MachineSignal.Text2, size = sizes.getValue(role.scale), weight = weights.getValue(role.weight), mono = role.face == TypeFace.Code, maxLines = 3) }
            val based = render(false, MachineSignalFonts(chrome = chrome, base = MachineSignal.Type.base)) { Text(sample, role = role, ink = Ink.Text, lines = 3) }
            if (legacy.indices.any { legacy[it] != based[it] }) failures += "base/$role differs"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun selectionUsesTheSignalAccentAndForeignInksFailLoudly() {
        var selection: TextSelectionColors? = null
        render(true) { selection = LocalTextSelectionColors.current }
        assertEquals(TextSelectionColors(MachineSignal.Accent, MachineSignal.Accent.copy(alpha = .4f)), selection)
        val foreign = object : InkRole {}
        assertFailsWith<ThemeMismatch> { MachineSignalSnapshot.Editor.ink(foreign) }
    }

    private fun same(name: String, hangar: Boolean, legacy: @Composable () -> Unit, surface: @Composable () -> Unit) {
        val expected = render(hangar, content = legacy)
        val actual = render(hangar, content = surface)
        val differing = expected.indices.count { expected[it] != actual[it] }
        if (differing > 0) failures += "$name: $differing pixels differ"
    }

    private fun render(hangar: Boolean, fonts: MachineSignalFonts = MachineSignalFonts(chrome = chrome), content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = 520, height = 96, density = Density(2f)) {
            ProvideMachineSignalFonts(fonts) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    Box(Modifier.fillMaxSize().background(MachineSignal.Editor.Canvas).padding(4.dp)) {
                        if (hangar) ProvideTextStyle(hangarBody, content) else content()
                    }
                }
            }
        }
        val pixels = scene.render().toComposeImageBitmap().toPixelMap().buffer
        scene.close()
        return pixels
    }
}
