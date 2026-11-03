package dev.shibasis.reaktor.blueprint

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.blueprint.canvas.BlueprintIcon
import dev.shibasis.reaktor.blueprint.canvas.BlueprintPainter
import dev.shibasis.reaktor.blueprint.canvas.paintMap
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
    val Body = MachineSignal.Editor.Canvas.copy(alpha = .95f)
    val Grid = CanvasGrid(minor = MachineSignal.Editor.Muted.copy(alpha = .025f), major = MachineSignal.Editor.Muted.copy(alpha = .055f))
    val Container = MachineSignal.Editor.Source
    val Live = MachineSignal.Status.Ok
    val Hop = MachineSignal.Editor.Source
    val Rule = MachineSignal.Entity.Auth
    val Faint = Color.White.copy(alpha = .16f)
    val Pulse = lerp(Live, Color.White, .8f)
    const val FarZoom = 0.42
    val ProseLevels = GraphLevels(meshIn = .28, meshOut = .25, farIn = 1.0, farOut = .92)
    val ProseZoom = 0.01..2.5
    val ProseBands = listOf(ProseBand(.69, 16.0), ProseBand(.5, 22.0), ProseBand(.4, 28.0, block = true), ProseBand(.31, 36.0, block = true), ProseBand(.25, 44.0, block = true), ProseBand(0.0, 0.0, block = true))
    val ProseTitles = listOf(16.0, 14.0, 12.0)
    const val ProseMeta = 12.0

    data class ProseBand(val from: Double, val size: Double, val block: Boolean = false)

    fun fitted(text: String, width: Double, base: Double, zoom: Double): TextUnit {
        val wanted = base * (1.0 / zoom.coerceIn(0.35, 1.0)).coerceAtMost(1.8)
        val fits = width / (text.length.coerceAtLeast(1) * 0.61)
        return wanted.coerceAtMost(fits.coerceAtLeast(base)).sp
    }

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
    mapLevels: BlueprintMapLevels<K>? = null,
    mapLevel: BlueprintMapLevel<K>? = null,
    onGeometry: ((BlueprintLayout<K>, Double, List<Bounds>) -> Unit)? = null,
    labelExclusions: List<Rect> = emptyList(),
) {
    val currentMapLevel = mapLevel ?: mapLevels?.at(canvas.zoom)
    val paintedMap = if (mapLevels != null && currentMapLevel != null) paintMap(mapLevels, currentMapLevel) else null
    val paintedLayout = paintedMap?.layout ?: layout
    var viewerRegion by remember(canvas, mapLevels?.full?.key, editor) { mutableStateOf<Pair<String, String?>?>(null) }
    val selectedRegion = viewerRegion?.takeIf { editor == null && it.second == selected }?.first?.let { key ->
        mapLevels?.full?.frames?.firstOrNull { it.key == key }
    }
    val shownSelection = if (selectedRegion != null) currentMapLevel?.frames?.get(selectedRegion.key)?.summary?.id else selected
    val rawLooks = mapLevels?.let { levels ->
        (levels.full.cards.values + paintedLayout.cards.values.filter { it.id !in levels.full.cards }).associate { it.id to cardLook(it) }
    }.orEmpty()
    val fullFrames = mapLevels?.full?.frames?.associate { it.key to frameLook(it) }.orEmpty()
    val fullLooks = if (mapLevels != null && currentMapLevel != null) {
        val forwarded = paintedMap?.forwardedPorts ?: currentMapLevel.forwardedPorts
        rawLooks + currentMapLevel.frames.values.mapNotNull { group ->
            group.summary?.let { original -> paintedLayout.cards[original.id]?.let { card ->
                card.id to mapSummaryLook(mapLevels.full, group, card, rawLooks, fullFrames[group.frame.key], forwarded, MachineSignal.colors.textStrong)
            } }
        }
    } else rawLooks
    val fullStyles = mapLevels?.full?.links?.associate { it.id to linkStyle(it) }.orEmpty()
    val cards = remember(paintedLayout) { paintedLayout.cards.values.toList() }
    val boxes = remember(paintedLayout) { paintedLayout.frames + cards }
    val markerIndices = remember(boxes, opening) { boxes.indices.filter { index ->
        index < paintedLayout.frames.size || cards[index - paintedLayout.frames.size].id !in opening?.coveredCards.orEmpty()
    } }
    val markerBoxes = remember(boxes, markerIndices) { markerIndices.map(boxes::get) }
    val wired = remember(paintedLayout, port) { port?.let { at -> paintedLayout.links.filter(at::carries).mapTo(hashSetOf()) { it.id } } }
    val shown = remember(paintedLayout, port, wired, highlight) { port?.let { at -> paintedLayout.links.filter { it.id in wired.orEmpty() }.flatMapTo(hashSetOf(at.card)) { listOf(it.from, it.to) } } ?: highlight }
    val folds = editor?.folds.orEmpty()
    val looks = cards.map { card ->
        (folds[card.id]?.let { fold -> foldLook?.invoke(card, fold) ?: folded(editor!!, card, fold, frameLook) } ?: fullLooks[card.id] ?: cardLook(card))?.let { look -> port?.takeIf { it.card == card.id }?.let { at -> look.copy(pins = look.pins.mapIndexed { k, pin -> pin?.copy(selected = card.pins.getOrNull(k)?.let { at.matches(card.id, it) } == true) }) } ?: look }
    }
    val shownLooks = if (selectedRegion == null) looks else looks.mapIndexed { index, look -> look?.copy(selected = cards[index].id == shownSelection) }
    val glows = cards.map { glow(it.id) }
    val tints = editor?.placement?.notes?.associate { it.id to it.tint }
    val frameLooks = paintedLayout.frames.map { frame -> frameLook(frame).let { look -> tints?.get(frame.key)?.takeIf { it != 0L }?.let { look.copy(lit = Color(it.toInt())) } ?: look } }
    val styles = paintedLayout.links.map { link ->
        linkStyle(link).let { style -> wired?.let { ids -> (link.id in ids).let { on -> Blueprint.link(style.color, true, on, moving = on, width = style.width, dash = style.dash, lifted = on) } } ?: style }
    }
    val density = LocalDensity.current.density
    val fonts = LocalMachineSignalFonts.current
    val base = LocalTextStyle.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val colors = MachineSignal.colors
    val painter = remember(graphics, resolver, direction, fonts, base, colors) {
        BlueprintPainter(graphics, TextMeasurer(resolver, Density(1f), direction, cacheSize = 512), BlueprintType(fonts.ui, fonts.mono, base, fonts.graphLabel), colors)
    }
    fun syncPainter() {
        canvas.insetTop = TopInset.value.toDouble()
        canvas.insetStart = startInset.value.toDouble()
        canvas.extent = boxes
        painter.sync(editor?.takeIf { it.settled.cards === paintedLayout.cards }?.layout ?: paintedLayout, shownLooks, glows, frameLooks, styles, shownSelection, shown, labels, (editor?.gesture as? Gesture.Move)?.cards.orEmpty(), editor?.context.orEmpty(), opening,
            mapLevels, currentMapLevel, fullLooks, fullFrames, fullStyles, canvas.zoom)
        canvas.reading = painter::reading
        canvas.readableZoom = painter::readableZoom
        canvas.paintedBounds = painter::paintedBounds
        editor?.font = fonts.ui
    }
    SideEffect { syncPainter() }
    LaunchedEffect(canvas, canvas.pinRequest, paintedLayout) {
        while (canvas.pinRequest != null) { withFrameNanos { }; canvas.revealPin() }
    }
    DisposableEffect(interaction, editor, painter, canvas, editable) {
        val editing = (interaction ?: editor).takeIf { editable }
        editing?.pins { painter.rows(it, canvas.zoom) }
        onDispose { editing?.pins { true } }
    }
    LaunchedEffect(fitKey, canvas.flow.canvasSize, paintedLayout, density) {
        syncPainter()
        if (boxes.isEmpty()) return@LaunchedEffect
        canvas.fitOnce(fitKey, fitScene) { initialFit?.invoke() ?: canvas.fit(boxes, minZoom = canvas.zoomRange.start) }
    }
    val phase = remember { mutableFloatStateOf(0f) }
    if (styles.any { it.flowing }) LaunchedEffect(Unit) {
        val begin = withFrameMillis { it }
        while (true) withFrameMillis { phase.floatValue = (it - begin) % FlowCycleMillis * FlowPeriod / FlowCycleMillis }
    }
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(pulses) { while (pulses.any { it.born.elapsedNow().inWholeMilliseconds < it.travelMillis }) withFrameNanos { tick.intValue++ } }
    val projection = if (editor != null && mapLevels != null && currentMapLevel != null) {
        val forwarded = paintedMap?.forwardedPorts ?: currentMapLevel.forwardedPorts
        dev.shibasis.reaktor.blueprint.canvas.ProjectedMapInteraction(mapLevels.full,
            currentMapLevel.copy(layout = paintedLayout, forwardedPorts = forwarded))
    } else null
    fun applyProjected(resolved: dev.shibasis.reaktor.blueprint.canvas.ProjectedMapInteraction.Resolved?): Boolean {
        if (resolved == null || editor == null) return false
        editor.select(resolved.selection)
        resolved.pin?.let { (card, pin) -> onPinClick?.invoke(card, pin) }
        return true
    }
    val cardClick by rememberUpdatedState({ id: String ->
        val group = currentMapLevel?.frames?.values?.firstOrNull { it.summary?.id == id }
        if (editor == null && group != null) {
            viewerRegion = group.frame.key to selected
            onFrameClick?.invoke(group.frame)
        } else {
            viewerRegion = null
            if (editor == null) onCardClick(id)
            else if (projection == null) editor.select(Selection(setOf(id))) else applyProjected(projection.card(id))
        }
        Unit
    })
    val linkClick by rememberUpdatedState({ link: Link ->
        viewerRegion = null
        if (projection == null) onLinkClick(link) else applyProjected(projection.wire(link))
        Unit
    })
    val pinClick by rememberUpdatedState(if (projection == null && onPinClick == null) null else { card: Card, pin: Pin ->
        viewerRegion = null
        if (projection == null) onPinClick?.invoke(card, pin) else applyProjected(projection.pin(card.ref(pin)))
        Unit
    })
    val paneClick by rememberUpdatedState({ viewerRegion = null; onPaneClick() })
    val cardOpen by rememberUpdatedState(onCardOpen)
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
            cardAt(x, y)?.let { id -> pinClick?.let { click -> painter.pinAt(x, y)?.takeIf { it.first.id !in coveredCards }?.let { (card, pin) -> click(card, pin) } } ?: cardClick(id); true }
                ?: linkAt(x, y, reach)?.let { linkClick(it); true }
                ?: endAt(x, y)?.let { id -> cardClick(id); true }
                ?: (painter.frameAt(x, y)?.let { frameClick?.invoke(it); true } ?: false)
        },
        modifier = shell,
        onPane = paneClick,
        onSize = { canvas.resize(it, window.containerSize) },
        selection = { selectedRegion ?: shownSelection?.takeIf { it !in coveredCards }?.let(paintedLayout.cards::get) },
        step = { by ->
            val order = cards.indices.filter { shownLooks[it] != null && cards[it].id !in coveredCards }.sortedWith(compareBy({ cards[it].y }, { cards[it].x }))
            if (order.isNotEmpty()) cards[order[(order.indexOfFirst { cards[it].id == shownSelection } + if (shownSelection == null && by < 0) 0 else by).mod(order.size)]].let { cardClick(it.id) }
        },
        open = { x, y ->
            painter.frameAt(x, y)?.let { frame -> frameOpen?.invoke(frame) ?: canvas.fit(frame.x, frame.y, frame.width, frame.height, maxZoom = 1.4); true }
                ?: cardAt(x, y)?.let { id -> cardOpen?.invoke(id) ?: paintedLayout.cards[id]?.let { canvas.centre(it.x, it.y, it.width, it.height) }; true } ?: false
        },
        enterOpens = onCardOpen != null,
        focusable = focusable,
        editing = if (editable) interaction ?: editor else null,
        projectedHit = projection?.takeIf { editable && interaction == null && editor?.tool == dev.shibasis.reaktor.blueprint.editor.EditorTool.Select }?.let { projected ->
            { x, y, reach ->
                val pin = painter.pinAt(x, y)?.takeIf { (card, _) -> card.id !in coveredCards }
                val card = cardAt(x, y)
                val link = linkAt(x, y, reach)
                when {
                    pin != null && projected.projectedPin(pin.first.ref(pin.second)) -> { applyProjected(projected.pin(pin.first.ref(pin.second))); true }
                    card != null && projected.projectedCard(card) -> { applyProjected(projected.card(card)); true }
                    link != null && projected.projectedWire(link) -> { applyProjected(projected.wire(link)); true }
                    else -> false
                }
            }
        },
        markers = { focus ->
            GraphMarkers(canvas, markerBoxes, { markerIndices[it].let { index -> if (index < paintedLayout.frames.size) "frame:${paintedLayout.frames[index].key}" else cards[index - paintedLayout.frames.size].id } }) { marker, zoom ->
                val i = markerIndices[marker]
                if (i < paintedLayout.frames.size) FrameMarker(paintedLayout.frames[i], frameLooks[i])
                else shownLooks[i - paintedLayout.frames.size]?.let { look ->
                    val card = cards[i - paintedLayout.frames.size]
                    val opacity = painter.visualOpacity(card.id)
                    CardMarker(card, look, !painter.rows(card.id, zoom), pinTip, { focus(); cardClick(it) }, pinClick?.let { click -> { card, pin -> focus(); click(card, pin) } }, painter.headerScale(card.id, zoom), opacity)
                    look.agent?.takeIf { mapLevels?.full?.cards?.containsKey(card.id) != false }?.let { agent ->
                        painter.agentBounds(card.id, zoom)?.let { bounds -> AgentMarker(card, agent, bounds, canvas, onAgentOpen, opacity) }
                    }
                    cardContent?.let { content ->
                        Box(Modifier.absoluteOffset(card.x.toFloat().dp, (card.y + BlueprintEngine.HeaderHeight).toFloat().dp)
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(card.width.toFloat().dp, (card.height - BlueprintEngine.HeaderHeight).coerceAtLeast(0.0).toFloat().dp).clipToBounds().graphicsLayer { alpha = opacity }) {
                            content(card)
                        }
                    }
                }
            }
        },
    ) { camera, viewport, scale ->
        tick.intValue
        syncPainter()
        painter.draw(this, camera, viewport, scale, canvas.level(camera.zoom), phase.floatValue, pulses, labelExclusions)
        onGeometry?.let { observe ->
            val full = mapLevels?.full ?: paintedLayout
            val banners = full.frames.filter { it.parent == null }.flatMap { painter.paintedBounds(it, camera.zoom) }
                .distinctBy { listOf(it.x, it.y, it.width, it.height) }
            observe(full, camera.zoom, banners)
        }
    }
    }
    if (editor == null) view(modifier) else Box(modifier) { view(Modifier.fillMaxSize()); GraphEditorLayer(editor) }
}

