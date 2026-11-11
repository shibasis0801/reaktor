package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.blueprint.canvas.Area
import dev.shibasis.reaktor.ui.machinesignal.colors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.blueprint.canvas.BlueprintIcon
import dev.shibasis.reaktor.blueprint.canvas.BlueprintPainter
import dev.shibasis.reaktor.blueprint.canvas.BlueprintHeaderSlots
import dev.shibasis.reaktor.blueprint.canvas.BlueprintDetailZoom
import dev.shibasis.reaktor.blueprint.canvas.BlueprintMapTitleZoom
import dev.shibasis.reaktor.blueprint.canvas.MapTextCache
import dev.shibasis.reaktor.blueprint.canvas.MapMarkerBatch
import dev.shibasis.reaktor.blueprint.canvas.BlueprintOverviewCards
import dev.shibasis.reaktor.blueprint.canvas.BlueprintAgentBadge
import dev.shibasis.reaktor.blueprint.canvas.BlueprintBadgeBounds
import dev.shibasis.reaktor.blueprint.canvas.BlueprintType
import dev.shibasis.reaktor.blueprint.canvas.CanvasGrid
import dev.shibasis.reaktor.blueprint.canvas.CanvasPulse
import dev.shibasis.reaktor.blueprint.canvas.GraphMarkers
import dev.shibasis.reaktor.blueprint.canvas.GraphView
import dev.shibasis.reaktor.blueprint.canvas.Said
import dev.shibasis.reaktor.blueprint.canvas.at
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.GraphLevels
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
import dev.shibasis.reaktor.blueprint.editor.EditorTool
import dev.shibasis.reaktor.blueprint.editor.FoldSeparator
import dev.shibasis.reaktor.blueprint.editor.Fold
import dev.shibasis.reaktor.blueprint.editor.Gesture
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.GraphEditorLayer
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.Popover
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.Tooltip
import dev.shibasis.reaktor.surface.compose.TooltipAppearance
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.ui.machinesignal.LocalMachineSignalFonts
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal

object Blueprint {
    val Container: Color @Composable get() = MachineSignal.colors.source
    val Live = MachineSignal.Status.Ok
    val Hop: Color @Composable get() = MachineSignal.colors.source
    val Rule: Color @Composable get() = MachineSignal.colors.entities.auth
    val Faint: Color @Composable get() = MachineSignal.colors.textStrong.copy(alpha = .16f)
    val Pulse = lerp(Live, Color.White, .8f)
    val ProseZoom = 0.01..2.5
    val ProseTitles = listOf(16.0, 14.0, 12.0)
    const val ProseMeta = 12.0

    fun link(
        tone: Color,
        highlighting: Boolean,
        lit: Boolean,
        cross: Boolean = false,
        signal: Boolean = false,
        moving: Boolean = false,
        pulse: Boolean = false,
        width: Float? = null,
        dash: Pair<Float, Float>? = null,
        lifted: Boolean = false,
    ): LinkStyle = LinkStyle(
        color = tone,
        alpha = when {
            !highlighting && cross && !signal -> 0.22f
            !highlighting -> if (signal) 0.95f else 0.6f
            lit -> 1f
            else -> 0.05f
        },
        width = when {
            lit -> 2.6f
            else -> width ?: 1.5f
        },
        glow = when {
            lit -> tone.copy(alpha = .35f)
            pulse -> tone.copy(alpha = .3f)
            else -> null
        },
        dash = if (moving) 7f to 5f else dash,
        flowing = moving,
        lifted = lifted,
    )
}

