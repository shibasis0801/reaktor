package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.ui.machinesignal.colors

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
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
import dev.shibasis.reaktor.blueprint.canvas.BlueprintPainter
import dev.shibasis.reaktor.blueprint.canvas.BlueprintType
import dev.shibasis.reaktor.blueprint.canvas.CanvasGrid
import dev.shibasis.reaktor.blueprint.canvas.CanvasPulse
import dev.shibasis.reaktor.blueprint.canvas.GraphMarkers
import dev.shibasis.reaktor.blueprint.canvas.GraphView
import dev.shibasis.reaktor.blueprint.canvas.Said
import dev.shibasis.reaktor.blueprint.canvas.at
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
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
) {
    val cards = remember(layout) { layout.cards.values.toList() }
    val boxes = remember(layout) { layout.frames + cards }
    val wired = remember(layout, port) { port?.let { at -> layout.links.filter(at::carries).mapTo(hashSetOf()) { it.id } } }
    val shown = remember(layout, port, wired, highlight) { port?.let { at -> layout.links.filter { it.id in wired.orEmpty() }.flatMapTo(hashSetOf(at.card)) { listOf(it.from, it.to) } } ?: highlight }
    val looks = cards.map { card ->
        cardLook(card)?.let { look -> port?.takeIf { it.card == card.id }?.let { at -> look.copy(pins = look.pins.mapIndexed { k, pin -> pin?.copy(selected = card.pins.getOrNull(k)?.let { at.matches(card.id, it) } == true) }) } ?: look }
    }
    val glows = cards.map { glow(it.id) }
    val tints = editor?.placement?.notes?.associate { it.id to it.tint }
    val frameLooks = layout.frames.map { frame -> frameLook(frame).let { look -> tints?.get(frame.key)?.takeIf { it != 0L }?.let { look.copy(lit = Color(it.toInt())) } ?: look } }
    val styles = layout.links.map { link ->
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
    SideEffect { canvas.insetTop = TopInset.value.toDouble(); canvas.insetStart = startInset.value.toDouble(); canvas.extent = boxes }
    LaunchedEffect(fitKey, canvas.flow.canvasSize, layout, density) {
        if (boxes.isEmpty()) return@LaunchedEffect
        canvas.fitOnce(fitKey, fitScene) { canvas.fit(boxes) }
    }
    val phase = remember { mutableFloatStateOf(0f) }
    if (styles.any { it.flowing }) LaunchedEffect(Unit) {
        val begin = withFrameMillis { it }
        while (true) withFrameMillis { phase.floatValue = (it - begin) % FlowCycleMillis * FlowPeriod / FlowCycleMillis }
    }
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(pulses) { while (pulses.any { it.born.elapsedNow().inWholeMilliseconds < it.travelMillis }) withFrameNanos { tick.intValue++ } }
    val cardClick by rememberUpdatedState(if (editor == null) onCardClick else { id: String -> editor.select(Selection(setOf(id))) })
    val linkClick by rememberUpdatedState(onLinkClick)
    val pinClick by rememberUpdatedState(onPinClick)
    val cardOpen by rememberUpdatedState(onCardOpen)
    val window = LocalWindowInfo.current
    fun reveal(card: Card) = canvas.reveal(card.x, card.y, card.width, card.height)
    val view: @Composable (Modifier) -> Unit = { shell ->
    GraphView(painter, canvas,
        hit = { x, y, reach -> painter.endAt(x, y) != null || painter.frameAt(x, y) == null && (painter.cardAt(x, y) != null || painter.visibleLinkAt(x, y, reach) >= 0) },
        tap = { x, y, reach ->
            painter.endAt(x, y)?.let { id -> cardClick(id); layout.cards[id]?.let(::reveal); true }
                ?: (painter.frameAt(x, y) != null || painter.cardAt(x, y)?.let { id -> pinClick?.let { click -> painter.pinAt(x, y)?.let { (card, pin) -> click(card, pin) } } ?: cardClick(id); true }
                ?: painter.visibleLinkAt(x, y, reach).takeIf { it >= 0 }?.let { linkClick(layout.links[it]); true } ?: false)
        },
        modifier = shell,
        onPane = onPaneClick,
        onSize = { canvas.resize(it, window.containerSize) },
        selection = { selected?.let(layout.cards::get) },
        step = { by ->
            val order = cards.indices.filter { looks[it] != null }.sortedWith(compareBy({ cards[it].y }, { cards[it].x }))
            if (order.isNotEmpty()) cards[order[(order.indexOfFirst { cards[it].id == selected } + if (selected == null && by < 0) 0 else by).mod(order.size)]].let { cardClick(it.id); reveal(it) }
        },
        open = { x, y ->
            painter.frameAt(x, y)?.let { canvas.fit(it.x, it.y, it.width, it.height, maxZoom = 1.4); true }
                ?: painter.cardAt(x, y)?.let { id -> cardOpen?.invoke(id) ?: layout.cards[id]?.let { canvas.centre(it.x, it.y, it.width, it.height) }; true } ?: false
        },
        enterOpens = onCardOpen != null,
        focusable = focusable,
        editing = editor,
        markers = { focus ->
            GraphMarkers(canvas, boxes, { if (it < layout.frames.size) "frame:${layout.frames[it].key}" else cards[it - layout.frames.size].id }) { i, zoom ->
                if (i < layout.frames.size) FrameMarker(layout.frames[i], frameLooks[i])
                else looks[i - layout.frames.size]?.let { look -> CardMarker(cards[i - layout.frames.size], look, zoom < Blueprint.FarZoom, pinTip, { focus(); cardClick(it) }, pinClick?.let { click -> { card, pin -> focus(); click(card, pin) } }) }
            }
        },
    ) { camera, viewport, scale ->
        tick.intValue
        painter.sync(editor?.takeIf { it.settled.cards === layout.cards }?.layout ?: layout, looks, glows, frameLooks, styles, selected, shown, labels, (editor?.gesture as? Gesture.Move)?.cards.orEmpty())
        painter.draw(this, camera, viewport, scale, canvas.level(camera.zoom), phase.floatValue, pulses)
    }
    }
    if (editor == null) view(modifier) else Box(modifier) { view(Modifier.fillMaxSize()); GraphEditorLayer(editor) }
}

@Composable
private fun FrameMarker(frame: Frame, look: BlueprintFrameLook) {
    Box(Modifier.at(frame.x, frame.y, frame.width, BlueprintEngine.FrameTop).tagged(look.tag)) {
        listOf(frame.label, look.count, frame.detail.joinToString(" · ")).forEach { Said(it) }
        look.marks.forEach { Said(it.said) }
    }
}

@Composable
private fun CardMarker(card: Card, look: BlueprintCardLook, far: Boolean, pinTip: (Card, Pin) -> BlueprintTip?, onCardClick: (String) -> Unit, onPinClick: ((Card, Pin) -> Unit)?) {
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
            val pinLook = look.pins.getOrNull(k) ?: return@forEachIndexed
            val text = pinLook.text
            val width = if (oneSided) card.width else card.width / 2
            Box(Modifier.at(if (pin.provides && !oneSided) card.width / 2 else 0.0, pin.y - BlueprintEngine.RowHeight / 2, width, BlueprintEngine.RowHeight).then(if (onPinClick == null) Modifier else Modifier.semantics {
                selected = pinLook.selected
                onClick { onPinClick(card, pin); true }
            }.tagged(look.tag?.let { "$it-${if (pin.provides) "provides" else "needs"}-${pin.key}" }))) {
                val tip = pinTip(card, pin)
                if (tip == null) Said(text) else Tooltip(appearance = tip.appearance ?: UnframedBlueprintTip, tip = tip.content) { Said(text) }
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
