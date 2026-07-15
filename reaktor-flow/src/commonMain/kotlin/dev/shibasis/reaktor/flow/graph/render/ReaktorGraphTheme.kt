package dev.shibasis.reaktor.flow.graph.render

import dev.shibasis.composeflow.compose.primitives.EdgeRenderStyle
import dev.shibasis.composeflow.compose.primitives.HandleRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeRenderStyle
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.hiddenHandleBorder
import androidx.compose.ui.unit.dp

// References:
// - Fluent UI / styled-system token layering: semantic scene style lives in one object instead of
//   scattered renderer literals.
// - Compose custom layout guidance: renderers should consume a stable measurement/style contract,
//   not invent their own equivalent sizing vocabulary.
internal fun graphNodeRenderStyle(
    node: Node,
    highlightedKind: ReaktorNodeKind?,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
): NodeRenderStyle {
    val data = node.data as? ReaktorGraphNodeData ?: return NodeRenderStyle()
    val matchesKind = highlightedKind == null || data.kind == highlightedKind
    return NodeRenderStyle(
        alpha = if (matchesKind) 1f else 0.12f,
        scale = 1f,
        backgroundColor = data.kind.bodyColor.copy(alpha = if (matchesKind) 0.92f else 0.44f),
        borderColor = when {
            node.selected -> style.canvas.selected
            data.isScopeSummary && matchesKind -> data.kind.borderColor.copy(alpha = 0.92f)
            matchesKind -> data.kind.borderColor
            else -> data.kind.borderColor.copy(alpha = 0.30f)
        },
        // Blueprint-style bloom: selection gets the accent halo; collapsed scope summaries carry
        // a faint kind-colored halo so drill-in targets read as "alive" at a glance.
        glowColor = when {
            node.selected -> style.canvas.selected
            data.isScopeSummary && matchesKind -> data.kind.borderColor.copy(alpha = 0.55f)
            else -> null
        },
    )
}

internal fun graphEdgeRenderStyle(
    edge: Edge,
    nodeKinds: Map<String, ReaktorNodeKind>,
    highlightedKind: ReaktorNodeKind?,
    selectedFlowId: String? = null,
): EdgeRenderStyle {
    val data = edge.data as? ReaktorGraphEdgeData ?: return EdgeRenderStyle()
    val sourceKind = nodeKinds[edge.source]
    val targetKind = nodeKinds[edge.target]
    val matchesKind = highlightedKind == null || sourceKind == highlightedKind || targetKind == highlightedKind
    val active = edge.selected ||
        (selectedFlowId != null && (edge.source == selectedFlowId || edge.target == selectedFlowId))
    // Attention model (ported from the web graph views): wires touching the selection burn hot
    // and flow; the rest sit back; kind-filtered-out edges almost disappear.
    val alpha = when {
        !matchesKind -> 0.08f
        active -> 0.95f
        selectedFlowId != null -> 0.30f
        else -> 0.55f
    }
    val baseWidth = when (data.kind) {
        ReaktorEdgeKind.Navigation -> 2.4f
        ReaktorEdgeKind.Attachment -> 1.9f
        ReaktorEdgeKind.Data -> 1.7f
        ReaktorEdgeKind.Containment -> 1.4f
    }
    return EdgeRenderStyle(
        alpha = alpha,
        color = data.kind.color.copy(alpha = if (matchesKind) 0.92f else 0.35f),
        width = if (active) baseWidth + 0.8f else baseWidth,
        glowColor = if (active) data.kind.color else null,
        dashOn = when {
            active -> 9f
            data.kind == ReaktorEdgeKind.Containment -> 4f
            else -> null
        },
        dashOff = when {
            active -> 6f
            data.kind == ReaktorEdgeKind.Containment -> 5f
            else -> null
        },
        flowAnimated = active,
    )
}

internal fun graphHandleRenderStyle(
    node: Node,
    handle: Handle,
    highlightedKind: ReaktorNodeKind?,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
): HandleRenderStyle {
    val data = node.data as? ReaktorGraphNodeData ?: return HandleRenderStyle()
    val matchesKind = highlightedKind == null || data.kind == highlightedKind
    val color = when (handle.id) {
        "__nav__", "navBinding", "routeBinding" -> ReaktorEdgeKind.Navigation.color
        "__contains__" -> ReaktorEdgeKind.Containment.color
        else -> when (handle.type) {
            HandleType.Source -> data.providerPorts.firstOrNull { it.handleId == handle.id }?.color
            HandleType.Target -> data.consumerPorts.firstOrNull { it.handleId == handle.id }?.color
        } ?: style.canvas.mutedText
    }
    return HandleRenderStyle(
        fillColor = color,
        borderColor = style.hiddenHandleBorder(),
        alpha = if (matchesKind) 0f else 0f,
        size = style.chrome.hiddenHandleSizePx.toFloat().dp,
    )
}