internal fun mapSummaryLook(
    full: BlueprintLayout<*>, group: BlueprintFrameLevel, card: Card,
    looks: Map<String, BlueprintCardLook?>, frame: BlueprintFrameLook?,
    forwarded: Map<PinRef, List<PinRef>>, text: Color,
): BlueprintCardLook {
    val supplied = looks[card.id]
    val members = group.members.mapNotNull(looks::get)
    val marks = (frame?.marks.orEmpty() + members.flatMap { it.marks } + supplied?.marks.orEmpty()).distinct()
        .sortedBy { if (it.kind == BlueprintMark.Kind.Badge) 0 else 1 }.take(2)
    val agents = (members.mapNotNull { it.agent } + listOfNotNull(supplied?.agent)).distinctBy { it.label }
    val agent = (supplied?.agent ?: agents.firstOrNull())?.let { it.copy(otherAgents = maxOf(it.otherAgents, agents.size - 1)) }
    val tone = supplied?.tone ?: frame?.lit ?: Blueprint.Container
    val pins = card.pins.mapIndexed { index, pin ->
        val refs = forwarded[PinRef(card.id, pin.key, pin.provides)].orEmpty()
        val source = refs.mapNotNull { ref ->
            val at = full.cards[ref.card]?.pins?.indexOfFirst { ref.matches(ref.card, it) } ?: -1
            looks[ref.card]?.pins?.getOrNull(at)
        }
        supplied?.pins?.getOrNull(index) ?: source.singleOrNull()?.takeIf { refs.size == 1 } ?: BlueprintPinLook(source.firstOrNull()?.color ?: tone,
            "${refs.size} ${pin.type}", source.firstOrNull()?.textColor ?: text, pin.provides)
    }
    val count = card.folded.takeIf { it > 0 } ?: group.members.size
    val subtitle = "$count node${if (count == 1) "" else "s"}"
    return (supplied ?: BlueprintCardLook(tone, frame?.icon ?: dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol,
        group.frame.label, subtitle, tag = "blueprint-summary:${group.frame.key}"))
        .copy(title = group.frame.label, subtitle = subtitle, tag = supplied?.tag ?: "blueprint-summary:${group.frame.key}",
            action = supplied?.action ?: "Select ${group.frame.label}",
            face = BlueprintFace.Region, marks = marks, agent = agent, pins = pins)
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
private fun FrameMarker(frame: Frame, look: BlueprintFrameLook) {
    Box(Modifier.at(frame.x, frame.y, frame.width, BlueprintEngine.FrameTop).tagged(look.tag)) {
        listOf(frame.label, look.count, frame.detail.joinToString(" · ")).forEach { Said(it) }
        look.marks.forEach { Said(it.said) }
    }
}

@Composable
private fun AgentMarker(card: Card, agent: BlueprintAgent, bounds: BlueprintBadgeBounds, canvas: GraphCanvasState, onOpen: (BlueprintAgent) -> Unit, opacity: Float = 1f) {
    val visible by remember(card, canvas) { derivedStateOf {
        val view = canvas.flow.viewport
        val size = canvas.flow.canvasSize
        card.x * view.zoom + view.x < size.width && (card.x + card.width) * view.zoom + view.x > 0 &&
            card.y * view.zoom + view.y < size.height && (card.y + card.height) * view.zoom + view.y > 0
    } }
    Box(Modifier.absoluteOffset(bounds.x.toFloat().dp, bounds.y.toFloat().dp)
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        .requiredSize((bounds.width / bounds.scale).toFloat().dp, (bounds.height / bounds.scale).toFloat().dp)
        .graphicsLayer { alpha = opacity; scaleX = bounds.scale.toFloat(); scaleY = scaleX; transformOrigin = TransformOrigin(0f, 0f) }) {
        BlueprintAgentBadge(agent, { onOpen(agent) }, Modifier.fillMaxSize(), compact = bounds.compact, visible = visible, tag = "blueprint-agent-${card.id}")
    }
}

@Composable
internal fun CardMarker(card: Card, look: BlueprintCardLook, far: Boolean, pinTip: (Card, Pin) -> BlueprintTip?, onCardClick: (String) -> Unit, onPinClick: ((Card, Pin) -> Unit)?, headerScale: Float? = if (far) null else 1f, opacity: Float = 1f) {
    Box(Modifier.at(card.x, card.y, card.width, card.height).graphicsLayer { alpha = opacity }.semantics {
        selected = look.selected
        look.action?.let { onClick(label = it) { onCardClick(card.id); true } }
    }.tagged(look.tag)) {
        if (!far) look.choice?.let { choice ->
            Box(Modifier.at(0.0, 0.0, BlueprintEngine.RowHeight, BlueprintEngine.RowHeight), contentAlignment = Alignment.Center) {
                dev.shibasis.reaktor.surface.compose.Checkbox(if (choice.checked) dev.shibasis.reaktor.surface.compose.CheckState.Checked else dev.shibasis.reaktor.surface.compose.CheckState.Unchecked, choice.change, Modifier.testTag(choice.tag))
            }
        }
        Box(Modifier.at(0.0, 0.0, card.width, BlueprintEngine.HeaderHeight)) {
            Said(look.title)
            look.imageDescription?.let { Said(it) }
            if (!far) look.eyebrow?.let { Said(it) }
            Said(look.subtitle)
            if (headerScale != null && look.actions.isNotEmpty()) {
                Row(Modifier.align(Alignment.TopEnd).padding(end = MachineSignal.Editor.controlGap * headerScale),
                    verticalAlignment = Alignment.CenterVertically) {
                    look.actions.forEach { action ->
                        Tooltip(tip = { CanvasText(action.label, MachineSignal.colors.textStrong, MachineSignal.Editor.label) }) {
                            val glyph = remember(action.icon) { BlueprintIcon(action.icon) }
                            Canvas(Modifier.size(MachineSignal.Editor.controlHeight * headerScale)
                                .testTag("blueprint-card-action:${card.id}:${action.id}")
                                .semantics { contentDescription = action.label }
                                .clickable(onClickLabel = action.label, role = Role.Button, onClick = action.onInvoke)) {
                                val side = MachineSignal.Editor.controlIconSize.toPx() * headerScale
                                glyph.draw(this, (size.width - side) / 2, (size.height - side) / 2, side, look.tone)
                            }
                        }
                    }
                }
            }
        }
        if (far) return@Box
        look.marks.forEach { Said(it.said) }
        look.folded?.let { Said(it) }
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        card.pins.forEachIndexed { k, pin ->
            val pinLook = look.pins.getOrNull(k) ?: return@forEachIndexed
            val text = pinLook.text
            val width = if (oneSided) card.width else card.width / 2
            Box(Modifier.at(if (pin.provides && !oneSided) card.width / 2 else 0.0, pin.y - BlueprintEngine.RowHeight / 2, width, BlueprintEngine.RowHeight).then(if (onPinClick == null) Modifier else Modifier.semantics {
                selected = pinLook.selected
                onClick { onPinClick(card, pin); true }
            }.tagged(pinLook.tag ?: look.tag?.let { "$it-${if (pin.provides) "provides" else "needs"}-${pin.key}" }))) {
                val tip = pinTip(card, pin)
                if (tip == null) Said(text) else Tooltip(appearance = tip.appearance ?: UnframedBlueprintTip, tip = tip.content) { Said(text) }
                pinLook.marks.forEach { Said(it.said) }
                pinLook.choice?.let { choice ->
                    Box(Modifier.at(if (pin.provides) width - BlueprintEngine.RowHeight else 0.0, 0.0, BlueprintEngine.RowHeight, BlueprintEngine.RowHeight), contentAlignment = Alignment.Center) {
                        dev.shibasis.reaktor.surface.compose.Checkbox(if (choice.checked) dev.shibasis.reaktor.surface.compose.CheckState.Checked else dev.shibasis.reaktor.surface.compose.CheckState.Unchecked, choice.change, Modifier.testTag(choice.tag))
                    }
                }
            }
        }
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
