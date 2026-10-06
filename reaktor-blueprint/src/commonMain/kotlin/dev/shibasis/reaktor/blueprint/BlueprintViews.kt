package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.blueprint.canvas.CanvasCard
import dev.shibasis.reaktor.blueprint.canvas.CanvasFrame
import dev.shibasis.reaktor.blueprint.canvas.CanvasGrid
import dev.shibasis.reaktor.blueprint.canvas.CanvasLink
import dev.shibasis.reaktor.blueprint.canvas.CanvasPulse
import dev.shibasis.reaktor.blueprint.canvas.CardStyle
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvas
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkArrow
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.Tooltip
import dev.shibasis.reaktor.surface.compose.TooltipAppearance
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.LocalMachineSignalFonts

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

    fun header(tone: Color): Brush = Brush.horizontalGradient(listOf(lerp(tone, Color.Black, .3f), lerp(tone, Color.Black, .78f)))

    fun far(tone: Color): Brush = Brush.horizontalGradient(listOf(lerp(tone, Color.Black, .45f), lerp(tone, Color.Black, .8f)))

    fun scaled(base: Double, zoom: Double): TextUnit = (base * (1.0 / zoom.coerceIn(0.35, 1.0)).coerceAtMost(1.8)).sp

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
    frameContent: @Composable (Frame) -> Unit,
    cardContent: @Composable (Card) -> Unit,
    onCardClick: (String) -> Unit,
    onLinkClick: (Link) -> Unit,
    onPaneClick: () -> Unit,
    modifier: Modifier = Modifier,
    labels: Map<String, String> = emptyMap(),
    fitKey: Int = layout.key.hashCode() * 31 + layout.cards.keys.hashCode(),
    startInset: Dp = 0.dp,
    pulses: List<CanvasPulse> = emptyList(),
    glow: (String) -> Color? = { null },
) {
    val frames = remember(layout) { layout.frames.map { CanvasFrame("frame:${it.key}", it.x, it.y, it.width, it.height, 1) } }
    val framesById = remember(layout) { layout.frames.associateBy { "frame:${it.key}" } }
    val cards = remember(layout) { layout.cards.values.map { CanvasCard(it.id, it.x, it.y, it.width, it.height) } }
    val links = remember(layout, labels) {
        layout.links.map { CanvasLink(it.id, it.from, it.to, it.points, labels[it.id], if (it.reversed) LinkArrow.Start else LinkArrow.End) }
    }
    val linksById = remember(layout) { layout.links.associateBy { it.id } }
    Box(modifier.fillMaxSize().background(MachineSignal.Editor.Canvas)) {
        GraphCanvas(
            frames = frames,
            cards = cards,
            links = links,
            state = canvas,
            background = MachineSignal.Editor.Canvas,
            fitKey = fitKey,
            topInset = 48.dp,
            startInset = startInset,
            grid = Blueprint.Grid,
            pulses = pulses,
            frameContent = { frame -> framesById[frame.id]?.let { frameContent(it) } },
            cardContent = { card -> layout.cards[card.id]?.let { cardContent(it) } },
            cardStyle = { card ->
                CardStyle(
                    alpha = if (highlight.isEmpty() || card.id in highlight) 1f else 0.2f,
                    glow = if (card.id == selected) MachineSignal.Editor.Accent.copy(alpha = 0.45f) else glow(card.id),
                )
            },
            linkStyle = { link -> linksById[link.id]?.let(linkStyle) ?: LinkStyle(color = MachineSignal.Editor.Muted) },
            onCardClick = onCardClick,
            onLinkClick = { id -> linksById[id]?.let(onLinkClick) },
            onPaneClick = onPaneClick,
        )
    }
}

@Composable
fun BlueprintFrame(
    frame: Frame,
    zoom: Double,
    icon: ImageVector,
    count: String,
    modifier: Modifier = Modifier,
    lit: Color? = null,
    badges: @Composable RowScope.() -> Unit = {},
) {
    val tint = Blueprint.Container
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxSize().clip(shape)
            .background(tint.copy(alpha = if (frame.muted) .03f else .06f))
            .border(if (lit != null) 1.5.dp else 1.dp, lit?.copy(alpha = .75f) ?: tint.copy(alpha = .28f), shape),
    ) {
        Row(
            modifier.fillMaxWidth().height(BlueprintEngine.FrameTop.dp)
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = .2f), tint.copy(alpha = .07f))))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, Modifier.size((14 * (1.0 / zoom.coerceIn(0.35, 1.0)).coerceAtMost(1.8)).dp), tint = tint)
            CanvasText(frame.label, color = MachineSignal.Editor.Text, size = Blueprint.scaled(13.0, zoom), weight = FontWeight.SemiBold)
            CanvasText(count, color = MachineSignal.Editor.Muted, size = Blueprint.scaled(11.0, zoom))
            CanvasText(frame.detail.joinToString(" · "), color = MachineSignal.Editor.Muted, size = Blueprint.scaled(11.0, zoom), mono = true, maxLines = 1)
            badges()
        }
    }
}

