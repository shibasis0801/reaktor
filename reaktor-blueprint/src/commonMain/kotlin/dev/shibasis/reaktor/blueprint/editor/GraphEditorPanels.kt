package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.ListBox
import dev.shibasis.reaktor.surface.compose.Select
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.TextField
import dev.shibasis.reaktor.surface.listSource

@Composable
fun GraphChanges(editor: GraphEditor, modifier: Modifier = Modifier, onPick: (Difference) -> Unit = {}) {
    val entries = editor.diff.differences
    val problems = editor.problems
    Column(modifier.testTag("graph-editor-changes"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(when {
            entries.isEmpty() -> "No changes yet"
            else -> "${entries.count { it.change == Change.Added }} added · ${entries.count { it.change == Change.Changed }} changed · ${entries.count { it.change == Change.Removed }} removed"
        }, role = Type.Label, ink = Ink.Text)
        Text(if (problems.isEmpty()) "Valid · ready to save" else "${problems.size} problem${if (problems.size == 1) "" else "s"} to fix before saving",
            Modifier.testTag("graph-editor-validity"), role = Type.Meta, ink = if (problems.isEmpty()) Ink.Ok else Ink.Danger)
        problems.take(6).forEach { problem ->
            Button({ editor.reveal(problem.subject); onPick(Difference(Change.Changed, problem.subject, editor.node(problem.subject)?.id, problem.text, "")) }, Modifier.fillMaxWidth()) {
                Text(problem.text, role = Type.Meta, ink = Ink.Danger, lines = 3)
            }
        }
        ListBox(listSource(entries, { it.change.name + ":" + it.subject }, text = { it.title }, activateOnPress = { true }), emptySet(), {},
            Modifier.fillMaxWidth().heightIn(max = 520.dp),
            onActivate = { key -> entries.firstOrNull { it.change.name + ":" + it.subject == key }?.let { editor.reveal(it.subject); onPick(it) } }) { entry ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).testTag("graph-editor-change-${entry.subject}"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.change.sign, Modifier.width(14.dp), role = Type.Body.strong, ink = when (entry.change) { Change.Added -> Ink.Ok; Change.Changed -> Ink.Warn; Change.Removed -> Ink.Danger })
                Column(Modifier.weight(1f)) {
                    Text(entry.title, role = Type.Label.code, ink = Ink.Text)
                    if (entry.detail.isNotEmpty()) Text(entry.detail, role = Type.Meta, ink = Ink.Muted, lines = 3)
                }
                Button({ editor.revert(entry) }, Modifier.testTag("graph-editor-revert-${entry.subject}")) { Text("Revert", role = Type.Meta) }
            }
        }
    }
}

@Composable
fun GraphNodeDetails(editor: GraphEditor, id: String, modifier: Modifier = Modifier) {
    val node = editor.node(id)
    val terms = editor.terms
    Column(modifier.testTag("graph-editor-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (node == null) {
            val removed = editor.base.node(id)
            Text(removed?.label ?: id, role = Type.Body.strong, ink = Ink.Text)
            Text("Removed in this change", role = Type.Meta, ink = Ink.Danger)
            if (removed != null) Button({ editor.revert(Difference(Change.Removed, id, id, removed.label, "")) }, Modifier.testTag("graph-editor-restore")) { Text("Restore") }
            return@Column
        }
        editor.change(id)?.let { change -> Text(when (change) { Change.Added -> "Added in this change"; Change.Changed -> "Changed in this change"; Change.Removed -> "Removed" }, role = Type.Meta,
            ink = when (change) { Change.Added -> Ink.Ok; Change.Changed -> Ink.Warn; Change.Removed -> Ink.Danger }) }
        Commit("Name", node.label, "graph-editor-name") { value -> editor.update(id) { it.copy(label = value) } }
        if (terms.types) Commit("Type", node.type, "graph-editor-type") { value -> editor.update(id) { it.copy(type = value) } }
        if (node.kind == NodeKind.Screen || node.route != null) Commit("Route", node.route.orEmpty(), "graph-editor-route", allowBlank = true) { value -> editor.update(id) { it.copy(route = value.ifBlank { null }) } }
        if (terms.kinds) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kind", Modifier.width(64.dp), role = Type.Meta, ink = Ink.Muted)
            Choice(node.kind.name, NodeKind.entries.filter { it != NodeKind.Route }.map { it.name to it.name }, "graph-editor-kind") { value -> editor.update(id) { it.copy(kind = NodeKind.valueOf(value)) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Graph", Modifier.width(64.dp), role = Type.Meta, ink = Ink.Muted)
            Choice(node.scope, editor.shape.scopes.map { it.id to it.label }, "graph-editor-scope") { value -> editor.update(id) { it.copy(scope = value) } }
        }
        val rows = terms.rows(node)
        if (rows == null) {
            Ports(editor, node, provides = false)
            Ports(editor, node, provides = true)
            NewPort(editor, node)
        } else {
            Rows(editor, node, rows)
            NewRow(editor, node, rows)
        }
        val wires = editor.shape.wires.filter { it.provider == id || it.consumer == id }
        if (wires.isNotEmpty()) {
            Text("Links · ${wires.size}", role = Type.Label, ink = Ink.Text)
            wires.forEach { wire ->
                val other = if (wire.provider == id) editor.node(wire.consumer) else editor.node(wire.provider)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (wire.provider == id) "${terms.port(node, wire.providerPort)} → ${other?.let { terms.end(it, wire.consumerPort) } ?: wire.consumerPort}"
                        else "${other?.let { terms.end(it, wire.providerPort) } ?: wire.providerPort} → ${terms.port(node, wire.consumerPort)}",
                        Modifier.weight(1f), role = Type.Meta.code, ink = Ink.Text, lines = 2)
                    Button({ editor.edit { s, p -> s.disconnect(setOf(wire.id)) to p } }, Modifier.testTag("graph-editor-unlink-${wire.id}")) { Text("Unlink", role = Type.Meta) }
                }
            }
        }
    }
}

