# AGENTS.md — Reaktor framework

Reaktor is the framework substrate. Product code lives in the consuming repos
(`../bestbuds`, `../manna`); the canonical product guide is `../bestbuds/AGENTS.md` and its design
bar (§2) governs here too.

## 1. First moves

- Read `../bestbuds/AGENTS.md` §2 (design bar), §3.1 (dependency direction), §4.4 (graph-editor
  layer boundaries). They are written from the product side but they bind this repo.
- `README.md` and `LLM_CONTEXT.md` predate the control-plane and Machine Signal work. Trust code
  over both.

## 2. The graph is the foundation

**User direction, 13 September 2026:** "the graph is the king of reaktor, we don't need to
build graph agnostic stuff." This supersedes the former mandatory dual-surface rule.

- Build Reaktor capabilities around the graph's identities, typed ports, ownership, lifecycle,
  queries and commands. Agents, navigation, data and tools participate in that model.
- A separate graph-agnostic API, pure-library kernel or optional graph projection is **not a
  requirement**. Add a conventional facade only when an actual consumer benefits from it.
- Preserve useful existing APIs and ordinary implementation helpers. This direction does not
  require a breaking rewrite or turning every algorithm or log entry into an active node.
- Keep semantic definitions distinct from runtime activations and UI presentation. Headless graph
  hosts must not acquire Compose or workbench dependencies merely to run agents or inspect data.
- One logical graph does not require one global scheduler, process, database or loaded object heap.
  Preserve declared ownership and the layer boundaries below.

## 3. Layer boundaries (do not cross)

```
compose-flow      generic canvas, viewport, pan/zoom/fit, generic edges, handles, minimap
  └─ reaktor-flow Reaktor graph adaptation, measurement, layout, scene, ReaktorGraphEditor,
                  GraphDocument/GraphEditorSession, and the host re-exports consumers need
       └─ app     shell chrome, panes, inspector (bestbuds/modules/engine)
```

Never put Reaktor graph semantics in `compose-flow`, workbench chrome in `reaktor-flow`, or graph
layout in an app module. An app that needs a `compose-flow` type consumes it through a
`reaktor-flow` re-export, never directly.

## 4. Design system

`reaktor-ui/machinesignal` is the single definition of the operator visual language: palette,
provenance families, spacing, radii, type scale, authored component metrics, and the kit built on
them. Values are transcribed from `bestbuds/modules/reaktor.pen`.

- consumers never hardcode hex or geometry; they read `MachineSignal`
- `TruthClass`/`Fact` (`reaktor-core/truth`) carry provenance; `MachineSignal.provenance()` is the
  only sanctioned mapping from truth to color
- parity tests in the consuming app fail when a surface drifts from the design file

`reaktor-tactile` remains a placeholder with no source; do not extend it.

## 5. Build & verify

```bash
./gradlew :reaktor-ui:compileKotlinJvm --console=plain
./gradlew :reaktor-flow:jvmTest --console=plain
./gradlew :reaktor-core:jvmTest --console=plain
```

Composite builds mean a change here rebuilds `../bestbuds` automatically; run
`(cd ../bestbuds && ./gradlew :engine:jvmTest)` when you touch `reaktor-ui`, `reaktor-flow`,
`reaktor-core` or `reaktor-graph`.

Performance work uses the harnesses in `reaktor-performance` and
`reaktor-flexbuffer/flamechart/` — budgets and profiles, never guesses.

## 6. Coding rules

- `commonMain` stays platform-safe; platform APIs go in the platform source sets
- explicit `StateFlow` backing fields; never force-unwrap a port with `.impl!!`
- comments default to none — explain a non-obvious *why* (a constraint or invariant), never what
  the code does
- generated/vendored trees (`build/`, `ts/import/`, `ts/export/`, `kotlin-js-store/`) are not
  hand-edited

## 7. Stop and report before editing if

- a layer boundary in §3 would have to be crossed to make something work
- design tokens would need a second source of truth
