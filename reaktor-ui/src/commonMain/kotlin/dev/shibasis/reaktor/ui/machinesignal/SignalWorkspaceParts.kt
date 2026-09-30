package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A navigation row is an object to select, distinct from the commands that act on it. */
@Composable
fun SignalNavigationItem(
    title: String, subtitle: String = "", selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    val (interaction, hovered) = rememberHover()
    Row(modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.layerRowHeight)
        .background(when { selected -> MachineSignal.Editor.AccentSoft; hovered -> MachineSignal.Editor.Raised; else -> Color.Transparent })
        .hoverable(interaction).clickable(interactionSource = interaction, indication = null, enabled = enabled,
            role = Role.Button, onClick = onClick).semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(MachineSignal.Editor.layerRowHeight)
            .background(if (selected) MachineSignal.Editor.Accent else Color.Transparent))
        Row(Modifier.weight(1f).padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
                SignalText(title, color = MachineSignal.Editor.Text, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (subtitle.isNotBlank()) SignalText(subtitle, color = MachineSignal.Editor.Muted, size = MachineSignal.Editor.meta, maxLines = 2)
            }
            trailing?.invoke()
        }
    }
}

/** Deliberate empty or unavailable workspace, with one clear explanation and room for a real action. */
@Composable
fun SignalWorkspaceEmptyState(
    title: String, description: String, modifier: Modifier = Modifier,
    symbol: String = "◇", detail: String = "", actions: (@Composable RowScope.() -> Unit)? = null,
) = BoxWithConstraints(modifier.fillMaxSize().background(MachineSignal.Editor.Canvas), contentAlignment = Alignment.Center) {
    val compact = maxHeight < MachineSignal.Editor.toolbarHeight * 7
    Column(Modifier.widthIn(max = MachineSignal.Editor.inspectorWidth + MachineSignal.Editor.navigatorWidth / 2)
        .padding(if (compact) MachineSignal.Space.s3 else MachineSignal.Space.s6), verticalArrangement = Arrangement.spacedBy(if (compact) MachineSignal.Space.s2 else MachineSignal.Space.s4)) {
        if (!compact) Box(Modifier.size(MachineSignal.Editor.toolHitSize + MachineSignal.Space.s3)
            .background(MachineSignal.Editor.Surface, MachineSignal.Shape.Panel)
            .border(1.dp, MachineSignal.Editor.Line, MachineSignal.Shape.Panel), contentAlignment = Alignment.Center) {
            SignalText(symbol, color = MachineSignal.Editor.Accent, size = MachineSignal.Type.display)
        }
        SignalText(title, color = MachineSignal.Editor.Text, size = MachineSignal.Type.title, weight = FontWeight.SemiBold, maxLines = 3)
        SignalText(description, color = MachineSignal.Editor.Muted, maxLines = Int.MAX_VALUE)
        if (!compact && detail.isNotBlank()) SignalText(detail, color = MachineSignal.Editor.Unknown, size = MachineSignal.Editor.label, maxLines = Int.MAX_VALUE)
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), content = actions)
    }
}
