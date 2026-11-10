package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
import dev.shibasis.reaktor.blueprint.editor.Fold
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.graph.core.NodeShape

data class BlueprintAgent(
    val image: ImageBitmap?,
    val label: String,
    val activity: Activity,
    val otherAgents: Int = 0,
    val effort: String? = null,
    val model: String? = null,
    val role: String? = null,
    val companions: List<BlueprintAgent> = emptyList(),
    val key: String? = null,
    val person: Boolean = false,
) {
    init {
        require(otherAgents >= 0)
    }

    enum class Activity(val label: String) {
        Unassigned("Unassigned"),
        Assigned("Assigned"),
        Claimed("Claimed"),
        Live("Live"),
        Stale("Stale"),
        Completed("Completed"),
        LastChangedBy("Last changed by"),
        Unavailable("Activity unavailable"),
    }
}

@Composable
fun BlueprintGraph(
    editor: GraphEditor,
    canvas: GraphCanvasState,
    cardLook: @Composable (NodeShape) -> BlueprintCardLook,
    groupLook: @Composable (Frame) -> BlueprintFrameLook,
    linkStyle: (Link) -> LinkStyle?,
    linkLabel: (Link) -> String?,
    modifier: Modifier = Modifier,
    foldLook: (@Composable (Card, Fold) -> BlueprintCardLook?)? = null,
    onAgentOpen: (BlueprintAgent) -> Unit = {},
    opening: BlueprintOpening<String>? = null,
    cardContent: (@Composable (Card) -> Unit)? = null,
    onPinClick: ((Card, Pin) -> Unit)? = null,
    onCardOpen: ((String) -> Unit)? = null,
    onPaneClick: (() -> Unit)? = null,
    editable: Boolean = true,
    onFrameClick: ((Frame) -> Unit)? = null,
    onFrameOpen: ((Frame) -> Unit)? = null,
    bundles: Boolean? = null,
    onGeometry: ((BlueprintLayout<String>, Double) -> Unit)? = null,
    denseOverview: Boolean = false,
    subject: String? = null,
    mapLayers: Boolean = false,
    mapDetail: Boolean = false,
    fitKey: Int? = null,
    fitScene: Any? = null,
    initialFit: (() -> Unit)? = null,
    startInset: Dp = 0.dp,
    topInset: Dp = 48.dp,
) {
    androidx.compose.runtime.SideEffect { bundles?.let { editor.routing = editor.routing.copy(bundles = it) } }
    val activeOpening = opening ?: editor.opening
    val layout = activeOpening?.layout ?: editor.settled
    val faint = Blueprint.Faint
    val fit = fitKey ?: (layout.key.hashCode() * 31 + layout.cards.keys.hashCode())
    BlueprintCanvas(
        layout = layout,
        canvas = canvas,
        fitKey = fit,
        fitScene = fitScene ?: fit,
        initialFit = initialFit,
        startInset = startInset,
        topInset = topInset,
        highlight = editor.selection.nodes,
        selected = editor.selection.nodes.singleOrNull(),
        linkStyle = { link -> linkStyle(link) ?: LinkStyle(faint) },
        frameLook = groupLook,
        cardLook = { card -> (editor.node(card.id) ?: editor.ghost(card.id))?.let { node ->
            cardLook(node).copy(selected = card.id in editor.selection.nodes)
        } },
        onCardClick = { id ->
            editor.select(if (layout.frames.any { it.key == id }) Selection(notes = setOf(id)) else Selection(nodes = setOf(id)))
        },
        onLinkClick = { editor.select(Selection(wires = it.members.toSet())) },
        onPaneClick = onPaneClick ?: { editor.select(Selection()) },
        onPinClick = onPinClick,
        onCardOpen = onCardOpen,
        onFrameClick = onFrameClick,
        onFrameOpen = onFrameOpen,
        editable = editable,
        modifier = modifier,
        labels = layout.links.mapNotNull { link -> linkLabel(link)?.let { link.id to it } }.toMap(),
        focusable = true,
        editor = editor,
        foldLook = foldLook,
        cardContent = cardContent,
        onAgentOpen = onAgentOpen,
        opening = activeOpening,
        onGeometry = onGeometry,
        denseOverview = denseOverview,
        subject = subject,
        mapLayers = mapLayers,
        mapDetail = mapDetail,
    )
}
