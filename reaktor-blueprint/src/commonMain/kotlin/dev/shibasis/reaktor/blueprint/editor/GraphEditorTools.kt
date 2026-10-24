package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.canvas.GraphCommands
import dev.shibasis.reaktor.surface.ChordFinding
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.Popover
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.TextField
import dev.shibasis.reaktor.surface.compose.platformKeyConvention
import dev.shibasis.reaktor.surface.compose.stroke
import dev.shibasis.reaktor.surface.findings
import dev.shibasis.reaktor.surface.label
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun BoxScope.Minimap(editor: GraphEditor) {
    val camera = editor.camera ?: return
    val shown = editor.layout
    if (!editor.prefs.minimap || shown.cards.isEmpty()) return
    val ink = editor.ink
    val frame = remember { DoubleArray(3) }
    fun look(at: Offset) { if (frame[2] > 0) camera.look(frame[0] + at.x / frame[2], frame[1] + at.y / frame[2]) }
    Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = MinimapLift.dp).size(MinimapWidth.dp, MinimapHeight.dp).clip(RoundedCornerShape(8.dp)).background(ink.surface)
        .pointerInput(camera) { detectTapGestures { look(it) } }
        .pointerInput(camera) { detectDragGestures { change, _ -> look(change.position) } }
        .testTag("graph-editor-minimap")) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val seen = camera.seen
            val boxes = shown.cards.values + shown.frames
            val left = min(boxes.minOf { it.x }, seen?.x ?: Double.MAX_VALUE)
            val top = min(boxes.minOf { it.y }, seen?.y ?: Double.MAX_VALUE)
            val right = max(boxes.maxOf { it.x + it.width }, seen?.let { it.x + it.width } ?: -Double.MAX_VALUE)
            val bottom = max(boxes.maxOf { it.y + it.height }, seen?.let { it.y + it.height } ?: -Double.MAX_VALUE)
            val scale = min(size.width / (right - left).coerceAtLeast(1.0), size.height / (bottom - top).coerceAtLeast(1.0))
            frame[0] = left - 6.dp.toPx() / scale; frame[1] = top - 6.dp.toPx() / scale; frame[2] = scale
            fun at(x: Double, y: Double) = Offset(((x - left) * scale).toFloat(), ((y - top) * scale).toFloat())
            fun extent(width: Double, height: Double) = Size((width * scale).toFloat().coerceAtLeast(1f), (height * scale).toFloat().coerceAtLeast(1f))
            shown.frames.forEach { drawRect(ink.text.copy(alpha = .08f), at(it.x, it.y), extent(it.width, it.height)) }
            shown.cards.values.forEach { card ->
                val tone = when {
                    card.id in editor.selection.nodes || card.id in editor.selection.notes -> ink.accent
                    else -> editor.change(card.id)?.let(editor::tone) ?: ink.text.copy(alpha = .45f)
                }
                drawRect(tone, at(card.x, card.y), extent(card.width, card.height))
            }
            seen?.let { drawRect(ink.accent, at(it.x, it.y), extent(it.width, it.height), style = Stroke(1.5.dp.toPx())) }
        }
    }
}

