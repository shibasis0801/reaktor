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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.ListBox
import dev.shibasis.reaktor.surface.compose.Select
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.TextField
import dev.shibasis.reaktor.surface.listSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToLong

@Composable
fun GraphChanges(editor: GraphEditor, modifier: Modifier = Modifier, onPick: (Difference) -> Unit = {}) {
    val entries = editor.diff.differences
    val problems = editor.problems
    Column(modifier.testTag("graph-editor-changes"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        editor.recovered?.let { Recovered(editor, it) }
        editor.against?.let { version ->
            Row(Modifier.fillMaxWidth().testTag("graph-editor-comparing"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Showing what changed since revision ${version.revision}", Modifier.weight(1f), role = Type.Meta, ink = Ink.Warn, lines = 2)
                Button({ editor.compare(null) }, Modifier.testTag("graph-editor-stop-comparing")) { Text("Stop comparing", role = Type.Meta) }
            }
        }
        if (editor.dirty) Text("Unsaved edits are kept until you save or discard them",
            Modifier.testTag("graph-editor-kept").then(if (editor.kept) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}), role = Type.Meta, ink = Ink.Muted, lines = 2)
        if (editor.applied) Text("Applied · the live graph already has every change in this draft", Modifier.testTag("graph-editor-applied"), role = Type.Meta, ink = Ink.Ok, lines = 2)
        if (editor.conflicts.isNotEmpty()) {
            Text("${editor.conflicts.size} change${if (editor.conflicts.size == 1) "" else "s"} no longer fit${if (editor.conflicts.size == 1) "s" else ""} the live graph and ${if (editor.conflicts.size == 1) "was" else "were"} left out",
                Modifier.testTag("graph-editor-conflicts"), role = Type.Meta, ink = Ink.Danger, lines = 2)
            editor.conflicts.take(ConflictsShown).forEach { Text("· ${it.summary}", role = Type.Meta, ink = Ink.Muted, lines = 2) }
        }
        Text(when {
            entries.isEmpty() -> "No changes yet"
            else -> "${entries.count { it.change == Change.Added }} added · ${entries.count { it.change == Change.Changed }} changed · ${entries.count { it.change == Change.Removed }} removed"
        }, role = Type.Label, ink = Ink.Text)
        Text(if (problems.isEmpty()) "Valid · ready to save" else "${problems.size} problem${if (problems.size == 1) "" else "s"} to fix before saving",
            Modifier.testTag("graph-editor-validity"), role = Type.Meta, ink = if (problems.isEmpty()) Ink.Ok else Ink.Danger)
        val warnings = editor.warnings
        if (warnings.isNotEmpty()) Text("${warnings.size} warning${if (warnings.size == 1) "" else "s"} · saving is still allowed", Modifier.testTag("graph-editor-warnings"), role = Type.Meta, ink = Ink.Warn)
        val issues = problems.map { it to true } + warnings.map { it to false }
        if (issues.isNotEmpty()) ListBox(listSource(issues.indices.toList(), { "issue-$it" }, text = { issues[it].first.text }, activateOnPress = { true }), emptySet(), {},
            Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("graph-editor-issues"),
            onActivate = { key -> issues.getOrNull(key.removePrefix("issue-").toInt())?.first?.let { problem ->
                editor.reveal(problem.subject); onPick(Difference(Change.Changed, problem.subject, editor.node(problem.subject)?.id, problem.text, ""))
            } }) { i ->
            Text(issues[i].first.text, Modifier.padding(horizontal = 6.dp, vertical = 4.dp), role = Type.Meta, ink = if (issues[i].second) Ink.Danger else Ink.Warn, lines = 3)
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
        Parts(editor)
        Trials(editor, onPick)
        Failures(editor, onPick)
        Versions(editor)
        History(editor)
    }
}

@Composable
private fun Recovered(editor: GraphEditor, set: ChangeSet) {
    Column(Modifier.fillMaxWidth().testTag("graph-editor-recovered"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Edits you did not save last time were kept" + (set.size.takeIf { it > 0 }?.let { " · $it change${if (it == 1) "" else "s"}" } ?: ""), role = Type.Meta, ink = Ink.Warn, lines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ editor.restore(set) }, Modifier.testTag("graph-editor-restore-unsaved")) { Text("Restore them", role = Type.Meta) }
            Button({ editor.recovered = null }, Modifier.testTag("graph-editor-drop-unsaved")) { Text("Throw them away", role = Type.Meta) }
        }
    }
}

@Composable
private fun Parts(editor: GraphEditor) {
    editor.saved.forEach { set ->
        val stale = editor.stale(set.id)
        if (stale.isEmpty()) return@forEach
        Row(Modifier.fillMaxWidth().testTag("graph-editor-part-${set.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${stale.size} use${if (stale.size == 1) " of ${set.title} is" else "s of ${set.title} are"} older than its revision ${set.revision}", Modifier.weight(1f), role = Type.Meta, ink = Ink.Warn, lines = 2)
            Button({ editor.updateUses(set.id) }, Modifier.testTag("graph-editor-update-uses-${set.id}")) { Text("Update them", role = Type.Meta) }
        }
    }
}

@Composable
private fun Trials(editor: GraphEditor, onPick: (Difference) -> Unit) {
    val trial = editor.trial ?: return
    val current = trial.revision == editor.revision
    Text("Trial of revision ${trial.revision} · ${trial.passed} passed · ${trial.failed} failed", Modifier.testTag("graph-editor-trial"), role = Type.Label, ink = if (trial.failed > 0) Ink.Danger else Ink.Ok)
    if (!current) Text("This trial ran on an older revision. Try the draft again to check the latest edits.", role = Type.Meta, ink = Ink.Warn, lines = 2)
    if (trial.summary.isNotEmpty()) Text(redacted(trial.summary), role = Type.Meta, ink = Ink.Muted, lines = 6)
    ListBox(listSource(trial.nodes.indices.toList(), { "trial-$it" }, text = { trial.nodes[it].node }, activateOnPress = { true }), emptySet(), {},
        Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("graph-editor-trial-nodes"),
        onActivate = { key -> trial.nodes.getOrNull(key.removePrefix("trial-").toInt())?.let { result ->
            val id = editor.shape.visible(result.node) ?: result.node
            editor.reveal(id); onPick(Difference(Change.Changed, id, id, result.detail, ""))
        } }) { i ->
        val result = trial.nodes[i]
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text("${if (result.ok) "✓" else "✕"} ${editor.node(result.node)?.label ?: result.node}", role = Type.Meta, ink = if (result.ok) Ink.Ok else Ink.Danger, lines = 1)
            if (result.detail.isNotEmpty()) Text(redacted(result.detail), role = Type.Meta, ink = Ink.Muted, lines = 3)
        }
    }
}

@Composable
private fun Versions(editor: GraphEditor) {
    val versions = editor.versions
    if (versions.isEmpty()) return
    Text("Versions · ${versions.size}", role = Type.Label, ink = Ink.Text)
    Text("Compare shows on the canvas what changed since that revision. Restore brings it back as an edit you can undo.", role = Type.Meta, ink = Ink.Muted, lines = 3)
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).testTag("graph-editor-versions"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        versions.take(VersionsShown).forEach { version ->
            Row(Modifier.fillMaxWidth().testTag("graph-editor-version-${version.revision}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val changes = version.graph?.changes?.size ?: 0
                Text("r${version.revision} · $changes change${if (changes == 1) "" else "s"} · ${editor.stamp(version.savedAtMillis)}", Modifier.weight(1f), role = Type.Meta,
                    ink = if (version.revision == editor.revision) Ink.Ok else Ink.Text, lines = 2)
                Button({ editor.compare(version) }, Modifier.testTag("graph-editor-compare-${version.revision}")) { Text(if (editor.against == version) "Comparing" else "Compare", role = Type.Meta) }
                Button({ editor.rollback(version) }, Modifier.testTag("graph-editor-rollback-${version.revision}")) { Text("Restore", role = Type.Meta) }
            }
        }
    }
}

@Composable
private fun Failures(editor: GraphEditor, onPick: (Difference) -> Unit) {
    val failures = editor.runs?.failures.orEmpty()
    if (failures.isEmpty()) return
    Text("Recent failures · ${failures.size}", role = Type.Label, ink = Ink.Danger)
    ListBox(listSource(failures.indices.toList(), { "failure-$it" }, text = { failures[it].text }, activateOnPress = { true }), emptySet(), {},
        Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("graph-editor-failures"),
        onActivate = { key -> failures.getOrNull(key.removePrefix("failure-").toInt())?.let { failure ->
            val id = editor.shape.visible(failure.node) ?: failure.node
            editor.reveal(id); onPick(Difference(Change.Changed, id, id, failure.text, ""))
        } }) { i ->
        val failure = failures[i]
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(editor.node(failure.node)?.let { editor.terms.end(it, failure.port) } ?: "${failure.node}.${failure.port}", role = Type.Meta.code, ink = Ink.Text, lines = 1)
            Text(redacted(failure.text), role = Type.Meta, ink = Ink.Danger, lines = 3)
        }
    }
}

