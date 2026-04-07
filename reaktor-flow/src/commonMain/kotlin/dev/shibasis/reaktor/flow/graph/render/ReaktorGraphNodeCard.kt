package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.shibasis.composeflow.compose.primitives.NodeProps
import dev.shibasis.reaktor.flow.graph.layout.DefaultGraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import kotlin.math.max

@Composable
internal fun BoxScope.ReaktorGraphNodeCard(props: NodeProps) {
    val data = props.data as? ReaktorGraphNodeData ?: return
    val rowCount = max(1, max(data.consumerPorts.size, data.providerPorts.size))
    val metrics = DefaultGraphFlowMetrics
    val density = LocalDensity.current

    // Similar to the Compose "measure once, render many" guidance: the card only consumes the
    // precomputed render metrics and semantic port data; it does not own graph/layout math.
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        ReaktorNodeTitle(
            title = data.title,
            titleColor = data.kind.titleColor,
            isRootNode = data.isRootNode,
            metrics = metrics,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = with(density) { dpOf(metrics.bodyPaddingX) },
                    vertical = with(density) { dpOf(metrics.nodePaddingY) },
                ),
            verticalArrangement = Arrangement.Top,
        ) {
            if (rowCount == 1 && data.consumerPorts.isEmpty() && data.providerPorts.isEmpty()) {
                data.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        color = GraphCanvasMuted,
                        fontSize = with(density) { spOf(metrics.titleFontSize) },
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                ReaktorNodePorts(
                    consumerPorts = data.consumerPorts,
                    providerPorts = data.providerPorts,
                    metrics = metrics,
                )
            }
        }
    }
}