@Composable
fun <K> BlueprintCanvas(
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
    onPinOpen: ((Card, Pin) -> Boolean)? = null,
    focusable: Boolean = false,
    port: PinRef? = null,
    editor: GraphEditor? = null,
    cardContent: (@Composable (Card) -> Unit)? = null,
    interaction: dev.shibasis.reaktor.blueprint.canvas.CanvasEditing? = null,
    initialFit: (() -> Unit)? = null,
    foldLook: (@Composable (Card, Fold) -> BlueprintCardLook?)? = null,
    editable: Boolean = true,
    onAgentOpen: (BlueprintAgent) -> Unit = {},
    opening: BlueprintOpening<K>? = null,
    onFrameClick: ((Frame) -> Unit)? = null,
    onFrameOpen: ((Frame) -> Unit)? = null,
    onGeometry: ((BlueprintLayout<K>, Double) -> Unit)? = null,
    labelExclusions: List<Rect> = emptyList(),
    focusLabels: Boolean = false,
    denseOverview: Boolean = false,
    subject: String? = null,
    topInset: Dp = TopInset,
    mapLayers: Boolean = false,
    mapDetail: Boolean = false,
) {
    val cards = remember(layout) { layout.cards.values.toList() }
    val boxes = remember(layout) { layout.frames + cards }
    val detailLevel by remember(canvas) { derivedStateOf { canvas.level(canvas.zoom) } }
    val detailedMarkers by remember(canvas, cards.size, denseOverview, mapDetail) { derivedStateOf { if (mapDetail) canvas.zoom >= BlueprintMapTitleZoom else !denseOverview || cards.size <= BlueprintOverviewCards || canvas.zoom >= BlueprintDetailZoom } }
    val markerIndices = remember(boxes, opening, detailedMarkers, selected) { boxes.indices.filter { index ->
        if (index < layout.frames.size) true else cards[index - layout.frames.size].let { card ->
            card.id !in opening?.coveredCards.orEmpty() && (detailedMarkers || card.id == selected || card.pins.any { it.id == selected || selected in it.aliases })
        }
    } }
    val wired = remember(layout, port) { port?.let { at -> layout.links.filter(at::carries).mapTo(hashSetOf()) { it.id } } }
    val shown = remember(layout, port, wired, highlight) { port?.let { at -> layout.links.filter { it.id in wired.orEmpty() }.flatMapTo(hashSetOf(at.card)) { listOf(it.from, it.to) } } ?: highlight }
    val folds = editor?.folds.orEmpty()
    val looks = cards.map { card ->
        (folds[card.id]?.let { fold -> foldLook?.invoke(card, fold) ?: folded(editor!!, card, fold, frameLook) } ?: cardLook(card))?.let { look -> port?.takeIf { it.card == card.id }?.let { at -> look.copy(pins = look.pins.mapIndexed { k, pin -> pin?.copy(selected = card.pins.getOrNull(k)?.let { at.matches(card.id, it) } == true) }) } ?: look }
    }
    val glows = cards.map { glow(it.id) }
    val tints = editor?.placement?.notes?.associate { it.id to it.tint }
    val frameLooks = layout.frames.map { frame -> frameLook(frame).let { look -> tints?.get(frame.key)?.takeIf { it != 0L }?.let { look.copy(lit = Color(it.toInt())) } ?: look } }
    val controlled = (if (detailLevel == 2) frameLooks.indices.filter { frameLooks[it].hasHeaderContent } else emptyList()) +
        (if (detailLevel > 0) cards.indices.filter { looks[it]?.drawsControls == true }.map { it + layout.frames.size } else emptyList())
    val carried = if (detailLevel > 0) cards.indices.filter { cardContent != null || looks[it]?.agent != null }.map { it + layout.frames.size } else emptyList()
    val stillIndices = remember(markerIndices, controlled) { controlled.toHashSet().let { live -> markerIndices.filter { it !in live } } }
    val liveIndices = remember(markerIndices, controlled, carried) { (controlled + carried).toHashSet().let { live -> markerIndices.filter { it in live } } }
    val stillBoxes = remember(boxes, stillIndices) { stillIndices.map(boxes::get) }
    val liveBoxes = remember(boxes, liveIndices) { liveIndices.map(boxes::get) }
    val styles = layout.links.map { link ->
        linkStyle(link).let { style -> wired?.let { ids -> (link.id in ids).let { on -> Blueprint.link(style.color, true, on, moving = on, width = style.width, dash = style.dash, lifted = on).copy(startLabel = style.startLabel, endLabel = style.endLabel) } } ?: style }
    }
    val density = LocalDensity.current.density
    val fonts = LocalMachineSignalFonts.current
    val base = LocalTextStyle.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val colors = MachineSignal.colors
    val painter = remember(graphics, resolver, direction, fonts, base, colors, denseOverview, mapLayers, mapDetail) {
        BlueprintPainter(graphics, TextMeasurer(resolver, Density(1f), direction, cacheSize = if (mapLayers) MapTextCache else 512), BlueprintType(fonts.ui, fonts.mono, base, fonts.graphLabel), colors, denseOverview, mapLayers, mapDetail)
    }
    fun syncPainter() {
        canvas.insetTop = topInset.value.toDouble()
        canvas.insetStart = startInset.value.toDouble()
        canvas.extent = boxes
        painter.sync(editor?.takeIf { it.settled.cards === layout.cards }?.layout ?: layout, looks, glows, frameLooks, styles, selected, shown, labels, (editor?.gesture as? Gesture.Move)?.cards.orEmpty(), editor?.context.orEmpty(),
            focusLabels)
        canvas.reading = painter::reading
        canvas.readableZoom = painter::readableZoom
        editor?.font = fonts.ui
    }
    SideEffect { syncPainter() }
    LaunchedEffect(canvas, canvas.pinRequest, layout) {
        while (canvas.pinRequest != null) { withFrameNanos { }; canvas.revealPin() }
    }
    DisposableEffect(interaction, editor, painter, canvas, editable) {
        val editing = (interaction ?: editor).takeIf { editable }
        editing?.pins { painter.drawn(it) }
        onDispose { editing?.pins { true } }
    }
    LaunchedEffect(fitKey, canvas.flow.canvasSize, layout, density) {
        syncPainter()
        if (boxes.isEmpty()) return@LaunchedEffect
        canvas.fitOnce(fitKey, fitScene) {
            initialFit?.invoke() ?: cards.firstOrNull { it.id == subject }?.let { canvas.fitReadable(boxes, it) }
                ?: canvas.fit(boxes, minZoom = canvas.zoomRange.start)
        }
    }
    val phase = remember { mutableFloatStateOf(0f) }
    if (styles.any { it.flowing }) LaunchedEffect(Unit) {
        val begin = withFrameMillis { it }
        while (true) withFrameMillis { phase.floatValue = (it - begin) % FlowCycleMillis * FlowPeriod / FlowCycleMillis }
    }
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(pulses) { while (pulses.any { it.born.elapsedNow().inWholeMilliseconds < it.travelMillis }) withFrameNanos { tick.intValue++ } }
    val cardClick by rememberUpdatedState({ id: String ->
        if (editor == null) onCardClick(id) else editor.select(Selection(setOf(id)))
        Unit
    })
    val linkClick by rememberUpdatedState(onLinkClick)
    val pinClick by rememberUpdatedState(onPinClick)
    val paneClick by rememberUpdatedState(onPaneClick)
    val cardOpen by rememberUpdatedState(onCardOpen)
    val pinOpen by rememberUpdatedState(onPinOpen)
    val frameClick by rememberUpdatedState(onFrameClick)
    val frameOpen by rememberUpdatedState(onFrameOpen)
    val window = LocalWindowInfo.current
    val coveredCards = opening?.coveredCards.orEmpty().keys
    val coveredRoutes = opening?.coveredRoutes.orEmpty()
    SideEffect { editor?.cover(coveredCards, coveredRoutes) }
    DisposableEffect(editor) { onDispose { editor?.cover(emptySet(), emptySet()) } }
    fun cardAt(x: Float, y: Float) = painter.cardAt(x, y)?.takeIf { it !in coveredCards }
    fun endAt(x: Float, y: Float) = painter.endAt(x, y)?.takeIf { it !in coveredCards }
    fun linkAt(x: Float, y: Float, reach: Float) = painter.paintedLinkAt(x, y, reach)?.takeIf { it.id !in coveredRoutes }
    val view: @Composable (Modifier) -> Unit = { shell ->
    GraphView(painter, canvas,
        hit = { x, y, reach -> cardAt(x, y) != null || linkAt(x, y, reach) != null || endAt(x, y) != null || frameClick != null && painter.frameAt(x, y) != null },
        tap = { x, y, reach ->
            cardAt(x, y)?.let { id -> pinClick?.let { click -> painter.pinAt(x, y)?.takeIf { it.first.id !in coveredCards }?.let { (card, pin) -> click(card, pin) } } ?: painter.rowAt(x, y)?.onClick?.invoke() ?: cardClick(id); true }
                ?: linkAt(x, y, reach)?.let { linkClick(it); true }
                ?: endAt(x, y)?.let { id -> cardClick(id); true }
                ?: (painter.frameAt(x, y)?.let { frameClick?.invoke(it); true } ?: false)
        },
        modifier = shell,
        onPane = paneClick,
        onSize = { canvas.resize(it, window.containerSize) },
        selection = { selected?.takeIf { it !in coveredCards }?.let(layout.cards::get) },
        step = { by ->
            val order = cards.indices.filter { looks[it] != null && cards[it].id !in coveredCards }.sortedWith(compareBy({ cards[it].y }, { cards[it].x }))
            if (order.isNotEmpty()) cards[order[(order.indexOfFirst { cards[it].id == selected } + if (selected == null && by < 0) 0 else by).mod(order.size)]].let { cardClick(it.id) }
        },
        open = { x, y ->
            pinOpen?.let { opener -> painter.rowPinAt(x, y)?.takeIf { it.first.id !in coveredCards }?.let { (card, pin) -> opener(card, pin) } } == true
                || painter.frameAt(x, y)?.let { frame -> frameOpen?.invoke(frame) ?: canvas.fit(frame.x, frame.y, frame.width, frame.height, maxZoom = 1.4); true }
                ?: cardAt(x, y)?.let { id -> cardOpen?.invoke(id) ?: layout.cards[id]?.let { canvas.centre(it.x, it.y, it.width, it.height) }; true } ?: false
        },
        doubleOpen = { x, y -> pinOpen?.let { opener -> painter.rowPinAt(x, y)?.takeIf { it.first.id !in coveredCards }?.let { (card, pin) -> opener(card, pin) } } == true },
        enterOpens = onCardOpen != null,
        focusable = focusable,
        editing = if (editable) interaction ?: editor else null,
        preempt = if (editable && interaction == null && editor != null && onFrameClick != null) { x, y, reach ->
            editor.tool == EditorTool.Select && cardAt(x, y) == null && linkAt(x, y, reach) == null && endAt(x, y) == null &&
                painter.frameAt(x, y)?.takeIf { frame -> editor.placement.notes.none { it.id == frame.key } }?.let { frameClick?.invoke(it); true } == true
        } else null,
        markers = { focus ->
            val markerId = { index: Int -> if (index < layout.frames.size) "frame:${layout.frames[index].key}" else cards[index - layout.frames.size].id }
            @Composable fun Marker(card: Card, look: BlueprintCardLook) {
                val slots = if (look.hasHeaderContent && detailLevel > 0) {
                    val dimmed = card.id in editor?.context.orEmpty() || shown.isNotEmpty() && card.id !in shown
                    painter.cardHeaderSlots(card, look, dimmed)
                } else null
                CardMarker(card, look, pinTip, { focus(); cardClick(it) },
                    pinClick?.let { click -> { card, pin -> focus(); click(card, pin) } }, slots, lean = mapLayers)
            }
            @Composable fun Region(frame: Frame, look: BlueprintFrameLook) {
                val slots = if (look.hasHeaderContent && detailLevel == 2) painter.frameHeaderSlots(frame, look) else null
                FrameMarker(frame, Area(frame.x, frame.y, frame.width, BlueprintEngine.FrameTop), look,
                    frameClick?.let { click -> { focus(); click(frame) } }, slots)
            }
            GraphMarkers(canvas, stillBoxes, { markerId(stillIndices[it]) }, follow = false, batch = if (mapLayers) MapMarkerBatch else Int.MAX_VALUE) { marker, _ ->
                val i = stillIndices[marker]
                if (i < layout.frames.size) Region(layout.frames[i], frameLooks[i])
                else looks[i - layout.frames.size]?.let { look -> Marker(cards[i - layout.frames.size], look) }
            }
            if (liveBoxes.isNotEmpty()) GraphMarkers(canvas, liveBoxes, { markerId(liveIndices[it]) }, batch = if (mapLayers) MapMarkerBatch else Int.MAX_VALUE) { marker, _ ->
                val i = liveIndices[marker]
                val moving = editor?.takeIf { it.settled.cards === layout.cards }?.layout
                if (i < layout.frames.size) {
                    val frame = layout.frames[i]
                    Region(moving?.frames?.getOrNull(i)?.takeIf { it.key == frame.key } ?: frame, frameLooks[i])
                } else looks[i - layout.frames.size]?.let { look ->
                    val card = cards[i - layout.frames.size]
                    if (look.drawsControls) Marker(if (look.hasHeaderContent) moving?.cards?.get(card.id) ?: card else card, look)
                    look.agent?.let { agent ->
                        painter.agentBounds(card.id)?.let { bounds -> AgentMarker(card, agent, bounds, canvas, onAgentOpen) }
                    }
                    cardContent?.let { content ->
                        Box(Modifier.absoluteOffset(card.x.toFloat().dp, (card.y + BlueprintEngine.HeaderHeight).toFloat().dp)
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(card.width.toFloat().dp, (card.height - BlueprintEngine.HeaderHeight).coerceAtLeast(0.0).toFloat().dp).clipToBounds()) {
                            content(card)
                        }
                    }
                }
            }
        },
    ) { camera, viewport, scale ->
        tick.intValue
        syncPainter()
        painter.draw(this, camera, viewport, scale, phase.floatValue, pulses, labelExclusions)
        onGeometry?.invoke(layout, camera.zoom)
    }
    }
    if (editor == null) view(modifier) else Box(modifier) { view(Modifier.fillMaxSize()); GraphEditorLayer(editor) }
}