@Composable
private fun History(editor: GraphEditor) {
    val past = editor.history.take(HistoryShown)
    val next = editor.future.take(HistoryShown)
    if (past.isEmpty() && next.isEmpty()) return
    val steps = next.indices.reversed().map { "redo-$it" to next[it] } + listOf(Now to "Now") + past.indices.map { "undo-$it" to past[it] }
    Text("History", role = Type.Label, ink = Ink.Text)
    Text("Pick a step to undo it and everything after it, or a greyed step to redo up to it", role = Type.Meta, ink = Ink.Muted, lines = 2)
    ListBox(listSource(steps, { it.first }, text = { it.second }, activateOnPress = { true }), emptySet(), {},
        Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("graph-editor-history"),
        onActivate = { key -> key.substringAfter('-').toIntOrNull()?.let { at -> if (key.startsWith("undo")) editor.undo(at + 1) else editor.redo(at + 1) } }) { (key, name) ->
        Text(if (key == Now) "Now" else name, Modifier.padding(horizontal = 6.dp, vertical = 4.dp).testTag("graph-editor-step-$key"), role = Type.Meta,
            ink = when { key == Now -> Ink.Ok; key.startsWith("redo") -> Ink.Muted; else -> Ink.Text }, lines = 2)
    }
}

@Composable
fun KeepRecovery(editor: GraphEditor, keep: suspend (ChangeSet?) -> Unit) {
    LaunchedEffect(editor) {
        var mine = false
        snapshotFlow { Triple(editor.dirty, editor.recovered != null, if (editor.dirty) listOf(editor.shape, editor.placement, editor.title) else null) }
            .collectLatest { (dirty, held) ->
                when {
                    dirty -> { delay(RecoveryDelay); keep(editor.recovery()); mine = true; editor.kept = true }
                    held -> mine = true
                    mine -> { keep(null); mine = false; editor.kept = false }
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
        Commit("Note", editor.placement.remarks[id].orEmpty(), "graph-editor-note", allowBlank = true) { value -> editor.remark(id, value) }
        node.part?.let { part -> PartOf(editor, node, part) }
        if (terms.types) Commit("Type", node.type, "graph-editor-type") { value -> editor.update(id) { it.copy(type = value) } }
        if (node.kind == NodeKind.Screen || node.route != null) Commit("Route", node.route.orEmpty(), "graph-editor-route", allowBlank = true) { value -> editor.update(id) { it.copy(route = value.ifBlank { null }) } }
        if (terms.kinds) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kind", Modifier.width(64.dp), role = Type.Meta, ink = Ink.Muted)
            Choice(node.kind.name, NodeKind.entries.filter { it != NodeKind.Route }.map { it.name to it.name }, "graph-editor-kind") { value -> editor.update(id) { it.copy(kind = NodeKind.valueOf(value)) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(terms.scope.replaceFirstChar { it.uppercase() }, Modifier.width(64.dp), role = Type.Meta, ink = Ink.Muted)
            Choice(node.scope, editor.shape.scopes.map { it.id to it.label }, "graph-editor-scope") { value -> editor.update(id) { it.copy(scope = value) } }
        }
        val rows = terms.rows(node)
        if (rows == null) {
            Ports(editor, node, provides = false)
            if (terms.settings) Settings(editor, node)
            Ports(editor, node, provides = true)
            NewPort(editor, node)
        } else {
            Rows(editor, node, rows)
            NewRow(editor, node, rows)
            val wires = editor.shape.wires.filter { it.provider == id || it.consumer == id }
            if (wires.isNotEmpty()) Text("Links · ${wires.size}", role = Type.Label, ink = Ink.Text)
            wires.forEach { Wire(editor, node, it) }
        }
    }
}

@Composable
private fun PartOf(editor: GraphEditor, node: NodeShape, part: String) {
    val set = editor.saved.firstOrNull { it.id == part }
    val uses = editor.shape.uses(part)
    val stale = editor.stale(part)
    Column(Modifier.fillMaxWidth().testTag("graph-editor-part-of"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Part of ${set?.title ?: "a saved part"} · revision ${node.attributes[PartRevisionKey] ?: "?"}${set?.let { " of ${it.revision}" }.orEmpty()} · ${uses.size} use${if (uses.size == 1) "" else "s"} in this graph",
            role = Type.Meta, ink = if (stale.any { node.id in it.nodes }) Ink.Warn else Ink.Muted, lines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (editor.onTemplate != null) Button({ editor.keepPart(node.id) }, Modifier.testTag("graph-editor-keep-part")) { Text("Keep as the new version", role = Type.Meta) }
            if (stale.isNotEmpty()) Button({ editor.updateUses(part) }, Modifier.testTag("graph-editor-update-uses")) { Text("Update ${stale.size} use${if (stale.size == 1) "" else "s"}", role = Type.Meta) }
        }
    }
}

@Composable
private fun Ports(editor: GraphEditor, node: NodeShape, provides: Boolean) {
    val ports = node.ports.filter { it.provides == provides && !it.internal }
    Text("${if (provides) "Outputs" else "Inputs"} · ${ports.size}", role = Type.Label, ink = Ink.Text)
    ports.forEach { port ->
        val wires = editor.links(PinEnd(node.id, port.key, provides))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Commit(null, port.key, "graph-editor-port-${port.key}", Modifier.weight(1f)) { value -> editor.rename(node.id, port, value) }
                Commit(null, port.type, "graph-editor-port-type-${port.key}", Modifier.weight(1f)) { value -> editor.update(node.id) { it.copy(ports = it.ports.map { p -> if (p == port) p.copy(type = value) else p }) } }
                Button({ editor.update(node.id) { it.copy(ports = it.ports - port) } }, Modifier.testTag("graph-editor-remove-port-${port.key}")) { Text("Remove", role = Type.Meta) }
            }
            wires.forEach { Wire(editor, node, it) }
            if (!provides && wires.isEmpty() && editor.terms.settings)
                Commit("Default", node.attributes[DefaultSetting + port.key].orEmpty(), "graph-editor-default-${port.key}", allowBlank = true) { value -> editor.setting(node.id, DefaultSetting + port.key, value.ifEmpty { null }) }
            editor.runs?.let { runs ->
                runs.port(node.id, port.key)?.let { Run(it, port.key) }
                val pin = PinEnd(node.id, port.key, provides)
                Button({ editor.watch(pin) }, Modifier.testTag("graph-editor-watch-${port.key}")) { Text(if (pin in editor.watched) "Stop watching" else "Watch on the canvas", role = Type.Meta) }
            }
        }
    }
}

@Composable
private fun Wire(editor: GraphEditor, node: NodeShape, wire: WireShape) {
    val out = wire.provider == node.id
    val far = if (out) wire.consumer else wire.provider
    val key = if (out) wire.consumerPort else wire.providerPort
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button({ editor.reveal(editor.shape.visible(far) ?: far) }, Modifier.weight(1f).testTag("graph-editor-go-${wire.id}")) {
            Text("${if (out) "→" else "←"} ${editor.node(far)?.let { editor.terms.end(it, key) } ?: "$far.$key"}", Modifier.fillMaxWidth(), role = Type.Meta.code, ink = Ink.Text, lines = 2)
        }
        Button({ editor.edit { s, p -> s.disconnect(setOf(wire.id)) to p } }, Modifier.testTag("graph-editor-unlink-${wire.id}")) { Text("Unlink", role = Type.Meta) }
    }
}

