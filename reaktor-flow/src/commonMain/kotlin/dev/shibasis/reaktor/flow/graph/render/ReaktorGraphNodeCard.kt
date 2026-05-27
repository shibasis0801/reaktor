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
import androidx.compose.ui.text.style.TextOverflow
import dev.shibasis.composeflow.compose.primitives.NodeProps
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
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

    Column(modifier = Modifier.fillMaxSize()) {
        ReaktorNodeTitle(
            title = data.title,
            titleColor = data.kind.titleColor,
            isRootNode = data.isRootNode,
            style = style,
        )

        Column(
            modifier = Modifier
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
    }
}
