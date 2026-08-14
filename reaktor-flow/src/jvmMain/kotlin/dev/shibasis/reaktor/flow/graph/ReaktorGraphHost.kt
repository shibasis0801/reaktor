package dev.shibasis.reaktor.flow.graph

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposeWindow
import dev.shibasis.composeflow.compose.interaction.FlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.ProvideFlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.rememberFlowDesktopViewportPlatformBridge

@Composable
fun rememberReaktorGraphViewportBridge(window: ComposeWindow?): FlowViewportPlatformBridge? =
    rememberFlowDesktopViewportPlatformBridge(window)

@Composable
fun ProvideReaktorGraphViewport(
    bridge: FlowViewportPlatformBridge?,
    content: @Composable () -> Unit,
) = ProvideFlowViewportPlatformBridge(bridge, content)
