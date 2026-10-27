# compose-flow

The pan and zoom viewport under `reaktor-blueprint`'s canvas. Every map in Hangar, Manna and the
BestBuds tools draws through blueprint, and blueprint moves its camera through this module.

- `Viewport` and `XYPosition`: the camera and a point in graph space.
- `ReactFlowState`: the camera as Compose state, with `panBy`, `zoomTo`, `zoomBy` and `screenToFlowPosition`.
- `flowWheelAndTrackpadViewportGestures` and `flowPointerViewportGestures`: wheel, trackpad, drag and pinch.
- `FlowViewportPlatformBridge`, with `FlowDesktopViewportPlatformBridge` on the desktop: native wheel anchoring and pinch.

Gesture feel is tuned in `ViewportTuning` (`runtime/State.kt`).

The module must not depend on `reaktor-graph`.
