@file:JsModule("reaktor-flow")
@file:JsNonModule

package dev.shibasis.reaktor.flow.react

import js.array.ReadonlyArray
import react.FC
import react.Props
import react.PropsWithChildren

typealias XYPosition = reaktor.flow.XYPosition
typealias Dimensions = reaktor.flow.Dimensions
typealias Viewport = reaktor.flow.Viewport
typealias FitViewOptions = reaktor.flow.FitViewOptions
typealias Node<Data> = reaktor.flow.Node<Data>
typealias Edge<Data> = reaktor.flow.Edge<Data>
typealias EdgeMarker = reaktor.flow.EdgeMarker
typealias Connection = reaktor.flow.Connection
typealias NodeChange<Data> = reaktor.flow.NodeChange<Data>
typealias EdgeChange<Data> = reaktor.flow.EdgeChange<Data>
typealias ReactFlowInstance = reaktor.flow.ReactFlowInstance
typealias NodesState<Data> = reaktor.flow.NodesState<Data>
typealias EdgesState<Data> = reaktor.flow.EdgesState<Data>

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
