// Automatically generated - do not modify!

package compose.flow

// unhandled import: addEdge as addEdgeImpl from "@xyflow/react"
// unhandled import: applyEdgeChanges as applyEdgeChangesImpl from "@xyflow/react"
// unhandled import: applyNodeChanges as applyNodeChangesImpl from "@xyflow/react"
// unhandled import: useEdgesState as useEdgesStateImpl from "@xyflow/react"
// unhandled import: useNodesState as useNodesStateImpl from "@xyflow/react"
// unhandled import: useReactFlow as useReactFlowImpl from "@xyflow/react"
// unhandled import: BackgroundVariant as BackgroundVariantValue from "@xyflow/react"
// unhandled import: MarkerType as MarkerTypeValue from "@xyflow/react"
// unhandled import: Position as PositionValue from "@xyflow/react"

external interface EdgesState<Data /* default is Any? */> {
var edges: js.array.ReadonlyArray<Edge<Data>>
fun setEdges(next: js.array.ReadonlyArray<Edge<Data>>): Unit

fun setEdges(next: (edges: js.array.ReadonlyArray<Edge<Data>>) -> js.array.ReadonlyArray<Edge<Data>>): Unit
fun onEdgesChange(changes: js.array.ReadonlyArray<EdgeChange<Data>>): Unit
}
