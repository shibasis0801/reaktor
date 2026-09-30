package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The graph surface: ports, nodes, wires, scopes and canvas chrome.
 *
 * These are the pieces the graph editor draws on its canvas rather than in the shell. Sizes come
 * from `MachineSignal.Metrics.Graph`, which mirrors the authored boxes in reaktor.pen.
 */

private val g = MachineSignal.Metrics.Graph

/** `Pin / Exec` — execution flow, drawn as an arrow so it never reads as data. */
@Composable
fun ExecPin(modifier: Modifier = Modifier, color: Color = MachineSignal.Signal, filled: Boolean = true) =
    Canvas(modifier.size(g.execPin)) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
            close()
        }
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
    }

/** `Pin / Provider` — a value leaving a node. */
@Composable
fun ProviderPin(modifier: Modifier = Modifier, color: Color = MachineSignal.Edge.Data) =
    Canvas(modifier.size(g.dataPin)) {
        drawCircle(color, radius = size.minDimension / 2f - g.pinStroke.toPx() / 2f)
    }

/** `Pin / Consumer` — a value entering a node; hollow, so direction reads at a glance. */
@Composable
fun ConsumerPin(modifier: Modifier = Modifier, color: Color = MachineSignal.Edge.Data) =
    Canvas(modifier.size(g.dataPin)) {
        drawCircle(
            color,
            radius = size.minDimension / 2f - g.pinStroke.toPx() / 2f,
            style = Stroke(width = g.pinStroke.toPx()),
        )
    }

/** `Pin / Off` — an unconnected port. */
@Composable
fun OffPin(modifier: Modifier = Modifier, color: Color = MachineSignal.Edge.PortOff) =
    Canvas(modifier.size(g.offPin)) {
        drawCircle(color, radius = size.minDimension / 2f - 0.5f, style = Stroke(width = 1.dp.toPx()))
    }

/** `Wire / Reroute` — a handle placed on a wire. */
@Composable
fun WireReroute(modifier: Modifier = Modifier, color: Color = MachineSignal.Bg4) =
    Canvas(modifier.size(g.rerouteSize)) {
        drawCircle(color, radius = size.minDimension / 2f - g.pinStroke.toPx() / 2f)
        drawCircle(
            MachineSignal.Line3,
            radius = size.minDimension / 2f - g.pinStroke.toPx() / 2f,
            style = Stroke(width = g.pinStroke.toPx()),
        )
    }

/** `Wire / Label` — what a wire carries. */
@Composable
fun WireLabel(text: String, modifier: Modifier = Modifier) = Box(
    modifier
        .height(g.wireLabelHeight)
        .background(MachineSignal.Bg1, MachineSignal.Shape.Tight)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Tight)
        .padding(horizontal = g.wireLabelPaddingX),
    contentAlignment = Alignment.Center,
) {
    SignalText(text, color = MachineSignal.Text2, size = MachineSignal.Type.data, mono = true)
}

/** `Wire / Live Value` — the value on the wire right now. */
@Composable
fun WireLiveValue(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.Signal,
) = Row(
    modifier
        .height(g.wireLabelHeight)
        .background(MachineSignal.Bg2, RoundedCornerShape(g.wireValueRadius))
        .border(1.dp, color.copy(alpha = 0.42f), RoundedCornerShape(g.wireValueRadius))
        .padding(horizontal = g.wireValuePaddingX),
    horizontalArrangement = Arrangement.spacedBy(g.wireValueGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(5.dp).background(color, RoundedCornerShape(3.dp)))
    SignalText(
        text,
        color = MachineSignal.AccentText,
        size = MachineSignal.Type.data,
        weight = FontWeight.SemiBold,
        mono = true,
    )
}

