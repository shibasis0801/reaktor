package dev.shibasis.composeflow.react

import js.array.ReadonlyArray
import kotlin.js.unsafeCast
import compose.flow.addEdge as addEdgeImpl
import compose.flow.applyEdgeChanges as applyEdgeChangesImpl
import compose.flow.applyNodeChanges as applyNodeChangesImpl
import compose.flow.useEdgesState as useEdgesStateImpl
import compose.flow.useNodesState as useNodesStateImpl
import compose.flow.useReactFlow as useReactFlowImpl

fun <Data> useNodesState(
    initialNodes: ReadonlyArray<Node<Data>> = emptyReadonlyArray(),
): NodesState<Data> = useNodesStateImpl(initialNodes)

fun <Data> useEdgesState(
    initialEdges: ReadonlyArray<Edge<Data>> = emptyReadonlyArray(),
): EdgesState<Data> = useEdgesStateImpl(initialEdges)

fun <Data> addEdge(
    connection: Connection,
    edges: ReadonlyArray<Edge<Data>>,
): ReadonlyArray<Edge<Data>> = addEdgeImpl(connection, edges)

fun <Data> applyNodeChanges(
    changes: ReadonlyArray<NodeChange<Data>>,
    nodes: ReadonlyArray<Node<Data>>,
): ReadonlyArray<Node<Data>> = applyNodeChangesImpl(changes, nodes)

fun <Data> applyEdgeChanges(
    changes: ReadonlyArray<EdgeChange<Data>>,
    edges: ReadonlyArray<Edge<Data>>,
): ReadonlyArray<Edge<Data>> = applyEdgeChangesImpl(changes, edges)

fun useReactFlow(): ReactFlowInstance = useReactFlowImpl()

private fun <T> emptyReadonlyArray(): ReadonlyArray<T> = emptyArray<T>().unsafeCast<ReadonlyArray<T>>()