@Composable
private fun Ports(editor: GraphEditor, node: NodeShape, provides: Boolean) {
    val ports = node.ports.filter { it.provides == provides && !it.internal }
    Text("${if (provides) "Outputs" else "Inputs"} · ${ports.size}", role = Type.Label, ink = Ink.Text)
    ports.forEach { port ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Commit(null, port.key, "graph-editor-port-${port.key}", Modifier.weight(1f)) { value -> editor.update(node.id) { it.copy(ports = it.ports.map { p -> if (p == port) p.copy(key = value) else p }) } }
            Commit(null, port.type, "graph-editor-port-type-${port.key}", Modifier.weight(1f)) { value -> editor.update(node.id) { it.copy(ports = it.ports.map { p -> if (p == port) p.copy(type = value) else p }) } }
            Button({ editor.update(node.id) { it.copy(ports = it.ports - port) } }, Modifier.testTag("graph-editor-remove-port-${port.key}")) { Text("Remove", role = Type.Meta) }
        }
    }
}

@Composable
private fun Rows(editor: GraphEditor, node: NodeShape, rows: List<String>) {
    Text("Rows · ${rows.size}", role = Type.Label, ink = Ink.Text)
    rows.forEach { key ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(editor.terms.port(node, key), Modifier.weight(1f).testTag("graph-editor-row-$key"), role = Type.Body, ink = Ink.Text, lines = 2)
            Button({ editor.update(node.id) { it.copy(ports = it.ports.filterNot { port -> port.key == key }) } }, Modifier.testTag("graph-editor-remove-row-$key")) { Text("Remove", role = Type.Meta) }
        }
    }
}

@Composable
private fun NewRow(editor: GraphEditor, node: NodeShape, rows: List<String>) {
    val title = remember(node.id) { TextFieldState() }
    fun add() {
        val text = title.text.toString().trim()
        if (text.isEmpty()) { editor.notice = "A row needs a title"; return }
        if (rows.any { it == text || editor.terms.port(node, it) == text }) { editor.notice = "${node.label} already has a row named $text"; return }
        editor.update(node.id) { editor.terms.row(it, text) }
        title.edit { replace(0, length, "") }
    }
    Text("Add a row", role = Type.Label, ink = Ink.Text)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextField(title, Modifier.weight(1f).testTag("graph-editor-new-row"), placeholder = { Text("title", ink = Ink.Muted) }, keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { add() })
        Button(::add, Modifier.testTag("graph-editor-add-row")) { Text("Add", role = Type.Meta) }
    }
}

@Composable
private fun NewPort(editor: GraphEditor, node: NodeShape) {
    val key = remember(node.id) { TextFieldState() }
    val type = remember(node.id) { TextFieldState() }
    var provides by remember(node.id) { mutableStateOf(false) }
    fun add() {
        val name = key.text.toString().trim()
        val kind = type.text.toString().trim()
        if (name.isEmpty() || kind.isEmpty()) { editor.notice = "A port needs a name and a type"; return }
        if (node.port(name, provides) != null) { editor.notice = "${node.label} already has ${if (provides) "an output" else "an input"} named $name"; return }
        editor.update(node.id) { it.copy(ports = it.ports + PortShape(name, kind, provides, if (provides) PortWiring.Unused else PortWiring.Unmatched)) }
        key.edit { replace(0, length, "") }; type.edit { replace(0, length, "") }
    }
    Text("Add a port", role = Type.Label, ink = Ink.Text)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextField(key, Modifier.weight(1f).testTag("graph-editor-new-port"), placeholder = { Text("name", ink = Ink.Muted) })
        TextField(type, Modifier.weight(1f).testTag("graph-editor-new-port-type"), placeholder = { Text("type", ink = Ink.Muted) }, keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { add() })
        Choice(if (provides) "out" else "in", listOf("in" to "In", "out" to "Out"), "graph-editor-new-port-side") { provides = it == "out" }
        Button(::add, Modifier.testTag("graph-editor-add-port")) { Text("Add", role = Type.Meta) }
    }
}

@Composable
private fun Commit(label: String?, value: String, tag: String, modifier: Modifier = Modifier, allowBlank: Boolean = false, onCommit: (String) -> Unit) {
    val state = remember(value) { TextFieldState(value) }
    fun commit() { val next = state.text.toString().trim(); if (next != value && (allowBlank || next.isNotEmpty())) onCommit(next) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { Text(it, Modifier.width(64.dp), role = Type.Meta, ink = Ink.Muted) }
        TextField(state, Modifier.weight(1f).testTag(tag).onFocusChanged { if (!it.isFocused) commit() }, keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { commit() })
    }
}

@Composable
private fun Choice(selected: String, options: List<Pair<String, String>>, tag: String, onChange: (String) -> Unit) {
    Select(selected, onChange) {
        Trigger(Modifier.testTag(tag)) { Text(options.firstOrNull { it.first == selected }?.second ?: selected, role = Type.Label) }
        Options { options.forEach { (key, label) -> Option(key, Modifier.testTag("$tag-$key")) { Text(label) } } }
    }
}

internal val Submit = KeyboardOptions(imeAction = ImeAction.Done)
