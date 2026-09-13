package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/** Pinning retains an identity, never a stale copy of its observed properties. */
@Stable
class SignalInspectorState<T>(initiallyCollapsed: Boolean = false) {
    var width by mutableStateOf(MachineSignal.Editor.inspectorWidth)
    var collapsed by mutableStateOf(initiallyCollapsed)
    var pinnedSubject by mutableStateOf<T?>(null)
        private set
    val isPinned: Boolean get() = pinnedSubject != null
    fun subject(selection: T?): T? = pinnedSubject ?: selection
    fun pin(subject: T?) { pinnedSubject = subject }
    fun followSelection() { pinnedSubject = null }
    fun reveal() { collapsed = false }
    fun resetWidth() { width = MachineSignal.Editor.inspectorWidth }
}

@Composable
fun <T> SignalInspectorLayout(
    state: SignalInspectorState<T>,
    selection: T?,
    title: String,
    modifier: Modifier = Modifier,
    contentMin: Dp = MachineSignal.Editor.navigatorWidth,
    allowPin: Boolean = true,
    content: @Composable () -> Unit,
    inspector: @Composable (T?) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val minWidth = MachineSignal.Editor.navigatorWidth
        val maxWidth = (maxWidth - contentMin - MachineSignal.Space.s2).coerceAtLeast(minWidth)
        val width = state.width.coerceIn(minWidth, maxWidth)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            if (state.collapsed) {
                VerticalDivider(color = MachineSignal.Editor.Line)
                MachineSignalTooltip("Show $title") {
                    Box(Modifier.width(MachineSignal.Editor.controlHeight).fillMaxHeight()
                        .background(MachineSignal.Editor.Surface)
                        .semantics { contentDescription = "Show $title" }
                        .clickable(role = Role.Button) { state.reveal() }, contentAlignment = Alignment.TopCenter) {
                        SignalText("‹", Modifier.padding(vertical = MachineSignal.Space.s3), color = MachineSignal.Editor.Muted)
                    }
                }
            } else {
                SignalResizeHandle(SignalSplitAxis.Horizontal, "Resize $title",
                    onResize = { state.width = (width - it).coerceIn(minWidth, maxWidth) }, onReset = state::resetWidth)
                Column(Modifier.width(width).fillMaxHeight().background(MachineSignal.Editor.Surface)) {
                    Row(Modifier.fillMaxWidth().height(MachineSignal.Editor.sectionHeaderHeight)
                        .background(MachineSignal.Editor.Raised).padding(horizontal = MachineSignal.Space.s2),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
                        SignalText(title, Modifier.weight(1f), color = MachineSignal.Editor.Text, weight = FontWeight.Medium)
                        if (allowPin) SignalButton(if (state.isPinned) "Unpin" else "Pin", {
                            if (state.isPinned) state.followSelection() else state.pin(selection)
                        }, enabled = state.isPinned || selection != null, tone = SignalTone.Ghost,
                            modifier = Modifier.semantics { stateDescription = if (state.isPinned) "Pinned" else "Follows selection" })
                        SignalButton("Hide", { state.collapsed = true }, tone = SignalTone.Ghost)
                    }
                    DividerLine(color = MachineSignal.Editor.Line)
                    Box(Modifier.weight(1f).fillMaxWidth()) { inspector(state.subject(selection)) }
                }
            }
        }
    }
}

@Composable
fun SignalPropertyRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.treeRowHeight)
        .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s1),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
        SignalText(label, Modifier.weight(.35f), color = MachineSignal.Editor.Muted, maxLines = Int.MAX_VALUE)
        SelectionContainer(Modifier.weight(.65f)) {
            SignalText(value, color = MachineSignal.Editor.Text, maxLines = Int.MAX_VALUE)
        }
    }
}

@Composable
fun SignalInspectorSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(MachineSignal.Editor.sectionHeaderHeight)
            .background(MachineSignal.Editor.Raised)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = MachineSignal.Space.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
            SignalText(if (expanded) "⌄" else "›", color = MachineSignal.Editor.Muted)
            SignalText(title, color = MachineSignal.Editor.Text, weight = FontWeight.Medium)
        }
        if (expanded) Column(Modifier.padding(vertical = MachineSignal.Space.s2), content = content)
        DividerLine(color = MachineSignal.Editor.Line)
    }
}
