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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
fun PaneToolbar(
    title: String,
    modifier: Modifier = Modifier,
    counts: List<Pair<String, Int>> = emptyList(),
    actions: (@Composable RowScope.() -> Unit)? = null,
) = Row(
    modifier
        .fillMaxWidth()
        .height(40.dp)
        .background(MachineSignal.Bg1)
        .padding(horizontal = MachineSignal.Space.s3),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3),
    verticalAlignment = Alignment.CenterVertically,
) {
    SignalText(title, color = MachineSignal.Text1, size = MachineSignal.Type.title, weight = FontWeight.SemiBold)
    counts.forEach { (label, value) ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalText(value.toString(), color = MachineSignal.Text2, size = MachineSignal.Type.caption, mono = true)
            SignalText(label, color = MachineSignal.Text4, size = MachineSignal.Type.caption)
        }
    }
    Spacer(Modifier.weight(1f))
    if (actions != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
fun JumpOutStrip(
    links: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (links.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .background(MachineSignal.Bg1)
            .testTag("jump-out")
            .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow("Consoles")
        links.forEach { (label, url) ->
            SignalButton(label, onClick = { onOpen(url) }, tone = SignalTone.Ghost)
        }
    }
}

data class SignalAction(val label: String, val enabled: Boolean = true, val onInvoke: () -> Unit)

@Composable
fun SignalContextMenu(
    actions: List<SignalAction>,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    if (actions.isEmpty()) return
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(MachineSignal.Bg2),
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                enabled = action.enabled,
                onClick = { onDismiss(); action.onInvoke() },
                text = {
                    SignalText(
                        action.label,
                        color = if (action.enabled) MachineSignal.Text2 else MachineSignal.Text4,
                        size = MachineSignal.Type.caption,
                    )
                },
            )
        }
    }
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier, color: Color = MachineSignal.Line1) =
    Box(modifier.fillMaxHeight().width(1.dp).background(color))

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
) = Column(modifier.background(background)) {
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
fun SignalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SignalTone = SignalTone.Secondary,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
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
    leading?.invoke()
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
    // The authored tile is a fixed 220 wide; callers in a row override with weight or fillMaxWidth.
    Modifier
        .width(MachineSignal.Metrics.metricTileWidth)
        .then(modifier)
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

/**
 * `SubTab / On` and `SubTab / Off` — an underline tab, not a filled pill. The design carries the
 * selection on a 2dp rule beneath the label, spanning the label rather than the padded box.
 */
@Composable
fun SubTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) = Column(
    modifier
        // Max, not Min: the underline spans the label, so the tab has to be as wide as the label
        // wants on one line. Min intrinsic width of an ellipsizing single-line label is a word
        // break, which is identical for one-word tabs and truncates every multi-word one —
        // "Chain Builder" rendered as "Chai…" until this was Max.
        .width(IntrinsicSize.Max)
        // Fixed to the authored total so the underline lands on the authored baseline whatever the
        // label's intrinsic height turns out to be — the prose ramp is a touch smaller than the file.
        .height(MachineSignal.Metrics.subTabHeight)
        .clickable(role = Role.Tab, onClick = onClick)
        .semantics { this.selected = selected; this.role = Role.Tab }
        .padding(
            start = MachineSignal.Metrics.subTabPaddingX,
            end = MachineSignal.Metrics.subTabPaddingX,
            top = MachineSignal.Metrics.subTabPaddingTop,
        ),
    verticalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.subTabGap),
) {
    Row(
        Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalText(
            text = label,
            color = if (selected) MachineSignal.Text1 else MachineSignal.Text3,
            size = MachineSignal.Type.label,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (count != null && count > 0) {
            SignalText(count.toString(), color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(MachineSignal.Metrics.subTabUnderlineHeight)
            .background(if (selected) MachineSignal.Accent else Color.Transparent),
    )
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
) = Column(modifier.fillMaxWidth().height(MachineSignal.Metrics.contextBarHeight)) {
    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
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
        color = color,
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
fun EntityChip(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.AccentText,
    onClick: (() -> Unit)? = null,
) = Box(
    modifier
        .height(MachineSignal.Metrics.entityChipHeight)
        .background(MachineSignal.Bg3, MachineSignal.Shape.Tight)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Tight)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = MachineSignal.Metrics.entityChipPaddingX),
    contentAlignment = Alignment.Center,
) {
    SignalText(label, color = color, size = MachineSignal.Type.data, weight = FontWeight.Medium, mono = true)
}

/** `Pill / Status` — a dot and a reading, in the status colour. */
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
fun BranchPill(
    branch: String,
    revision: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) = Row(
    modifier
        .height(MachineSignal.Metrics.branchPillHeight)
        .background(MachineSignal.Bg2, MachineSignal.Shape.Control)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Control)
        .padding(horizontal = MachineSignal.Metrics.branchPillPaddingX),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.branchPillGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    leading?.invoke()
    SignalText(branch, color = MachineSignal.Text2, size = MachineSignal.Type.dataStrong, mono = true)
    if (revision != null) {
        SignalText(revision, color = MachineSignal.Text4, size = MachineSignal.Type.dataStrong, mono = true)
    }
}

/** `Row / Tree` — one line of a navigator. */
@Composable
fun TreeRow(
    name: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    selected: Boolean = false,
    depth: Int = 0,
    accent: Color? = null,
    onClick: () -> Unit = {},
) {
    val (interaction, hovered) = rememberHover()
    Row(
        modifier
            .fillMaxWidth()
            .height(MachineSignal.Metrics.treeRowHeight)
            .background(rowSurface(selected, hovered), MachineSignal.Shape.Tight)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(
                start = MachineSignal.Metrics.treeRowPaddingX + (MachineSignal.Space.s3 * depth),
                end = MachineSignal.Metrics.treeRowPaddingX,
            ),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.treeRowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) StatusDot(accent)
        SignalText(
            text = name,
            modifier = Modifier.weight(1f, fill = false),
            color = if (selected) MachineSignal.Text1 else MachineSignal.Text2,
            size = MachineSignal.Type.dataStrong,
            weight = FontWeight.Medium,
            mono = true,
        )
        if (meta != null) {
            SignalText(meta, color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
        }
    }
}

/** `Row / Command` — one graph command in the change set. */
@Composable
fun CommandRow(
    id: String,
    summary: String,
    status: String,
    modifier: Modifier = Modifier,
    statusColor: Color = MachineSignal.statusColor(status),
    trailing: (@Composable RowScope.() -> Unit)? = null,
) = Row(
    modifier
        .fillMaxWidth()
        .height(MachineSignal.Metrics.commandRowHeight)
        .background(MachineSignal.Bg2, MachineSignal.Shape.Control)
        .border(1.dp, MachineSignal.Line1, MachineSignal.Shape.Control)
        .padding(horizontal = MachineSignal.Metrics.commandRowPaddingX),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.commandRowGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    SignalText(id, color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
    SignalText(
        text = summary,
        modifier = Modifier.weight(1f),
        color = MachineSignal.Text2,
        size = MachineSignal.Type.data,
        mono = true,
    )
    SignalText(
        text = status,
        color = statusColor,
        size = MachineSignal.Type.dataMicro,
        weight = FontWeight.SemiBold,
        mono = true,
    )
    if (trailing != null) trailing()
}

/** `Tab / Mode` — top-level workbench mode. */
@Composable
fun ModeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    leading: (@Composable () -> Unit)? = null,
    /** The authored `TKbdW` slot after the label — empty on the boards, used by hosts that bind a chord. */
    trailing: (@Composable () -> Unit)? = null,
) = Row(
    modifier
        .height(MachineSignal.Metrics.modeTabHeight)
        .background(if (selected) MachineSignal.SelectedSoft else Color.Transparent, MachineSignal.Shape.Control)
        .border(
            1.dp,
            if (selected) MachineSignal.AccentLine else Color.Transparent,
            MachineSignal.Shape.Control,
        )
        .clickable(role = Role.Tab, onClick = onClick)
        .semantics { this.selected = selected; this.role = Role.Tab }
        .padding(horizontal = MachineSignal.Metrics.modeTabPaddingX),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.modeTabGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    leading?.invoke()
    SignalText(
        text = label,
        color = if (selected) MachineSignal.AccentText else MachineSignal.Text3,
        size = MachineSignal.Type.label,
        weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
    )
    if (shortcut != null) {
        SignalText(shortcut, color = if (selected) MachineSignal.AccentText else MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
    }
    trailing?.invoke()
}

/** `Segmented / Env` — mutually exclusive environment choice. */
@Composable
fun EnvSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Label to show for an option; the underlying value is what [onSelect] reports. */
    label: (String) -> String = { it },
    /** Lets a host address individual segments from an end-to-end driver. */
    optionTag: ((String) -> String)? = null,
) = Row(
    modifier
        .height(MachineSignal.Metrics.envSegmentedHeight)
        .background(MachineSignal.Bg2, MachineSignal.Shape.Control)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Control)
        .padding(MachineSignal.Metrics.envSegmentedPadding),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.envSegmentedGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    options.forEach { option ->
        val isSelected = option == selected
        Box(
            Modifier
                .fillMaxHeight()
                .then(optionTag?.let { Modifier.testTag(it(option)) } ?: Modifier)
                .background(
                    if (isSelected) MachineSignal.SelectedSoft else Color.Transparent,
                    MachineSignal.Shape.Tight,
                )
                .clickable(role = Role.Tab, onClick = { onSelect(option) })
                .semantics { this.selected = isSelected; this.role = Role.Tab }
                .padding(horizontal = MachineSignal.Space.s3),
            contentAlignment = Alignment.Center,
        ) {
            SignalText(
                text = label(option),
                color = if (isSelected) MachineSignal.AccentText else MachineSignal.Text3,
                size = MachineSignal.Type.label,
                weight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

/** `Field / Search` — the workbench search affordance. */
@Composable
fun SearchField(
    placeholder: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
) = Row(
    // The authored field is a fixed 280 wide; the top bar overrides it when it needs to stretch.
    Modifier
        .width(MachineSignal.Metrics.searchFieldWidth)
        .then(modifier)
        .height(MachineSignal.Metrics.searchFieldHeight)
        .background(MachineSignal.Bg1, MachineSignal.Shape.Control)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Control)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = MachineSignal.Metrics.searchPaddingX),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.searchFieldGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    leading?.invoke()
    SignalText(
        text = placeholder,
        modifier = Modifier.weight(1f),
        color = MachineSignal.Text3,
        size = MachineSignal.Type.label,
    )
    if (shortcut != null) Kbd(shortcut)
}

/** `Avatar` — an actor, human or agent. */
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
