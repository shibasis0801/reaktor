package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.ListBox
import dev.shibasis.reaktor.surface.compose.Menu
import dev.shibasis.reaktor.surface.compose.MenuPopupScope
import dev.shibasis.reaktor.surface.compose.OverlayAnchor
import dev.shibasis.reaktor.surface.compose.Popover
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.TextField
import dev.shibasis.reaktor.surface.listSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun GraphEditorLayer(editor: GraphEditor, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        (editor.gesture as? Gesture.Wiring)?.let { g ->
            val verdict = g.verdict ?: return@let
            Tip(verdict.message, if (verdict.ok) Ink.Ok else Ink.Danger, editor, g.window - origin + Offset(16f, 18f))
        }
        editor.notice?.let { message ->
            LaunchedEffect(message) { delay(2800); if (editor.notice == message) editor.notice = null }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp).clip(RoundedCornerShape(8.dp)).background(editor.ink.surface).padding(horizontal = 12.dp, vertical = 8.dp).testTag("graph-editor-notice")) {
                Text(message, role = Type.Meta, ink = Ink.Text, lines = 2)
            }
        }
        when (val request = editor.request) {
            is Request.Menu -> EditorMenu(editor, request)
            is Request.Palette -> Anchored(request.window - origin) { EditorPalette(editor, request) }
            is Request.Rename -> Anchored(request.window - origin) { EditorRename(editor, request) }
            null -> {}
        }
    }
}

@Composable
private fun BoxScope.Tip(text: String, ink: Ink, editor: GraphEditor, at: Offset) {
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.clip(RoundedCornerShape(6.dp)).background(editor.ink.surface)
        .padding(horizontal = 10.dp, vertical = 6.dp).testTag("graph-editor-verdict")) {
        Text(text, role = Type.Meta, ink = ink, lines = 2)
    }
}

@Composable
private fun Anchored(at: Offset, content: @Composable () -> Unit) {
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) { content() }
}

@Composable
private fun EditorMenu(editor: GraphEditor, request: Request.Menu) {
    Menu(true, { if (!it) editor.request = null }, anchor = OverlayAnchor.Point(request.window)) {
        Popup {
            fun run(action: () -> Unit) { editor.request = null; action() }
            when (val target = request.target) {
                is Target.OnCard -> CardEntries(editor, request, target.id, ::run)
                is Target.OnPin -> {
                    val links = editor.links(target.pin)
                    Entry("unlink-pin", "Break ${links.size} link${if (links.size == 1) "" else "s"} on ${editor.node(target.pin.node)?.let { editor.terms.port(it, target.pin.key) } ?: target.pin.key}", "⌥ click", links.isNotEmpty()) { run { editor.breakLinks(target.pin) } }
                    Entry("add", "Add a connected node…", null) { run { editor.request = Request.Palette(request.at, request.window, target.pin) } }
                    Separator()
                    CardEntries(editor, request, target.pin.node, ::run)
                }
                is Target.OnWire -> {
                    Entry("delete", "Delete wire", "⌫") { run { editor.delete(Selection(wires = setOf(target.id))) } }
                    Entry("bend", "Add a reroute point", "double-click") { run { editor.bend(target.id, request.at) } }
                    Entry("unbend", "Remove reroute points", null, editor.placement.bends.containsKey(target.id)) { run { editor.edit { s, p -> s to p.copy(bends = p.bends - target.id) } } }
                }
                is Target.OnNote -> {
                    Entry("rename", "Rename comment", "double-click") { run { editor.request = Request.Rename(request.at, request.window, target.id, note = true) } }
                    Submenu("colour", trigger = { Text("Colour") }) {
                        NoteTints.forEach { (name, tint) -> Entry("tint-$name", name, null) { run { editor.recolour(target.id, tint) } } }
                    }
                    Entry("delete", "Delete comment", "⌫") { run { editor.delete(Selection(notes = setOf(target.id))) } }
                }
                Target.Empty -> Entry("add", "Add a node…", "Space") { run { editor.request = Request.Palette(request.at, request.window) } }
            }
        }
    }
}

