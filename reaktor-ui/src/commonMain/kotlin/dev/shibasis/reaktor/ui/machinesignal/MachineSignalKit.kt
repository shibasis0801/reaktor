package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.core.truth.Fact
import dev.shibasis.reaktor.core.truth.TruthClass
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.TextRole
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Badge
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import dev.shibasis.reaktor.surface.compose.Separator
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.TextAppearance
import dev.shibasis.reaktor.ui.machinesignal.surface.SignalTypography
import dev.shibasis.reaktor.ui.machinesignal.surface.divider
import dev.shibasis.reaktor.ui.machinesignal.surface.provenanceBadge

data class MachineSignalFonts(
    val ui: FontFamily = FontFamily.SansSerif,
    val mono: FontFamily = FontFamily.Monospace,
    val chrome: PlatformTextStyle? = null,
    val base: TextStyle? = null,
)

val LocalMachineSignalFonts = staticCompositionLocalOf { MachineSignalFonts() }

@Composable
fun ProvideMachineSignalFonts(fonts: MachineSignalFonts, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalMachineSignalFonts provides fonts, content = content)

@Composable
fun rememberSignalGlyph(text: String, role: TextRole = Type.Body, ink: InkRole = Ink.Text): Modifier {
    val style = SignalTypography.style(role, ink, LocalThemeSnapshot.current.signalOrBoard)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    return remember(text, style, measurer) {
        val glyph = SharedGlyph(text, style, measurer)
        Modifier
            .drawWithCache {
                val layout = glyph.layout(Constraints.fixed(this.size.width.roundToInt(), this.size.height.roundToInt()), layoutDirection, this)
                onDrawBehind { drawText(layout) }
            }
            .semantics { this.text = glyph.described }
    }
}

private class SharedGlyph(private val text: String, private val style: TextStyle, private val measurer: TextMeasurer) {
    val described = AnnotatedString(text)
    private var last: TextLayoutResult? = null

    fun layout(box: Constraints, direction: LayoutDirection, density: Density): TextLayoutResult =
        last?.takeIf { it.layoutInput.fits(box, direction, density) }
            ?: measurer.measure(text, style, TextOverflow.Ellipsis, maxLines = 1, constraints = box, layoutDirection = direction, density = density).also { last = it }

    private fun TextLayoutInput.fits(box: Constraints, direction: LayoutDirection, density: Density) =
        constraints == box && layoutDirection == direction && this.density.density == density.density && this.density.fontScale == density.fontScale
}

enum class SignalTone(val fill: Color, val text: Color, val line: Color, val hover: Color) {
    // Btn / Primary is authored as a solid accent fill with a white label, not a tint — the golden
    // gate reads the difference as a near-total pixel mismatch, so keep this solid.
    Primary(MachineSignal.Accent, Color.White, MachineSignal.AccentLine, MachineSignal.Accent2),
    Secondary(MachineSignal.Bg3, MachineSignal.Text1, MachineSignal.Line2, MachineSignal.Bg4),
    Ghost(Color.Transparent, MachineSignal.Text2, MachineSignal.Line1, MachineSignal.Bg2),
    Danger(MachineSignal.Status.Error.copy(alpha = 20f / 255f), MachineSignal.Status.Error, MachineSignal.Status.Error.copy(alpha = 82f / 255f), MachineSignal.Status.Error.copy(alpha = 48f / 255f)),
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) =
    Box(modifier.size(6.dp).background(color, RoundedCornerShape(3.dp)))

@Composable
fun StatusDot(ink: InkRole, modifier: Modifier = Modifier) = StatusDot(LocalThemeSnapshot.current.machineSignal.ink(ink), modifier)

@Composable
fun RoundDot(ink: InkRole, modifier: Modifier = Modifier) =
    Box(modifier.clip(CircleShape).background(LocalThemeSnapshot.current.machineSignal.ink(ink)))

