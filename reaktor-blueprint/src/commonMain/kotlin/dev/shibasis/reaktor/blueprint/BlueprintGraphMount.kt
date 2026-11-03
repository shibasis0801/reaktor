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

fun AppGraph.blueprintMap(request: LayoutRequest = LayoutRequest(Detail.Ports)): BlueprintMapLevels<LayoutRequest> {
    val full = AppGraphLayout.layout(this, request.copy(detail = Detail.Ports, nested = true, bundles = true,
        expandedFlows = containers.keys))
    return full.blueprintApplicationMapLevels()
}

/** Keep organizational wrappers open so their actual child scopes and corridors remain visible. */
fun <K> BlueprintLayout<K>.blueprintApplicationMapLevels(): BlueprintMapLevels<K> {
    val wrappers = frames.mapNotNullTo(HashSet()) { it.parent }
    return blueprintMapLevels { frame ->
        "summary:${frame.key}".takeIf { frame.key !in wrappers && (frame.parent != null || frames.size == 1) }
    }
}

fun <K> BlueprintLayout<K>.blueprintMapLevels(
    summary: (Frame) -> String? = { frame -> "summary:${frame.key}".takeIf { frame.parent != null || frames.size == 1 } },
): BlueprintMapLevels<K> {
    val frameOf = frames.flatMap { frame -> frame.nodes.map { it to frame } }.toMap()
    return BlueprintMapLevels.prepare(this, titleZoom = { card ->
        maxOf(frameOf[card.id]?.let { frame -> 384.0 / frame.width.coerceAtLeast(1.0) } ?: 0.0,
            128.0 / card.width.coerceAtLeast(1.0), 48.0 / card.height.coerceAtLeast(1.0)).coerceAtMost(.9 / 1.12)
    }, summary = summary)
}

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
    mapLevels: BlueprintMapLevels<K>? = null,
    mapLevel: BlueprintMapLevel<K>? = null,
    onGeometry: ((BlueprintLayout<K>, Double, List<Bounds>) -> Unit)? = null,
    labelExclusions: List<Rect> = emptyList(),
) {
    val drawn = mapLevel?.layout ?: layout
    val full = mapLevels?.full ?: layout
    BlueprintCanvas(drawn, canvas, highlight, selected, linkStyle, frameLook, cardLook, onCardClick, onLinkClick, onPaneClick,
        modifier = modifier, labels = labels, fitKey = fitKey, fitScene = fitScene, startInset = startInset, pulses = pulses,
        glow = glow, pinTip = pinTip, onPinClick = onPinClick, onCardOpen = onCardOpen, focusable = focusable, port = port,
        editor = editor, cardContent = cardContent, interaction = interaction,
        initialFit = initialFit ?: mapLevels?.let { { canvas.fit(full.frames + full.cards.values) } }, foldLook = foldLook,
        editable = editable, onAgentOpen = onAgentOpen, onFrameClick = onFrameClick, mapLevels = mapLevels, mapLevel = mapLevel, onGeometry = onGeometry, labelExclusions = labelExclusions)
}
