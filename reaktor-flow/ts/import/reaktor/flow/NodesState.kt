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

external interface NodesState<Data /* default is Any? */> {
var nodes: js.array.ReadonlyArray<Node<Data>>
fun setNodes(next: js.array.ReadonlyArray<Node<Data>>): Unit

fun setNodes(next: (nodes: js.array.ReadonlyArray<Node<Data>>) -> js.array.ReadonlyArray<Node<Data>>): Unit
fun onNodesChange(changes: js.array.ReadonlyArray<NodeChange<Data>>): Unit
}