@Composable
private fun MenuPopupScope.CardEntries(editor: GraphEditor, request: Request.Menu, id: String, run: (() -> Unit) -> Unit) {
    val many = editor.selection.nodes.size > 1
    Entry("rename", "Rename", "F2", !many) { run { editor.request = Request.Rename(request.at, request.window, id, note = false) } }
    Entry("open", "Open", "Enter") { run { editor.onOpen(id) } }
    Separator()
    Entry("copy", "Copy", "⌘C") { run { editor.copy() } }
    Entry("cut", "Cut", "⌘X") { run { editor.cut() } }
    Entry("duplicate", "Duplicate", "⌘D") { run { editor.duplicate() } }
    Entry("delete", "Delete", "⌫") { run { editor.delete() } }
    Separator()
    Entry("break", "Break all links", null, editor.shape.wires.any { it.provider == id || it.consumer == id }) {
        run { editor.edit { s, p -> s.disconnect(s.wires.filter { it.provider in editor.selection.nodes || it.consumer in editor.selection.nodes }.mapTo(hashSetOf()) { it.id }) to p } }
    }
    Entry("comment", "Comment the selection", "C") { run { editor.comment() } }
    Entry("straighten", "Straighten connections", "Q") { run { editor.straighten() } }
    if (many) Submenu("align", trigger = { Text("Align") }) {
        Entry("align-top", "Top", "⇧W") { run { editor.align(Edge.Top) } }
        Entry("align-middle", "Middle", "⌥⇧W") { run { editor.align(Edge.Middle) } }
        Entry("align-bottom", "Bottom", "⇧S") { run { editor.align(Edge.Bottom) } }
        Entry("align-left", "Left", "⇧A") { run { editor.align(Edge.Left) } }
        Entry("align-centre", "Centre", "⌥⇧S") { run { editor.align(Edge.Centre) } }
        Entry("align-right", "Right", "⇧D") { run { editor.align(Edge.Right) } }
        Separator()
        Entry("distribute-x", "Distribute horizontally", null, editor.selection.nodes.size > 2) { run { editor.distribute(true) } }
        Entry("distribute-y", "Distribute vertically", null, editor.selection.nodes.size > 2) { run { editor.distribute(false) } }
    }
}

@Composable
private fun MenuPopupScope.Entry(key: String, label: String, chord: String?, enabled: Boolean = true, action: () -> Unit) =
    Item(key, action, Modifier.testTag("graph-editor-menu-$key"), enabled) {
        Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), role = Type.Body, ink = if (enabled) Ink.Text else Ink.Muted)
            chord?.let { Text(it, role = Type.Meta, ink = Ink.Muted) }
        }
    }

@Composable
private fun EditorPalette(editor: GraphEditor, request: Request.Palette) {
    val query = rememberTextFieldState()
    val pin: PortShape? = request.from?.let { editor.shape.node(it.node)?.port(it.key, it.provides) }
    val words = query.text.toString().trim().lowercase().split(' ').filter { it.isNotEmpty() }
    val choices = remember(words, pin, editor.templates) {
        editor.templates.filter { template -> template.offers(pin) && words.all { word -> template.label.lowercase().contains(word) || template.type.lowercase().contains(word) || template.kind.name.lowercase().contains(word) || template.ports.any { it.key.lowercase().contains(word) } } }.take(80)
    }
    fun choose(template: Template) { editor.request = null; editor.add(template, request.at, request.from) }
    Popover(true, { if (!it) editor.request = null }) {
        Content {
            Column(Modifier.width(340.dp).padding(8.dp).testTag("graph-editor-palette"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pin?.let { "Nodes that ${if (it.provides) "need" else "provide"} ${it.type}" } ?: "Add a node", role = Type.Meta, ink = Ink.Muted, lines = 2)
                TextField(query, Modifier.fillMaxWidth().testTag("graph-editor-search"), placeholder = { Text("Search nodes, types and ports…", ink = Ink.Muted) },
                    keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { choices.firstOrNull()?.let(::choose) })
                if (choices.isEmpty()) Text("Nothing matches.", role = Type.Meta, ink = Ink.Muted)
                ListBox(listSource(choices, { "${it.kind}:${it.type}:${it.label}" }, text = { it.label }, activateOnPress = { true }), emptySet(), {},
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    onActivate = { key -> choices.firstOrNull { "${it.kind}:${it.type}:${it.label}" == key }?.let(::choose) }) { template ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(template.label, role = Type.Body, ink = Ink.Text)
                        Text("${template.kind.name} · ${template.ports.count { !it.provides }} in · ${template.ports.count { it.provides }} out", role = Type.Meta, ink = Ink.Muted)
                    }
                }
                if (request.from == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Quiet("Paste here", "graph-editor-paste") { editor.request = null; editor.paste(request.at) }
                    Quiet("Add comment", "graph-editor-comment") { editor.request = null; editor.comment(Selection()) }
                }
            }
        }
    }
}

@Composable
private fun Quiet(label: String, tag: String, action: () -> Unit) =
    dev.shibasis.reaktor.surface.compose.Button(action, Modifier.testTag(tag)) { Text(label, role = Type.Label) }

@Composable
private fun EditorRename(editor: GraphEditor, request: Request.Rename) {
    val current = if (request.note) editor.placement.notes.firstOrNull { it.id == request.subject }?.text else editor.node(request.subject)?.label
    val state: TextFieldState = remember(request.subject) { current.orEmpty().let { TextFieldState(it, TextRange(0, it.length)) } }
    Popover(true, { if (!it) editor.request = null }) {
        Content {
            Column(Modifier.width(280.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (request.note) "Comment" else "Name", role = Type.Meta, ink = Ink.Muted)
                TextField(state, Modifier.fillMaxWidth().testTag("graph-editor-rename"),
                    keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { editor.rename(request.subject, state.text.toString()); editor.request = null })
            }
        }
    }
}

val NoteTints = listOf(
    "Plain" to 0L,
    "Blue" to 0xFF4C8DFF,
    "Green" to 0xFF3FB97A,
    "Amber" to 0xFFE0A33A,
    "Red" to 0xFFE5534B,
    "Violet" to 0xFF9B6BFF,
)
