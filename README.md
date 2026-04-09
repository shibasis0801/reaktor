# Reaktor

Reaktor is a Kotlin Multiplatform framework stack for building graph-structured apps, services, and platform bridges across Android, iOS, JVM, JS, Cloudflare Workers, and native C++ interop.

It is the shared runtime used by:
- [BestBuds](/Users/ovd/dev/bestbuds/README.md)
- `Manna`

## Dependency direction

At the repo level, the intended direction is:

1. `reaktor-core` / `reaktor-graph-port` / `reaktor-graph`
   - graph runtime and framework substrate
2. `compose-flow`
   - generic flow canvas/runtime
3. `reaktor-flow`
   - Reaktor graph scene/editor layer
4. product repos
   - BestBuds
   - Manna

This matters operationally:
- reusable framework abstractions belong in `reaktor`
- product-specific behavior should not be pushed down into `compose-flow`
- editor-shell concerns should not leak into `reaktor-flow`

## Current architecture

Reaktor now has three especially important layers for editor and graph work:

1. `reaktor-core` + `reaktor-graph-port` + `reaktor-graph`
   - the graph runtime, typed ports, lifecycle, navigation, DI, and service model
2. `compose-flow`
   - the generic flow-canvas substrate: viewport state, pan/zoom/fit, generic nodes/edges/handles, minimap, controls, interaction plumbing
3. `reaktor-flow`
   - the Reaktor graph scene system: graph adaptation, measurement, layout strategy, node rendering, graph chrome, and editor framing

That split is intentional:
- generic flow behavior belongs in `compose-flow`
- Reaktor graph semantics belong in `reaktor-flow`
- product shells such as the BestBuds desktop editor should consume `reaktor-flow`, not reimplement graph internals

## Where to start by task type

If you are new to the repo, this is the practical map.

### Graph runtime, ports, navigation, DI
Start with:
- [reaktor-core](/Users/ovd/dev/reaktor/reaktor-core/README.md)
- [reaktor-graph](/Users/ovd/dev/reaktor/reaktor-graph/README.md)

### Generic graph canvas behavior
Start with:
- [compose-flow](/Users/ovd/dev/reaktor/compose-flow/README.md)

Typical work:
- pan / zoom / fit
- edge drawing
- generic minimap / controls
- pointer / wheel / trackpad behavior

### Reaktor graph scene and graph editor behavior
Start with:
- [reaktor-flow](/Users/ovd/dev/reaktor/reaktor-flow/README.md)

Typical work:
- Reaktor node measurement
- graph layout strategy
- graph chrome
- graph-specific framing

### Product shell and desktop workbench behavior
Do not start in this repo. Start in:
- [BestBuds root README](/Users/ovd/dev/bestbuds/README.md)
- [BestBuds engine / graph editor guide](/Users/ovd/dev/bestbuds/modules/engine/README.md)

## What Reaktor is for

Reaktor is built around a few stable ideas:
- graph-first composition: apps and services are assembled as graphs of nodes
- typed ports and edges: features talk through explicit contracts instead of ad-hoc globals
- capability composition: lifecycle, concurrency, DI, navigation, storage, auth, telemetry
- shared service contracts: the same request/response types can back clients and servers
- platform adapters: Android, iOS, JVM, JS, Cloudflare, Google, native C++
- explicit layering: runtime substrate, graph scene layer, product shell

## Core modules

| Module | Purpose |
| --- | --- |
| `reaktor-core` | adapters, feature registry, capabilities, common runtime primitives |
| `reaktor-graph-port` | typed provider/consumer ports and edges |
| `reaktor-graph` | graph runtime, navigation, node lifecycle, service integration |
| `compose-flow` | generic flow canvas/runtime, viewport interactions, node/edge rendering substrate |
| `reaktor-flow` | Reaktor graph scene, measurement, layout strategy, rendering, editor surface |
| `reaktor-io` | request/response contracts, route patterns, transport helpers |
| `reaktor-auth` | social login, JWT verification, RBAC models, auth service contracts |
| `reaktor-db` | object database, repositories, graph database policy helpers |
| `reaktor-cloudflare` | Workers, D1, R2, Durable Objects, PartyKit, service bindings |
| `reaktor-google` | Google Pub/Sub adapters and related integrations |
| `reaktor-media` | camera, image, speech, gallery, media caching |
| `reaktor-location` | cross-platform location adapters |
| `reaktor-notification` | notification adapter surface |
| `reaktor-work` | background task orchestration |
| `reaktor-ui` | shared UI tokens and components |
| `reaktor-flexbuffer` | native FlexBuffers utility layer and KMP bridge |
| `reaktor-ffi` | Hermes/native bridge layer |
| `dependeasy` | internal Gradle plugin and target orchestration |

## High-signal architecture map

### Graph runtime

Reaktor graphs are assembled from:
- `Graph`: a scoped runtime containing nodes, navigation, DI, coroutine scope, and lifecycle
- `Node`: the unit of behavior; logic, route, container, controller, or actor-like node
- `ProviderPort<T>` / `ConsumerPort<T>`: typed contracts between nodes
- `Edge<T>`: validated connection between provider and consumer

This runtime lives primarily in:
- [reaktor-core](/Users/ovd/dev/reaktor/reaktor-core/README.md)
- [reaktor-graph](/Users/ovd/dev/reaktor/reaktor-graph/README.md)
- `reaktor-graph-port`

Important ideas in this layer:
- typed provider and consumer ports
- graph-scoped capability composition
- route/container navigation across nested graphs
- graph-local auto-wiring with DI fallback

### Generic flow substrate

`compose-flow` exists so the graph editor does not have to bury pan/zoom/fit/selection behavior inside product code.

It owns:
- flow model and viewport state
- wheel/trackpad/pointer gesture mapping
- edge drawing primitives
- generic minimap / controls / background
- React Flow parity work

See:
- [compose-flow](/Users/ovd/dev/reaktor/compose-flow/README.md)

Important design rule:
- `compose-flow` should stay generic, even when BestBuds is the current main consumer
- if a change introduces Reaktor-specific graph assumptions into `compose-flow`, that change is probably going in the wrong layer

### Reaktor graph scene

`reaktor-flow` is the layer that turns Reaktor graph semantics into an actual editor scene.

It owns:
- `reaktor-graph -> flow` adaptation
- node measurement
- layout strategy
- graph regions
- node cards / legend / toolbar / minimap
- graph-specific framing policy
- the high-level `ReaktorGraphEditor(...)` surface used by product hosts

See:
- [reaktor-flow](/Users/ovd/dev/reaktor/reaktor-flow/README.md)

Important design rule:
- `reaktor-flow` should own graph semantics, measurement, layout, and graph-specific rendering
- it should not own desktop workbench chrome

### Product shell

BestBuds desktop owns the editor shell, not the graph runtime:
- title bar
- pane layout
- inspector / preview / tree
- app switching and shell chrome

See:
- [BestBuds](/Users/ovd/dev/bestbuds/README.md)
- [BestBuds engine / graph editor guide](/Users/ovd/dev/bestbuds/modules/engine/README.md)

## Build model

Reaktor is a composite Gradle build and provides its own internal plugins from `dependeasy`.

Important build characteristics:
- Kotlin Multiplatform is the default
- native dependencies such as Hermes and FlatBuffers are bootstrapped into `.github_modules`
- generated JS exports live under `*/ts/export`
- consumer repos such as BestBuds use `includeBuild("../reaktor")`

That means changes in `reaktor` are immediately visible to product repos using the composite build. Treat framework changes as high-leverage changes.

## Quick start

### Prerequisites
- Java 21+
- Android SDK
- Xcode + iOS platform if building Darwin targets
- CMake and Ninja for native modules
- CocoaPods for iOS dependencies

Detailed setup: [SETUP.md](/Users/ovd/dev/reaktor/SETUP.md)

### Build the framework

```bash
./gradlew build
```

### Useful targets

```bash
./gradlew :reaktor-graph:allTests
./gradlew :compose-flow:reportParity
./gradlew :reaktor-flow:jvmTest
./gradlew :reaktor-ffi:assembleDebug
./gradlew :reaktor-flexbuffer:iphoneosCMake
```

### Useful development loops

When working on the graph/editor stack:

```bash
./gradlew :compose-flow:compileKotlinJvm :reaktor-flow:compileKotlinJvm --no-daemon --console=plain
```

When working on framework-wide changes that affect BestBuds desktop:

```bash
cd /Users/ovd/dev/bestbuds
./gradlew :engine:compileKotlin :reaktorDesktop:compileKotlin --no-daemon --console=plain
```

## Documentation map

Framework-wide entry points:
- [SETUP.md](/Users/ovd/dev/reaktor/SETUP.md)
- [reaktor-core](/Users/ovd/dev/reaktor/reaktor-core/README.md)
- [reaktor-graph](/Users/ovd/dev/reaktor/reaktor-graph/README.md)
- [compose-flow](/Users/ovd/dev/reaktor/compose-flow/README.md)
- [reaktor-flow](/Users/ovd/dev/reaktor/reaktor-flow/README.md)
- [reaktor-auth](/Users/ovd/dev/reaktor/reaktor-auth/README.md)
- [reaktor-db](/Users/ovd/dev/reaktor/reaktor-db/README.md)
- [reaktor-cloudflare](/Users/ovd/dev/reaktor/reaktor-cloudflare/README.md)
- [reaktor-ffi](/Users/ovd/dev/reaktor/reaktor-ffi/README.md)
- [reaktor-flexbuffer](/Users/ovd/dev/reaktor/reaktor-flexbuffer/README.md)
- [tools/maestro](/Users/ovd/dev/reaktor/tools/maestro/README.md)

## Status

Reaktor is not a polished general-purpose public framework yet. It is an active product-backed runtime.

Most mature:
- graph runtime and typed ports
- service contracts
- auth and RBAC model
- Cloudflare worker abstractions
- build tooling
- the new `compose-flow` / `reaktor-flow` split for graph editing

Still evolving:
- React Flow parity depth in `compose-flow`
- web-side migration to the new graph stack
- some platform modules and older bridge surfaces

## Guidance for contributors

Use these rules to keep the system coherent:

- add reusable graph/runtime abstractions in `reaktor`
- keep `compose-flow` generic
- keep Reaktor graph semantics in `reaktor-flow`
- keep product shell code in BestBuds or another consumer repo
- prefer one obvious tuning surface over multiple overlapping token systems
- when fixing graph readability, check measurement and layout before tweaking render modifiers
