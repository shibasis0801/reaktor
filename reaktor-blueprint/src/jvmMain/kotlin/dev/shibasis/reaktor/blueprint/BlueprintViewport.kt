package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposeWindow
import dev.shibasis.composeflow.compose.interaction.FlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.ProvideFlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.rememberFlowDesktopViewportPlatformBridge

@Composable
fun rememberBlueprintViewportBridge(window: ComposeWindow?): FlowViewportPlatformBridge? =
    rememberFlowDesktopViewportPlatformBridge(window)

@Composable
fun ProvideBlueprintViewport(
    bridge: FlowViewportPlatformBridge?,
    content: @Composable () -> Unit,
) = ProvideFlowViewportPlatformBridge(bridge, content)
