package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.composeflow.compose.interaction.FlowViewportGestureConfig
import dev.shibasis.composeflow.compose.interaction.FlowViewportPlatformGestureEffect
import dev.shibasis.composeflow.compose.interaction.LocalFlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.flowPointerViewportGestures
import dev.shibasis.composeflow.compose.interaction.flowViewportPointerTracking
import dev.shibasis.composeflow.compose.interaction.flowWheelAndTrackpadViewportGestures
import dev.shibasis.composeflow.compose.interaction.rememberFlowViewportInteractionState
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.blueprint.canvas.BlueprintPainter
import dev.shibasis.reaktor.blueprint.canvas.BlueprintType
import dev.shibasis.reaktor.blueprint.canvas.CanvasGrid
import dev.shibasis.reaktor.blueprint.canvas.CanvasPulse
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.Tooltip
import dev.shibasis.reaktor.surface.compose.TooltipAppearance
import dev.shibasis.reaktor.surface.compose.TooltipGroup
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.ui.machinesignal.LocalMachineSignalFonts
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

object Blueprint {
    val Body = MachineSignal.Editor.Canvas.copy(alpha = .95f)
    val Grid = CanvasGrid(minor = Color.White.copy(alpha = .028f), major = Color.White.copy(alpha = .07f))
    val Container = MachineSignal.Editor.Source
    val Live = MachineSignal.Status.Ok
    val Hop = MachineSignal.Editor.Source
    val Rule = MachineSignal.Entity.Auth
    val Faint = Color.White.copy(alpha = .16f)
    val Pulse = lerp(Live, Color.White, .8f)
    const val FarZoom = 0.42

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
    startInset: Dp = 0.dp,
    pulses: List<CanvasPulse> = emptyList(),
    glow: (String) -> Color? = { null },
    pinTip: (Card, Pin) -> BlueprintTip? = { _, _ -> null },
) {
    val cards = remember(layout) { layout.cards.values.toList() }
    val looks = cards.map { cardLook(it) }
    val glows = cards.map { glow(it.id) }
    val frameLooks = layout.frames.map { frameLook(it) }
    val styles = layout.links.map(linkStyle)
    val density = LocalDensity.current.density
    val fonts = LocalMachineSignalFonts.current
    val base = LocalTextStyle.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val painter = remember(graphics, resolver, direction, fonts, base) {
        BlueprintPainter(graphics, TextMeasurer(resolver, Density(1f), direction, cacheSize = 512), BlueprintType(fonts.ui, fonts.mono, base))
    }
    DisposableEffect(painter) { onDispose(painter::release) }
    LaunchedEffect(painter) { painter.run() }
    val flow = canvas.flow
    SideEffect { canvas.density(density.toDouble()); canvas.insetTop = TopInset.value.toDouble(); canvas.insetStart = startInset.value.toDouble() }
    LaunchedEffect(fitKey, flow.canvasSize, layout, density) {
        if (cards.isEmpty() && layout.frames.isEmpty()) return@LaunchedEffect
        canvas.fitOnce(fitKey) { canvas.fit(cards + layout.frames) }
    }
    val phase = remember { mutableFloatStateOf(0f) }
    if (styles.any { it.flowing }) LaunchedEffect(Unit) {
        val begin = withFrameMillis { it }
        while (true) withFrameMillis { phase.floatValue = (it - begin) % FlowCycleMillis * FlowPeriod / FlowCycleMillis }
    }
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(pulses) { while (pulses.any { it.born.elapsedNow().inWholeMilliseconds < it.travelMillis }) withFrameNanos { tick.intValue++ } }
    val interaction = rememberFlowViewportInteractionState()
    val bridge = LocalFlowViewportPlatformBridge.current
    val config = remember(density) { FlowViewportGestureConfig(minZoom = MinZoom * density, maxZoom = MaxZoom * density) }
    FlowViewportPlatformGestureEffect(flow, interaction, config, bridge)
    val cardClick = rememberUpdatedState(onCardClick)
    val linkClick = rememberUpdatedState(onLinkClick)
    val paneClick = rememberUpdatedState(onPaneClick)
    val pane = remember { { paneClick.value() } }
    val clickCard = remember { { id: String -> cardClick.value(id) } }
    val tap = remember(painter, canvas) {
        { point: Offset ->
            val at = canvas.flow.screenToFlowPosition(point.x.toDouble(), point.y.toDouble())
            val x = at.x.toFloat(); val y = at.y.toFloat()
            when (val card = painter.cardAt(x, y)) {
                null -> painter.headerAt(x, y) || painter.linkAt(x, y, (LinkReach / canvas.density).toFloat())?.also { linkClick.value(it) } != null
                else -> { clickCard(card); true }
            }
        }
    }
    TooltipGroup {
        Box(
            modifier.fillMaxSize().clipToBounds()
                .onSizeChanged { flow.setCanvasSize(it) }
                .onGloballyPositioned { interaction.updateCanvasGeometry(it.positionInWindow(), it.boundsInWindow(), density) }
                .flowViewportPointerTracking(interaction)
                .flowWheelAndTrackpadViewportGestures(flow, interaction, config, bridge)
                .flowPointerViewportGestures(flow, interaction, config, onPaneClick = pane, onPaneTap = tap)
                .drawBehind {
                    painter.version.intValue
                    tick.intValue
                    painter.sync(layout, looks, glows, frameLooks, styles, selected, highlight, labels)
                    withTransform({ scale(density, density, Offset.Zero) }) {
                        painter.draw(this, canvas.viewport(), Size(size.width / density, size.height / density), density, phase.floatValue, pulses)
                    }
                },
        ) {
            BlueprintMarkers(layout, cards, looks, frameLooks, flow, density, pinTip, clickCard)
        }
    }
}

