package dev.shibasis.composeflow.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.compose.theme.FlowPanelSurface
import dev.shibasis.composeflow.compose.theme.FlowSurface
import dev.shibasis.composeflow.compose.theme.FlowText
import dev.shibasis.composeflow.compose.theme.FlowVisualDefaults
import dev.shibasis.composeflow.model.PanelPosition
import dev.shibasis.composeflow.model.Viewport
import kotlin.math.roundToInt

@Composable
fun Controls(
    modifier: Modifier = Modifier,
    viewport: Viewport,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
) {
    FlowControls(modifier, viewport, onZoomIn, onZoomOut, onFitView)
}

@Composable
fun BoxScope.Panel(
    position: PanelPosition = PanelPosition.TopRight,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.align(position.alignment()), content = content)
}

@Composable
fun BoxScope.ViewportPortal(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), content = content)
}

@Composable
internal fun FlowControls(
    modifier: Modifier,
    viewport: Viewport,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = FlowPanelSurface,
        shape = RoundedCornerShape(FlowSizing.controlsContainerRadius),
        tonalElevation = FlowSizing.controlsElevation,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(FlowSizing.controlGroupPadding)) {
            ControlButton(label = "-", onClick = onZoomOut)
            ControlButton(label = "+", onClick = onZoomIn)
            ControlButton(label = "Fit", onClick = onFitView)
            Text(
                text = "${(viewport.zoom * 100).roundToInt()}%",
                color = FlowText.copy(alpha = FlowVisualDefaults.panelTextAlpha),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = FlowSizing.viewportLabelSpacing),
            )
        }
    }
}

@Composable
internal fun ControlButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = FlowSizing.controlButtonSpacing),
        color = FlowSurface,
        shape = RoundedCornerShape(FlowSizing.controlButtonRadius),
    ) {
        Box(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(
                    horizontal = FlowSizing.controlButtonHorizontalPadding,
                    vertical = FlowSizing.controlButtonVerticalPadding,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = FlowText, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun PanelPosition.alignment(): Alignment = when (this) {
    PanelPosition.TopLeft -> Alignment.TopStart
    PanelPosition.TopCenter -> Alignment.TopCenter
    PanelPosition.TopRight -> Alignment.TopEnd
    PanelPosition.BottomLeft -> Alignment.BottomStart
    PanelPosition.BottomCenter -> Alignment.BottomCenter
    PanelPosition.BottomRight -> Alignment.BottomEnd
}
