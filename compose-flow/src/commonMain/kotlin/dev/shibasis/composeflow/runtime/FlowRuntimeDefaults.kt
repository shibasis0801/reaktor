package dev.shibasis.composeflow.runtime

// References:
// - AndroidX Compose internals/custom layout docs: keep measurement contracts explicit and avoid
//   burying layout assumptions in leaf modifiers.
// - xyflow/react store + viewport helpers: viewport math is maintained in editor-space pixels and
//   applied at the render boundary, not mixed into node content rendering.
internal object FlowRuntimeDefaults {
    const val minZoom = 0.25
    const val maxZoom = 2.0
    const val defaultHandleOffset = 0.5
    const val minHandleOffset = 0.08
    const val maxHandleOffset = 0.92
    const val selectedNodeZIndex = 100f
}