@Composable
private fun folded(editor: GraphEditor, card: Card, fold: Fold, frameLook: @Composable (Frame) -> BlueprintFrameLook): BlueprintCardLook {
    val look = frameLook(Frame(fold.id, fold.title, card.x, card.y, card.width, card.height, fold.members, emptyList(), false))
    val tone = look.lit ?: Blueprint.Container
    val muted = MachineSignal.colors.textMuted
    return BlueprintCardLook(tone, look.icon, fold.title, "${fold.members.size} node${if (fold.members.size == 1) "" else "s"} folded · double-click to expand",
        selected = fold.id in editor.selection.notes, folded = "${card.pins.size} pins", marks = look.marks,
        pins = card.pins.map { pin -> BlueprintPinLook(tone, pin.key.substringBefore(FoldSeparator).let { owner -> editor.node(owner)?.let { editor.terms.end(it, pin.key.substringAfter(FoldSeparator)) } } ?: pin.key, muted, true) },
        tag = "graph-fold-${fold.id}", action = "Select ${fold.title}")
}

@Composable
private fun FrameMarker(frame: Frame, target: Bounds, look: BlueprintFrameLook, onFrameClick: (() -> Unit)?, slots: BlueprintHeaderSlots?) {
    Box(Modifier.at(target.x, target.y, target.width, target.height).semantics {
        selected = look.selected
        onFrameClick?.let { click -> onClick(label = "Select ${frame.label}") { click(); true } }
    }.tagged(look.tag)) {
        if (slots?.title == null) Said(frame.label)
        Said(look.count)
        Said(frame.detail.joinToString(" · "))
        look.marks.filterNot { it in look.markContent }.forEach { Said(it.said) }
        slots?.let { HeaderContent(it, look.titleContent, look.markContent) }
    }
}

