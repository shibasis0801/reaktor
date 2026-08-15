package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.core.truth.Fact
import dev.shibasis.reaktor.core.truth.TruthClass

data class MachineSignalFonts(
    val ui: FontFamily = FontFamily.SansSerif,
    val mono: FontFamily = FontFamily.Monospace,
)

val LocalMachineSignalFonts = staticCompositionLocalOf { MachineSignalFonts() }

@Composable
fun ProvideMachineSignalFonts(fonts: MachineSignalFonts, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalMachineSignalFonts provides fonts, content = content)

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
    color = color,
    fontFamily = if (mono) LocalMachineSignalFonts.current.mono else LocalMachineSignalFonts.current.ui,
    fontSize = size,
    fontWeight = weight,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MachineSignal.Text4) = Text(
    text = text.uppercase(),
    modifier = modifier,
    color = color,
    fontFamily = LocalMachineSignalFonts.current.ui,
    fontSize = MachineSignal.Type.eyebrow,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = MachineSignal.Type.eyebrowTracking,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun DividerLine(modifier: Modifier = Modifier, color: Color = MachineSignal.Line1) =
    Box(modifier.fillMaxWidth().height(1.dp).background(color))

@Composable
fun SignalPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    background: Color = MachineSignal.Bg1,
    contentPadding: Dp = MachineSignal.Space.s3,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier
        .background(background, MachineSignal.Shape.Panel)
        .border(1.dp, MachineSignal.Line1, MachineSignal.Shape.Panel),
) {
    if (title != null || trailing != null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = contentPadding, vertical = MachineSignal.Space.s2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(title.orEmpty())
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        DividerLine()
    }
    Column(Modifier.padding(contentPadding), content = content)
}

enum class SignalTone(val fill: Color, val text: Color, val line: Color, val hover: Color) {
    Primary(MachineSignal.AccentSoft, MachineSignal.AccentText, MachineSignal.AccentLine, Color(0x385C80FF)),
    Secondary(MachineSignal.Bg3, MachineSignal.Text1, MachineSignal.Line2, MachineSignal.Bg4),
    Ghost(Color.Transparent, MachineSignal.Text2, MachineSignal.Line1, MachineSignal.Bg2),
    Danger(Color(0x14FF666B), MachineSignal.Status.Error, Color(0x52FF666B), Color(0x30FF666B)),
}

@Composable
fun SignalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SignalTone = SignalTone.Secondary,
    enabled: Boolean = true,
) = Row(
    modifier
        .height(MachineSignal.Metrics.buttonHeight)
        .background(if (enabled) tone.fill else Color.Transparent, MachineSignal.Shape.Control)
        .border(1.dp, if (enabled) tone.line else MachineSignal.Line1, MachineSignal.Shape.Control)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { role = Role.Button; if (!enabled) disabled() }
        .padding(
            horizontal = if (tone == SignalTone.Ghost) {
                MachineSignal.Metrics.ghostPaddingX
            } else {
                MachineSignal.Metrics.buttonPaddingX
            },
        ),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.buttonGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    SignalText(
        text = label,
        color = if (enabled) tone.text else MachineSignal.Text4,
        size = MachineSignal.Type.control,
        weight = if (tone == SignalTone.Primary || tone == SignalTone.Danger) {
            FontWeight.SemiBold
        } else {
            FontWeight.Medium
        },
    )
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
        .background(fill, MachineSignal.Shape.Tight)
        .border(1.dp, line, MachineSignal.Shape.Tight)
        .padding(
            horizontal = MachineSignal.Metrics.chipPaddingX,
            vertical = MachineSignal.Metrics.chipPaddingY,
        ),
) {
    SignalText(label, color = color, size = MachineSignal.Type.micro, weight = FontWeight.Medium, mono = mono)
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) =
    Box(modifier.size(6.dp).background(color, RoundedCornerShape(3.dp)))

@Composable
fun ProvenanceBadge(truth: TruthClass, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = MachineSignal.provenance(truth)
    Row(
        modifier
            .background(colors.soft, MachineSignal.Shape.Tight)
            .border(1.dp, colors.line, MachineSignal.Shape.Tight)
            .padding(
                horizontal = MachineSignal.Metrics.chipPaddingX,
                vertical = MachineSignal.Metrics.chipPaddingY,
            )
            .testTag("provenance-${truth.name.lowercase()}"),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalText(truth.abbreviation, color = colors.base, size = MachineSignal.Type.micro, weight = FontWeight.SemiBold)
        if (detail != null) SignalText(detail, color = MachineSignal.Text4, size = MachineSignal.Type.micro)
    }
}

