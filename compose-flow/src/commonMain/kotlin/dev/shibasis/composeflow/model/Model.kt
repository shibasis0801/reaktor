package dev.shibasis.composeflow.model

import androidx.compose.runtime.Immutable

@Immutable
data class XYPosition(
    val x: Double = 0.0,
    val y: Double = 0.0,
)

@Immutable
data class Dimensions(
    val width: Double = 0.0,
    val height: Double = 0.0,
)

@Immutable
data class Viewport(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val zoom: Double = 1.0,
)

@Immutable
data class FitViewOptions(
    val padding: Double = 0.12,
    val minZoom: Double = 0.25,
    val maxZoom: Double = 2.0,
)

enum class Position {
    Left,
    Top,
    Right,
    Bottom,
}

enum class MarkerType {
    Arrow,
    ArrowClosed,
}

enum class BackgroundVariant {
    Dots,
    Lines,
    Cross,
}

enum class PanelPosition {
    TopLeft,
    TopCenter,
    TopRight,
    BottomLeft,
    BottomCenter,
    BottomRight,
}

enum class HandleType {
    Source,
    Target,
}

@Immutable
data class Handle(
    val id: String,
    val type: HandleType,
    val position: Position,
    val offset: Double? = null,
    val inset: Double? = null,
)

@Immutable
data class EdgeMarker(
    val type: MarkerType = MarkerType.ArrowClosed,
    val color: String? = null,
    val width: Double? = null,
    val height: Double? = null,
)

@Immutable
data class Connection(
    val source: String? = null,
    val target: String? = null,
    val sourceHandle: String? = null,
    val targetHandle: String? = null,
)

@Immutable
data class Node(
    val id: String,
    val position: XYPosition,
    val data: Any? = null,
    val type: String? = null,
    val width: Double? = null,
    val height: Double? = null,
    val measured: Dimensions? = null,
    val handles: List<Handle> = emptyList(),
    val sourcePosition: Position = Position.Bottom,
    val targetPosition: Position = Position.Top,
    val parentId: String? = null,
    val showDefaultHandles: Boolean = true,
    val selected: Boolean = false,
    val dragging: Boolean = false,
    val hidden: Boolean = false,
    val zIndex: Int = 0,
    val draggable: Boolean = true,
    val selectable: Boolean = true,
    val connectable: Boolean = true,
    val deletable: Boolean = true,
    val extent: Pair<XYPosition, XYPosition>? = null,
)

@Immutable
data class Edge(
    val id: String,
    val source: String,
    val target: String,
    val sourceHandle: String? = null,
    val targetHandle: String? = null,
    val type: String? = null,
    val data: Any? = null,
    val label: String? = null,
    val selected: Boolean = false,
    val hidden: Boolean = false,
    val animated: Boolean = false,
    val markerStart: EdgeMarker? = null,
    val markerEnd: EdgeMarker? = null,
    val zIndex: Int = 0,
    val selectable: Boolean = true,
    val deletable: Boolean = true,
    val reconnectable: Boolean = false,
    val interactionWidth: Double = 20.0,
)

sealed interface NodeChange {
    val id: String
}

@Immutable
data class NodeAddChange(
    override val id: String,
    val item: Node,
    val index: Int? = null,
) : NodeChange

@Immutable
data class NodeRemoveChange(
    override val id: String,
) : NodeChange

@Immutable
data class NodeReplaceChange(
    override val id: String,
    val item: Node,
) : NodeChange

@Immutable
data class NodeSelectionChange(
    override val id: String,
    val selected: Boolean,
) : NodeChange

@Immutable
data class NodePositionChange(
    override val id: String,
    val position: XYPosition,
    val dragging: Boolean = false,
) : NodeChange

@Immutable
data class NodeDimensionChange(
    override val id: String,
    val dimensions: Dimensions,
) : NodeChange

sealed interface EdgeChange {
    val id: String
}

@Immutable
data class EdgeAddChange(
    override val id: String,
    val item: Edge,
    val index: Int? = null,
) : EdgeChange

@Immutable
data class EdgeRemoveChange(
    override val id: String,
) : EdgeChange

@Immutable
data class EdgeReplaceChange(
    override val id: String,
    val item: Edge,
) : EdgeChange

@Immutable
data class EdgeSelectionChange(
    override val id: String,
    val selected: Boolean,
) : EdgeChange