@Composable
private fun HeaderContent(
    slots: BlueprintHeaderSlots,
    titleContent: (@Composable (TextStyle) -> Unit)?,
    markContent: Map<BlueprintMark, @Composable () -> Unit>,
) {
    fun Rect.slot() = Modifier.at(left.toDouble(), top.toDouble(), width.toDouble(), height.toDouble())
    slots.title?.takeUnless { it.bounds.isEmpty }?.let { title ->
        Box(title.bounds.slot().clipToBounds(), contentAlignment = if (title.centered) Alignment.CenterStart else Alignment.TopStart) {
            titleContent?.invoke(title.style)
        }
    }
    val inheritedStyle = LocalTextStyle.current
    slots.marks.forEach { (mark, bounds, style) ->
        Box(bounds.slot()) {
            CompositionLocalProvider(LocalTextStyle provides (style ?: inheritedStyle)) { markContent[mark]?.invoke() }
        }
    }
}

internal object BlueprintBadgeStyle {
    const val HorizontalPadding = 5f
    const val MinHeight = 16f
    const val Radius = 3f
    const val Tint = .18f
    val Shape = RoundedCornerShape(Radius.dp)
}

/** A badge for controls hosted in a Blueprint mark slot, using the slot's label style. */
@Composable
fun BlueprintBadge(mark: BlueprintMark, highlighted: Boolean = false) {
    val shape = BlueprintBadgeStyle.Shape
    Box(
        Modifier.heightIn(min = BlueprintBadgeStyle.MinHeight.dp)
            .background(mark.color.copy(alpha = BlueprintBadgeStyle.Tint), shape)
            .then(if (highlighted) Modifier.border(MachineSignal.Stroke.hairline, mark.color, shape) else Modifier)
            .padding(horizontal = BlueprintBadgeStyle.HorizontalPadding.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(mark.text, style = LocalTextStyle.current.copy(color = mark.color), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun AgentMarker(card: Card, agent: BlueprintAgent, bounds: BlueprintBadgeBounds, canvas: GraphCanvasState, onOpen: (BlueprintAgent) -> Unit) {
    val visible by remember(card, canvas) { derivedStateOf {
        val view = canvas.flow.viewport
        val size = canvas.flow.canvasSize
        card.x * view.zoom + view.x < size.width && (card.x + card.width) * view.zoom + view.x > 0 &&
            card.y * view.zoom + view.y < size.height && (card.y + card.height) * view.zoom + view.y > 0
    } }
    Box(Modifier.absoluteOffset(bounds.x.toFloat().dp, bounds.y.toFloat().dp)
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        .requiredSize((bounds.width / bounds.scale).toFloat().dp, (bounds.height / bounds.scale).toFloat().dp)
        .graphicsLayer { scaleX = bounds.scale.toFloat(); scaleY = scaleX; transformOrigin = TransformOrigin(0f, 0f) }) {
        BlueprintAgentBadge(agent, { onOpen(agent) }, Modifier.fillMaxSize(), compact = bounds.compact, visible = visible, tag = "blueprint-agent-${card.id}", fillHeight = true, onParticipantOpen = onOpen)
    }
}

@Composable
internal fun CardMarker(card: Card, look: BlueprintCardLook, pinTip: (Card, Pin) -> BlueprintTip?, onCardClick: (String) -> Unit,
                        onPinClick: ((Card, Pin) -> Unit)?, slots: BlueprintHeaderSlots? = null, lean: Boolean = false) {
    val exact = !lean || slots != null || look.actions.isNotEmpty() || look.choice != null || look.rows.any { it.content.choice != null } || look.pins.any { it?.choice != null }
    Box(Modifier.at(card.x, card.y, card.width, card.height, exact).semantics {
        selected = look.selected
        look.action?.let { onClick(label = it) { onCardClick(card.id); true } }
    }.tagged(look.tag)) {
        look.choice?.let { choice ->
            Box(Modifier.at(0.0, 0.0, BlueprintEngine.RowHeight, BlueprintEngine.RowHeight), contentAlignment = Alignment.Center) {
                dev.shibasis.reaktor.surface.compose.Checkbox(if (choice.checked) dev.shibasis.reaktor.surface.compose.CheckState.Checked else dev.shibasis.reaktor.surface.compose.CheckState.Unchecked, choice.change, Modifier.testTag(choice.tag))
            }
        }
        Box(Modifier.at(0.0, 0.0, card.width, BlueprintEngine.HeaderHeight, exact)) {
            if (slots?.title == null) Said(look.title)
            look.imageDescription?.let { Said(it) }
            look.eyebrow?.let { Said(it) }
            Said(look.subtitle)
            if (look.actions.isNotEmpty()) {
                Row(Modifier.align(Alignment.TopEnd).padding(end = MachineSignal.Editor.controlGap),
                    verticalAlignment = Alignment.CenterVertically) {
                    look.actions.forEach { action ->
                        Tooltip(tip = { CanvasText(action.label, MachineSignal.colors.textStrong, MachineSignal.Editor.label) }) {
                            val glyph = remember(action.icon) { BlueprintIcon(action.icon) }
                            Canvas(Modifier.size(MachineSignal.Editor.controlHeight)
                                .testTag("blueprint-card-action:${card.id}:${action.id}")
                                .semantics { contentDescription = action.label }
                                .clickable(onClickLabel = action.label, role = Role.Button, onClick = action.onInvoke)) {
                                val side = MachineSignal.Editor.controlIconSize.toPx()
                                glyph.draw(this, (size.width - side) / 2, (size.height - side) / 2, side, look.tone)
                            }
                        }
                    }
                }
            }
        }
        look.marks.filterNot { it in look.markContent }.forEach { Said(it.said) }
        look.folded?.let { Said(it) }
        look.rows.forEachIndexed { index, row ->
            val content = row.content
            val cells = listOf(content) + row.trailingCells
            Box(Modifier.at(0.0, BlueprintEngine.pinY(index, card.pinHeader) - BlueprintEngine.RowHeight / 2, card.width, BlueprintEngine.RowHeight, exact).semantics {
                selected = content.selected
                row.onClick?.let { click -> onClick { click(); true } }
                if (lean) text = AnnotatedString(cells.flatMap { cell -> listOf(cell.text) + cell.marks.map { it.said } }.filter(String::isNotEmpty).joinToString(" · "))
            }.tagged(content.tag)) {
                if (!lean) cells.forEach { cell ->
                    Said(cell.text)
                    cell.marks.forEach { Said(it.said) }
                }
                content.choice?.let { choice ->
                    Box(Modifier.at(0.0, 0.0, BlueprintEngine.RowHeight, BlueprintEngine.RowHeight), contentAlignment = Alignment.Center) {
                        dev.shibasis.reaktor.surface.compose.Checkbox(if (choice.checked) dev.shibasis.reaktor.surface.compose.CheckState.Checked else dev.shibasis.reaktor.surface.compose.CheckState.Unchecked, choice.change, Modifier.testTag(choice.tag))
                    }
                }
            }
        }
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        card.pins.forEachIndexed { k, pin ->
            val pinLook = look.pins.getOrNull(k) ?: return@forEachIndexed
            val shared = look.rows.any { it.id == pin.key }
            if (lean && shared) return@forEachIndexed
            val text = if (shared) if (pin.provides) "Output anchor" else "Input anchor" else pinLook.text
            val width = if (shared) BlueprintEngine.RowHeight else if (oneSided) card.width else card.width / 2
            val left = if (shared && pin.provides) card.width - width else if (pin.provides && !oneSided) card.width / 2 else 0.0
            Box(Modifier.at(left, pin.y - BlueprintEngine.RowHeight / 2, width, BlueprintEngine.RowHeight, exact).then(if (onPinClick == null) Modifier else Modifier.semantics {
                selected = pinLook.selected
                onClick { onPinClick(card, pin); true }
            }.tagged(pinLook.tag ?: look.tag?.let { "$it-${if (pin.provides) "provides" else "needs"}-${pin.key}" }))) {
                val tip = pinTip(card, pin)
                if (tip == null) Said(text) else Tooltip(appearance = tip.appearance ?: UnframedBlueprintTip, tip = tip.content) { Said(text) }
                if (!shared) {
                    pinLook.marks.forEach { Said(it.said) }
                    pinLook.choice?.let { choice ->
                        Box(Modifier.at(if (pin.provides) width - BlueprintEngine.RowHeight else 0.0, 0.0, BlueprintEngine.RowHeight, BlueprintEngine.RowHeight), contentAlignment = Alignment.Center) {
                            dev.shibasis.reaktor.surface.compose.Checkbox(if (choice.checked) dev.shibasis.reaktor.surface.compose.CheckState.Checked else dev.shibasis.reaktor.surface.compose.CheckState.Unchecked, choice.change, Modifier.testTag(choice.tag))
                        }
                    }
                }
            }
        }
        slots?.let { HeaderContent(it, look.titleContent, look.markContent) }
    }
}

private val BlueprintMark.said get() = when (kind) {
    BlueprintMark.Kind.Tag -> text.uppercase()
    else -> text
}

private fun Modifier.tagged(tag: String?) = if (tag == null) this else testTag(tag)

private val TopInset = 48.dp
private const val FlowPeriod = 22f
private const val FlowCycleMillis = 700L

sealed interface LegendEntry {
    data class Dot(val color: Color, val label: String) : LegendEntry
    data class Line(val color: Color, val label: String) : LegendEntry
    data class Note(val label: String) : LegendEntry
}

@Composable
fun measureBlueprintBody(node: BlueprintNode, look: BlueprintCardLook): BlueprintBody {
    val fonts = LocalMachineSignalFonts.current
    val base = LocalTextStyle.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val colors = MachineSignal.colors
    val painter = remember(graphics, resolver, direction, fonts, base, colors) {
        BlueprintPainter(graphics, TextMeasurer(resolver, Density(1f), direction, cacheSize = 512), BlueprintType(fonts.ui, fonts.mono, base, fonts.graphLabel), colors)
    }
    return remember(painter, node, look) { painter.body(node, look) }
}

@Composable
fun measureBlueprintBodies(entries: Map<String, Pair<BlueprintNode, BlueprintCardLook>>): Map<String, BlueprintBody> {
    val fonts = LocalMachineSignalFonts.current
    val base = LocalTextStyle.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val colors = MachineSignal.colors
    val painter = remember(graphics, resolver, direction, fonts, base, colors) {
        BlueprintPainter(graphics, TextMeasurer(resolver, Density(1f), direction, cacheSize = 2048), BlueprintType(fonts.ui, fonts.mono, base, fonts.graphLabel), colors)
    }
    return remember(painter, entries) {
        entries.mapValues { (_, pair) -> painter.body(pair.first, pair.second) }
    }
}

@Composable
fun BlueprintLegend(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Popover(expanded, { expanded = it }, modifier) {
        Trigger(Modifier.testTag("blueprint-legend")) {
            CanvasText("Legend", color = MachineSignal.colors.textMuted, size = MachineSignal.Type.label)
        }
        Content {
            Column(Modifier.width(MachineSignal.GraphResults.width).padding(MachineSignal.Space.s3),
                verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
                entries.forEach { entry ->
                    when (entry) {
                        is LegendEntry.Dot -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
                            Box(Modifier.size(MachineSignal.Space.s2).clip(CircleShape).background(entry.color))
                            CanvasText(entry.label, color = MachineSignal.colors.textMuted, size = MachineSignal.Type.label)
                        }
                        is LegendEntry.Line -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
                            Box(Modifier.width(MachineSignal.Space.s4).height(MachineSignal.Space.s0_5).background(entry.color))
                            CanvasText(entry.label, color = MachineSignal.colors.textMuted, size = MachineSignal.Type.label)
                        }
                        is LegendEntry.Note -> CanvasText(entry.label, color = MachineSignal.colors.textMuted, size = MachineSignal.Type.label)
                    }
                }
            }
        }
    }
}

@Composable
fun BlueprintWatermark(title: String, line: String, lineColor: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        CanvasText(title.uppercase(), color = MachineSignal.colors.textStrong.copy(alpha = .07f), size = 38.sp, weight = FontWeight.Bold)
        CanvasText(line, color = lineColor, size = 12.sp, weight = FontWeight.SemiBold)
    }
}

private val UnframedBlueprintTip: TooltipAppearance = object : TooltipAppearance {
    @Composable
    override fun Content(properties: TipContent, state: TooltipState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: TooltipSlots) = slots.tip()
}

@Composable
private fun CanvasText(text: String, color: Color, size: TextUnit, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontFamily = LocalMachineSignalFonts.current.ui, fontSize = size, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
