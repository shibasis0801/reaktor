package dev.shibasis.reaktor.flow.graph.model

import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Node
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode

enum class ReaktorNodeKind(
    val label: String,
    val titleColor: Color,
    val bodyColor: Color,
    val borderColor: Color,
) {
    Screen("Screen", Color(0xFF2D6B3F), Color(0xFF192820), Color(0xFF3D8C55)),
    Route("Route", Color(0xFF2E4A80), Color(0xFF182040), Color(0xFF4A78CC)),
    Container("Container", Color(0xFF5E3498), Color(0xFF221838), Color(0xFF8B60C8)),
    Service("Service", Color(0xFF8B5A2B), Color(0xFF261E14), Color(0xFFCC8844)),
    Node("Node", Color(0xFF3C4A6E), Color(0xFF1C2030), Color(0xFF4A5580)),
}

enum class ReaktorEdgeKind(
    val label: String,
    val color: Color,
) {
    Attachment("Attachment", Color(0xFF7FB0FF)),
    Navigation("Navigation", Color(0xFF55A8F4)),
    Data("Data", Color(0xFF55D46E)),
    Containment("Containment", Color(0xFFC38CFF)),
}

data class ReaktorPortData(
    val handleId: String,
    val label: String,
    val type: String,
    val color: Color,
    val connected: Boolean,
)

data class ReaktorGraphNodeData(
    val nodeId: String,
    val title: String,
    val subtitle: String?,
    val graphLabel: String,
    val isRootNode: Boolean,
    val providerCount: Int,
    val consumerCount: Int,
    val providerPorts: List<ReaktorPortData>,
    val consumerPorts: List<ReaktorPortData>,
    val hiddenProviderCount: Int,
    val hiddenConsumerCount: Int,
    val kind: ReaktorNodeKind,
)

data class ReaktorGraphEdgeData(
    val kind: ReaktorEdgeKind,
    val label: String? = null,
)

data class ReaktorGraphRegion(
    val label: String,
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val color: Color,
    val depth: Int,
)

data class ReaktorFlowGraph(
    val nodes: List<Node>,
    val edges: List<Edge>,
    val regions: List<ReaktorGraphRegion>,
    val graphNodes: Map<String, GraphNode>,
    val flowIdsByNode: Map<GraphNode, String>,
    val graphIdsByNode: Map<GraphNode, String>,
    val graphs: Map<String, Graph>,
)