@Composable
private fun Run(run: PortRun, key: String) {
    Column(Modifier.fillMaxWidth().testTag("graph-editor-run-$key")) {
        Text(listOfNotNull("${run.calls} call${if (run.calls == 1) "" else "s"}", run.failures.takeIf { it > 0 }?.let { "$it failed" }, run.p50Nanos?.let { "typical ${millis(it)}" }).joinToString(" · "),
            role = Type.Meta, ink = if (run.failures > 0) Ink.Danger else Ink.Muted)
        run.last?.let { Text("Last: ${redacted(it)}", role = Type.Meta.code, ink = Ink.Muted, lines = 4) }
        run.failure?.let { Text("Failed: ${redacted(it)}", role = Type.Meta.code, ink = Ink.Danger, lines = 4) }
    }
}

@Composable
private fun Settings(editor: GraphEditor, node: NodeShape) {
    val settings = node.attributes.filterKeys { !it.startsWith(DefaultSetting) && !it.startsWith(PartPrefix) }.entries.sortedBy { it.key }
    Text("Settings · ${settings.size}", role = Type.Label, ink = Ink.Text)
    settings.forEach { (key, value) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(key, Modifier.width(96.dp), role = Type.Meta.code, ink = Ink.Muted, lines = 2)
            Commit(null, value, "graph-editor-setting-$key", Modifier.weight(1f), allowBlank = true) { editor.setting(node.id, key, it) }
            Button({ editor.setting(node.id, key, null) }, Modifier.testTag("graph-editor-remove-setting-$key")) { Text("Remove", role = Type.Meta) }
        }
    }
    val key = remember(node.id) { TextFieldState() }
    val value = remember(node.id) { TextFieldState() }
    fun add() {
        val name = key.text.toString().trim()
        if (name.isEmpty()) { editor.notice = "A setting needs a name"; return }
        if (name in node.attributes) { editor.notice = "${node.label} already has the setting $name"; return }
        editor.setting(node.id, name, value.text.toString().trim())
        key.edit { replace(0, length, "") }; value.edit { replace(0, length, "") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextField(key, Modifier.weight(1f).testTag("graph-editor-new-setting"), placeholder = { Text("setting", ink = Ink.Muted) })
        TextField(value, Modifier.weight(1f).testTag("graph-editor-new-setting-value"), placeholder = { Text("value", ink = Ink.Muted) }, keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { add() })
        Button(::add, Modifier.testTag("graph-editor-add-setting")) { Text("Add", role = Type.Meta) }
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
    val known = remember(editor.shape) { editor.shape.nodes.flatMap { it.ports }.filterNot { it.internal }.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.take(KnownTypes).map { it.key } }
    Text("Add a port", role = Type.Label, ink = Ink.Text)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextField(key, Modifier.weight(1f).testTag("graph-editor-new-port"), placeholder = { Text("name", ink = Ink.Muted) })
        Choice(if (provides) "out" else "in", listOf("in" to "In", "out" to "Out"), "graph-editor-new-port-side") { provides = it == "out" }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextField(type, Modifier.weight(1f).testTag("graph-editor-new-port-type"), placeholder = { Text("type", ink = Ink.Muted) }, keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { add() })
        if (known.isNotEmpty()) Select(null, { type.setTextAndPlaceCursorAtEnd(it) }) {
            Trigger(Modifier.testTag("graph-editor-known-types")) { Text("Types", role = Type.Label) }
            Options { known.forEach { name -> Option(name, Modifier.testTag("graph-editor-known-type-$name"), typeahead = name) { Text(name, role = Type.Body.code) } } }
        }
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

fun redacted(text: String): String = text.take(RunText)
    .replace(SecretField) { "${it.groupValues[1]}•••" }
    .replace(Bearer) { "${it.groupValues[1]} •••" }
    .replace(Jwt, "•••")
    .replace(Opaque, "•••")

private fun millis(nanos: Long): String = (nanos / 1_000_000.0).let { if (it < 10) "${(it * 10).roundToLong() / 10.0} ms" else "${it.roundToLong()} ms" }

internal val Submit = KeyboardOptions(imeAction = ImeAction.Done)

const val DefaultSetting = "default."

private const val KnownTypes = 40
private const val ConflictsShown = 12
private const val HistoryShown = 30
private const val VersionsShown = 40
private const val RecoveryDelay = 1500L
private const val RunText = 600
private const val Now = "now"
private val SecretField = Regex("""("?[\w.-]*(?:token|secret|password|passwd|api[_-]?key|authorization|cookie|session|credential|private[_-]?key)[\w.-]*"?\s*[:=]\s*)(?:(?:bearer|basic)\s+)?(?:"[^"]*"|[^\s,;}&]+)""", RegexOption.IGNORE_CASE)
private val Bearer = Regex("""\b(bearer|basic)\s+[\w.~+/=-]+""", RegexOption.IGNORE_CASE)
private val Jwt = Regex("""\beyJ[\w-]{6,}\.[\w-]{6,}\.[\w-]+""")
private val Opaque = Regex("""\b[A-Za-z0-9_+=-]{40,}""")
