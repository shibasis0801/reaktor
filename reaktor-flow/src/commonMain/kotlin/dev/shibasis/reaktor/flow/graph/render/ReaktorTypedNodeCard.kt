package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.shibasis.composeflow.compose.primitives.NodeProps
import dev.shibasis.reaktor.flow.graph.ReaktorPortDirection
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.flow.graph.model.ReaktorScopeDisclosure
import dev.shibasis.reaktor.flow.graph.model.ScopeNodeCountAttribute
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.dpOf
import dev.shibasis.reaktor.flow.graph.style.spOf

/** Native, data-bound N54ZXW card anatomy; every painted port is an actual directed handle. */
@Composable
internal fun ReaktorTypedNodeCard(
    props: NodeProps,
    data: ReaktorGraphNodeData,
    style: ReaktorGraphStyle,
    onSelectPort: ((ReaktorPortDirection, ReaktorPortData) -> Unit)?,
    scopeDisclosure: ReaktorScopeDisclosure? = null,
) {
    val typed = requireNotNull(style.typedNode)
    val density = LocalDensity.current
    val kindColor = typed.kindColors[data.kind.label] ?: style.canvas.selected
    val icon = when (data.kind) {
        ReaktorNodeKind.Screen, ReaktorNodeKind.Ui -> "panels-top-left"
        ReaktorNodeKind.Service -> "plug"
        ReaktorNodeKind.Repository, ReaktorNodeKind.Data -> "database"
        ReaktorNodeKind.Route -> "route"
        ReaktorNodeKind.Interactor -> "workflow"
        else -> "box"
    }
    val ports = data.consumerPorts.map { ReaktorPortDirection.Consumer to it } +
        data.providerPorts.map { ReaktorPortDirection.Provider to it }
    val visiblePorts = ports.take(typed.maxPortRows.coerceAtLeast(1))
    val foldedPortCount = ports.size - visiblePorts.size
    val tag = props.id.lowercase().map { if (it.isLetterOrDigit() || it in "._-") it else '-' }.joinToString("").trim('-')
    // A collapsed boundary card has one obvious meaning, so it gets one obvious gesture: the whole
    // card folds and unfolds its scope. No caret, no chevron, no instruction line. Every other card
    // keeps selection, and the trailing control still opens the inspector on both.
    val fold = scopeDisclosure?.takeIf { data.isScopeSummary }
    val activate = fold?.let { { it.onToggle(data.scopeId) } } ?: props.onClick
    Column(Modifier.fillMaxSize().testTag("reaktor-graph-node-$tag")
        .clickable(enabled = activate != null, role = Role.Button) { activate?.invoke() }
        .semantics {
            contentDescription = if (fold == null) graphNodeAccessibilityLabel(data)
            else "${if (fold.isExpanded(data.scopeId)) "Collapse" else "Expand"} scope ${data.title}"
            selected = props.selected
        }) {
        Row(Modifier.fillMaxWidth().height(with(density) { dpOf(typed.familyHeightPx) })
            .background(style.canvas.panelChrome).padding(horizontal = with(density) { dpOf(style.node.bodyPaddingXPx) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.port.gapPx) })) {
            TypedNodeIcon(icon, kindColor, style.chrome.captionFontPx + style.port.dotSizePx / 4, style)
            TypedNodeText(if (data.isScopeSummary) "SCOPE" else data.kind.label.uppercase(),
                style.chrome.captionFontPx, kindColor, style, weight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (!data.isScopeSummary) {
                TypedNodeText(if (data.scopeId == "root") "Root" else data.graphLabel, style.chrome.captionFontPx, style.canvas.mutedText, style,
                    modifier = Modifier.widthIn(max = with(density) { dpOf(style.node.minWidthPx / 2) }))
            }
        }
        Row(Modifier.fillMaxWidth().height(with(density) { dpOf(style.node.titleHeightPx) })
            .padding(horizontal = with(density) { dpOf(style.node.bodyPaddingXPx) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.port.gapPx) })) {
            TypedNodeText(data.title, style.node.titleFontPx, style.canvas.text, style,
                modifier = Modifier.weight(1f), weight = FontWeight.SemiBold)
            // Opens the same exact node inspector; no separate object or synthetic menu state.
            Box(Modifier.clickable(enabled = props.onClick != null, role = Role.Button) { props.onClick?.invoke() }
                .semantics { contentDescription = "Inspect ${data.title}" }) {
                TypedNodeIcon("ellipsis", style.canvas.mutedText, style.port.dotSizePx + style.port.gapPx, style)
            }
        }
        if (ports.isEmpty()) {
            Row(Modifier.fillMaxWidth().height(with(density) { dpOf(style.port.rowHeightPx) })
                .padding(horizontal = with(density) { dpOf(style.node.bodyPaddingXPx) }), verticalAlignment = Alignment.CenterVertically) {
                TypedNodeText(data.subtitle?.takeIf { it.isNotBlank() }
                    ?: if (data.isScopeSummary) "Empty scope" else "No exposed ports",
                    style.port.fontPx, style.canvas.mutedText, style)
            }
        } else visiblePorts.forEach { (direction, port) ->
            val provider = direction == ReaktorPortDirection.Provider
            Row(Modifier.fillMaxWidth().height(with(density) { dpOf(style.port.rowHeightPx) })
                .clickable(enabled = onSelectPort != null, role = Role.Button) { onSelectPort?.invoke(direction, port) }
                .testTag("reaktor-graph-port-${props.id}-${if (provider) "provider" else "consumer"}-${port.handleId}")
                .semantics { contentDescription = "Graph port: ${direction.name.lowercase()} ${port.label}; type ${port.type}; ${if (port.connected) "connected" else "open"}" }
                .padding(horizontal = with(density) { dpOf(style.node.bodyPaddingXPx) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.port.gapPx) })) {
                if (!provider) TypedPortPin(style)
                TypedNodeText(if (port.type.isBlank() || port.label.contains(" : ")) port.label else "${port.label} : ${port.type}",
                    style.port.fontPx, style.canvas.text, style, Modifier.weight(1f))
                if (provider) TypedPortPin(style)
            }
        }
        if (data.isScopeSummary) {
            FoldedScopeContents(
                nodeCount = data.attributes[ScopeNodeCountAttribute]?.toIntOrNull() ?: 1,
                color = kindColor,
                unitPx = style.port.dotSizePx,
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .padding(horizontal = with(density) { dpOf(style.node.bodyPaddingXPx) }),
            )
            return@Column
        }
        val reading = when {
            data.attributes["architectureKind"] == "database-record" -> "Returned record · database-local identity"
            ports.isEmpty() -> "Topology · no exposed ports"
            foldedPortCount > 0 -> "${visiblePorts.size}/${ports.size} ports · $foldedPortCount folded · inspect all"
            else -> "${data.consumerPorts.size} in · ${data.providerPorts.size} out · ${ports.count { it.second.connected }}/${ports.size} wired"
        }
        Row(Modifier.fillMaxWidth().weight(1f).heightIn(min = with(density) { dpOf(style.node.footerHeightPx) })
            .drawBehind { drawLine(style.canvas.border, Offset.Zero, Offset(size.width, 0f), strokeWidth = style.port.dotSizePx.toFloat() / 8f) }
            .padding(horizontal = with(density) { dpOf(style.node.bodyPaddingXPx) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.port.gapPx) })) {
            TypedNodeText(reading, style.chrome.captionFontPx, style.canvas.mutedText, style, Modifier.weight(1f))
            TypedNodeIcon("pin", style.canvas.mutedText, style.chrome.captionFontPx + style.port.dotSizePx / 4, style)
        }
    }
}

@Composable
private fun TypedPortPin(style: ReaktorGraphStyle) {
    val density = LocalDensity.current
    Canvas(Modifier.size(with(density) { dpOf(style.port.dotSizePx) })) {
        val stroke = style.port.dotSizePx.toFloat() * 1.5f / 8f
        drawCircle(requireNotNull(style.typedNode).pinColor, radius = size.minDimension / 2,
            style = Stroke(stroke))
    }
}

@Composable
private fun TypedNodeText(text: String, size: Double, color: Color, style: ReaktorGraphStyle,
    modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal) {
    val density = LocalDensity.current
    Text(text, modifier = modifier, color = color, fontFamily = requireNotNull(style.typedNode).uiFont,
        fontSize = with(density) { spOf(size) }, lineHeight = with(density) { spOf(size * 1.3) },
        fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TypedNodeIcon(name: String, color: Color, sizePx: Double, style: ReaktorGraphStyle) {
    val density = LocalDensity.current
    val path = remember(name) { ReaktorTypedNodeIcons.path(name) }
    Canvas(Modifier.size(with(density) { dpOf(sizePx) })) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) { drawPath(path, color) }
    }
}
