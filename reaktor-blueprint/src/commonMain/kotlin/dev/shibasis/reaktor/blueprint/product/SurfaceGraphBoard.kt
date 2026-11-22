package dev.shibasis.reaktor.blueprint.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.blueprint.kanban.BlueprintKanban
import dev.shibasis.reaktor.blueprint.kanban.KanbanLane
import dev.shibasis.reaktor.blueprint.kanban.KanbanProjection
import dev.shibasis.reaktor.blueprint.kanban.rememberBlueprintKanbanState
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant

@Composable
fun SurfaceGraphBoard(
    nodes: List<SurfaceGraphNode>,
    lanes: List<KanbanLane>,
    laneOf: (SurfaceGraphNode) -> String?,
    selectedId: String?,
    appearance: SurfaceGraphAppearance,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    threadOf: (SurfaceGraphNode) -> String = { it.scope },
    threads: Set<String>? = null,
    sceneKey: String = "board",
    onMove: ((String, String) -> Boolean)? = null,
    onMoveResult: (suspend (String, String) -> EditReceipt)? = null,
    onOpen: (String) -> Unit = {},
    onClear: () -> Unit = {},
    onNotice: ((String) -> Unit)? = null,
    visible: (SurfaceGraphNode) -> Boolean = { true },
    closingReasonOf: (SurfaceGraphNode) -> String? = { null },
    fillLanes: Boolean = false,
) {
    val model = remember(sceneKey, nodes, appearance) { SurfaceGraphSimple(nodes, emptyList(), appearance) }
    val lane by rememberUpdatedState(laneOf)
    val thread by rememberUpdatedState(threadOf)
    val projection by remember(model, lanes, threads, visible, closingReasonOf) { derivedStateOf {
        KanbanProjection.from(model.editor.shape, lanes, laneOf = { shape -> model.nodes[shape.id]?.let(lane) },
            threadOf = { shape -> model.nodes[shape.id]?.let(thread) ?: shape.scope }, threads = threads,
            visible = { shape -> model.nodes[shape.id]?.let(visible) == true },
            closingReasonOf = { shape -> model.nodes[shape.id]?.let(closingReasonOf) })
    } }
    val state = rememberBlueprintKanbanState()
    val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    SideEffect {
        model.editor.onSelect = { id -> if (id == null) onClear() else select(id) }
        if (model.editor.selection.nodes.singleOrNull() != selectedId && (selectedId == null || selectedId in model.nodes))
            model.editor.select(Selection(nodes = selectedId?.let(::setOf).orEmpty()))
    }
    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
        BlueprintKanban(sceneKey, projection, model.editor, cardLook = { shape -> model.look(shape.id, appearance).let { look -> model.nodes[shape.id]?.color?.let { look.copy(outline = it) } ?: look } }, modifier, state,
            onMove = if (onMove == null && onMoveResult == null) null else { node, to ->
                onMoveResult?.invoke(node.id, to.id) ?: if (move?.invoke(node.id, to.id) == true)
                    EditReceipt.Accepted(model.editor.editRevision) else EditReceipt.Refused("This move was not saved")
            }, onOpen = onOpen, onClear = onClear, onNotice = onNotice, fillLanes = fillLanes)
    }
}
