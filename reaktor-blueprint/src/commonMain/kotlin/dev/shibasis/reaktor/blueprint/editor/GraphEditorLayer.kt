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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
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
import dev.shibasis.reaktor.surface.compose.ToggleGroup
import dev.shibasis.reaktor.surface.compose.foundation.FlowRow
import dev.shibasis.reaktor.surface.listSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun GraphEditorLayer(editor: GraphEditor, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val measurer = remember(resolver, direction) { TextMeasurer(resolver, Density(1f), direction, cacheSize = MeasuredTexts) }
    SideEffect { editor.measurer = measurer }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        Minimap(editor)
        editor.carrying?.let { carried -> Tip("Add ${carried.template.label}", Ink.Text, editor, carried.window - origin + Offset(14f, 14f)) }
        if (editor.others.isNotEmpty()) Row(Modifier.align(Alignment.TopCenter).padding(top = if (editor.focus != null) 56.dp else 12.dp).clip(RoundedCornerShape(8.dp)).background(editor.ink.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("graph-editor-presence"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(presence(editor.others, editor.locked), role = Type.Meta, ink = if (editor.locked) Ink.Warn else Ink.Muted, lines = 2)
            if (editor.locked) Quiet("Take over", "graph-editor-take-over") { editor.takeOver() }
        }
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
        if (editor.focus != null) Crumbs(editor)
        when (val request = editor.request) {
            is Request.Menu -> EditorMenu(editor, request)
            is Request.Palette -> Anchored(request.window - origin) { EditorPalette(editor, request) }
            is Request.Rename -> Anchored(request.window - origin) { EditorRename(editor, request) }
            is Request.Naming -> Anchored(request.window - origin) { EditorNaming(editor, request) }
            is Request.Find -> Box(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 56.dp)) { EditorFind(editor) }
            is Request.Remark -> Anchored(request.window - origin) { EditorRemark(editor, request) }
            is Request.Describe -> Anchored(request.window - origin) { EditorDescribe(editor, request) }
            is Request.Keys -> Box(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 56.dp)) { EditorKeys(editor, request) }
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
private fun BoxScope.Crumbs(editor: GraphEditor) {
    Row(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).background(editor.ink.surface).padding(horizontal = 8.dp, vertical = 4.dp)
        .testTag("graph-editor-crumbs"), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Quiet("Whole graph", "graph-editor-crumb-root") { editor.open(null) }
        editor.crumbs.forEach { note ->
            Text("›", role = Type.Meta, ink = Ink.Muted)
            Quiet(note.text, "graph-editor-crumb-${note.id}") { editor.open(note.id) }
        }
        Text("Esc goes up", role = Type.Meta, ink = Ink.Muted)
    }
}

@Composable
private fun Anchored(at: Offset, content: @Composable () -> Unit) {
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) { content() }
}

