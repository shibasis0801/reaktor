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
import dev.shibasis.reaktor.flow.graph.style.dpOf
import androidx.compose.ui.unit.Density
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
    density: Density = Density(1f),
): NodeRenderStyle {
    val data = node.data as? ReaktorGraphNodeData ?: return NodeRenderStyle()
    val matchesKind = highlightedKind == null || data.kind == highlightedKind
    return NodeRenderStyle(
        alpha = if (matchesKind) 1f else 0.12f,
        scale = 1f,
        cornerRadius = with(density) { dpOf(style.node.cornerRadiusPx) },
        borderWidth = if (style.typedNode != null) (if (node.selected) 2 else 1).dp else null,
        backgroundColor = if (style.typedNode != null) style.canvas.panelSurface
            else data.kind.bodyColor.copy(alpha = if (matchesKind) 0.92f else 0.44f),
        borderColor = when {
            node.selected -> style.canvas.selected
            style.typedNode != null -> if (data.kind == ReaktorNodeKind.Interactor) style.canvas.border
                else style.typedNode.kindColors[data.kind.label] ?: style.canvas.border
            data.isScopeSummary && matchesKind -> data.kind.borderColor.copy(alpha = 0.92f)
            matchesKind -> data.kind.borderColor
            else -> data.kind.borderColor.copy(alpha = 0.30f)
        },
        // Blueprint-style bloom: selection gets the accent halo; collapsed scope summaries carry
        // a faint kind-colored halo so drill-in targets read as "alive" at a glance.
        glowColor = when {
            style.typedNode != null -> null
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
    density: Density = Density(1f),
): EdgeRenderStyle {
    val data = edge.data as? ReaktorGraphEdgeData ?: return EdgeRenderStyle()
    val sourceKind = nodeKinds[edge.source]
    val targetKind = nodeKinds[edge.target]
    val matchesKind = highlightedKind == null || sourceKind == highlightedKind || targetKind == highlightedKind
    val active = edge.selected ||
        (selectedFlowId != null && (edge.source == selectedFlowId || edge.target == selectedFlowId))
    // Selection emphasizes a structural relation. It does not establish observed traffic.
    // Craft rule: one level of emphasis at a time. Wiring (data) and navigation carry the
    // structure and read at full strength; attachment sits back; containment is a whisper —
    // position already encodes it, so its wires only confirm, never compete. Selection pulls
    // its neighbourhood forward and everything else recedes further than before.
    val alpha = when {
        !matchesKind -> 0.06f
        active -> 0.95f
        selectedFlowId != null -> 0.16f
        data.kind == ReaktorEdgeKind.Containment -> 0.22f
        data.kind == ReaktorEdgeKind.Attachment -> 0.38f
        ReaktorEdgeKind.Navigation == data.kind -> 0.60f
        else -> 0.50f
    }
    val baseWidth = when (data.kind) {
        ReaktorEdgeKind.Navigation -> 1.8f
        ReaktorEdgeKind.Attachment -> 1.3f
        ReaktorEdgeKind.Data -> 1.6f
        ReaktorEdgeKind.Containment -> 1.0f
    }
    return EdgeRenderStyle(
        alpha = alpha,
        color = data.kind.color.copy(alpha = if (matchesKind) 0.92f else 0.35f),
        width = (if (active) baseWidth + 0.8f else baseWidth) * density.density,
        glowColor = if (active) data.kind.color else null,
        dashOn = when {
            data.kind == ReaktorEdgeKind.Containment -> 3f * density.density
            else -> null
        },
        dashOff = when {
            data.kind == ReaktorEdgeKind.Containment -> 6f * density.density
            else -> null
        },
        flowAnimated = false,
    )
}

internal fun graphHandleRenderStyle(
    node: Node,
    handle: Handle,
    highlightedKind: ReaktorNodeKind?,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    density: Density = Density(1f),
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
        size = with(density) { dpOf(style.chrome.hiddenHandleSizePx) },
    )
}