@Composable
fun KeyValueRow(
    key: String,
    fact: Fact<String>,
    modifier: Modifier = Modifier,
    keyWidth: Dp = 132.dp,
) {
    val editor = editorVariant()
    Row(
        modifier.fillMaxWidth().then(if (editor) Modifier.heightIn(min = MachineSignal.Editor.treeRowHeight).padding(vertical = MachineSignal.Space.s1) else Modifier.height(MachineSignal.Metrics.kvRowHeight)),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, if (editor) Modifier.weight(.38f) else Modifier.width(keyWidth), role = Type.Label, ink = Ink.Unknown, lines = if (editor) Int.MAX_VALUE else 1, appearance = PassiveText)
        Text(fact.value, Modifier.weight(if (editor) .62f else 1f), role = if (editor) Type.Body else Type.Body.code,
            ink = if (fact.provesHealth) Ink.Text else Ink.Unknown, lines = if (editor) Int.MAX_VALUE else 1, appearance = PassiveText)
        if (fact.truth != TruthClass.Live) Provenance(fact.truth)
    }
}

@Composable
fun NotWiredYet(what: String, turnsOnWith: String, modifier: Modifier = Modifier) = Column(
    modifier
        .fillMaxWidth()
        .background(signalColors().surface, MachineSignal.Shape.Panel)
        .border(1.dp, signalColors().lineSubtle, MachineSignal.Shape.Panel)
        .padding(MachineSignal.Space.s4)
        .testTag("not-wired-yet"),
    verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Provenance(TruthClass.Unknown)
        Text(what, role = Type.Body.medium, ink = Ink.Text, lines = 4, appearance = PassiveText)
    }
    Text("Turns on with: $turnsOnWith", role = Type.Body, ink = Ink.Unknown, lines = 3, appearance = PassiveText)
}

@Composable
fun ContextBar(
    breadcrumb: List<String>,
    modifier: Modifier = Modifier,
    truth: TruthClass? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) = Column(modifier.fillMaxWidth().height(if (editorVariant()) MachineSignal.Editor.menuHeight else MachineSignal.Metrics.contextBarHeight)) {
    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(signalColors().surface)
            .padding(horizontal = MachineSignal.Metrics.shellPaddingX),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.contextBarGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            breadcrumb.forEachIndexed { index, crumb ->
                if (index > 0) Text("/", role = Type.Body, ink = SignalInk.Rule, appearance = PassiveText)
                Text(crumb.uppercase(), role = Type.Eyebrow, ink = if (index == breadcrumb.lastIndex) Ink.Text else Ink.Unknown, appearance = PassiveText)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trailing != null) trailing()
            if (truth != null) Provenance(truth)
        }
    }
    Separator(appearance = divider())
}

@Composable
fun StatusPill(
    label: String,
    ink: InkRole,
    modifier: Modifier = Modifier,
) {
    val color = LocalThemeSnapshot.current.signalOrBoard.ink(ink)
    Row(
        modifier
            .height(MachineSignal.Metrics.statusPillHeight)
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(MachineSignal.Radius.statusPill))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(MachineSignal.Radius.statusPill))
            .padding(horizontal = MachineSignal.Metrics.statusPillPaddingX),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.statusPillGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        Text(label, role = Type.Data.strong.code, ink = Ink.Strong, appearance = PassiveText)
    }
}

@Composable
private fun Provenance(truth: TruthClass) =
    Badge(Modifier.testTag("provenance-${truth.name.lowercase()}"), provenanceBadge(truth)) { Text(truth.abbreviation, appearance = PassiveText) }

private val PassiveText: TextAppearance = object : TextAppearance {
    @Composable
    override fun style(role: TextRole?, ink: InkRole?, theme: ThemeSnapshot): TextStyle = SignalTypography.style(role, ink, theme.signalOrBoard)
}

@Composable
private fun signalColors(): MachineSignalColors = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.colors ?: MachineSignalColors.Board

@Composable
private fun editorVariant(): Boolean = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor
