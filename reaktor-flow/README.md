# reaktor-flow

`reaktor-flow` is the Reaktor-specific graph adapter/editor layer built on top of `compose-flow`.

It owns the mapping from `reaktor-graph` semantics into a flow editor/visualizer.

## Ownership

This module owns:

- `reaktor-graph` -> flow adaptation
- Reaktor-specific node, edge, port, and region data
- Reaktor graph layout and measurement
- Reaktor node card rendering and graph chrome
- Reaktor-specific framing policy and editor behavior
- high-level graph editor entry points used by product hosts

This module depends on:

- `:compose-flow`
- `:reaktor-graph`

Products such as BestBuds should depend on `reaktor-flow`, not on low-level `compose-flow` APIs directly for graph editing.

## Package layout

The source is split by responsibility so layout and rendering are easier to reason about:

- `dev.shibasis.reaktor.flow.graph.model`
- `dev.shibasis.reaktor.flow.graph.adapter`
- `dev.shibasis.reaktor.flow.graph.layout`
- `dev.shibasis.reaktor.flow.graph.render`
- `dev.shibasis.reaktor.flow.graph.editor`

Compatibility entry points remain available from:

- `dev.shibasis.reaktor.flow.graph`

See:

- `src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/Api.kt`

## Important APIs

- `buildReaktorFlowGraph(graph: Graph)`
- `ReaktorFlowGraph`
- `ReaktorGraphCanvas(...)`
- `ReaktorGraphEditor(...)`

`ReaktorGraphEditor(...)` is the preferred high-level host surface for desktop/product code.

## Internal layering

The intended layering is:

- Adapter:
  - graph extraction from `reaktor-graph`
- Layout:
  - measurement, lane placement, region bounds
- Render:
  - Reaktor-specific visuals
- Editor:
  - graph toolbar, selection behavior, first-open/readable framing

Rule:

- layout math does not belong in atom/molecule-style render functions
- styling does not belong in layout code

## Current phase-1 exclusions

This module does not migrate or own:

- `reaktorWeb`
- `Manna`
- raw React Flow consumers outside the Reaktor graph editor path

Those stay separate until the generic `compose-flow` layer has stronger parity coverage and a more stable API surface.
