@file:JsModule("compose-flow")
@file:JsNonModule

package dev.shibasis.composeflow.react

import js.array.ReadonlyArray
import react.FC
import react.Props
import react.PropsWithChildren

typealias XYPosition = compose.flow.XYPosition
typealias Dimensions = compose.flow.Dimensions
typealias Viewport = compose.flow.Viewport
typealias FitViewOptions = compose.flow.FitViewOptions
typealias Node<Data> = compose.flow.Node<Data>
typealias Edge<Data> = compose.flow.Edge<Data>
typealias EdgeMarker = compose.flow.EdgeMarker
typealias Connection = compose.flow.Connection
typealias NodeChange<Data> = compose.flow.NodeChange<Data>
typealias EdgeChange<Data> = compose.flow.EdgeChange<Data>
typealias ReactFlowInstance = compose.flow.ReactFlowInstance
typealias NodesState<Data> = compose.flow.NodesState<Data>
typealias EdgesState<Data> = compose.flow.EdgesState<Data>

typealias PositionValue = String
typealias MarkerTypeValue = String
typealias BackgroundVariantValue = String

@JsName("Position")
external object Position {
    val Left: PositionValue
    val Top: PositionValue
    val Right: PositionValue
    val Bottom: PositionValue
}

@JsName("MarkerType")
external object MarkerType {
    val Arrow: MarkerTypeValue
    val ArrowClosed: MarkerTypeValue
}

@JsName("BackgroundVariant")
external object BackgroundVariant {
    val Dots: BackgroundVariantValue
    val Lines: BackgroundVariantValue
    val Cross: BackgroundVariantValue
}

external interface ReactFlowProps<NodeData, EdgeData> : PropsWithChildren {
    var nodes: ReadonlyArray<Node<NodeData>>
    var edges: ReadonlyArray<Edge<EdgeData>>
    var onNodesChange: ((ReadonlyArray<NodeChange<NodeData>>) -> Unit)?
    var onEdgesChange: ((ReadonlyArray<EdgeChange<EdgeData>>) -> Unit)?
    var onConnect: ((Connection) -> Unit)?
    var onNodeClick: ((dynamic, Node<NodeData>) -> Unit)?
    var nodeTypes: dynamic
    var defaultEdgeOptions: dynamic
    var fitView: Boolean?
    var fitViewOptions: FitViewOptions?
    var attributionPosition: String?
    var className: String?
    var style: dynamic
}

external interface BackgroundProps : Props {
    var variant: BackgroundVariantValue?
    var gap: Number?
    var size: Number?
    var color: String?
    var className: String?
    var style: dynamic
}

external interface ControlsProps : Props {
    var className: String?
    var style: dynamic
}

external interface PanelProps : PropsWithChildren {
    var position: String?
    var className: String?
    var style: dynamic
}

external interface MiniMapProps<NodeData> : Props {
    var nodeStrokeWidth: Number?
    var zoomable: Boolean?
    var pannable: Boolean?
    var className: String?
    var style: dynamic
    var nodeColor: ((Node<NodeData>) -> String)?
}

external interface NodeResizerProps : Props {
    var minWidth: Number?
    var minHeight: Number?
    var maxWidth: Number?
    var maxHeight: Number?
    var isVisible: Boolean?
    var keepAspectRatio: Boolean?
    var className: String?
    var style: dynamic
}

external interface HandleProps : Props {
    var id: String?
    var type: String
    var position: PositionValue
    var isConnectable: Boolean?
    var className: String?
    var style: dynamic
}

@JsName("default")
external val ReactFlow: FC<ReactFlowProps<Any?, Any?>>

external val ReactFlowProvider: FC<PropsWithChildren>

external val Background: FC<BackgroundProps>

external val Controls: FC<ControlsProps>

external val Panel: FC<PanelProps>

external val MiniMap: FC<MiniMapProps<Any?>>

external val ViewportPortal: FC<PropsWithChildren>

external val NodeResizer: FC<NodeResizerProps>

external val Handle: FC<HandleProps>
