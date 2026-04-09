# reaktor-flow

`reaktor-flow` is the Reaktor-specific graph adapter/editor layer built on top of `compose-flow`.

It owns the mapping from `reaktor-graph` semantics into a readable flow editor and visualizer.

## Ownership

This module owns:
- `reaktor-graph` -> flow adaptation
- Reaktor-specific node, edge, port, and region data
- node measurement
- layout strategy
- Reaktor node card rendering and graph chrome
- Reaktor-specific framing policy and editor behavior
- high-level graph editor entry points used by product hosts

This module depends on:
- `:compose-flow`
- `:reaktor-graph`

Products such as BestBuds should depend on `reaktor-flow`, not on low-level `compose-flow` APIs directly for graph editing.

## Current architecture

The source is split by responsibility so layout and rendering are understandable and tunable:

- `dev.shibasis.reaktor.flow.graph.model`
- `dev.shibasis.reaktor.flow.graph.adapter`
- `dev.shibasis.reaktor.flow.graph.layout`
- `dev.shibasis.reaktor.flow.graph.render`
- `dev.shibasis.reaktor.flow.graph.editor`
- `dev.shibasis.reaktor.flow.graph.style`

Compatibility entry points remain available from:
- `dev.shibasis.reaktor.flow.graph`

See:
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/Api.kt`

## The important split

`reaktor-flow` is where the graph editor stops being generic and becomes Reaktor-specific.

`compose-flow` should only know:
- nodes, edges, handles, viewport, gestures, generic edge drawing

`reaktor-flow` should know:
- route nodes vs service nodes vs screen nodes
- graph regions and root route markers
- how Reaktor nodes are measured
- how Reaktor graphs are laid out
- how Reaktor graph chrome should look

That boundary is the reason the graph editor is now easier to understand and tune.

## Important APIs

- `buildReaktorFlowGraph(graph: Graph, style: ReaktorGraphStyle = DefaultReaktorGraphStyle)`
- `ReaktorFlowGraph`
- `ReaktorGraphCanvas(...)`
- `ReaktorGraphEditor(...)`
- `ReaktorGraphStyle`

`ReaktorGraphEditor(...)` is the preferred high-level host surface for desktop/product code.

## One graph style contract

The single graph-scene tuning entrypoint is:
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/style/ReaktorGraphStyle.kt`

That style object owns the graph scene knobs for:
- node width and height defaults
- node title size and padding
- port row height, port font, port dot size
- graph region padding and bounds
- toolbar / legend / minimap sizing
- first-open readable framing and zoom limits
- layout spacing vocabulary

If you want to change graph readability, start with `ReaktorGraphStyle`, not with random component modifiers.

## Measurement and layout

Measurement is now the source of truth.

Important files:
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/adapter/ReaktorFlowMeasurement.kt`
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/layout/ReaktorGraphLayoutStrategy.kt`
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/adapter/ReaktorFlowAssembly.kt`

Current default layout model:
- semantic layered lane layout
- compound region packing
- bezier edges

Current strategy shape:
- `ReaktorGraphLayoutStrategy`
- `BlueprintReaktorGraphLayoutStrategy`

This is deliberate. The layout strategy is separated so future layout variants can be added without collapsing placement logic back into the builder.

## Tuning map

### Change node size / title / ports
Edit:
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/style/ReaktorGraphStyle.kt`

Key sections:
- `node`
- `port`
- `widthPolicy`

### Change node placement / spacing between groups
Edit:
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/style/ReaktorGraphStyle.kt`
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/layout/ReaktorGraphLayoutStrategy.kt`

Key sections:
- `layout`
- `region`

### Change toolbar / legend / minimap / framing
Edit:
- `/Users/ovd/dev/reaktor/reaktor-flow/src/commonMain/kotlin/dev/shibasis/reaktor/flow/graph/style/ReaktorGraphStyle.kt`

Key sections:
- `chrome`
- `viewport`

## Internal layering rules

The intended layering is:
- adapter
  - graph extraction from `reaktor-graph`
- layout
  - measurement, lane placement, region bounds, framing policy
- render
  - Reaktor-specific visuals
- editor
  - scene assembly and host wiring

Rules:
- layout math does not belong in render functions
- styling does not belong in layout code
- desktop shell concerns do not belong here

## Related docs

- [Reaktor root README](/Users/ovd/dev/reaktor/README.md)
- [compose-flow](/Users/ovd/dev/reaktor/compose-flow/README.md)
- [BestBuds engine / graph editor guide](/Users/ovd/dev/bestbuds/modules/engine/README.md)

## Current phase exclusions

This module does not migrate or own:
- `reaktorWeb`
- `Manna`
- raw React Flow consumers outside the Reaktor graph editor path

Those stay separate until the generic `compose-flow` layer has stronger parity coverage and a more stable API surface.
