package dev.shibasis.composeflow.compose.theme

import androidx.compose.ui.graphics.Color

// Token-first layering is intentional here. It mirrors Fluent/React design-token systems and keeps
// renderer files focused on composition and measurement instead of spreading color/alpha literals
// across runtime code paths.
internal val FlowSurface = Color(0xFF0F131C)
internal val FlowBorder = Color(0xFF232B3E)
internal val FlowText = Color(0xFFE6EAF2)
internal val FlowHandleSource = Color(0xFF3B82F6)
internal val FlowHandleTarget = Color(0xFFFB923C)
internal val FlowEdge = Color(0xFF8CA0C8)
internal val FlowCanvasBackground = Color(0xFF06080D)
internal val FlowPanelSurface = Color(0xD90A0D14)
internal val FlowPanelSecondarySurface = Color(0xFF0F131C)
internal val FlowSelection = Color(0xFF4B7BFF)
internal val FlowHandleBorder = Color(0xFF06080D)
internal val FlowGridMinor = Color(0x1F8CA0C8)
internal val FlowGridMinorCross = Color(0x188CA0C8)
internal val FlowGridMajor = Color(0x2B8CA0C8)

internal object FlowVisualDefaults {
    const val selectedNodeSurfaceAlpha = 0.98f
    const val idleNodeSurfaceAlpha = 0.92f
    const val secondaryTextAlpha = 0.7f
    const val panelTextAlpha = 0.75f
    const val miniMapEdgeAlpha = 0.55f
}