@Composable
private fun BlueprintMarkers(layout: BlueprintLayout<*>, cards: List<Card>, looks: List<BlueprintCardLook?>, frameLooks: List<BlueprintFrameLook>,
    flow: ReactFlowState, density: Float, pinTip: (Card, Pin) -> BlueprintTip?, onCardClick: (String) -> Unit) {
    var shown by remember(layout, cards, flow, density) { mutableStateOf<Shown?>(null) }
    LaunchedEffect(layout, cards, flow, density) {
        snapshotFlow { flow.viewport to flow.canvasSize }.collectLatest {
            if (shown != null) repeat(SettleFrames) { withFrameNanos { } }
            shown = visibleMarkers(layout, cards, flow, density)
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val view = flow.viewport
        translationX = view.x.toFloat(); translationY = view.y.toFloat()
        scaleX = (view.zoom / density).toFloat(); scaleY = scaleX
        transformOrigin = TransformOrigin(0f, 0f)
    }) {
        val now = shown ?: return@Box
        now.frames.forEach { i -> key("frame:${layout.frames[i].key}") { FrameMarker(layout.frames[i], frameLooks[i]) } }
        now.cards.forEach { i -> looks[i]?.let { look -> key(cards[i].id) { CardMarker(cards[i], look, now.far, pinTip, onCardClick) } } }
    }
}

private data class Shown(val frames: List<Int>, val cards: List<Int>, val far: Boolean)

private fun visibleMarkers(layout: BlueprintLayout<*>, cards: List<Card>, flow: ReactFlowState, density: Float): Shown {
    val view = flow.viewport
    val scale = view.zoom
    val mx = flow.canvasSize.width / scale / 4; val my = flow.canvasSize.height / scale / 4
    val left = -view.x / scale - mx; val top = -view.y / scale - my
    val right = (flow.canvasSize.width - view.x) / scale + mx; val bottom = (flow.canvasSize.height - view.y) / scale + my
    fun within(x: Double, y: Double, width: Double, height: Double) = x < right && y < bottom && x + width > left && y + height > top
    return Shown(layout.frames.indices.filter { layout.frames[it].let { f -> within(f.x, f.y, f.width, f.height) } },
        cards.indices.filter { cards[it].let { c -> within(c.x, c.y, c.width, c.height) } }, view.zoom / density < Blueprint.FarZoom)
}

