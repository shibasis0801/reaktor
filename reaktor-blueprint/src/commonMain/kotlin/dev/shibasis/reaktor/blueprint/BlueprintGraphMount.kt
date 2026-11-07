package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.canvas.CanvasEditing
import dev.shibasis.reaktor.blueprint.canvas.CanvasPulse
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
import dev.shibasis.reaktor.blueprint.editor.Fold
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.AppGraphLayout
import dev.shibasis.reaktor.blueprint.graph.Detail
import dev.shibasis.reaktor.blueprint.graph.LayoutRequest

fun AppGraph.blueprintMap(request: LayoutRequest = LayoutRequest(Detail.Ports)): BlueprintLayout<LayoutRequest> =
    AppGraphLayout.layout(this, request.copy(detail = Detail.Ports, nested = true, bundles = true, expandedFlows = containers.keys))

@Composable
fun <K> BlueprintGraph(
    layout: BlueprintLayout<K>,
    canvas: GraphCanvasState,
    highlight: Set<String>,
    selected: String?,
    linkStyle: (Link) -> LinkStyle,
    frameLook: @Composable (Frame) -> BlueprintFrameLook,
    cardLook: @Composable (Card) -> BlueprintCardLook?,
    onCardClick: (String) -> Unit,
    onLinkClick: (Link) -> Unit,
    onPaneClick: () -> Unit,
    modifier: Modifier = Modifier,
    labels: Map<String, String> = emptyMap(),
    fitKey: Int = layout.key.hashCode() * 31 + layout.cards.keys.hashCode(),
    fitScene: Any? = fitKey,
    startInset: Dp = 0.dp,
    pulses: List<CanvasPulse> = emptyList(),
    glow: (String) -> Color? = { null },
    pinTip: (Card, Pin) -> BlueprintTip? = { _, _ -> null },
    onPinClick: ((Card, Pin) -> Unit)? = null,
    onCardOpen: ((String) -> Unit)? = null,
    focusable: Boolean = false,
    port: PinRef? = null,
    editor: GraphEditor? = null,
    cardContent: (@Composable (Card) -> Unit)? = null,
    interaction: CanvasEditing? = null,
    initialFit: (() -> Unit)? = null,
    foldLook: (@Composable (Card, Fold) -> BlueprintCardLook?)? = null,
    editable: Boolean = true,
    onAgentOpen: (BlueprintAgent) -> Unit = {},
    onFrameClick: ((Frame) -> Unit)? = null,
    onGeometry: ((BlueprintLayout<K>, Double) -> Unit)? = null,
    labelExclusions: List<Rect> = emptyList(),
    focusLabels: Boolean = false,
    denseOverview: Boolean = false,
    subject: String? = null,
    topInset: Dp = 48.dp,
    mapLayers: Boolean = false,
) {
    BlueprintCanvas(layout, canvas, highlight, selected, linkStyle, frameLook, cardLook, onCardClick, onLinkClick, onPaneClick,
        modifier = modifier, labels = labels, fitKey = fitKey, fitScene = fitScene, startInset = startInset, pulses = pulses,
        glow = glow, pinTip = pinTip, onPinClick = onPinClick, onCardOpen = onCardOpen, focusable = focusable, port = port,
        editor = editor, cardContent = cardContent, interaction = interaction,
        initialFit = initialFit, foldLook = foldLook,
        editable = editable, onAgentOpen = onAgentOpen, onFrameClick = onFrameClick, onGeometry = onGeometry, labelExclusions = labelExclusions, focusLabels = focusLabels, denseOverview = denseOverview, subject = subject, topInset = topInset, mapLayers = mapLayers)
}
