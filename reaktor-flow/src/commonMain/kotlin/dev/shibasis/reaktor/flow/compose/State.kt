package dev.shibasis.reaktor.flow.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.flow.Edge
import dev.shibasis.reaktor.flow.EdgeChange
import dev.shibasis.reaktor.flow.FitViewOptions
import dev.shibasis.reaktor.flow.Node
import dev.shibasis.reaktor.flow.NodeChange
import dev.shibasis.reaktor.flow.Viewport
import dev.shibasis.reaktor.flow.XYPosition
import dev.shibasis.reaktor.flow.applyEdgeChanges
import dev.shibasis.reaktor.flow.applyNodeChanges

class NodesState internal constructor(initialNodes: List<Node>) {
    var nodes by mutableStateOf(initialNodes)
        private set

    fun replaceNodes(next: List<Node>) {
        nodes = next
    }

    fun updateNodes(transform: (List<Node>) -> List<Node>) {
        nodes = transform(nodes)
    }

    fun onNodesChange(changes: List<NodeChange>) {
        nodes = applyNodeChanges(changes, nodes)
    }

    operator fun component1(): List<Node> = nodes

    operator fun component2(): (List<Node>) -> Unit = ::replaceNodes

    operator fun component3(): (List<NodeChange>) -> Unit = ::onNodesChange
}

class EdgesState internal constructor(initialEdges: List<Edge>) {
    var edges by mutableStateOf(initialEdges)
        private set

    fun replaceEdges(next: List<Edge>) {
        edges = next
    }

    fun updateEdges(transform: (List<Edge>) -> List<Edge>) {
        edges = transform(edges)
    }

    fun onEdgesChange(changes: List<EdgeChange>) {
        edges = applyEdgeChanges(changes, edges)
    }

    operator fun component1(): List<Edge> = edges

    operator fun component2(): (List<Edge>) -> Unit = ::replaceEdges

    operator fun component3(): (List<EdgeChange>) -> Unit = ::onEdgesChange
}

@Composable
fun rememberNodesState(initialNodes: List<Node> = emptyList()): NodesState {
    val state = remember { NodesState(initialNodes) }
    LaunchedEffect(initialNodes) {
        state.replaceNodes(initialNodes)
    }
    return state
}

@Composable
fun rememberEdgesState(initialEdges: List<Edge> = emptyList()): EdgesState {
    val state = remember { EdgesState(initialEdges) }
    LaunchedEffect(initialEdges) {
        state.replaceEdges(initialEdges)
    }
    return state
}

@Stable
class ReactFlowState internal constructor(
    initialViewport: Viewport,
) {
    var viewport by mutableStateOf(initialViewport)
        internal set

    var canvasSize by mutableStateOf(IntSize.Zero)
        internal set

    var selectedNodeIds by mutableStateOf(emptySet<String>())
        internal set

    var selectedEdgeIds by mutableStateOf(emptySet<String>())
        internal set

    fun setViewport(next: Viewport) {
        viewport = next
    }

    fun panBy(dx: Double, dy: Double) {
        viewport = viewport.copy(
            x = viewport.x + dx,
            y = viewport.y + dy,
        )
    }

    fun screenToFlowPosition(
        screenX: Double,
        screenY: Double,
    ): XYPosition = XYPosition(
        x = (screenX - viewport.x) / viewport.zoom,
        y = (screenY - viewport.y) / viewport.zoom,
    )

    fun centerOn(
        position: XYPosition,
        zoom: Double = viewport.zoom,
    ) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) {
            return
        }

        viewport = Viewport(
            x = canvasSize.width / 2.0 - position.x * zoom,
            y = canvasSize.height / 2.0 - position.y * zoom,
            zoom = zoom,
        )
    }

    fun focusNode(
        node: Node,
        defaultNodeWidth: Double,
        defaultNodeHeight: Double,
        zoom: Double = viewport.zoom,
    ) {
        val width = node.measured?.width ?: node.width ?: defaultNodeWidth
        val height = node.measured?.height ?: node.height ?: defaultNodeHeight
        centerOn(
            position = XYPosition(
                x = node.position.x + width / 2.0,
                y = node.position.y + height / 2.0,
            ),
            zoom = zoom,
        )
    }

    fun zoomTo(
        zoom: Double,
        anchorX: Double? = null,
        anchorY: Double? = null,
        minZoom: Double = 0.25,
        maxZoom: Double = 2.0,
    ) {
        val nextZoom = zoom.coerceIn(minZoom, maxZoom)
        val current = viewport

        if (anchorX == null || anchorY == null) {
            viewport = current.copy(zoom = nextZoom)
            return
        }

        val worldX = (anchorX - current.x) / current.zoom
        val worldY = (anchorY - current.y) / current.zoom

        viewport = Viewport(
            x = anchorX - worldX * nextZoom,
            y = anchorY - worldY * nextZoom,
            zoom = nextZoom,
        )
    }

    fun zoomBy(
        factor: Double,
        anchorX: Double? = null,
        anchorY: Double? = null,
        minZoom: Double = 0.25,
        maxZoom: Double = 2.0,
    ) {
        zoomTo(
            zoom = viewport.zoom * factor,
            anchorX = anchorX,
            anchorY = anchorY,
            minZoom = minZoom,
            maxZoom = maxZoom,
        )
    }

    fun fitView(
        nodes: List<Node>,
        defaultNodeWidth: Double,
        defaultNodeHeight: Double,
        options: FitViewOptions = FitViewOptions(),
    ) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0 || nodes.isEmpty()) {
            return
        }

        viewport = fitViewport(
            nodes = nodes,
            viewportWidth = canvasSize.width.toDouble(),
            viewportHeight = canvasSize.height.toDouble(),
            defaultNodeWidth = defaultNodeWidth,
            defaultNodeHeight = defaultNodeHeight,
            options = options,
        )
    }
}

internal val LocalReactFlowState = staticCompositionLocalOf<ReactFlowState?> { null }

@Composable
fun rememberReactFlowState(initialViewport: Viewport = Viewport()): ReactFlowState =
    remember { ReactFlowState(initialViewport) }

@Composable
fun ReactFlowProvider(
    state: ReactFlowState = rememberReactFlowState(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReactFlowState provides state) {
        content()
    }
}

@Composable
fun useReactFlowState(): ReactFlowState =
    LocalReactFlowState.current ?: error("ReactFlowState is not available. Wrap the content in ReactFlowProvider or pass state directly.")

@Composable
fun useViewport(): Viewport = useReactFlowState().viewport