@Composable
private fun FrameMarker(frame: Frame, look: BlueprintFrameLook) {
    Box(Modifier.at(frame.x, frame.y, frame.width, BlueprintEngine.FrameTop).tagged(look.tag)) {
        listOf(frame.label, look.count, frame.detail.joinToString(" · ")).forEach { Said(it) }
        look.marks.forEach { Said(it.said) }
    }
}

@Composable
private fun CardMarker(card: Card, look: BlueprintCardLook, far: Boolean, pinTip: (Card, Pin) -> BlueprintTip?, onCardClick: (String) -> Unit) {
    Box(Modifier.at(card.x, card.y, card.width, card.height).semantics {
        selected = look.selected
        look.action?.let { onClick(label = it) { onCardClick(card.id); true } }
    }.tagged(look.tag)) {
        Said(look.title)
        if (far) return@Box
        Said(look.subtitle)
        look.marks.forEach { Said(it.said) }
        look.folded?.let { Said(it) }
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        card.pins.forEachIndexed { k, pin ->
            val text = look.pins.getOrNull(k)?.text ?: return@forEachIndexed
            val width = if (oneSided) card.width else card.width / 2
            Box(Modifier.at(if (pin.provides && !oneSided) card.width / 2 else 0.0, pin.y - BlueprintEngine.RowHeight / 2, width, BlueprintEngine.RowHeight)) {
                val tip = pinTip(card, pin)
                if (tip == null) Said(text) else Tooltip(appearance = tip.appearance ?: UnframedBlueprintTip, tip = tip.content) { Said(text) }
            }
        }
    }
}

@Composable
private fun Said(text: String) {
    if (text.isNotEmpty()) Box(Modifier.fillMaxSize().semantics { this.text = AnnotatedString(text) })
}

private val BlueprintMark.said get() = when (kind) {
    BlueprintMark.Kind.Tag -> text.uppercase()
    else -> text
}

private fun Modifier.at(x: Double, y: Double, width: Double, height: Double) = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed((width * density).roundToInt(), (height * density).roundToInt()))
    layout(0, 0) { placeable.place((x * density).roundToInt(), (y * density).roundToInt()) }
}

private fun Modifier.tagged(tag: String?) = if (tag == null) this else testTag(tag)

private val TopInset = 48.dp
private const val MinZoom = 0.05
private const val MaxZoom = 2.5
private const val LinkReach = 20.0
private const val FlowPeriod = 22f
private const val FlowCycleMillis = 700L
private const val SettleFrames = 6

sealed interface LegendEntry {
    data class Dot(val color: Color, val label: String) : LegendEntry
    data class Line(val color: Color, val label: String) : LegendEntry
    data class Note(val label: String) : LegendEntry
}

@Composable
fun BlueprintLegend(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(MachineSignal.Editor.Surface.copy(alpha = .92f))
            .border(1.dp, MachineSignal.Editor.Line, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { entry ->
            when (entry) {
                is LegendEntry.Dot -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(entry.color))
                    CanvasText(entry.label, color = MachineSignal.Editor.Muted, size = 10.5.sp)
                }
                is LegendEntry.Line -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(14.dp).height(2.dp).background(entry.color))
                    CanvasText(entry.label, color = MachineSignal.Editor.Muted, size = 10.5.sp)
                }
                is LegendEntry.Note -> CanvasText(entry.label, color = MachineSignal.Editor.Muted, size = 10.5.sp)
            }
        }
    }
}

@Composable
fun BlueprintWatermark(title: String, line: String, lineColor: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        CanvasText(title.uppercase(), color = Color.White.copy(alpha = .07f), size = 38.sp, weight = FontWeight.Bold)
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
