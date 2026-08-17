package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.shibasis.composeflow.compose.primitives.NodeProps
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.dpOf
import dev.shibasis.reaktor.flow.graph.style.spOf
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun BoxScope.ReaktorGraphNodeCard(
    props: NodeProps,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
) {
    val data = props.data as? ReaktorGraphNodeData ?: return
    val previewRows = style.port.previewRows.coerceAtLeast(1)
    val visibleConsumerPorts = data.consumerPorts.take(previewRows)
    val visibleProviderPorts = data.providerPorts.take(previewRows)
    val rowCount = min(max(1, max(data.consumerPorts.size, data.providerPorts.size)), previewRows)
    val density = LocalDensity.current
    val activation = props.onClick

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("reaktor-graph-node-${props.id.asTestIdSegment()}")
            .then(
                activation?.let { activate ->
                    Modifier.pointerInput(activate) {
                        detectTapGestures(onTap = { activate() })
                    }
                } ?: Modifier,
            )
            .semantics {
                contentDescription = graphNodeAccessibilityLabel(data)
                role = Role.Button
                activation?.let { activate ->
                    onClick(action = {
                        activate()
                        true
                    })
                }
            },
    ) {
        ReaktorNodeTitle(
            title = data.title,
            titleColor = data.kind.titleColor,
            isRootNode = data.isRootNode,
            style = style,
            tag = if (data.isScopeSummary) "SCOPE" else kindTag(data.kind),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    horizontal = with(density) { dpOf(style.node.bodyPaddingXPx) },
                    vertical = with(density) { dpOf(style.node.verticalPaddingPx) },
                ),
            verticalArrangement = Arrangement.Top,
        ) {
            if (rowCount == 1 && data.consumerPorts.isEmpty() && data.providerPorts.isEmpty()) {
                data.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        color = style.canvas.mutedText,
                        fontSize = with(density) { spOf(style.chrome.bodyFontPx) },
                        fontFamily = style.canvas.monoFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                ReaktorNodePorts(
                    consumerPorts = visibleConsumerPorts,
                    providerPorts = visibleProviderPorts,
                    style = style,
                )
            }
        }
        ReaktorNodeFooter(data = data, style = style)
    }
}

/**
 * The `Node / Card` foot band, and only what the runtime can prove: how much of the card's wiring
 * is actually connected. Latency and rate readings join when per-node telemetry is live — an
 * invented `p95` here would defeat the entire truth bar.
 */
@Composable
private fun ReaktorNodeFooter(
    data: ReaktorGraphNodeData,
    style: ReaktorGraphStyle,
) {
    if (style.node.footerHeightPx <= 0.0) return
    val density = LocalDensity.current
    val total = data.consumerPorts.size + data.providerPorts.size
    val wired = data.consumerPorts.count { it.connected } + data.providerPorts.count { it.connected }
    val reading = when {
        total == 0 -> "no ports"
        else -> "$wired/$total wired"
    }
    val dot = when {
        total == 0 -> style.canvas.mutedText
        wired == total -> Color(0xFF35C978)
        wired > 0 -> Color(0xFFF3B84B)
        else -> style.canvas.mutedText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { dpOf(style.node.footerHeightPx) })
            .background(Color(0x2E000000))
            .padding(horizontal = with(density) { dpOf(style.node.bodyPaddingXPx) }),
        horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.port.gapPx) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(with(density) { dpOf(5.0) })
                .background(dot, CircleShape),
        )
        Text(
            text = reading,
            color = style.canvas.mutedText,
            fontSize = with(density) { spOf(style.chrome.captionFontPx) },
            fontFamily = style.canvas.monoFont,
            maxLines = 1,
        )
    }
}

private fun String.asTestIdSegment(): String =
    lowercase().map { character ->
        when {
            character.isLetterOrDigit() || character == '.' || character == '_' || character == '-' -> character
            else -> '-'
        }
    }.joinToString("").trim('-').ifBlank { "node" }

/** Uppercase mono type badge per node kind — the Machine Signal node-anatomy tag. */
private fun kindTag(kind: ReaktorNodeKind): String = when (kind.label) {
    "Screen" -> "SCR"
    "Route" -> "RTE"
    "Container" -> "GRP"
    "UI" -> "UI"
    "Action" -> "ACT"
    "Interactor" -> "INTR"
    "Service" -> "SVC"
    "Repository" -> "REPO"
    "Actor" -> "ACTR"
    "Edge" -> "EDGE"
    "Data" -> "DATA"
    "Topic" -> "TOPIC"
    "Telemetry" -> "TELEM"
    "Test" -> "TEST"
    "Release" -> "REL"
    "Auth" -> "AUTH"
    "Infra" -> "INFRA"
    "Agent" -> "AGENT"
    else -> "NODE"
}
