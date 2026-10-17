package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.core.truth.Fact
import dev.shibasis.reaktor.core.truth.TruthClass
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot

@Composable
internal fun workspaceColor(color: Color): Color {
    val colors = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.colors ?: return color
    return when (color) {
        MachineSignal.Bg0 -> colors.canvas
        MachineSignal.Bg1 -> colors.surface
        MachineSignal.Bg2 -> colors.surfaceAlt
        MachineSignal.Bg3 -> colors.raised
        MachineSignal.Bg4 -> colors.raisedAlt
        MachineSignal.Line1 -> colors.lineSubtle
        MachineSignal.Line2 -> colors.line
        MachineSignal.Line3 -> colors.lineStrong
        MachineSignal.Text1 -> colors.textStrong
        MachineSignal.Text2 -> colors.text
        MachineSignal.Text3 -> colors.textMuted
        MachineSignal.Text4 -> colors.textFaint
        else -> color
    }
}

@Composable
private fun retiredFonts(): MachineSignalFonts = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.fonts ?: LocalMachineSignalFonts.current

@Composable
private fun retiredColors(): MachineSignalColors = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.colors ?: MachineSignalColors.Board

@Composable
fun SignalText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.Text2,
    size: TextUnit = MachineSignal.Type.body,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    maxLines: Int = 1,
) = Text(
    text = text,
    modifier = modifier,
    color = workspaceColor(color),
    fontFamily = if (mono) retiredFonts().mono else retiredFonts().ui,
    fontSize = size,
    fontWeight = weight,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MachineSignal.Text4) = Text(
    text = text.uppercase(),
    modifier = modifier,
    color = workspaceColor(color),
    fontFamily = retiredFonts().ui,
    fontSize = MachineSignal.Type.eyebrow,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = MachineSignal.Type.eyebrowTracking,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun VerticalDivider(modifier: Modifier = Modifier, color: Color = MachineSignal.Line1) =
    Box(modifier.fillMaxHeight().width(1.dp).background(workspaceColor(color)))

@Composable
fun DividerLine(modifier: Modifier = Modifier, color: Color = MachineSignal.Line1) =
    Box(modifier.fillMaxWidth().height(1.dp).background(workspaceColor(color)))

@Composable
fun SignalPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    background: Color = MachineSignal.Bg1,
    contentPadding: Dp = MachineSignal.Space.s3,
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier.background(workspaceColor(background)).then(
    if ((LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor) Modifier.border(1.dp, MachineSignal.Editor.Code.GutterLine) else Modifier
)) {
    if (title != null || trailing != null) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = contentPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalText(text = title.orEmpty(), color = MachineSignal.Text2, weight = FontWeight.Medium)
            if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        DividerLine()
    }
    Column(Modifier.padding(contentPadding), content = content)
}

@Composable
fun SignalPill(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.Text3,
    fill: Color = MachineSignal.Bg2,
    line: Color = MachineSignal.Line1,
    mono: Boolean = false,
) = Box(
    modifier
        .background(workspaceColor(fill), MachineSignal.Shape.Tight)
        .border(1.dp, workspaceColor(line), MachineSignal.Shape.Tight)
        .padding(horizontal = MachineSignal.Metrics.chipPaddingX, vertical = MachineSignal.Metrics.chipPaddingY),
) {
    SignalText(label, color = color, size = MachineSignal.Type.micro, weight = FontWeight.Medium, mono = mono)
}

@Composable
fun ProvenanceBadge(truth: TruthClass, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = MachineSignal.provenance(truth)
    Row(
        modifier
            .background(colors.soft, MachineSignal.Shape.Tight)
            .border(1.dp, colors.line, MachineSignal.Shape.Tight)
            .padding(horizontal = MachineSignal.Metrics.chipPaddingX, vertical = MachineSignal.Metrics.chipPaddingY)
            .testTag("provenance-${truth.name.lowercase()}"),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalText(truth.abbreviation, color = colors.base, size = MachineSignal.Type.micro, weight = FontWeight.SemiBold)
        if (detail != null) SignalText(detail, color = MachineSignal.Text4, size = MachineSignal.Type.micro)
    }
}

@Composable
fun Kbd(keys: String, modifier: Modifier = Modifier) = Box(
    modifier
        .height(MachineSignal.Metrics.kbdHeight)
        .background(retiredColors().raised, MachineSignal.Shape.Tight)
        .border(1.dp, retiredColors().line, MachineSignal.Shape.Tight)
        .padding(horizontal = MachineSignal.Metrics.kbdPaddingX),
    contentAlignment = Alignment.Center,
) {
    SignalText(text = keys, color = MachineSignal.Text3, size = MachineSignal.Type.data, weight = FontWeight.SemiBold, mono = true)
}

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) = Box(
    modifier
        .background(retiredColors().raisedAlt, RoundedCornerShape(MachineSignal.Radius.countBadge))
        .padding(horizontal = MachineSignal.Metrics.countBadgePaddingX, vertical = MachineSignal.Metrics.countBadgePaddingY),
    contentAlignment = Alignment.Center,
) {
    SignalText(text = count.toString(), color = MachineSignal.Text2, size = MachineSignal.Type.dataMicro, weight = FontWeight.SemiBold, mono = true)
}

