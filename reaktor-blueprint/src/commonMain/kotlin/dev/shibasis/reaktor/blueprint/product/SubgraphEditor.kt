package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.GraphSource
import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.blueprint.Layered
import dev.shibasis.reaktor.blueprint.LayeredEdge
import dev.shibasis.reaktor.blueprint.LayeredNode
import dev.shibasis.reaktor.blueprint.LayeredSpacing
import dev.shibasis.reaktor.blueprint.canvas.*
import dev.shibasis.reaktor.graph.selection.*
import dev.shibasis.reaktor.surface.*
import dev.shibasis.reaktor.surface.compose.*
import kotlinx.coroutines.delay
import kotlin.math.*

@Composable
fun SubgraphEditor(
    graph: SearchGraph,
    documents: List<Subgraph>,
    appearance: SurfaceGraphAppearance,
    newId: () -> String,
    put: (Subgraph, Int) -> Unit,
    modifier: Modifier = Modifier,
    space: Dp = 8.dp,
    sourceGraph: GraphShape? = null,
    sourceLayout: BlueprintLayout<String>? = null,
    sourceCardLook: (@Composable (NodeShape) -> BlueprintCardLook)? = null,
    sourceFrameLook: (@Composable (Frame) -> BlueprintFrameLook)? = null,
    sourceLinkStyle: ((Link, Selection) -> LinkStyle?)? = null,
    sourceLinkLabel: ((Link) -> String?)? = null,
    canvas: GraphCanvasState? = null,
) {
    require(listOf(sourceGraph, sourceLayout, sourceCardLook, sourceFrameLook, sourceLinkStyle, sourceLinkLabel).let { inputs ->
        inputs.all { it == null } || inputs.all { it != null }
    }) { "A supplied map requires its complete typed graph, layout and presentation" }
    var draft by remember(graph.id) { mutableStateOf<Subgraph?>(null) }
    var base by remember(graph.id) { mutableStateOf(0) }
    var notice by remember(graph.id) { mutableStateOf("") }
    var saved by remember(graph.id) { mutableStateOf(false) }
    var offered by remember(graph.id) { mutableStateOf(emptySet<String>()) }
    var selected by remember(graph.id) { mutableStateOf<String?>(null) }
    var connection by remember(graph.id) { mutableStateOf<String?>(null) }
    var library by remember(graph.id) { mutableStateOf(false) }
    val query = remember(graph.id) { TextFieldState() }
    val intent = remember(graph.id) { TextFieldState() }
    val name = remember(graph.id) { TextFieldState() }
    val rememberedCamera = remember(graph.id) { GraphCanvasState() }
    val camera = canvas ?: rememberedCamera
    val byId = remember(graph) { graph.nodes.associateBy { it.id } }
    val views = documents.filter { it.graphId == graph.id }.sortedBy { it.title }
    fun load(value: Subgraph) {
        draft = value; base = value.revision; offered = value.nodes.toSet()
        name.setTextAndPlaceCursorAtEnd(value.title); intent.setTextAndPlaceCursorAtEnd(value.intent)
        query.setTextAndPlaceCursorAtEnd("")
        selected = null; connection = null; library = false; notice = ""
    }
    fun clear() {
        draft = null; base = 0; offered = emptySet(); selected = null; connection = null; library = false; notice = ""
        name.setTextAndPlaceCursorAtEnd(""); query.setTextAndPlaceCursorAtEnd(""); intent.setTextAndPlaceCursorAtEnd("")
    }
    fun search() {
        if (query.text.isBlank()) return
        runCatching {
            val found = graph.query(SubgraphQuery(query.text.toString(), limit = 80))
            if (draft == null && found.nodes.isNotEmpty()) {
                val title = query.text.toString().trim().take(72)
                draft = found.proposal(newId(), title, query.text.toString())
                name.setTextAndPlaceCursorAtEnd(title); intent.setTextAndPlaceCursorAtEnd(query.text.toString()); base = 0
            }
            offered = offered + found.nodes.map { it.id }; selected = null; connection = null; library = false
            notice = if (found.nodes.isEmpty()) "No matches. Try another topic, or ask your agent to propose a view."
                else if (found.truncated) "Showing 80 candidates. Narrow your query for a smaller view." else ""
            query.setTextAndPlaceCursorAtEnd("")
        }.onFailure { notice = it.message.orEmpty() }
    }
    fun toggle(id: String) {
        val current = draft ?: return
        val nodes = if (id in current.nodes) current.nodes - id else current.nodes + id
        val edges = graph.edges.filter { it.from in nodes && it.to in nodes && (current.edges == null || it.id in current.edges.orEmpty() || (id !in current.nodes && (it.from == id || it.to == id))) }.map { it.id }
          draft = current.copy(nodes = nodes, edges = edges, reasons = current.reasons.filterKeys { it in nodes },
              groups = current.groups.map { group -> group.copy(nodes = group.nodes.filter { it in nodes }) }.filter { it.nodes.isNotEmpty() })
    }
    fun toggleEdge(id: String) {
        val current = draft ?: return
        val included = current.edges ?: graph.edges.filter { it.from in current.nodes && it.to in current.nodes }.map { it.id }
        draft = current.copy(edges = if (id in included) included - id else included + id)
    }
    fun persist(status: String) {
        runCatching {
            val current = requireNotNull(draft)
            require((documents.firstOrNull { it.id == current.id }?.revision ?: 0) == base) { "This view changed elsewhere. Reopen its latest version before saving." }
            val next = current.copy(title = name.text.toString().ifBlank { current.title }, intent = intent.text.toString(),
                sourceRevision = graph.revision, status = status, revision = base + 1)
            graph.validate(next); put(next, base); load(next); saved = true
        }.onFailure { notice = it.message.orEmpty() }
    }
    LaunchedEffect(saved) { if (saved) { delay(2200); saved = false } }
    val current = draft
    val resolved = current?.let { runCatching { graph.resolve(it) }.getOrNull() }
    val changed = current != null && (current.status != "saved" || current != documents.firstOrNull { it.id == current.id } || name.text.toString() != current.title || intent.text.toString() != current.intent)
    val nodes = remember(graph, offered) { graph.nodes.filter { it.id in offered } }
    val links = remember(graph, offered) { graph.edges.filter { it.from in offered && it.to in offered } }
    val node = byId[selected]
    val edge = graph.edges.firstOrNull { it.id == connection }
    val field = remember(appearance, space) { SubgraphFieldAppearance(appearance, space) }
    val shape = RoundedCornerShape(space * 2)
    @Composable fun queryField(modifier: Modifier) {
        TextField(query, modifier.testTag("subgraph-query"), label = "Explore a subgraph",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = KeyboardActionHandler { search() }, appearance = field,
            placeholder = { SubgraphText(if (current == null) "What would you like to explore?" else "Find or add something…", appearance, muted = true, size = 15) },
            trailing = {
                if (query.text.isNotBlank()) Button(::search, Modifier.size(space * 4).semantics { contentDescription = "Explore" }, appearance = SubgraphPlainButton) {
                    Canvas(Modifier.size(space * 2)) {
                        drawLine(appearance.text, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .8f, size.height * .5f), 1.5.dp.toPx(), StrokeCap.Round)
                        drawLine(appearance.text, Offset(size.width * .55f, size.height * .2f), Offset(size.width * .85f, size.height * .5f), 1.5.dp.toPx(), StrokeCap.Round)
                        drawLine(appearance.text, Offset(size.width * .55f, size.height * .8f), Offset(size.width * .85f, size.height * .5f), 1.5.dp.toPx(), StrokeCap.Round)
                    }
                }
            })
    }
    BoxWithConstraints(modifier.testTag("subgraph-editor").background(appearance.canvas)) {
        val narrow = maxWidth < space * 75
        if (nodes.isNotEmpty() && current != null) {
            if (sourceGraph != null) SuppliedSubgraphBlueprint(sourceGraph, requireNotNull(sourceLayout),
                requireNotNull(sourceCardLook), requireNotNull(sourceFrameLook), requireNotNull(sourceLinkStyle), requireNotNull(sourceLinkLabel),
                graph, current, selected, connection, appearance, camera,
                { selected = it; connection = null; library = false },
                { connection = it; selected = null; library = false },
                { selected = null; connection = null; library = false },
                Modifier.fillMaxSize(), space, if (narrow) space * 16 else space * 10)
            else SubgraphBlueprint(nodes, links, current, selected, connection, appearance, camera,
                { selected = it; connection = null; library = false },
                { connection = it; selected = null; library = false },
                { selected = null; connection = null; library = false },
                Modifier.fillMaxSize(), space, if (narrow) space * 16 else space * 10)
        } else {
            Column(Modifier.align(Alignment.Center).widthIn(max = space * 76).padding(space * 3), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(space * 3)) {
                SubgraphEmptyMark(appearance, Modifier.size(space * 20, space * 12))
                SubgraphText("Follow an idea.", appearance, size = 28)
                queryField(Modifier.fillMaxWidth())
                SubgraphText("Explore a topic, or open a view proposed by your agent.", appearance, muted = true, size = 13, lines = 3)
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = space * 2, vertical = space), verticalArrangement = Arrangement.spacedBy(space)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space)) {
                Button({ library = !library; selected = null; connection = null }, Modifier.weight(if (narrow || current == null) 1f else .4f).heightIn(min = space * 6).semantics { contentDescription = "Views" }, appearance = SubgraphPlainButton) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space)) {
                        SubgraphText(current?.let { name.text.toString().ifBlank { it.title } } ?: "Your views", appearance, size = 17, lines = 1)
                        Canvas(Modifier.size(space * 1.5f)) {
                            drawPath(Path().apply { moveTo(1f, size.height * .35f); lineTo(size.width / 2, size.height * .7f); lineTo(size.width - 1, size.height * .35f) }, appearance.muted, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }
                if (current != null && !narrow) queryField(Modifier.weight(.6f).widthIn(max = space * 70))
                if (saved) SubgraphText("Saved", appearance, muted = true, size = 12)
                if (changed) SubgraphAction("Save", appearance, Modifier.testTag("subgraph-save"), primary = true, label = "Save subgraph") { persist("saved") }
            }
            if (current != null && narrow) queryField(Modifier.fillMaxWidth())
            if (notice.isNotBlank()) SubgraphText(notice, appearance, lines = 3, modifier = Modifier.testTag("subgraph-notice"))
        }
        if (current != null) {
            Row(Modifier.align(Alignment.BottomStart).padding(space * 2), horizontalArrangement = Arrangement.spacedBy(space), verticalAlignment = Alignment.CenterVertically) {
                SubgraphText((resolved?.nodes?.size ?: 0).toString() + " nodes · " + (resolved?.edges?.size ?: 0) + if (resolved?.edges?.size == 1) " relationship" else " relationships", appearance, muted = true, size = 12)
                if (resolved?.stale == true) SubgraphText("Source updated", appearance, muted = true, size = 12)
            }
            if (!narrow && selected == null && connection == null && !library) SubgraphText("Scroll to pan · pinch or Cmd/Ctrl+scroll to zoom", appearance, Modifier.align(Alignment.BottomEnd).padding(space * 2), muted = true, size = 11)
        }
        if (library) {
            Column(Modifier.align(Alignment.TopStart).padding(start = space * 2, top = space * 8).widthIn(max = space * 40).fillMaxWidth().heightIn(max = maxHeight - space * 11).clip(shape).background(appearance.card).border(1.dp, appearance.line.copy(alpha = .5f), shape).padding(space * 2), verticalArrangement = Arrangement.spacedBy(space * 1.5f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SubgraphText("Your views", appearance, strong = true)
                    SubgraphAction("New view", appearance) { clear() }
                }
                if (views.isEmpty()) SubgraphText("Saved views will appear here.", appearance, muted = true, lines = 2)
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(space)) {
                    items(views, key = { it.id }) { document ->
                        Button({ load(document) }, Modifier.fillMaxWidth().padding(vertical = space).semantics { contentDescription = document.title + " · " + document.status + " · r" + document.revision }, appearance = SubgraphPlainButton) {
                            Column(verticalArrangement = Arrangement.spacedBy(space / 2)) {
                                SubgraphText(document.title, appearance, strong = document.id == current?.id, lines = 2)
                                SubgraphText(document.nodes.size.toString() + " nodes" + if (document.status == "draft") " · draft" else "", appearance, muted = true, size = 12)
                            }
                        }
                    }
                }
                if (current != null) {
                    TextField(name, Modifier.fillMaxWidth().testTag("subgraph-name"), label = "View name", appearance = field)
                    TextField(intent, Modifier.fillMaxWidth(), label = "View purpose", appearance = field, placeholder = { SubgraphText("Purpose", appearance, muted = true) })
                    Row(horizontalArrangement = Arrangement.spacedBy(space)) {
                        SubgraphAction("Fit to view", appearance) { camera.fit(camera.extent, padding = .1); library = false }
                        SubgraphAction("Keep as draft", appearance) { persist("draft") }
                    }
                    if (resolved != null && (resolved.missingNodes.isNotEmpty() || resolved.missingEdges.isNotEmpty())) {
                        SubgraphText("Some members are no longer available.", appearance, muted = true, lines = 2)
                          SubgraphAction("Remove unavailable members", appearance) {
                              val present = resolved.nodes.map { it.id }
                              draft = current.copy(nodes = present, edges = resolved.edges.map { it.id }, reasons = current.reasons.filterKeys { it in present },
                                  groups = current.groups.map { group -> group.copy(nodes = group.nodes.filter { it in present }) }.filter { it.nodes.isNotEmpty() })
                          }
                    }
                }
            }
        }
        if (current != null && (node != null || edge != null)) {
            Column(Modifier.align(if (narrow) Alignment.BottomCenter else Alignment.CenterEnd).padding(space * 2).widthIn(max = space * 38).fillMaxWidth().heightIn(max = maxHeight * .6f).clip(shape).background(appearance.card).border(1.dp, appearance.line.copy(alpha = .5f), shape).padding(space * 2), verticalArrangement = Arrangement.spacedBy(space * 1.5f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SubgraphText(node?.kind?.replaceFirstChar { it.uppercase() } ?: "Connection", appearance, muted = true, size = 12)
                    Button({ selected = null; connection = null }, Modifier.size(space * 3).semantics { contentDescription = "Close details" }, appearance = SubgraphPlainButton) {
                        Canvas(Modifier.size(space * 1.5f)) {
                            drawLine(appearance.muted, Offset.Zero, Offset(size.width, size.height), 1.5.dp.toPx(), StrokeCap.Round)
                            drawLine(appearance.muted, Offset(0f, size.height), Offset(size.width, 0f), 1.5.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
                if (node != null) {
                    SubgraphText(node.label, appearance, strong = true, size = 18, lines = 3)
                    current.reasons[node.id]?.let { SubgraphText(it, appearance, muted = true, lines = 3) }
                    val included = node.id in current.nodes
                    SubgraphAction(if (included) "Remove from view" else "Add to view", appearance, label = (if (included) "Remove " else "Add ") + node.label) { toggle(node.id) }
                    val connections = graph.edges.filter { (it.from == node.id && it.to in current.nodes) || (it.to == node.id && it.from in current.nodes) }
                    if (included && connections.isNotEmpty()) {
                        SubgraphText("Connections", appearance, muted = true, size = 12)
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(space)) {
                            items(connections, key = { it.id }) { link ->
                                val kept = current.edges == null || link.id in current.edges.orEmpty()
                                Button({ toggleEdge(link.id) }, Modifier.fillMaxWidth().padding(vertical = space).semantics { contentDescription = (if (kept) "Exclude " else "Include ") + link.label.ifBlank { link.kind } + " · " + link.from + " → " + link.to }, appearance = SubgraphPlainButton) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space)) {
                                        Canvas(Modifier.size(space * 2)) {
                                            drawCircle(if (kept) appearance.selected else appearance.line, size.minDimension / 3, center, style = if (kept) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.dp.toPx()))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            SubgraphText(byId[if (link.from == node.id) link.to else link.from]?.label.orEmpty(), appearance, lines = 2)
                                            SubgraphText(link.label.ifBlank { link.kind }, appearance, muted = true, size = 11)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (edge != null) {
                    SubgraphText(byId[edge.from]?.label.orEmpty(), appearance, strong = true, lines = 2)
                    SubgraphText(edge.label.ifBlank { edge.kind }, appearance, muted = true)
                    SubgraphText(byId[edge.to]?.label.orEmpty(), appearance, strong = true, lines = 2)
                    val kept = current.edges == null || edge.id in current.edges.orEmpty()
                    SubgraphAction(if (kept) "Remove connection" else "Restore connection", appearance) { toggleEdge(edge.id) }
                }
            }
        }
    }
}

@Composable
private fun SuppliedSubgraphBlueprint(source: GraphShape, full: BlueprintLayout<String>,
    cardLook: @Composable (NodeShape) -> BlueprintCardLook, frameLook: @Composable (Frame) -> BlueprintFrameLook,
    linkStyle: (Link, Selection) -> LinkStyle?, linkLabel: (Link) -> String?, graph: SearchGraph, document: Subgraph,
    selected: String?, connection: String?, palette: SurfaceGraphAppearance, state: GraphCanvasState,
    onNode: (String) -> Unit, onEdge: (String) -> Unit, onClear: () -> Unit, modifier: Modifier, space: Dp, top: Dp) {
    val identities = remember(graph, source, full) {
        require(source.nodes.map { it.id }.toSet().size == source.nodes.size && graph.nodes.map { it.id }.toSet().size == graph.nodes.size) {
            "Source member identities must be unique"
        }
        val attached = source.nodes.filter { it.attachedTo != null && it.kind != NodeKind.Route }.groupBy { it.attachedTo }
        require(attached.values.all { it.size == 1 }) { "A supplied route requires one authoritative member per endpoint" }
        val projections = listOf(GraphSource.System, GraphSource.Desktop).map { AppGraph(source, it) }
        val routes = projections.first().hops.associateBy { "route:${it.id}" }
        require(graph.edges.map { it.id }.toSet().size == graph.edges.size) { "Source relationship identities must be unique" }
        require(source.wires.map { it.id }.toSet().size == source.wires.size) { "Typed wire identities must be unique" }
        val mapped = graph.edges.associate { edge ->
            val raw = if (edge.kind == "route") {
                val hop = requireNotNull(routes[edge.id]) { "A route relationship requires supplied route authority" }
                require(edge.from == hop.from && edge.to == hop.to) { "Directed route endpoints must match the source relationship" }
                hop.id
            } else edge.id
            edge.id to raw
        }
        require(mapped.values.toSet().size == mapped.size) { "Source relationships must resolve to distinct raw members" }
        require(projections.any { view ->
            graph.nodes.map { it.id }.toSet() == view.nodes.keys && graph.edges.size == view.wires.size + view.hops.size &&
                view.wires.all { wire -> graph.edges.any { it.kind == "wire" && it.id == wire.id && it.from == wire.provider && it.to == wire.consumer } } &&
                view.hops.all { hop -> graph.edges.any { it.kind == "route" && it.id == "route:${hop.id}" && it.from == hop.from && it.to == hop.to } }
        }) { "The search members and directed relationships must match one complete supplied source projection" }
        val members = full.links.flatMap { it.members }.toSet() + full.foldedLinks.keys
        require(mapped.values.all { it in members }) { "The full layout must retain every source relationship identity" }
        mapped
    }
    val edges = remember(graph, identities) { graph.edges.associateBy { identities.getValue(it.id) } }
    val sourceNodes = remember(source) { source.nodes.associateBy { it.id } }
    val related = remember(selected, graph) { graph.edges.filter { it.from == selected || it.to == selected }
        .flatMap { listOf(it.from, it.to) }.toSet() }
    val selection = Selection(nodes = selected?.let(::setOf).orEmpty(), wires = connection?.let { setOf(identities.getValue(it)) }.orEmpty())
    SideEffect {
        state.insetTop = top.value.toDouble(); state.insetBottom = (space * 8).value.toDouble()
        state.insetStart = (space * 3).value.toDouble(); state.insetEnd = (space * 3).value.toDouble()
    }
    dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface(
        dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant.Editor,
        dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity.Compact,
        mode = if (palette.canvas.luminance() > .5f) dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode.Light else dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode.Dark) {
        val faint = Blueprint.Faint
        BlueprintCanvas(full, state, selection.nodes, selected,
            linkStyle = { link -> linkStyle(link, selection)?.let { style ->
                val members = link.members.mapNotNull(edges::get)
                val included = members.all { it.from in document.nodes && it.to in document.nodes && (document.edges == null || it.id in document.edges.orEmpty()) }
                if (included) style else style.copy(alpha = style.alpha * .1f)
            } ?: LinkStyle(faint) },
            frameLook = frameLook,
            cardLook = { card -> sourceNodes[card.id]?.let { node -> cardLook(node).let { look -> look.copy(tag = "subgraph-node:${node.id}", action = look.action ?: node.label,
                selected = node.id == selected, muted = look.muted || node.id !in document.nodes || selected != null && node.id != selected && node.id !in related) } } },
            onCardClick = onNode,
            onLinkClick = { link -> val edge = link.members.singleOrNull()?.let(edges::get); if (edge != null) onEdge(edge.id) else onClear() },
            onPaneClick = onClear,
            modifier = modifier,
            labels = full.links.mapNotNull { link -> linkLabel(link)?.let { link.id to it } }.toMap(),
            onPinClick = { card, _ -> onNode(card.id) },
            focusable = true,
            editable = false)
    }
}

@Composable
private fun SubgraphBlueprint(nodes: List<SearchNode>, links: List<SearchEdge>, document: Subgraph, selected: String?, connection: String?,
    palette: SurfaceGraphAppearance, state: GraphCanvasState, onNode: (String) -> Unit, onEdge: (String) -> Unit,
    onClear: () -> Unit, modifier: Modifier, space: Dp, top: Dp) {
    val model = remember(nodes, links, palette.width) { subgraphBlueprint(nodes, links, palette) }
    val related = remember(selected, links) { links.filter { it.from == selected || it.to == selected }.flatMap { listOf(it.from, it.to) }.toSet() }
    val selectNode by rememberUpdatedState(onNode)
    val selectEdge by rememberUpdatedState(onEdge)
    val clear by rememberUpdatedState(onClear)
    SideEffect {
        state.insetTop = top.value.toDouble(); state.insetBottom = (space * 8).value.toDouble()
        state.insetStart = (space * 3).value.toDouble(); state.insetEnd = (space * 3).value.toDouble()
        val next = dev.shibasis.reaktor.blueprint.editor.Selection(nodes = selected?.let(::setOf).orEmpty(), wires = connection?.let(model::wire)?.let(::setOf).orEmpty())
        model.editor.onSelect = {}
        if (next != model.editor.selection) model.editor.select(next)
        model.editor.onSelect = { id ->
            val wire = model.editor.selection.wires.singleOrNull()?.let(model::link)
            when { id != null -> selectNode(id); wire != null -> selectEdge(wire.id); else -> clear() }
        }
    }
    dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface(
        dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant.Editor,
        dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity.Compact,
        mode = if (palette.canvas.luminance() > .5f) dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode.Light else dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode.Dark) {
        dev.shibasis.reaktor.blueprint.BlueprintGraph(model.editor, state,
            cardLook = { node -> model.look(node.id, palette).copy(tag = "subgraph-node:${node.id}", action = node.label, muted = node.id !in document.nodes || selected != null && node.id != selected && node.id !in related) },
            groupLook = { dev.shibasis.reaktor.blueprint.BlueprintFrameLook(SurfaceGraphSymbol, "") },
            linkStyle = { link -> model.link(link.id)?.let { edge ->
                val included = edge.from in document.nodes && edge.to in document.nodes && (document.edges == null || edge.id in document.edges.orEmpty())
                dev.shibasis.reaktor.blueprint.Blueprint.link(palette.selected, selected != null || connection != null,
                    edge.id == connection || selected != null && (edge.from == selected || edge.to == selected)).let { if (included) it else it.copy(alpha = .1f) }
            } },
            linkLabel = { model.link(it.id)?.label?.takeIf(String::isNotBlank) }, modifier = modifier)
    }
}

internal fun subgraphBlueprint(nodes: List<SearchNode>, links: List<SearchEdge>, palette: SurfaceGraphAppearance): SurfaceGraphSimple {
    val width = palette.width
    val height = dev.shibasis.reaktor.blueprint.BlueprintEngine.cardHeight(dev.shibasis.reaktor.blueprint.BlueprintFace.ProseRows)
    val placement = Layered.layout(nodes.map { LayeredNode(it.id, width, height) },
        links.map { LayeredEdge(it.id, it.from, it.to, weight = if (it.kind == "contains") 4 else 1) },
        LayeredSpacing(node = 16.0, layer = 104.0, edge = 9.0, component = 72.0, aspect = 1.6, wrap = 12))
    return SurfaceGraphSimple(nodes.map { SurfaceGraphNode(it.id, it.label, it.kind) },
        links.map { SurfaceGraphLink(it.id, it.from, it.to, it.label.ifBlank { it.kind }) }, palette,
        nodes.indices.associate { nodes[it].id to (placement.x[it] to placement.y[it]) })
}

@Composable
private fun SubgraphText(value: String, palette: SurfaceGraphAppearance, modifier: Modifier = Modifier, muted: Boolean = false, strong: Boolean = false, size: Int = 13, lines: Int = 1) {
    val look = remember(palette, muted, strong, size) { object : TextAppearance {
        @Composable override fun style(role: TextRole?, ink: InkRole?, theme: ThemeSnapshot) = TextStyle(color = if (muted) palette.muted else palette.text,
            fontFamily = if (size > 20) palette.titleFontFamily ?: palette.fontFamily else palette.fontFamily, fontSize = size.sp,
            lineHeight = (size * 1.4f).sp, fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal)
    } }
    Text(value, modifier, lines = lines, appearance = look)
}

private val SubgraphPlainButton = object : ButtonAppearance {
    @Composable override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) { slots.content() }
}

@Composable
private fun SubgraphAction(value: String, palette: SurfaceGraphAppearance, modifier: Modifier = Modifier, primary: Boolean = false, label: String = value, onClick: () -> Unit) {
    val look = remember(palette, primary) { object : ButtonAppearance {
        @Composable override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.heightIn(min = 34.dp).clip(shape).background(if (primary) palette.selected else if (state.hovered || state.pressed) palette.selected.copy(alpha = .07f) else palette.card).padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) { slots.content() }
        }
    } }
    Button(onClick, modifier.semantics { contentDescription = label }, appearance = look) { SubgraphText(value, palette.copy(text = if (primary) palette.canvas else palette.text), size = 13, strong = primary) }
}

private class SubgraphFieldAppearance(private val palette: SurfaceGraphAppearance, private val space: Dp) : FieldAppearance {
    @Composable override fun textStyle(properties: FieldProperties, theme: ThemeSnapshot) = TextStyle(color = palette.text, fontFamily = palette.fontFamily, fontSize = 15.sp, lineHeight = 22.sp)
    @Composable override fun cursor(properties: FieldProperties, theme: ThemeSnapshot) = SolidColor(palette.selected)
    @Composable override fun Content(properties: FieldProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FieldSlots) {
        val shape = RoundedCornerShape(space * 3)
        Row(Modifier.fillMaxWidth().heightIn(min = space * 6).clip(shape).background(palette.card).border(1.dp, if (state.focused) palette.selected.copy(alpha = .6f) else palette.line.copy(alpha = .4f), shape).padding(horizontal = space * 2, vertical = space), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space)) {
            Box(Modifier.weight(1f)) { if (properties.empty) slots.placeholder?.invoke(); slots.editor() }
            slots.trailing?.invoke()
        }
    }
}

@Composable
private fun SubgraphEmptyMark(palette: SurfaceGraphAppearance, modifier: Modifier) {
    Canvas(modifier) {
        val points = listOf(Offset(size.width * .18f, size.height * .6f), Offset(size.width * .43f, size.height * .22f), Offset(size.width * .56f, size.height * .78f), Offset(size.width * .83f, size.height * .4f))
        for ((a, b) in listOf(0 to 1, 0 to 2, 1 to 2, 1 to 3, 2 to 3)) drawLine(palette.selected.copy(alpha = .15f), points[a], points[b], 1.dp.toPx())
        points.forEachIndexed { index, p -> drawCircle(palette.selected.copy(alpha = if (index == 1) .65f else .25f), 4.dp.toPx(), p) }
    }
}
