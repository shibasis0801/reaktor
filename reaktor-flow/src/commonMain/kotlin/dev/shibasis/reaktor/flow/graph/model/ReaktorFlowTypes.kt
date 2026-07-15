package dev.shibasis.reaktor.flow.graph.model

import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Node
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode

enum class ReaktorNodeKind(
    val label: String,
    val titleColor: Color,
    val bodyColor: Color,
    val borderColor: Color,
) {
    Screen("Screen", ReaktorGraphPalette.screenTitle, ReaktorGraphPalette.screenBody, ReaktorGraphPalette.screenBorder),
    Route("Route", ReaktorGraphPalette.routeTitle, ReaktorGraphPalette.routeBody, ReaktorGraphPalette.routeBorder),
    Container("Container", ReaktorGraphPalette.containerTitle, ReaktorGraphPalette.containerBody, ReaktorGraphPalette.containerBorder),
    Ui("UI", ReaktorGraphPalette.uiTitle, ReaktorGraphPalette.uiBody, ReaktorGraphPalette.uiBorder),
    Action("Action", ReaktorGraphPalette.actionTitle, ReaktorGraphPalette.actionBody, ReaktorGraphPalette.actionBorder),
    Interactor("Interactor", ReaktorGraphPalette.interactorTitle, ReaktorGraphPalette.interactorBody, ReaktorGraphPalette.interactorBorder),
    Service("Service", ReaktorGraphPalette.serviceTitle, ReaktorGraphPalette.serviceBody, ReaktorGraphPalette.serviceBorder),
    Repository("Repository", ReaktorGraphPalette.repositoryTitle, ReaktorGraphPalette.repositoryBody, ReaktorGraphPalette.repositoryBorder),
    Actor("Actor", ReaktorGraphPalette.actorTitle, ReaktorGraphPalette.actorBody, ReaktorGraphPalette.actorBorder),
    Edge("Edge", ReaktorGraphPalette.edgeTitle, ReaktorGraphPalette.edgeBody, ReaktorGraphPalette.edgeBorder),
    Data("Data", ReaktorGraphPalette.dataTitle, ReaktorGraphPalette.dataBody, ReaktorGraphPalette.dataBorder),
    Topic("Topic", ReaktorGraphPalette.topicTitle, ReaktorGraphPalette.topicBody, ReaktorGraphPalette.topicBorder),
    Telemetry("Telemetry", ReaktorGraphPalette.telemetryTitle, ReaktorGraphPalette.telemetryBody, ReaktorGraphPalette.telemetryBorder),
    Test("Test", ReaktorGraphPalette.testTitle, ReaktorGraphPalette.testBody, ReaktorGraphPalette.testBorder),
    Release("Release", ReaktorGraphPalette.releaseTitle, ReaktorGraphPalette.releaseBody, ReaktorGraphPalette.releaseBorder),
    Auth("Auth", ReaktorGraphPalette.authTitle, ReaktorGraphPalette.authBody, ReaktorGraphPalette.authBorder),
    Infra("Infra", ReaktorGraphPalette.infraTitle, ReaktorGraphPalette.infraBody, ReaktorGraphPalette.infraBorder),
    Agent("Agent", ReaktorGraphPalette.agentTitle, ReaktorGraphPalette.agentBody, ReaktorGraphPalette.agentBorder),
    Node("Node", ReaktorGraphPalette.nodeTitle, ReaktorGraphPalette.nodeBody, ReaktorGraphPalette.nodeBorder),
}

enum class ReaktorEdgeKind(
    val label: String,
    val color: Color,
) {
    Attachment("Attachment", ReaktorGraphPalette.attachmentPort),
    Navigation("Navigation", ReaktorGraphPalette.navigationPort),
    Data("Data", ReaktorGraphPalette.dataPort),
    Containment("Containment", ReaktorGraphPalette.containmentPort),
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
    // True for the synthetic boundary card standing in for a collapsed child scope.
    val isScopeSummary: Boolean = false,
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
    val style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    // Full scope-id universe of the SOURCE graph (view-independent), for level operations —
    // `graphs` only contains scopes visible under the current projection.
    val allScopeIds: Set<String> = emptySet(),
)
