// Automatically generated - do not modify!

package reaktor.flow

// unhandled import: addEdge as addEdgeImpl from "@xyflow/react"
// unhandled import: applyEdgeChanges as applyEdgeChangesImpl from "@xyflow/react"
// unhandled import: applyNodeChanges as applyNodeChangesImpl from "@xyflow/react"
// unhandled import: useEdgesState as useEdgesStateImpl from "@xyflow/react"
// unhandled import: useNodesState as useNodesStateImpl from "@xyflow/react"
// unhandled import: useReactFlow as useReactFlowImpl from "@xyflow/react"
// unhandled import: BackgroundVariant as BackgroundVariantValue from "@xyflow/react"
// unhandled import: MarkerType as MarkerTypeValue from "@xyflow/react"
// unhandled import: Position as PositionValue from "@xyflow/react"

external interface ReactFlowInstance {
fun screenToFlowPosition(clientPosition: XYPosition): XYPosition
fun getViewport(): Viewport
fun setViewport(viewport: Viewport, options: FitViewOptions = definedExternally): js.promise.Promise<Boolean>
fun fitView(options: FitViewOptions = definedExternally): js.promise.Promise<Boolean>
fun zoomIn(options: FitViewOptions = definedExternally): js.promise.Promise<Boolean>
fun zoomOut(options: FitViewOptions = definedExternally): js.promise.Promise<Boolean>
fun setCenter(x: Double, y: Double, options: FitViewOptions = definedExternally): js.promise.Promise<Boolean>
}