@Composable
fun BlueprintCard(
    card: Card,
    zoom: Double,
    tone: Color,
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    subtitleMono: Boolean = false,
    selected: Boolean = false,
    outline: Color? = null,
    folded: String? = null,
    badges: @Composable RowScope.() -> Unit = {},
    pin: @Composable (Pin) -> Unit = {},
) {
    val far = zoom < Blueprint.FarZoom
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.fillMaxSize().clip(shape)
            .then(if (far) Modifier.background(Blueprint.far(tone)) else Modifier.background(Blueprint.Body))
            .border(
                when {
                    selected -> 2.dp
                    outline != null -> 1.6.dp
                    else -> 1.dp
                },
                when {
                    selected -> MachineSignal.Editor.Accent
                    outline != null -> outline
                    else -> tone.copy(alpha = .3f)
                },
                shape,
            )
            .then(modifier),
    ) {
        if (far) {
            CanvasText(title, Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp), color = MachineSignal.Editor.Text,
                size = Blueprint.fitted(title, card.width - 18, 13.0, zoom), weight = FontWeight.SemiBold, mono = true, maxLines = 1)
            return@Box
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(26.dp).background(Blueprint.header(tone)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = .92f))
                CanvasText(title, Modifier.weight(1f), color = Color.White, size = 12.5.sp, weight = FontWeight.SemiBold, mono = true, maxLines = 1)
                badges()
            }
            CanvasText(subtitle, Modifier.padding(start = 10.dp, end = 8.dp, top = 3.dp), color = MachineSignal.Editor.Muted,
                size = 10.5.sp, mono = subtitleMono, maxLines = 1)
        }
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        card.pins.forEach { entry ->
            Box(
                Modifier.offset(y = (entry.y - BlueprintEngine.RowHeight / 2).dp).height(BlueprintEngine.RowHeight.dp)
                    .fillMaxWidth(if (oneSided) 1f else 0.5f)
                    .let { if (entry.provides && !oneSided) it.offset(x = (card.width / 2).dp) else it },
            ) {
                pin(entry)
            }
        }
        folded?.let {
            CanvasText(it, Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 8.dp), color = MachineSignal.Editor.Muted, size = 9.5.sp)
        }
    }
}

@Composable
fun BlueprintPin(
    pin: Pin,
    color: Color,
    text: String,
    textColor: Color,
    filled: Boolean,
    chip: Boolean = false,
    tooltip: (@Composable () -> Unit)? = null,
    tip: TooltipAppearance = UnframedBlueprintTip,
) {
    val row = @Composable {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (pin.provides) Arrangement.End else Arrangement.Start,
        ) {
            if (!pin.provides) PinDot(color, filled)
            if (!pin.provides) Spacer(Modifier.width(6.dp))
            if (chip) CanvasText(text, Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = .2f)).padding(horizontal = 4.dp),
                color = Color.White, size = 10.sp, mono = true, maxLines = 1)
            else CanvasText(text, color = textColor, size = 10.5.sp, mono = true, maxLines = 1)
            if (pin.provides) Spacer(Modifier.width(6.dp))
            if (pin.provides) PinDot(color, filled)
        }
    }
    if (tooltip == null) row() else Tooltip(appearance = tip, tip = tooltip) { row() }
}

@Composable
private fun PinDot(color: Color, filled: Boolean) {
    Box(
        Modifier.size(8.dp).clip(CircleShape)
            .background(if (filled) color else Color.Transparent)
            .border(1.4.dp, color, CircleShape),
    )
}

@Composable
fun BlueprintTag(text: String, tone: Color) {
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(tone).padding(horizontal = 4.dp, vertical = 1.dp)) {
        CanvasText(text.uppercase(), color = MachineSignal.Editor.Canvas, size = 8.5.sp, weight = FontWeight.Bold)
    }
}

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
        CanvasText(title.uppercase(), color = Color.White.copy(alpha = .07f), size = 38.sp, weight = FontWeight.Bold, maxLines = 1)
        CanvasText(line, color = lineColor, size = 12.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

private val UnframedBlueprintTip: TooltipAppearance = object : TooltipAppearance {
    @Composable
    override fun Content(properties: TipContent, state: TooltipState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: TooltipSlots) = slots.tip()
}

@Composable
private fun CanvasText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    maxLines: Int = 1,
) {
    val fonts = LocalMachineSignalFonts.current
    Text(text, modifier, color = color, fontFamily = if (mono) fonts.mono else fonts.ui, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
