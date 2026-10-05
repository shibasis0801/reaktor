package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import dev.shibasis.reaktor.surface.compose.Align
import dev.shibasis.reaktor.surface.compose.Placement
import dev.shibasis.reaktor.surface.compose.Side
import dev.shibasis.reaktor.surface.compose.Menu
import dev.shibasis.reaktor.surface.compose.OverlayAnchor
import dev.shibasis.reaktor.ui.machinesignal.surface.ContextMenuPanel
import dev.shibasis.reaktor.ui.machinesignal.surface.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LegacySignalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SignalTone = SignalTone.Secondary,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val (interaction, hovered) = rememberHover()
    val editor = ((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)
    Row(
        modifier
            .height(if (editor) MachineSignal.Editor.controlHeight else MachineSignal.Metrics.buttonHeight)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(
                when {
                    !enabled -> Color.Transparent
                    hovered -> workspaceColor(tone.hover)
                    else -> workspaceColor(tone.fill)
                },
                MachineSignal.Shape.Control,
            )
            .border(1.dp, if (editor && tone == SignalTone.Ghost) Color.Transparent else workspaceColor(if (enabled) tone.line else MachineSignal.Line1), MachineSignal.Shape.Control)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { role = Role.Button; if (!enabled) disabled() }
            .padding(
                horizontal = if (editor) MachineSignal.Space.s2 else if (tone == SignalTone.Ghost) {
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
            size = if (editor) MachineSignal.Editor.label else MachineSignal.Type.control,
            weight = if (tone == SignalTone.Primary || tone == SignalTone.Danger) {
                FontWeight.SemiBold
            } else {
                FontWeight.Medium
            },
        )
    }
}

@Composable
fun LegacySubTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) = Column(
    modifier
        .width(IntrinsicSize.Max)
        .height(if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) MachineSignal.Editor.documentTabHeight else MachineSignal.Metrics.subTabHeight)
        .background(if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor) && selected) MachineSignal.Editor.AccentSoft else Color.Transparent)
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
            size = if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) MachineSignal.Editor.label else MachineSignal.Type.label,
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
fun LegacySignalContextMenu(
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
                modifier = Modifier.testTag(action.id ?: "signal-action-${action.label}"),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyMachineSignalTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        state = rememberTooltipState(),
        modifier = modifier,
        focusable = false,
        tooltip = { LegacyTooltipFrame(text) },
        content = content,
    )
}

@Composable
fun LegacyTooltipFrame(text: String) = Box(
    Modifier.widthIn(max = MachineSignal.Editor.navigatorWidth)
        .background(MachineSignal.Editor.Raised, MachineSignal.Shape.Tight)
        .border(MachineSignal.Space.s1 / 4, MachineSignal.Editor.Line, MachineSignal.Shape.Tight)
        .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
) {
    Text(
        text = text,
        color = MachineSignal.Editor.Text,
        fontFamily = LocalMachineSignalFonts.current.ui,
        fontSize = MachineSignal.Editor.label,
        lineHeight = MachineSignal.Editor.label * MachineSignal.Editor.lineHeight,
    )
}


@Composable
fun LegacySignalRow(
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

data class SignalAction(val label: String, val enabled: Boolean = true, val id: String? = null, val onInvoke: () -> Unit)

@Composable
fun FixtureSurfaceMenu(
    actions: List<SignalAction>,
    expanded: Boolean,
    onDismiss: () -> Unit,
    trigger: FocusRequester? = null,
) {
    if (actions.isEmpty() || !expanded) return
    val returnTo by rememberUpdatedState(trigger)
    DisposableEffect(Unit) { onDispose { returnTo?.requestFocus() } }
    var parent by remember { mutableStateOf<Rect?>(null) }
    Menu(
        expanded = parent != null,
        onExpandedChange = { if (!it) onDismiss() },
        modifier = Modifier.onPlaced { placed -> placed.parentCoordinates?.let { parent = Rect(it.positionInWindow(), it.size.toSize()) } },
        anchor = parent?.let(OverlayAnchor::Bounds),
        placement = ContextMenuPlacement,
    ) {
        Popup(ContextMenuPanel) {
            val tags = actions.map { it.id ?: "signal-action-${it.label}" }
            actions.forEachIndexed { index, action ->
                val tag = tags[index]
                Item(
                    if (tags.indexOf(tag) == index) tag else "$tag/$index",
                    action.onInvoke,
                    Modifier.testTag(tag),
                    enabled = action.enabled,
                    typeahead = action.label,
                    appearance = ContextMenuItem,
                ) {
                    SignalText(
                        action.label,
                        color = if (action.enabled) MachineSignal.Text2 else MachineSignal.Text4,
                        size = MachineSignal.Type.caption,
                    )
                }
            }
        }
    }
}

private val ContextMenuPlacement = Placement(Side.Below, Align.Start, gap = 0.dp, margin = 48.dp)

@Composable
internal fun FixtureSurfaceTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) = dev.shibasis.reaktor.surface.compose.Button(onClick, modifier.semantics { role = Role.Tab; this.selected = selected }, appearance = dev.shibasis.reaktor.ui.machinesignal.surface.subTab(selected)) {
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
