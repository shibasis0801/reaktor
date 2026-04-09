# compose-flow

`compose-flow` is the generic flow runtime and renderer layer.

It is intentionally not Reaktor-specific.

## Ownership

This module owns:
- generic flow model and change types
- generic viewport state and transform math
- pan / zoom / fit behavior
- generic node / edge / handle rendering abstractions
- generic controls / minimap / background
- generic interaction plumbing for pointer, wheel, trackpad, and platform bridges
- JS bridge over React Flow via the local TS/Karakum wrapper
- parity reporting against the React Flow feature surface

This module must not depend on `reaktor-graph`.

## Current architecture

The source is layered so interaction math, viewport state, and rendering can evolve independently:

- `dev.shibasis.composeflow.model`
- `dev.shibasis.composeflow.runtime`
- `dev.shibasis.composeflow.compose`
- `dev.shibasis.composeflow.react`
- `dev.shibasis.composeflow.parity`

Current source folders follow that split internally:
- `src/commonMain/.../model`
- `src/commonMain/.../runtime`
- `src/commonMain/.../compose/theme`
- `src/commonMain/.../compose/primitives`
- `src/commonMain/.../compose/components`
- `src/commonMain/.../compose/interaction`
- `src/jsMain/.../react`
- `src/jvmMain/.../compose/interaction`
- `parity/`

The public Compose API still stays under `dev.shibasis.composeflow.compose` so hosts do not need to care about the internal layering.

## Mental model

`compose-flow` should be treated like a Compose-native equivalent of the generic xyflow / React Flow substrate.

It owns only generic concerns:
- nodes, edges, handles, viewport
- selection and dragging state
- pan / zoom / fit behavior
- generic edge drawing
- generic interaction plumbing

It should not own:
- Reaktor node kinds
- graph regions with Reaktor semantics
- product shell behavior
- app-specific first-open heuristics beyond generic fit behavior

Those belong in `reaktor-flow` or the product host.

## Important APIs

- `Node`, `Edge`, `Handle`, `Viewport`, `Connection`
- `applyNodeChanges`, `applyEdgeChanges`, `addEdge`
- `ReactFlowState`
- `ReactFlowProvider`
- `ReactFlow`
- `Background`, `MiniMap`, `Controls`

Important implementation files:
- `/Users/ovd/dev/reaktor/compose-flow/src/commonMain/kotlin/dev/shibasis/composeflow/compose/ReactFlow.kt`
- `/Users/ovd/dev/reaktor/compose-flow/src/commonMain/kotlin/dev/shibasis/composeflow/runtime/State.kt`
- `/Users/ovd/dev/reaktor/compose-flow/src/commonMain/kotlin/dev/shibasis/composeflow/compose/interaction/FlowViewportGestures.kt`
- `/Users/ovd/dev/reaktor/compose-flow/src/commonMain/kotlin/dev/shibasis/composeflow/compose/interaction/FlowViewportPlatformBridge.kt`
- `/Users/ovd/dev/reaktor/compose-flow/src/jvmMain/kotlin/dev/shibasis/composeflow/compose/interaction/FlowDesktopViewportPlatformBridge.kt`
- `/Users/ovd/dev/reaktor/compose-flow/src/commonMain/kotlin/dev/shibasis/composeflow/compose/theme/FlowSizing.kt`

## Interaction architecture

Current interaction behavior is split intentionally:

- wheel / trackpad scroll path
  - `flowWheelAndTrackpadViewportGestures(...)`
- pointer-gesture path
  - `flowPointerViewportGestures(...)`
- platform bridge path
  - `FlowViewportPlatformBridge`
  - desktop implementation in `FlowDesktopViewportPlatformBridge`

That separation is there so platform-specific behavior such as macOS wheel anchoring or native pinch can be tuned without burying everything inside `ReactFlow.kt`.

If you need to tune trackpad or zoom behavior, start here:
- `/Users/ovd/dev/reaktor/compose-flow/src/commonMain/kotlin/dev/shibasis/composeflow/compose/interaction/FlowViewportGestures.kt`
- `/Users/ovd/dev/reaktor/compose-flow/src/commonMain/kotlin/dev/shibasis/composeflow/compose/theme/FlowSizing.kt`

## Tuning map

Change these in `FlowSizing.kt` when you want to retune generic canvas feel:
- `wheelPanFactor`
- `wheelZoomSensitivity`
- `wheelZoomFactorMin`
- `wheelZoomFactorMax`
- `pinchZoomSensitivity`
- `controlZoomFactor`
- bezier edge constants such as:
  - `bezierControlRatio`
  - `bezierAxisAlignedFactor`
  - `bezierVerticalCollinearBiasPx`

These are generic flow knobs, not Reaktor graph style knobs.

## Parity reporting

Use the Gradle task:

```sh
./gradlew :compose-flow:reportParity --no-daemon --console=plain
```

Outputs:
- `build/reports/compose-flow/parity.json`
- `build/reports/compose-flow/parity.md`

The source of truth is:
- `parity/features.json`

This is a maintained feature matrix, not an inferred report.

## JS / React Flow bridge

The TS wrapper package lives in:
- `ts/`

Typical verification command:

```sh
cd compose-flow/ts
npm run build
```

This generates/refreshes the Kotlin externals under:
- `ts/import/compose/flow`

## Out of scope in the current phase

These are intentionally not migrated during the current split:
- `reaktorWeb`
- `Manna`
- `reaktor-graph-port/ts`

They can continue using raw React Flow until `compose-flow` and `reaktor-flow` are stable enough to consume cleanly.
