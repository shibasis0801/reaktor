# compose-flow

`compose-flow` is the generic flow runtime and renderer layer.

It is intentionally not Reaktor-specific.

## Ownership

This module owns:

- generic flow model and change types
- Compose flow runtime and viewport state
- generic pan / zoom / fit / minimap behavior
- generic node / edge / handle rendering abstractions
- JS bridge over React Flow via the local TS/Karakum wrapper
- parity reporting against the React Flow feature surface

This module must not depend on `reaktor-graph`.

## Package layout

Source is organized in layered form so rendering can be changed without touching layout/runtime math:

- `dev.shibasis.composeflow.model`
- `dev.shibasis.composeflow.runtime`
- `dev.shibasis.composeflow.compose`
- `dev.shibasis.composeflow.react`
- `dev.shibasis.composeflow.parity`

Current source folders follow this split internally:

- `src/commonMain/.../model`
- `src/commonMain/.../runtime`
- `src/commonMain/.../compose/theme`
- `src/commonMain/.../compose/primitives`
- `src/commonMain/.../compose/components`
- `src/jsMain/.../react`
- `parity/`

The public Compose API still stays under `dev.shibasis.composeflow.compose` for now. The directories are layered even where the package surface is intentionally stable.

## Important APIs

- `Node`, `Edge`, `Handle`, `Viewport`, `Connection`
- `applyNodeChanges`, `applyEdgeChanges`, `addEdge`
- `ReactFlowState`
- `ReactFlowProvider`
- `ReactFlow`
- `Panel`, `ViewportPortal`, `Background`, `MiniMap`, `Controls`

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

## Out of scope in phase 1

These are intentionally not migrated during this split:

- `reaktorWeb`
- `Manna`
- `reaktor-graph-port/ts`

They can continue using raw React Flow until `compose-flow` and `reaktor-flow` are stable enough to consume cleanly.