@Composable
internal fun EditorKeys(editor: GraphEditor, request: Request.Keys) {
    val convention = remember { platformKeyConvention() }
    val defaults = remember { GraphCommands.commands + EditorCommands.commands }
    val current = CommandSet(defaults).remapped(editor.prefs.keys)
    val clashes = current.findings(convention).filterIsInstance<ChordFinding.Clash>().flatMap { clash -> clash.commands.map { it.value to clash.commands } }.toMap()
    var recording by remember { mutableStateOf<String?>(null) }
    val capture = remember { FocusRequester() }
    fun set(id: String, chord: String?) {
        editor.prefer(editor.prefs.copy(keys = if (chord == null) editor.prefs.keys - id else editor.prefs.keys + (id to chord)))
        recording = null
    }
    Popover(true, { if (!it && editor.request === request) editor.request = null }) {
        Content {
            Column(Modifier.width(420.dp).padding(8.dp).testTag("graph-editor-keys").onPreviewKeyEvent { event ->
                val id = recording ?: return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                val stroke = event.stroke() ?: return@onPreviewKeyEvent true
                when (stroke.key) {
                    KeyName.Escape -> recording = null
                    KeyName.Backspace, KeyName.Delete -> set(id, "")
                    else -> set(id, stroke.chord(convention).text())
                }
                true
            }.focusRequester(capture).focusable(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Keyboard shortcuts", role = Type.Body.strong, ink = Ink.Text)
                Text(recording?.let { id -> "Press the new keys for ${current.commands.first { it.id.value == id }.label} · Esc keeps the old ones · ⌫ removes the shortcut" }
                    ?: "Pick Change to give a command new keys. Your keys are kept for next time.", Modifier.testTag("graph-editor-keys-hint"), role = Type.Meta, ink = if (recording != null) Ink.Warn else Ink.Muted, lines = 3)
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    current.commands.distinctBy { it.label to it.chord }.forEach { command ->
                        val id = command.id.value
                        Row(Modifier.fillMaxWidth().testTag("graph-editor-key-$id"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(command.label, role = Type.Meta, ink = Ink.Text)
                                clashes[id]?.filter { it.value != id }?.mapNotNull { other -> current.commands.firstOrNull { it.id == other }?.label }?.takeIf { it.isNotEmpty() }
                                    ?.let { Text("Also used by ${it.joinToString(", ")}", role = Type.Meta, ink = Ink.Warn, lines = 2) }
                            }
                            Text(command.chord?.label(convention)?.ifEmpty { null } ?: "none", Modifier.testTag("graph-editor-chord-$id"), role = Type.Meta.code, ink = if (id in editor.prefs.keys) Ink.Warn else Ink.Muted)
                            Button({ recording = id }, Modifier.testTag("graph-editor-rekey-$id")) { Text(if (recording == id) "Press keys…" else "Change", role = Type.Meta) }
                            if (id in editor.prefs.keys) Button({ set(id, null) }, Modifier.testTag("graph-editor-unkey-$id")) { Text("Reset", role = Type.Meta) }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(recording) { if (recording != null) runCatching { capture.requestFocus() } }
}

@Composable
internal fun EditorDescribe(editor: GraphEditor, request: Request.Describe) {
    val state = remember(request) { TextFieldState() }
    val chosen = editor.selection.nodes.size
    fun send() { editor.request = null; editor.describe(state.text.toString()) }
    Popover(true, { if (!it && editor.request === request) editor.request = null }) {
        Content {
            Column(Modifier.width(380.dp).padding(8.dp).testTag("graph-editor-describe-panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (chosen == 0) "Describe a change" else "Describe a change to the $chosen selected node${if (chosen == 1) "" else "s"}", role = Type.Meta, ink = Ink.Muted)
                TextField(state, Modifier.fillMaxWidth().testTag("graph-editor-describe"), placeholder = { Text("Put a cache between the feed and its API…", ink = Ink.Muted) },
                    keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { send() })
                Text("An agent drafts the change. The draft opens here as a diff for you to review before anything is applied.", role = Type.Meta, ink = Ink.Muted, lines = 3)
                Button(::send, Modifier.testTag("graph-editor-describe-send")) { Text("Send to the agent", role = Type.Label) }
            }
        }
    }
}

@Composable
internal fun EditorRemark(editor: GraphEditor, request: Request.Remark) {
    val current = editor.placement.remarks[request.subject].orEmpty()
    val state = remember(request.subject) { TextFieldState(current, TextRange(0, current.length)) }
    Popover(true, { if (!it && editor.request === request) editor.request = null }) {
        Content {
            Column(Modifier.width(320.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Note on ${editor.node(request.subject)?.label ?: request.subject}", role = Type.Meta, ink = Ink.Muted)
                TextField(state, Modifier.fillMaxWidth().testTag("graph-editor-remark"), keyboardOptions = Submit,
                    onKeyboardAction = KeyboardActionHandler { editor.remark(request.subject, state.text.toString()); editor.request = null })
                Text("It shows above the node and travels with the draft. Leave it empty to remove it.", role = Type.Meta, ink = Ink.Muted, lines = 2)
            }
        }
    }
}

@Composable
fun GraphShelf(editor: GraphEditor, modifier: Modifier = Modifier) {
    val query = rememberTextFieldState()
    val words = query.text.toString().trim().lowercase().split(' ').filter { it.isNotEmpty() }
    val all = editor.choices
    val shown = all.filter { template -> words.all { word -> template.label.lowercase().contains(word) || template.type.lowercase().contains(word) || template.category.lowercase().contains(word) } }
    Column(modifier.testTag("graph-editor-shelf"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Add nodes", role = Type.Label, ink = Ink.Text)
        Text("Drag one onto the canvas, or pick Add to drop it in the middle of the view", role = Type.Meta, ink = Ink.Muted, lines = 2)
        TextField(query, Modifier.fillMaxWidth().testTag("graph-editor-shelf-search"), placeholder = { Text("Filter nodes…", ink = Ink.Muted) })
        shown.take(ShelfLimit).forEach { template -> ShelfRow(editor, template) }
        if (shown.size > ShelfLimit) Text("Showing $ShelfLimit of ${shown.size} · keep typing to narrow", role = Type.Meta, ink = Ink.Muted)
    }
}

@Composable
private fun ShelfRow(editor: GraphEditor, template: Template) {
    val origin = remember { floatArrayOf(0f, 0f) }
    val favourite = template.key in editor.prefs.favourites
    Row(Modifier.fillMaxWidth().onGloballyPositioned { it.positionInWindow().let { at -> origin[0] = at.x; origin[1] = at.y } }
        .pointerInput(editor, template) {
            detectDragGestures(
                onDragStart = { editor.carrying = Carry(template, Offset(origin[0], origin[1]) + it) },
                onDrag = { change, _ -> editor.carrying = editor.carrying?.copy(window = Offset(origin[0], origin[1]) + change.position) },
                onDragEnd = { editor.carrying?.let { editor.drop(it.window) } },
                onDragCancel = { editor.carrying = null },
            )
        }.testTag("graph-editor-shelf-${template.key}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(template.label, role = Type.Body, ink = Ink.Text, lines = 1)
            Text(listOfNotNull(template.category.takeIf { editor.terms.kinds || template.part != null }, template.part?.let { part -> part.changes.count { it.op == "addNode" }.let { "$it node${if (it == 1) "" else "s"}" } }).joinToString(" · "),
                role = Type.Meta, ink = Ink.Muted, lines = 1)
        }
        Button({ editor.favour(template) }, Modifier.testTag("graph-editor-favour-${template.key}")) { Text(if (favourite) "★" else "☆", role = Type.Meta, ink = if (favourite) Ink.Warn else Ink.Muted) }
        Button({ editor.camera?.seen?.let { editor.add(template, Spot(it.x + it.width / 2, it.y + it.height / 2)) } ?: run { editor.notice = "Open the canvas first" } },
            Modifier.testTag("graph-editor-shelf-add-${template.key}")) { Text("Add", role = Type.Meta) }
    }
}

internal fun presence(others: List<Holder>, locked: Boolean): String = others.firstOrNull { it.editing && it.ahead }?.takeIf { locked }?.let { "${it.name} is editing this draft · you can look, but changes wait until you take over" }
    ?: others.firstOrNull { it.editing }?.let { "${it.name} is editing too · they see it read only while you edit" }
    ?: "Also open: ${others.joinToString(", ") { it.name }}"

private const val MinimapWidth = 220
private const val MinimapHeight = 140
private const val MinimapLift = 48
private const val ShelfLimit = 40
