package dev.shibasis.reaktor.ui.machinesignal

import dev.shibasis.reaktor.ui.machinesignal.SignalTone
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.ui.machinesignal.surface.subTab

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
    color = workspaceColor(color),
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
    color = workspaceColor(color),
    fontFamily = LocalMachineSignalFonts.current.ui,
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
    if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) Modifier.border(1.dp, MachineSignal.Editor.Code.GutterLine) else Modifier
)) {
    if (title != null || trailing != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = contentPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalText(
                text = title.orEmpty(),
                color = MachineSignal.Text2,
                weight = FontWeight.Medium,
            )
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
    // Btn / Primary is authored as a solid accent fill with a white label, not a tint — the golden
    // gate reads the difference as a near-total pixel mismatch, so keep this solid.
    Primary(MachineSignal.Accent, Color.White, MachineSignal.AccentLine, MachineSignal.Accent2),
    Secondary(MachineSignal.Bg3, MachineSignal.Text1, MachineSignal.Line2, MachineSignal.Bg4),
    Ghost(Color.Transparent, MachineSignal.Text2, MachineSignal.Line1, MachineSignal.Bg2),
    Danger(Color(0x14FF666B), MachineSignal.Status.Error, Color(0x52FF666B), Color(0x30FF666B)),
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
    // The authored tile is a fixed 220 wide; callers in a row override with weight or fillMaxWidth.
    Modifier
        .width(MachineSignal.Metrics.metricTileWidth)
        .then(modifier)
        .background(workspaceColor(MachineSignal.Bg1), MachineSignal.Shape.Panel)
        .border(1.dp, workspaceColor(MachineSignal.Line1), MachineSignal.Shape.Panel)
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
    modifier.fillMaxWidth().then(if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) Modifier.heightIn(min = MachineSignal.Editor.treeRowHeight).padding(vertical = MachineSignal.Space.s1) else Modifier.height(MachineSignal.Metrics.kvRowHeight)),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
    verticalAlignment = Alignment.CenterVertically,
) {
    SignalText(key, if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) Modifier.weight(.38f) else Modifier.width(keyWidth), color = MachineSignal.Text4, size = MachineSignal.Editor.label, maxLines = if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) Int.MAX_VALUE else 1)
    SignalText(
        text = fact.value,
        modifier = Modifier.weight(if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) .62f else 1f),
        color = if (fact.provesHealth) MachineSignal.Text2 else MachineSignal.Text4,
        size = MachineSignal.Type.caption,
        mono = !((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor),
        maxLines = if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) Int.MAX_VALUE else 1,
    )
    if (fact.truth != TruthClass.Live) ProvenanceBadge(fact.truth)
}

@Composable
fun NotWiredYet(what: String, turnsOnWith: String, modifier: Modifier = Modifier) = Column(
    modifier
        .fillMaxWidth()
        .background(workspaceColor(MachineSignal.Bg1), MachineSignal.Shape.Panel)
        .border(1.dp, workspaceColor(MachineSignal.Line1), MachineSignal.Shape.Panel)
        .padding(MachineSignal.Space.s4)
        .testTag("not-wired-yet"),
    verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvenanceBadge(TruthClass.Unknown)
        SignalText(what, color = MachineSignal.Text2, weight = FontWeight.Medium, maxLines = 4)
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
) = Button(onClick, modifier.semantics { role = Role.Tab; this.selected = selected }, appearance = subTab(selected)) {
    SignalText(
        text = label,
        color = if (selected) MachineSignal.Text1 else MachineSignal.Text3,
        size = if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) MachineSignal.Editor.label else MachineSignal.Type.label,
        weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
    )
    if (count != null && count > 0) {
        SignalText(count.toString(), color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
    }
}

@Composable
fun SubTabRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(workspaceColor(MachineSignal.Bg1))
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
) = Column(modifier.fillMaxWidth().height(if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) MachineSignal.Editor.menuHeight else MachineSignal.Metrics.contextBarHeight)) {
    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(workspaceColor(MachineSignal.Bg1))
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

// ---------------------------------------------------------------------------
// Data primitives
//
// Every measurement below is the authored box from reaktor.pen, held in place by
// MachineSignalGeometryParityTest and, once its oracle is exported, the golden gate. Text uses the
// data ramp rather than the prose ramp: these are dense monospaced values where the design's own
// sizes are the right ones.
// ---------------------------------------------------------------------------

/** `Kbd` — a shortcut hint. */
@Composable
fun Kbd(keys: String, modifier: Modifier = Modifier) = Box(
    modifier
        .height(MachineSignal.Metrics.kbdHeight)
        .background(MachineSignal.Bg3, MachineSignal.Shape.Tight)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Tight)
        .padding(horizontal = MachineSignal.Metrics.kbdPaddingX),
    contentAlignment = Alignment.Center,
) {
    SignalText(
        text = keys,
        color = MachineSignal.Text3,
        size = MachineSignal.Type.data,
        weight = FontWeight.SemiBold,
        mono = true,
    )
}

/** `Badge / Count` — a count carried as a pill rather than bare text. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) = Box(
    modifier
        .background(MachineSignal.Bg4, RoundedCornerShape(MachineSignal.Radius.countBadge))
        .padding(
            horizontal = MachineSignal.Metrics.countBadgePaddingX,
            vertical = MachineSignal.Metrics.countBadgePaddingY,
        ),
    contentAlignment = Alignment.Center,
) {
    SignalText(
        text = count.toString(),
        color = MachineSignal.Text2,
        size = MachineSignal.Type.dataMicro,
        weight = FontWeight.SemiBold,
        mono = true,
    )
}

/**
 * `Badge / Kind` — an entity kind, tinted by the kind's own colour so a graph node and its badge
 * read as the same thing.
 */
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
        fontFamily = LocalMachineSignalFonts.current.mono,
        fontSize = MachineSignal.Type.dataMicro,
        fontWeight = FontWeight.Bold,
        letterSpacing = MachineSignal.Type.kindTracking,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `Chip / Entity` — a reference to something in the graph. */
@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) = Row(
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

/** `Pill / Branch` — the branch and revision the workbench is looking at. */
@Composable
fun Avatar(
    initials: String,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.Accent,
) = Box(
    modifier
        .size(MachineSignal.Metrics.avatarSize)
        .background(color, RoundedCornerShape(MachineSignal.Metrics.avatarSize / 2)),
    contentAlignment = Alignment.Center,
) {
    SignalText(
        text = initials.take(2).uppercase(),
        color = Color.White,
        size = MachineSignal.Type.data,
        weight = FontWeight.Bold,
    )
}
