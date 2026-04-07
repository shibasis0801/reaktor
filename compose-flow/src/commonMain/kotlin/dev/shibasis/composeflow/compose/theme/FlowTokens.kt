package dev.shibasis.composeflow.compose.theme

import androidx.compose.ui.graphics.Color

// Token-first layering is intentional here. It mirrors Fluent/React design-token systems and keeps
// renderer files focused on composition and measurement instead of spreading color/alpha literals
// across runtime code paths.
internal val FlowSurface = Color(0xFF0F172A)
internal val FlowBorder = Color(0xFF334155)
internal val FlowText = Color(0xFFE2E8F0)
internal val FlowHandleSource = Color(0xFF3B82F6)
internal val FlowHandleTarget = Color(0xFFFB923C)
internal val FlowEdge = Color(0xFF64748B)
internal val FlowCanvasBackground = Color(0xFF020617)
internal val FlowPanelSurface = Color(0xCC020617)
internal val FlowPanelSecondarySurface = Color(0xFF11182A)
internal val FlowSelection = Color(0xFF60A5FA)
internal val FlowHandleBorder = Color(0xFF09101D)
internal val FlowGridMinor = Color(0x142E3B55)
internal val FlowGridMinorCross = Color(0x122E3B55)
internal val FlowGridMajor = Color(0x203D5B8C)

internal object FlowVisualDefaults {
    const val selectedNodeSurfaceAlpha = 0.98f
    const val idleNodeSurfaceAlpha = 0.92f
    const val secondaryTextAlpha = 0.7f
    const val panelTextAlpha = 0.75f
    const val miniMapEdgeAlpha = 0.55f
}