@Composable
fun KindBadge(kind: String, modifier: Modifier = Modifier, color: Color = MachineSignal.entityColor(kind)) = Box(
    modifier
        .height(MachineSignal.Metrics.kindBadgeHeight)
        .background(color.copy(alpha = 0.16f), MachineSignal.Shape.Tight)
        .border(1.dp, color.copy(alpha = 0.32f), MachineSignal.Shape.Tight)
        .padding(horizontal = MachineSignal.Metrics.kindBadgePaddingX),
    contentAlignment = Alignment.Center,
) {
    Text(
        text = kind.uppercase(),
        color = workspaceColor(color),
        fontFamily = retiredFonts().mono,
        fontSize = MachineSignal.Type.dataMicro,
        fontWeight = FontWeight.Bold,
        letterSpacing = MachineSignal.Type.kindTracking,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun RetiredStatusPill(label: String, color: Color, modifier: Modifier = Modifier) = Row(
    modifier
        .height(MachineSignal.Metrics.statusPillHeight)
        .background(color.copy(alpha = 0.08f), RoundedCornerShape(MachineSignal.Radius.statusPill))
        .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(MachineSignal.Radius.statusPill))
        .padding(horizontal = MachineSignal.Metrics.statusPillPaddingX),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.statusPillGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    StatusDot(color)
    SignalText(label, color = MachineSignal.Text1, size = MachineSignal.Type.data, weight = FontWeight.SemiBold, mono = true)
}

@Composable
fun RetiredKeyValueRow(key: String, fact: Fact<String>, modifier: Modifier = Modifier, keyWidth: Dp = 132.dp) {
    val editor = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor
    Row(
        modifier.fillMaxWidth().then(if (editor) Modifier.heightIn(min = MachineSignal.Editor.treeRowHeight).padding(vertical = MachineSignal.Space.s1) else Modifier.height(MachineSignal.Metrics.kvRowHeight)),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalText(key, if (editor) Modifier.weight(.38f) else Modifier.width(keyWidth), color = MachineSignal.Text4, size = MachineSignal.Editor.label, maxLines = if (editor) Int.MAX_VALUE else 1)
        SignalText(fact.value, Modifier.weight(if (editor) .62f else 1f), color = if (fact.provesHealth) MachineSignal.Text2 else MachineSignal.Text4,
            size = MachineSignal.Type.caption, mono = !editor, maxLines = if (editor) Int.MAX_VALUE else 1)
        if (fact.truth != TruthClass.Live) ProvenanceBadge(fact.truth)
    }
}

@Composable
fun RetiredNotWiredYet(what: String, turnsOnWith: String, modifier: Modifier = Modifier) = Column(
    modifier
        .fillMaxWidth()
        .background(retiredColors().surface, MachineSignal.Shape.Panel)
        .border(1.dp, retiredColors().lineSubtle, MachineSignal.Shape.Panel)
        .padding(MachineSignal.Space.s4)
        .testTag("not-wired-yet"),
    verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
        ProvenanceBadge(TruthClass.Unknown)
        SignalText(what, color = MachineSignal.Text2, weight = FontWeight.Medium, maxLines = 4)
    }
    SignalText("Turns on with: $turnsOnWith", color = MachineSignal.Text4, size = MachineSignal.Type.caption, maxLines = 3)
}

@Composable
fun RetiredContextBar(breadcrumb: List<String>, modifier: Modifier = Modifier, truth: TruthClass? = null) = Column(
    modifier.fillMaxWidth().height(if ((LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor) MachineSignal.Editor.menuHeight else MachineSignal.Metrics.contextBarHeight),
) {
    Row(
        Modifier.fillMaxWidth().weight(1f).background(retiredColors().surface).padding(horizontal = MachineSignal.Metrics.shellPaddingX),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.contextBarGap), verticalAlignment = Alignment.CenterVertically) {
            breadcrumb.forEachIndexed { index, crumb ->
                if (index > 0) SignalText("/", color = MachineSignal.Line3, size = MachineSignal.Type.caption)
                Eyebrow(crumb, color = if (index == breadcrumb.lastIndex) MachineSignal.Text2 else MachineSignal.Text4)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
            if (truth != null) ProvenanceBadge(truth)
        }
    }
    DividerLine()
}
