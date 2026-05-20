@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.shibasis.composeflow.react

import dev.shibasis.composeflow.model.BackgroundVariant as CommonBackgroundVariant
import dev.shibasis.composeflow.model.Connection as CommonConnection
import dev.shibasis.composeflow.model.Dimensions as CommonDimensions
import dev.shibasis.composeflow.model.Edge as CommonEdge
import dev.shibasis.composeflow.model.EdgeMarker as CommonEdgeMarker
import dev.shibasis.composeflow.model.FitViewOptions as CommonFitViewOptions
import dev.shibasis.composeflow.model.MarkerType as CommonMarkerType
import dev.shibasis.composeflow.model.Node as CommonNode
import dev.shibasis.composeflow.model.Position as CommonPosition
import dev.shibasis.composeflow.model.Viewport as CommonViewport
import dev.shibasis.composeflow.model.XYPosition as CommonXYPosition
import js.array.ReadonlyArray
import kotlin.js.unsafeCast

fun CommonXYPosition.toReactFlow(): XYPosition = jso {
    x = this@toReactFlow.x
    y = this@toReactFlow.y
}

fun CommonDimensions.toReactFlow(): Dimensions = jso {
    width = this@toReactFlow.width
    height = this@toReactFlow.height
}

fun CommonViewport.toReactFlow(): Viewport = jso {
    x = this@toReactFlow.x
    y = this@toReactFlow.y
    zoom = this@toReactFlow.zoom
}

fun CommonFitViewOptions.toReactFlow(): FitViewOptions = jso {
    padding = this@toReactFlow.padding
    minZoom = this@toReactFlow.minZoom
    maxZoom = this@toReactFlow.maxZoom
}

fun CommonConnection.toReactFlow(): Connection = jso {
    source = this@toReactFlow.source
    target = this@toReactFlow.target
    sourceHandle = this@toReactFlow.sourceHandle
    targetHandle = this@toReactFlow.targetHandle
}

fun CommonEdgeMarker.toReactFlow(): EdgeMarker = jso {
    type = markerType(this@toReactFlow.type)
    color = this@toReactFlow.color
    width = this@toReactFlow.width
    height = this@toReactFlow.height
}

fun CommonNode.toReactFlow(): Node<Any?> = jso {
    id = this@toReactFlow.id
    position = this@toReactFlow.position.toReactFlow()
    data = this@toReactFlow.data
    type = this@toReactFlow.type
    width = this@toReactFlow.width
    height = this@toReactFlow.height
    measured = this@toReactFlow.measured?.toReactFlow()
    parentId = this@toReactFlow.parentId
    showDefaultHandles = this@toReactFlow.showDefaultHandles
    selected = this@toReactFlow.selected
    dragging = this@toReactFlow.dragging
    hidden = this@toReactFlow.hidden
    zIndex = this@toReactFlow.zIndex.toDouble()
    sourcePosition = position(this@toReactFlow.sourcePosition)
    targetPosition = position(this@toReactFlow.targetPosition)
    draggable = this@toReactFlow.draggable
    selectable = this@toReactFlow.selectable
    connectable = this@toReactFlow.connectable
    deletable = this@toReactFlow.deletable
}

fun CommonEdge.toReactFlow(): Edge<Any?> = jso {
    id = this@toReactFlow.id
    source = this@toReactFlow.source
    target = this@toReactFlow.target
    sourceHandle = this@toReactFlow.sourceHandle
    targetHandle = this@toReactFlow.targetHandle
    type = this@toReactFlow.type
    data = this@toReactFlow.data
    label = this@toReactFlow.label
    selected = this@toReactFlow.selected
    hidden = this@toReactFlow.hidden
    animated = this@toReactFlow.animated
    markerStart = this@toReactFlow.markerStart?.toReactFlow()
    markerEnd = this@toReactFlow.markerEnd?.toReactFlow()
    zIndex = this@toReactFlow.zIndex.toDouble()
    selectable = this@toReactFlow.selectable
    deletable = this@toReactFlow.deletable
    reconnectable = this@toReactFlow.reconnectable
    interactionWidth = this@toReactFlow.interactionWidth
}

fun List<CommonNode>.toReactFlowNodes(): ReadonlyArray<Node<Any?>> =
    map(CommonNode::toReactFlow).toTypedArray().unsafeCast<ReadonlyArray<Node<Any?>>>()

fun List<CommonEdge>.toReactFlowEdges(): ReadonlyArray<Edge<Any?>> =
    map(CommonEdge::toReactFlow).toTypedArray().unsafeCast<ReadonlyArray<Edge<Any?>>>()

fun position(value: CommonPosition): PositionValue = when (value) {
    CommonPosition.Left -> Position.Left
    CommonPosition.Top -> Position.Top
    CommonPosition.Right -> Position.Right
    CommonPosition.Bottom -> Position.Bottom
}

fun markerType(value: CommonMarkerType): MarkerTypeValue = when (value) {
    CommonMarkerType.Arrow -> MarkerType.Arrow
    CommonMarkerType.ArrowClosed -> MarkerType.ArrowClosed
}

fun backgroundVariant(value: CommonBackgroundVariant): BackgroundVariantValue = when (value) {
    CommonBackgroundVariant.Dots -> BackgroundVariant.Dots
    CommonBackgroundVariant.Lines -> BackgroundVariant.Lines
    CommonBackgroundVariant.Cross -> BackgroundVariant.Cross
}

private inline fun <T : Any> jso(builder: T.() -> Unit): T = (js("({})") as T).apply(builder)
