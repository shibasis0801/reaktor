package dev.shibasis.reaktor.blueprint.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.BlueprintAgent
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.GraphPart
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.blueprint.editor.node
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.colors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
class BlueprintKanbanState {
    private val scrolls = hashMapOf<Pair<String, String>, LazyListState>()
    private val boards = hashMapOf<String, LazyListState>()
    internal fun lane(source: String, id: String) = scrolls.getOrPut(source to id) { LazyListState() }
    internal fun board(source: String) = boards.getOrPut(source) { LazyListState() }
    internal var notice by mutableStateOf<String?>(null)
        private set
    internal var moving by mutableStateOf(false)
        private set
    internal var drag by mutableStateOf<Drag?>(null)
        private set
    internal data class Drag(val source: String, val id: String, val at: Offset)
    internal fun begin(source: String, id: String, at: Offset) { if (!moving) drag = Drag(source, id, at) }
    internal fun dragBy(delta: Offset) { drag = drag?.let { it.copy(at = it.at + delta) } }
    internal fun cancel() { drag = null }

    internal suspend fun move(source: String, editor: GraphEditor, projection: KanbanProjection, id: String, lane: KanbanLane,
                              proposal: ((NodeShape, KanbanLane) -> GraphPart)?, expectedSource: String = source): EditReceipt {
        if (moving) return EditReceipt.Refused("A status change is already pending")
        val node = projection.columns.asSequence().flatMap { it.items.asSequence() }.find { it.id == id }
        val reason = when {
            source != expectedSource -> "The graph source changed; try the move again"
            node == null || editor.shape.node(id) != node -> "The work item changed; try the move again"
            projection.columns.none { it.lane == lane } -> "This status is unavailable"
            proposal == null -> "Status changes are unavailable in this view"
            else -> null
        }
        if (reason != null) return EditReceipt.Refused(reason).also { notice = reason }
        if (projection.columns.any { it.lane == lane && it.items.any { item -> item.id == id } }) return EditReceipt.Accepted(editor.editRevision)
        moving = true
        notice = null
        return try {
            editor.propose(proposal!!(node!!, lane)).also { if (it is EditReceipt.Refused) notice = it.message }
        } finally { moving = false }
    }
}

@Composable
fun rememberBlueprintKanbanState() = remember { BlueprintKanbanState() }