/** `Node / Card` — a node on the canvas: head, ports, and a foot of live readings. */
@Composable
fun NodeCard(
    name: String,
    kind: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    footer: String? = null,
    footerColor: Color = MachineSignal.Status.Ok,
    onClick: () -> Unit = {},
    ports: @Composable ColumnScope.() -> Unit = {},
) = Column(
    modifier
        .width(g.nodeWidth)
        .background(MachineSignal.Bg2, MachineSignal.Shape.Node)
        .border(
            1.dp,
            if (selected) MachineSignal.AccentLine else MachineSignal.Line2,
            MachineSignal.Shape.Node,
        )
        .clickable(onClick = onClick),
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(g.nodeHeadHeight)
            .background(MachineSignal.Bg3)
            .padding(horizontal = g.nodePaddingX),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalText(
            name,
            modifier = Modifier.weight(1f),
            color = MachineSignal.Text1,
            size = MachineSignal.Type.dataStrong,
            weight = FontWeight.Bold,
            mono = true,
        )
        KindBadge(kind)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = MachineSignal.Space.s2), content = ports)
    if (footer != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(g.nodeFootHeight)
                .background(MachineSignal.Bg1)
                .padding(horizontal = g.nodePaddingX),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(footerColor)
            SignalText(footer, color = MachineSignal.Text3, size = MachineSignal.Type.dataMicro, mono = true)
        }
    }
}

/** One port row inside a [NodeCard]: a consumer on the left, a provider on the right. */
@Composable
fun NodePortRow(
    input: String? = null,
    output: String? = null,
    modifier: Modifier = Modifier,
    inputConnected: Boolean = true,
    outputConnected: Boolean = true,
) = Row(
    modifier.fillMaxWidth().height(g.nodePortRowHeight).padding(horizontal = g.nodePaddingX),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (inputConnected) ConsumerPin() else OffPin()
    if (input != null) {
        SignalText(input, color = MachineSignal.Text2, size = MachineSignal.Type.dataMicro, mono = true)
    }
    Spacer(Modifier.weight(1f))
    if (output != null) {
        SignalText(output, color = MachineSignal.Text2, size = MachineSignal.Type.dataMicro, mono = true)
    }
    if (outputConnected) ProviderPin() else OffPin()
}

/** `Node / Scope Summary` — a collapsed scope standing in for everything inside it. */
@Composable
fun NodeScopeSummary(
    name: String,
    kind: String,
    summary: String,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {},
) = Column(
    modifier
        .width(g.nodeWidth)
        .background(Color(0xFF1A0C33), MachineSignal.Shape.Node)
        .border(1.dp, MachineSignal.Entity.Container.copy(alpha = 0.5f), MachineSignal.Shape.Node),
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(g.nodeHeadHeight)
            .background(Color(0xFF1F0F3D))
            .padding(horizontal = g.nodePaddingX),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalText(
            name,
            modifier = Modifier.weight(1f),
            color = Color.White,
            size = MachineSignal.Type.dataStrong,
            weight = FontWeight.Bold,
            mono = true,
        )
        KindBadge(kind, color = MachineSignal.Entity.Container)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(g.scopeSummaryBodyHeight)
            .padding(horizontal = g.nodePaddingX),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalText(summary, color = MachineSignal.Text2, size = MachineSignal.Type.dataMicro, mono = true)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .background(MachineSignal.AccentSoft, MachineSignal.Shape.Tight)
                .clickable(onClick = onExpand)
                .padding(horizontal = MachineSignal.Space.s2, vertical = 2.dp),
        ) {
            SignalText("Expand", color = MachineSignal.AccentText, size = MachineSignal.Type.dataMicro, mono = true)
        }
    }
}

/** `Scope / Region` — the boundary drawn around a scope on the canvas. */
@Composable
fun ScopeRegion(
    name: String,
    meta: String,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.Accent,
    onCollapse: () -> Unit = {},
    content: @Composable () -> Unit = {},
) = Box(
    modifier
        .background(color.copy(alpha = 0.09f), MachineSignal.Shape.Panel)
        .border(1.2.dp, color.copy(alpha = 0.42f), MachineSignal.Shape.Panel),
) {
    content()
    Row(
        Modifier.padding(MachineSignal.Space.s2).align(Alignment.TopStart),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .background(Color(0xE8080C13), MachineSignal.Shape.Tight)
                .padding(horizontal = MachineSignal.Space.s2, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalText(name, color = color, size = MachineSignal.Type.dataStrong, weight = FontWeight.SemiBold, mono = true)
            SignalText(meta, color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
        }
    }
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(MachineSignal.Space.s2)
            .background(Color(0xE8080C13), MachineSignal.Shape.Tight)
            .clickable(onClick = onCollapse)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        SignalText("⊖", color = MachineSignal.Text3, size = MachineSignal.Type.dataStrong, mono = true)
    }
}