@Composable
fun MetricTile(
    label: String,
    fact: Fact<String>,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    caption: String? = null,
) = Column(
    modifier
        .background(MachineSignal.Bg1, MachineSignal.Shape.Panel)
        .border(1.dp, MachineSignal.Line1, MachineSignal.Shape.Panel)
        .padding(MachineSignal.Metrics.metricTilePadding),
    verticalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.metricTileGap),
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(label)
        StatusDot(MachineSignal.provenance(fact.truth).base)
    }
    SignalText(
        text = fact.value,
        color = if (fact.provesHealth) accent ?: MachineSignal.Text1 else MachineSignal.Text4,
        size = MachineSignal.Type.display,
        weight = FontWeight.SemiBold,
        mono = true,
    )
    if (caption != null) SignalText(caption, color = MachineSignal.Text4, size = MachineSignal.Type.micro)
}

@Composable
fun KeyValueRow(
    key: String,
    fact: Fact<String>,
    modifier: Modifier = Modifier,
    keyWidth: Dp = 132.dp,
) = Row(
    modifier.fillMaxWidth().height(MachineSignal.Metrics.kvRowHeight),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
    verticalAlignment = Alignment.CenterVertically,
) {
    SignalText(key, Modifier.width(keyWidth), color = MachineSignal.Text4, size = MachineSignal.Type.caption)
    SignalText(
        text = fact.value,
        modifier = Modifier.weight(1f),
        color = if (fact.provesHealth) MachineSignal.Text2 else MachineSignal.Text4,
        size = MachineSignal.Type.caption,
        mono = true,
    )
    if (fact.truth != TruthClass.Live) ProvenanceBadge(fact.truth)
}

@Composable
fun NotWiredYet(what: String, turnsOnWith: String, modifier: Modifier = Modifier) = Column(
    modifier
        .fillMaxWidth()
        .background(MachineSignal.Bg1, MachineSignal.Shape.Panel)
        .border(1.dp, MachineSignal.Line1, MachineSignal.Shape.Panel)
        .padding(MachineSignal.Space.s4)
        .testTag("not-wired-yet"),
    verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvenanceBadge(TruthClass.Unknown)
        SignalText(what, color = MachineSignal.Text2, weight = FontWeight.Medium)
    }
    SignalText("Turns on with: $turnsOnWith", color = MachineSignal.Text4, size = MachineSignal.Type.caption, maxLines = 3)
}

@Composable
fun SubTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) = Row(
    modifier
        .background(if (selected) MachineSignal.Bg3 else Color.Transparent, MachineSignal.Shape.Control)
        .border(1.dp, if (selected) MachineSignal.Line3 else Color.Transparent, MachineSignal.Shape.Control)
        .clickable(role = Role.Tab, onClick = onClick)
        .semantics { this.selected = selected; this.role = Role.Tab }
        .padding(
            start = MachineSignal.Metrics.subTabPaddingX,
            end = MachineSignal.Metrics.subTabPaddingX,
            top = MachineSignal.Metrics.subTabPaddingTop,
        ),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.subTabGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    SignalText(
        text = label,
        color = if (selected) MachineSignal.Text1 else MachineSignal.Text3,
        size = MachineSignal.Type.caption,
        weight = if (selected) FontWeight.Medium else FontWeight.Normal,
    )
    if (count != null && count > 0) {
        SignalText(count.toString(), color = MachineSignal.Text4, size = MachineSignal.Type.micro, mono = true)
    }
}

@Composable
fun SubTabRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MachineSignal.Bg1)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MachineSignal.Metrics.shellPaddingX),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.subTabGap),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        DividerLine()
    }

@Composable
fun ContextBar(
    breadcrumb: List<String>,
    modifier: Modifier = Modifier,
    truth: TruthClass? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) = Column(modifier.fillMaxWidth()) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(MachineSignal.Metrics.contextBarHeight)
            .background(MachineSignal.Bg1)
            .padding(horizontal = MachineSignal.Metrics.shellPaddingX),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.contextBarGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            breadcrumb.forEachIndexed { index, crumb ->
                if (index > 0) SignalText("/", color = MachineSignal.Line3, size = MachineSignal.Type.caption)
                Eyebrow(crumb, color = if (index == breadcrumb.lastIndex) MachineSignal.Text2 else MachineSignal.Text4)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trailing != null) trailing()
            if (truth != null) ProvenanceBadge(truth)
        }
    }
    DividerLine()
}

@Composable
fun SignalRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(rowSurface(selected, hovered))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) StatusDot(accent)
        content()
    }
}

fun rowSurface(selected: Boolean, hovered: Boolean): Color = when {
    selected -> MachineSignal.SelectedSoft
    hovered -> MachineSignal.Bg2
    else -> Color.Transparent
}

@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    return interaction to hovered
}