@Composable
fun BlueprintKanban(
    source: String,
    projection: KanbanProjection,
    editor: GraphEditor,
    cardLook: @Composable (NodeShape) -> BlueprintCardLook,
    modifier: Modifier = Modifier,
    state: BlueprintKanbanState = rememberBlueprintKanbanState(),
    proposal: ((NodeShape, KanbanLane) -> GraphPart)? = null,
    onAgentOpen: (BlueprintAgent) -> Unit = {},
) {
    val colors = MachineSignal.colors
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val bounds = remember(source) { hashMapOf<String, Rect>() }
    var origin by remember(source) { mutableStateOf(Offset.Zero) }
    val latestSource by rememberUpdatedState(source)
    val latestProjection by rememberUpdatedState(projection)
    val latestProposal by rememberUpdatedState(proposal)
    val latestEditor by rememberUpdatedState(editor)
    fun move(id: String, lane: KanbanLane, from: String = source) {
        scope.launch { state.move(from, latestEditor, latestProjection, id, lane, latestProposal, latestSource) }
    }
    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
        if (event.key == Key.Escape && state.drag != null) { state.cancel(); return true }
        val columns = projection.columns
        val selected = editor.selection.nodes.singleOrNull()
        val column = columns.indexOfFirst { lane -> lane.items.any { it.id == selected } }
        val index = columns.getOrNull(column)?.items?.indexOfFirst { it.id == selected } ?: -1
        val horizontal = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
        if (event.isShiftPressed) {
            if (horizontal == 0 || column < 0) return false
            columns.getOrNull(column + horizontal)?.let { move(selected!!, it.lane) }
            return true
        }
        val target = when (event.key) {
            Key.DirectionUp -> columns.getOrNull(column)?.items?.getOrNull((index - 1).coerceAtLeast(0))
            Key.DirectionDown -> columns.getOrNull(column)?.items?.getOrNull(index + 1) ?: if (column < 0) columns.firstNotNullOfOrNull { it.items.firstOrNull() } else null
            Key.DirectionLeft, Key.DirectionRight -> columns.getOrNull(column + horizontal)?.items?.let { it.getOrNull(index.coerceIn(0, (it.size - 1).coerceAtLeast(0))) }
            Key.MoveHome -> columns.getOrNull(column)?.items?.firstOrNull()
            Key.MoveEnd -> columns.getOrNull(column)?.items?.lastOrNull()
            else -> return false
        }
        target?.let { editor.select(Selection(setOf(it.id))) }
        return true
    }
    DisposableEffect(source, state) { onDispose { if (state.drag?.source == source) state.cancel() } }
    Box(modifier.background(colors.canvas).testTag("blueprint-kanban")
        .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
        .focusRequester(focus).onPreviewKeyEvent(::key).focusable()) {
        Column(Modifier.fillMaxSize()) {
            LazyRow(Modifier.weight(1f).fillMaxWidth(), state = state.board(source),
                contentPadding = PaddingValues(MachineSignal.Space.s3), horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
                items(projection.columns, key = { it.lane.id }) { column ->
                    val lane = column.lane
                    val hot = state.drag?.takeIf { it.source == source }?.at?.let { bounds[lane.id]?.contains(it) } == true
                    Column(Modifier.fillMaxHeight().width(MachineSignal.Editor.navigatorWidth).testTag("kanban-lane-${lane.id}")
                        .onGloballyPositioned { bounds[lane.id] = it.boundsInRoot() }
                        .border(MachineSignal.Stroke.hairline, if (hot) colors.controlAccent else colors.line, RoundedCornerShape(MachineSignal.Radius.panel))) {
                        Row(Modifier.fillMaxWidth().height(MachineSignal.Editor.toolbarHeight).padding(horizontal = MachineSignal.Space.s3)
                            .testTag("kanban-header-${lane.id}"), horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(lane.title, Modifier.weight(1f), role = Type.Body)
                            Text("${column.shownTotal}/${column.scopedTotal}", role = Type.Meta, ink = Ink.Muted)
                        }
                        LazyColumn(Modifier.fillMaxSize().testTag("kanban-items-${lane.id}"), state = state.lane(source, lane.id),
                            contentPadding = PaddingValues(MachineSignal.Space.s2), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
                            if (column.items.isEmpty()) item("empty") { Text(if (column.scopedTotal == 0) "No items" else "No matching items", Modifier.padding(MachineSignal.Space.s3), role = Type.Meta, ink = Ink.Muted) }
                            items(column.items, key = { it.id }) { node ->
                                val look = cardLook(node)
                                var topLeft by remember { mutableStateOf(Offset.Zero) }
                                KanbanCard(node, look, node.id in editor.selection.nodes, projection.closingReasons[node.id],
                                    onSelect = { focus.requestFocus(); editor.select(Selection(setOf(node.id))) }, onAgentOpen = onAgentOpen,
                                    modifier = Modifier.onGloballyPositioned { topLeft = it.boundsInRoot().topLeft }
                                        .pointerInput(source, node.id, proposal != null) {
                                            if (proposal != null) detectDragGestures(
                                                onDragStart = { state.begin(source, node.id, topLeft + it) },
                                                onDragCancel = state::cancel,
                                                onDragEnd = {
                                                    val drag = state.drag
                                                    state.cancel()
                                                    if (drag != null) projection.columns.firstOrNull { bounds[it.lane.id]?.contains(drag.at) == true }
                                                        ?.let { move(drag.id, it.lane, drag.source) }
                                                }, onDrag = { change, amount -> change.consume(); state.dragBy(amount) })
                                        })
                            }
                        }
                    }
                }
            }
            Text(state.notice ?: "Arrows select · Shift+Left/Right moves · Drag to a lane", Modifier.fillMaxWidth()
                .height(MachineSignal.Editor.toolbarHeight).padding(MachineSignal.Space.s2).testTag("kanban-notice"),
                role = Type.Meta, ink = if (state.notice == null) Ink.Muted else Ink.Warn, lines = 2)
        }
        state.drag?.takeIf { it.source == source }?.let { drag ->
            val title = projection.columns.asSequence().flatMap { it.items.asSequence() }.find { it.id == drag.id }?.label.orEmpty()
            Text(title, Modifier.offset { val at = drag.at - origin; IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                .width(MachineSignal.Editor.navigatorWidth).background(colors.surface, RoundedCornerShape(MachineSignal.Radius.node))
                .border(MachineSignal.Stroke.hairline, colors.controlAccent, RoundedCornerShape(MachineSignal.Radius.node))
                .padding(MachineSignal.Space.s3).testTag("kanban-drag"), role = Type.Body, lines = Int.MAX_VALUE)
        }
    }
}