/** `Graph / Comment Box` — an annotation pinned to the canvas. */
@Composable
fun GraphCommentBox(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.Status.Warn,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) = Column(
    modifier
        .background(color.copy(alpha = 0.08f), MachineSignal.Shape.Panel)
        .border(1.dp, color.copy(alpha = 0.32f), MachineSignal.Shape.Panel),
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = MachineSignal.Space.s3),
        contentAlignment = Alignment.CenterStart,
    ) {
        SignalText(title, color = color, size = MachineSignal.Type.dataStrong, weight = FontWeight.SemiBold)
    }
    if (body != null) Column(Modifier.padding(MachineSignal.Space.s3), content = body)
}

/** `Chrome / Minimap` — where you are in the graph. */
@Composable
fun ChromeMinimap(
    nodes: List<Pair<Offset, Color>>,
    viewport: androidx.compose.ui.geometry.Rect?,
    modifier: Modifier = Modifier,
) = Box(
    modifier
        .size(g.minimapWidth, g.minimapHeight)
        .background(MachineSignal.Bg2, MachineSignal.Shape.Control)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Control),
) {
    Canvas(Modifier.fillMaxWidth().height(g.minimapHeight)) {
        nodes.forEach { (position, color) ->
            drawRoundRect(
                color = color.copy(alpha = 0.65f),
                topLeft = Offset(position.x * size.width, position.y * size.height),
                size = androidx.compose.ui.geometry.Size(34.dp.toPx(), 12.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
        }
        viewport?.let {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.05f),
                topLeft = Offset(it.left * size.width, it.top * size.height),
                size = androidx.compose.ui.geometry.Size(it.width * size.width, it.height * size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
            drawRoundRect(
                color = MachineSignal.Line3,
                topLeft = Offset(it.left * size.width, it.top * size.height),
                size = androidx.compose.ui.geometry.Size(it.width * size.width, it.height * size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/** `Chrome / Zoom Cluster` — fit, the current zoom, and the steps either side of it. */
@Composable
fun ChromeZoomCluster(
    zoom: String,
    modifier: Modifier = Modifier,
    presets: List<String> = listOf("Fit", "100%"),
    onSelect: (String) -> Unit = {},
    onZoomOut: () -> Unit = {},
    onZoomIn: () -> Unit = {},
) = Row(
    modifier
        .height(g.zoomClusterHeight)
        .background(Color(0xEB080C13), MachineSignal.Shape.Control)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Control)
        .padding(horizontal = g.zoomClusterPaddingX),
    horizontalArrangement = Arrangement.spacedBy(g.zoomClusterGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    presets.forEach { preset -> ZoomSegment(preset, selected = false) { onSelect(preset) } }
    ZoomSegment(zoom, selected = true) {}
    ZoomSegment("−", selected = false, onClick = onZoomOut)
    ZoomSegment("+", selected = false, onClick = onZoomIn)
}

@Composable
private fun ZoomSegment(label: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier
        .height(g.zoomSegmentHeight)
        .background(if (selected) MachineSignal.SelectedSoft else Color.Transparent, MachineSignal.Shape.Tight)
        .clickable(onClick = onClick)
        .padding(horizontal = MachineSignal.Space.s2),
    contentAlignment = Alignment.Center,
) {
    SignalText(
        label,
        color = if (selected) MachineSignal.AccentText else MachineSignal.Text3,
        size = MachineSignal.Type.data,
        weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        mono = true,
    )
}

/** `Chrome / Legend` — which kinds are on the canvas, and how many of each. */
@Composable
fun ChromeLegend(
    entries: List<Triple<String, Color, Int>>,
    modifier: Modifier = Modifier,
    caption: String = "KINDS · CLICK TO ISOLATE",
    onSelect: (String) -> Unit = {},
) = Column(
    modifier
        .width(g.legendWidth)
        .background(Color(0xEB080C13), MachineSignal.Shape.Control)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Control)
        .padding(g.legendPadding),
    verticalArrangement = Arrangement.spacedBy(g.legendGap),
) {
    SignalText(caption, color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
    entries.forEach { (name, color, count) ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(g.legendRowHeight)
                .clickable { onSelect(name) },
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(MachineSignal.Space.s2).background(color, RoundedCornerShape(4.dp)))
            SignalText(name, color = MachineSignal.Text2, size = MachineSignal.Type.data)
            Spacer(Modifier.weight(1f))
            SignalText(count.toString(), color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
        }
    }
}

/** `Chrome / Canvas Stats` — the counts that describe what is on screen. */
@Composable
fun ChromeCanvasStats(
    readings: List<StatusReading>,
    modifier: Modifier = Modifier,
) = Row(
    modifier.height(MachineSignal.Metrics.statusPillHeight),
    horizontalArrangement = Arrangement.spacedBy(g.canvasStatsGap),
    verticalAlignment = Alignment.CenterVertically,
) {
    readings.forEach {
        SignalText(it.text, color = it.color, size = MachineSignal.Type.data, weight = FontWeight.Medium, mono = true)
    }
}

/** `Port / Connection Card` — one connection, in the inspector. */
@Composable
fun PortConnectionCard(
    from: String,
    to: String,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.Status.Ok,
    detail: String = "1 connection",
) = Row(
    modifier
        .fillMaxWidth()
        .height(g.connectionCardHeight)
        .background(color.copy(alpha = 0.08f), MachineSignal.Shape.Control)
        .border(1.dp, color.copy(alpha = 0.32f), MachineSignal.Shape.Control)
        .padding(horizontal = g.connectionCardPaddingX),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
    verticalAlignment = Alignment.CenterVertically,
) {
    StatusDot(color)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SignalText(from, color = MachineSignal.Text1, size = MachineSignal.Type.dataStrong, weight = FontWeight.SemiBold, mono = true)
        SignalText(to, color = MachineSignal.Text3, size = MachineSignal.Type.dataMicro, mono = true)
    }
    Spacer(Modifier.weight(1f))
    SignalText(detail, color = MachineSignal.Text2, size = MachineSignal.Type.data, mono = true)
}

/** One line of a [CodeDiffCard]. */
data class DiffLine(val text: String, val change: DiffChange = DiffChange.Context)

enum class DiffChange { Added, Removed, Context }

/** `CodeDiff / Card` — what an agent changed, as a diff. */
@Composable
fun CodeDiffCard(
    path: String,
    lines: List<DiffLine>,
    modifier: Modifier = Modifier,
    summary: String? = null,
) = Column(
    modifier
        .background(MachineSignal.Bg0, MachineSignal.Shape.Control)
        .border(1.dp, MachineSignal.Line2, MachineSignal.Shape.Control),
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(g.codeDiffHeadHeight)
            .background(MachineSignal.Bg2)
            .padding(horizontal = MachineSignal.Space.s3),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalText(path, color = MachineSignal.Text2, size = MachineSignal.Type.data, weight = FontWeight.SemiBold, mono = true)
        Spacer(Modifier.weight(1f))
        if (summary != null) {
            SignalText(summary, color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
        }
    }
    lines.forEach { line ->
        Box(
            Modifier
                .fillMaxWidth()
                .height(g.codeDiffLineHeight)
                .background(
                    when (line.change) {
                        DiffChange.Added -> Color(0x143FBF78)
                        DiffChange.Removed -> Color(0x14EF5B5B)
                        DiffChange.Context -> Color.Transparent
                    },
                )
                .padding(horizontal = MachineSignal.Space.s3),
            contentAlignment = Alignment.CenterStart,
        ) {
            SignalText(
                text = line.text,
                color = when (line.change) {
                    DiffChange.Added -> MachineSignal.Status.Ok
                    DiffChange.Removed -> Color(0xFFF0A2A2)
                    DiffChange.Context -> MachineSignal.Text4
                },
                size = MachineSignal.Type.data,
                mono = true,
            )
        }
    }
}

/** `Sparkline` — a trend, small enough to sit inside a metric tile. */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MachineSignal.Status.Ok,
    width: Dp = g.sparklineWidth,
    height: Dp = g.sparklineHeight,
) {
    if (values.size < 2) return
    Canvas(modifier.size(width, height)) {
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value - min) / span) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = g.sparklineStroke.toPx(), cap = StrokeCap.Round))
    }
}