@Composable
private fun EditorMenu(editor: GraphEditor, request: Request.Menu) {
    Menu(true, { if (!it && editor.request === request) editor.request = null }, anchor = OverlayAnchor.Point(request.window)) {
        Popup {
            fun run(action: () -> Unit) { editor.request = null; action() }
            when (val target = request.target) {
                is Target.OnCard -> CardEntries(editor, request, target.id, ::run)
                is Target.OnPin -> {
                    val links = editor.links(target.pin)
                    Entry("unlink-pin", "Break ${links.size} link${if (links.size == 1) "" else "s"} on ${editor.node(target.pin.node)?.let { editor.terms.port(it, target.pin.key) } ?: target.pin.key}", "⌥ click", links.isNotEmpty()) { run { editor.breakLinks(target.pin) } }
                    Entry("add", "Add a connected node…", null) { run { editor.request = Request.Palette(request.at, request.window, target.pin) } }
                    if (editor.runs != null) Entry("watch", if (target.pin in editor.watched) "Stop watching this value" else "Watch this value", null) { run { editor.watch(target.pin) } }
                    if (links.size > 1) Entry("bundle", "Bundle the ${links.size} links through one knot", null) { run { editor.bundle(target.pin) } }
                    links.take(GoLimit).forEach { wire ->
                        val (far, key) = if (target.pin.provides) wire.consumer to wire.consumerPort else wire.provider to wire.providerPort
                        Entry("go-${wire.id}", "Go to ${editor.node(far)?.let { editor.terms.end(it, key) } ?: far}", null) { run { editor.reveal(editor.shape.visible(far) ?: far) } }
                    }
                    Separator()
                    CardEntries(editor, request, target.pin.node, ::run)
                }
                is Target.OnWire -> {
                    Entry("delete", "Delete wire", "⌫") { run { editor.delete(Selection(wires = setOf(target.id))) } }
                    Entry("bend", "Add a reroute point", "double-click") { run { editor.bend(target.id, request.at) } }
                    Entry("unbend", "Remove reroute points", null, editor.placement.bends.containsKey(target.id)) { run { editor.edit { s, p -> s to p.copy(bends = p.bends - target.id) } } }
                }
                is Target.OnNote -> {
                    val shut = target.id in editor.folded
                    if (editor.terms.subgraphs || shut) Entry("fold", if (shut) "Expand" else "Fold into one card", if (shut) "double-click" else null) { run { if (shut) editor.unfold(target.id) else editor.fold(target.id) } }
                    Entry("focus", "Open on its own", null) { run { editor.open(target.id) } }
                    Separator()
                    Entry("rename", "Rename comment", "double-click") { run { editor.request = Request.Rename(request.at, request.window, target.id, note = true) } }
                    Submenu("colour", trigger = { Text("Colour") }) {
                        NoteTints.forEach { (name, tint) -> Entry("tint-$name", name, null) { run { editor.recolour(target.id, tint) } } }
                    }
                    Entry("delete", "Delete comment", "⌫") { run { editor.delete(Selection(notes = setOf(target.id))) } }
                }
                Target.Empty -> {
                    Entry("add", "Add a node…", "Space") { run { editor.request = Request.Palette(request.at, request.window) } }
                    Entry("paste", "Paste", "⌘V") { run { editor.paste(request.at) } }
                    if (editor.onDescribe != null) Entry("describe", "Describe a change…", "/") { run { editor.request = Request.Describe(request.at, request.window) } }
                    Entry("minimap", if (editor.prefs.minimap) "Hide the minimap" else "Show the minimap", "M") { run { editor.prefer(editor.prefs.copy(minimap = !editor.prefs.minimap)) } }
                    Entry("keys", "Keyboard shortcuts…", "⌘/") { run { editor.request = Request.Keys(request.at, request.window) } }
                    if (editor.focus != null) Entry("up", "Back to the whole graph", "Esc") { run { editor.open(null) } }
                }
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
    Entry("upstream", editor.terms.upstream, null) { run { editor.selectLinked(upstream = true) } }
    Entry("downstream", editor.terms.downstream, null) { run { editor.selectLinked(upstream = false) } }
    Entry("comment", "Comment the selection", "C") { run { editor.comment() } }
    editor.templateOf(id)?.let { template -> Entry("favour", if (template.key in editor.prefs.favourites) "Remove ${template.label} from favourites" else "Add ${template.label} to favourites", null) { run { editor.favour(template) } } }
    if (!many) Entry("remark", if (editor.placement.remarks.containsKey(id)) "Edit the note…" else "Add a note…", "⌥C") { run { editor.request = Request.Remark(request.at, request.window, id) } }
    if (editor.onDescribe != null) Entry("describe", "Describe a change to ${if (many) "these" else "this"}…", "/") { run { editor.request = Request.Describe(request.at, request.window) } }
    editor.node(id)?.part?.let { part ->
        val name = editor.saved.firstOrNull { it.id == part }?.title ?: "the part"
        if (editor.onTemplate != null) Entry("keep-part", "Keep this as the new version of $name", null) { run { editor.keepPart(id) } }
        val stale = editor.stale(part).size
        Entry("update-uses", "Update every use of $name" + if (stale > 0) " · $stale" else "", null, stale > 0) { run { editor.updateUses(part) } }
    }
    if (editor.terms.subgraphs) Entry("group", "Group into a new ${editor.terms.scope}…", null) { run { editor.request = Request.Naming(request.at, request.window, graph = true) } }
    if (editor.onTemplate != null) Entry("template", "Save as a template…", null) { run { editor.request = Request.Naming(request.at, request.window, graph = false) } }
    Entry("straighten", "Straighten connections", "Q") { run { editor.straighten() } }
    if (many) Entry("tidy", "Tidy up", "⌥⇧T") { run { editor.tidy() } }
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
    val all = editor.choices
    var category by remember { mutableStateOf<String?>(null) }
    val favourites = editor.prefs.favourites
    val categories = remember(all, favourites) { listOfNotNull(FavouriteCategory.takeIf { favourites.isNotEmpty() }, RecentCategory.takeIf { GraphEditor.recent.isNotEmpty() }) + all.map { it.category }.distinct() }
    val matching = remember(words, pin, all, category, favourites) {
        all.filter { template ->
            (category == null || when (category) { RecentCategory -> template.key in GraphEditor.recent; FavouriteCategory -> template.key in favourites; else -> template.category == category }) && template.offers(pin, editor.terms::fits) &&
                words.all { word -> template.label.lowercase().contains(word) || template.type.lowercase().contains(word) || template.category.lowercase().contains(word) || template.ports.any { it.key.lowercase().contains(word) } }
        }.sortedWith(compareBy({ favourites.indexOf(it.key).let { at -> if (at < 0) Int.MAX_VALUE else at } }, { GraphEditor.recent.indexOf(it.key).let { at -> if (at < 0) Int.MAX_VALUE else at } }))
    }
    val choices = matching.take(PaletteLimit)
    fun choose(template: Template) { editor.request = null; editor.add(template, request.at, request.from) }
    Popover(true, { if (!it && editor.request === request) editor.request = null }) {
        Content {
            Column(Modifier.width(340.dp).padding(8.dp).testTag("graph-editor-palette"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pin?.let { "Nodes that ${if (it.provides) "need" else "provide"} ${it.type}" } ?: "Add a node", role = Type.Meta, ink = Ink.Muted, lines = 2)
                TextField(query, Modifier.fillMaxWidth().testTag("graph-editor-search"), placeholder = { Text("Search nodes, types and ports…", ink = Ink.Muted) },
                    keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { choices.firstOrNull()?.let(::choose) })
                if (categories.size > 1) ToggleGroup(setOf(category ?: AllCategory), { next -> category = (next - (category ?: AllCategory)).firstOrNull()?.takeIf { it != AllCategory } },
                    Modifier.fillMaxWidth().testTag("graph-editor-categories")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (listOf(AllCategory) + categories).forEach { key -> Item(key, Modifier.testTag("graph-editor-category-$key")) { Text(key, role = Type.Meta) } }
                    }
                }
                if (choices.isEmpty()) Text("Nothing matches.", role = Type.Meta, ink = Ink.Muted)
                if (matching.size > choices.size) Text("Showing ${choices.size} of ${matching.size} · keep typing to narrow", role = Type.Meta, ink = Ink.Muted)
                ListBox(listSource(choices, { it.key }, text = { it.label }, activateOnPress = { true }), emptySet(), {},
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    onActivate = { key -> choices.firstOrNull { it.key == key }?.let(::choose) }) { template ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(template.label, role = Type.Body, ink = Ink.Text)
                        val ports = template.part?.let { part -> part.changes.count { it.op == "addNode" }.let { "$it node${if (it == 1) "" else "s"}" } }
                            ?: template.joins(pin, editor.terms::fits)?.let { "connects ${it.key}" } ?: "${template.ports.count { !it.provides }} in · ${template.ports.count { it.provides }} out"
                        val meta = listOfNotNull("★".takeIf { template.key in favourites }, template.category.takeIf { editor.terms.kinds || template.part != null }, ports.takeIf { editor.terms.types || template.part != null }, "recent".takeIf { template.key in GraphEditor.recent })
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), role = Type.Meta, ink = Ink.Muted)
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
private fun EditorFind(editor: GraphEditor) {
    val query = remember { TextFieldState(editor.sought, TextRange(0, editor.sought.length)) }
    val text = query.text.toString()
    val hits by remember(editor) { derivedStateOf { editor.find(query.text.toString()) } }
    var current by remember { mutableStateOf<String?>(null) }
    val at = hits.indexOfFirst { it.id == current }
    SideEffect { editor.sought = text }
    LaunchedEffect(hits) { editor.found = hits.mapTo(hashSetOf()) { it.id } }
    DisposableEffect(editor) { onDispose { editor.found = emptySet() } }
    fun go(i: Int) { current = hits[i].id; editor.reveal(hits[i].id) }
    Popover(true, { if (!it && editor.request is Request.Find) editor.request = null }) {
        Content {
            Column(Modifier.width(340.dp).padding(8.dp).testTag("graph-editor-find"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(query, Modifier.fillMaxWidth().testTag("graph-editor-find-field"), placeholder = { Text("Find nodes, ports and comments…", ink = Ink.Muted) },
                    keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { if (hits.isNotEmpty()) go((hits.indexOfFirst { it.id == current } + 1) % hits.size) })
                Text(when {
                    text.isBlank() -> "Enter steps through the matches"
                    hits.isEmpty() -> "Nothing matches."
                    at < 0 -> "${hits.size} match${if (hits.size == 1) "" else "es"} · Enter jumps to the first"
                    else -> "${at + 1} of ${hits.size}"
                }, Modifier.testTag("graph-editor-find-count"), role = Type.Meta, ink = Ink.Muted)
                if (hits.isNotEmpty()) ListBox(listSource(hits.take(FindLimit), { it.id }, text = { it.title }, activateOnPress = { true }), setOfNotNull(hits.getOrNull(at)?.id), {},
                    Modifier.fillMaxWidth().heightIn(max = 320.dp), onActivate = { key -> hits.indexOfFirst { it.id == key }.takeIf { it >= 0 }?.let(::go) }) { hit ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(hit.title, role = Type.Body, ink = Ink.Text)
                        if (hit.detail.isNotEmpty()) Text(hit.detail, role = Type.Meta, ink = Ink.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun Quiet(label: String, tag: String, action: () -> Unit) =
    dev.shibasis.reaktor.surface.compose.Button(action, Modifier.testTag(tag)) { Text(label, role = Type.Label) }

@Composable
private fun EditorNaming(editor: GraphEditor, request: Request.Naming) {
    val state = remember(request) { TextFieldState() }
    fun done() {
        val name = state.text.toString().trim()
        if (name.isEmpty()) { editor.notice = "Type a name first"; return }
        editor.request = null
        if (request.graph) editor.group(name) else editor.template(name)
    }
    Popover(true, { if (!it && editor.request === request) editor.request = null }) {
        Content {
            Column(Modifier.width(300.dp).padding(8.dp).testTag("graph-editor-naming-panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (request.graph) "Name the new ${editor.terms.scope}" else "Name the template", role = Type.Meta, ink = Ink.Muted)
                TextField(state, Modifier.fillMaxWidth().testTag("graph-editor-naming"), keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { done() })
                Text(if (request.graph) "The ${editor.selection.nodes.size} selected nodes move into it" else "It appears under Templates when you add a node", role = Type.Meta, ink = Ink.Muted, lines = 2)
            }
        }
    }
}

@Composable
private fun EditorRename(editor: GraphEditor, request: Request.Rename) {
    val current = if (request.note) editor.placement.notes.firstOrNull { it.id == request.subject }?.text else editor.node(request.subject)?.label
    val state: TextFieldState = remember(request.subject) { current.orEmpty().let { TextFieldState(it, TextRange(0, it.length)) } }
    Popover(true, { if (!it && editor.request === request) editor.request = null }) {
        Content {
            Column(Modifier.width(280.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (request.note) "Comment" else "Name", role = Type.Meta, ink = Ink.Muted)
                TextField(state, Modifier.fillMaxWidth().testTag("graph-editor-rename"),
                    keyboardOptions = Submit, onKeyboardAction = KeyboardActionHandler { editor.rename(request.subject, state.text.toString()); editor.request = null })
            }
        }
    }
}

private const val PaletteLimit = 80
private const val AllCategory = "All"
private const val RecentCategory = "Recent"
private const val FavouriteCategory = "Favourites"
private const val FindLimit = 200
private const val GoLimit = 6
private const val MeasuredTexts = 256

val NoteTints = listOf(
    "Plain" to 0L,
    "Blue" to 0xFF4C8DFF,
    "Green" to 0xFF3FB97A,
    "Amber" to 0xFFE0A33A,
    "Red" to 0xFFE5534B,
    "Violet" to 0xFF9B6BFF,
)
