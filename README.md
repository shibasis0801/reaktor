# Reaktor

Reaktor is a Kotlin Multiplatform application framework and framework stack for building graph-structured apps, services, and platform bridges across Android, iOS, JVM, JavaScript, Cloudflare Workers, and native C++ interop.

It is the shared runtime used by:
- [BestBuds](/Users/ovd/dev/bestbuds/README.md)
- `Manna`

## Graph Blueprint

The graph blueprint is a live visualization of how a Reaktor application is assembled. Every screen, service, repository, and navigation binding is a node in the graph, wired together through typed ports and edges.

![Reaktor Graph Blueprint - BestBuds application graph showing nodes, routes, containers, services, and navigation wires](https://media.licdn.com/dms/image/v2/D5622AQEjWgnHCS6qVQ/feedshare-shrink_800/B56Z1DUxzQGYAc-/0/1774951013900?e=2147483647&v=beta&t=78ucaCV3dKt5uHDhILM6wr8WlGcwEbZYmm0YC-Ah2i4)

*BestBuds running on Reaktor: screens (green), routes (blue), containers (yellow), services/data (orange), with navigation wires (dark blue) and data wires (green lines) connecting them.*

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
- **Graph-first composition**: apps and services are assembled as directed graphs of nodes
- **Typed ports and edges**: features communicate through explicit contracts, not ad-hoc globals
- **Capability composition**: lifecycle, concurrency, DI, navigation, storage, auth, telemetry are composable mixins
- **Shared service contracts**: the same request/response types can back both clients and servers
- **Platform adapters**: Android, iOS, JVM, JS, Cloudflare, Google Cloud, and native C++
- **Explicit layering**: runtime substrate, graph scene layer, and product shell stay separate

---

## Module overview

Every module has a stability level indicating its maturity:

| Level | Meaning |
| --- | --- |
| **Stable** | Production-tested, API unlikely to change. Used by shipping products. |
| **Experimental** | Integrated and functional, but API may evolve. Used in production with care. |
| **Early** | Partial implementation. Functional for its current scope but not feature-complete. |
| **Brainstorming** | Placeholder or minimal skeleton. Reserved for future development. |
| **Paused** | Previously active, now on hold. Code preserved but not recommended for new work. |

## Modules

### Foundation

| Module | Stability | Platforms | Description |
| --- | --- | --- | --- |
| [reaktor-core](/Users/ovd/dev/reaktor/reaktor-core/README.md) | **Stable** | Android, iOS, JVM, JS | adapters, feature registry, capabilities, cross-platform runtime primitives |
| `reaktor-graph-port` | **Stable** | Android, iOS, JVM, JS | typed provider/consumer ports, edges, and port wiring |
| [reaktor-graph](/Users/ovd/dev/reaktor/reaktor-graph/README.md) | **Stable** | Android, iOS, JVM, JS | graph runtime, node lifecycle, navigation, DI, services |
| [reaktor-io](/Users/ovd/dev/reaktor/reaktor-io/README.md) | **Stable** | Android, iOS, JVM, JS | route patterns, request/response shapes, HTTP/WebSocket transport |

### Graph Editor

| Module | Stability | Platforms | Description |
| --- | --- | --- | --- |
| [compose-flow](/Users/ovd/dev/reaktor/compose-flow/README.md) | **Experimental** | JVM, JS | generic flow canvas/runtime, viewport interactions, node/edge rendering substrate |
| [reaktor-flow](/Users/ovd/dev/reaktor/reaktor-flow/README.md) | **Experimental** | JVM, JS | Reaktor graph scene, measurement, layout strategy, rendering, editor surface |

### Identity and Data

| Module | Stability | Platforms | Description |
| --- | --- | --- | --- |
| [reaktor-auth](/Users/ovd/dev/reaktor/reaktor-auth/README.md) | **Stable** | Android, iOS, JVM, JS | OAuth2/OIDC social login (Google, Apple), JWT verification, RBAC |
| [reaktor-db](/Users/ovd/dev/reaktor/reaktor-db/README.md) | **Stable** | Android, iOS, JVM, JS | object database, observable stores, cache policies, offline-first repositories |

### Server and Cloud

| Module | Stability | Platforms | Description |
| --- | --- | --- | --- |
| [reaktor-cloudflare](/Users/ovd/dev/reaktor/reaktor-cloudflare/README.md) | **Experimental** | JS (Cloudflare Workers) | Workers, D1, R2, Durable Objects, PartyKit, Hono, service bindings |
| [reaktor-google](/Users/ovd/dev/reaktor/reaktor-google/README.md) | **Experimental** | JVM, JS, Android, iOS | Google Cloud Pub/Sub adapters |
| [reaktor-work](/Users/ovd/dev/reaktor/reaktor-work/README.md) | **Experimental** | Android, iOS, JVM, JS | background task orchestration with platform-native schedulers |

### Native Interop

| Module | Stability | Platforms | Description |
| --- | --- | --- | --- |
| [reaktor-ffi](/Users/ovd/dev/reaktor/reaktor-ffi/README.md) | **Experimental** | Android (JNI), iOS (cinterop) | native bridge layer with Hermes JS engine integration |
| [reaktor-flexbuffer](/Users/ovd/dev/reaktor/reaktor-flexbuffer/README.md) | **Experimental** | Android, iOS, JVM, JS | FlexBuffers serialization with native C++ utility layer |

### UI and Presentation

| Module | Stability | Platforms | Description |
| --- | --- | --- | --- |
| [reaktor-ui](/Users/ovd/dev/reaktor/reaktor-ui/README.md) | **Early** | Android, iOS, JVM, JS | design tokens, Compose-first components, cross-platform theming |
| [reaktor-media](/Users/ovd/dev/reaktor/reaktor-media/README.md) | **Early** | Android, iOS | camera, gallery, image caching, speech recognition/synthesis adapters |
| `reaktor-web` | **Brainstorming** | Android, iOS, JS | WebView abstraction for embedding web content in native apps |

### Platform Services

| Module | Stability | Platforms | Description |
| --- | --- | --- | --- |
| [reaktor-location](/Users/ovd/dev/reaktor/reaktor-location/README.md) | **Early** | Android, iOS | cross-platform location adapters |
| `reaktor-telemetry` | **Early** | Android, iOS, JVM, JS | OpenTelemetry tracing, Firebase Analytics and Crashlytics adapters |
| [reaktor-notification](/Users/ovd/dev/reaktor/reaktor-notification/README.md) | **Brainstorming** | Android, iOS, JVM, JS | notification delivery and registration adapter surface |
| [reaktor-tactile](/Users/ovd/dev/reaktor/reaktor-tactile/README.md) | **Brainstorming** | - | reserved for touch and haptics APIs |

### Tooling and Codegen

| Module | Stability | Platforms | Description |
| --- | --- | --- | --- |
| `dependeasy` | **Stable** | Gradle plugin | internal Gradle plugin for multiplatform target orchestration and native builds |
| `reaktor-compiler` | **Early** | JVM (build-time) | KSP processor for JS Promise wrappers of suspend functions |
| `reaktor-mcp` | **Brainstorming** | JVM | Model Context Protocol client and server stubs |
| [reaktor-react](/Users/ovd/dev/reaktor/reaktor-react/README.md) | **Paused** | Android, iOS | React Native JSI bridge. Not actively maintained. |

---

## High-signal architecture map

### Graph runtime

Reaktor applications are assembled from directed graphs:

- **`Graph`** is a scoped runtime container owning nodes, DI scope, lifecycle state, coroutine scope, and navigation state.
- **`Node`** is the unit of behavior. Variants include `BasicNode`, `ControllerNode`, `RouteNode`, `ContainerNode`, and service-oriented nodes.
- **`ProviderPort<T>` / `ConsumerPort<T>`** are typed contracts. Nodes communicate through ports, not direct calls.
- **`Edge<T>`** connects a consumer port to a provider port and is validated at connection time.
- **`autoWire()`** matches unconnected consumer ports to provider ports by type and key within a graph, with DI fallback.

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

`compose-flow` exists so graph editors do not bury pan/zoom/fit/selection behavior inside product code.

It owns:
- flow model and viewport state
- wheel/trackpad/pointer gesture mapping
- edge drawing primitives
- generic minimap, controls, and background
- React Flow parity work

See:
- [compose-flow](/Users/ovd/dev/reaktor/compose-flow/README.md)

Important design rule:
- `compose-flow` should stay generic, even when BestBuds is the current main consumer
- if a change introduces Reaktor-specific graph assumptions into `compose-flow`, it is probably going in the wrong layer

### Reaktor graph scene

`reaktor-flow` is the layer that turns Reaktor graph semantics into an editor scene.

It owns:
- `reaktor-graph -> flow` adaptation
- node measurement
- layout strategy
- graph regions
- node cards, legend, toolbar, and minimap
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
- inspector, preview, and tree
- app switching and shell chrome

See:
- [BestBuds](/Users/ovd/dev/bestbuds/README.md)
- [BestBuds engine / graph editor guide](/Users/ovd/dev/bestbuds/modules/engine/README.md)

### Capabilities

Behavior is composed into graphs and nodes through delegation, not deep inheritance:

| Capability | What it provides |
| --- | --- |
| `LifecycleCapability` | state machine: Created, Restoring, Attaching, Saving, Destroying |
| `ConcurrencyCapability` | owned `CoroutineScope`, dispatcher, structured concurrency helpers |
| `DependencyCapability` | scoped DI via Koin with parent/child scope hierarchy |
| `NavigationCapability` | back stack, `Push` / `Pop` / `Replace` / `Return`, cross-graph routing |

### Adapters and features

Platform-specific capabilities are installed through adapters registered in the global `Feature` registry.

Examples:
- `Feature.Auth`
- `Feature.Database`
- `Feature.Telemetry`

This adapter model is what keeps the shared graph runtime portable across Android, iOS, JVM, JS, and Cloudflare targets.

### Service model

Services are defined once and reused across client and server:

- typed `Request` / `Response` with kotlinx.serialization
- route-aware handlers such as `GetHandler`, `PostHandler`, `PutHandler`, `DeleteHandler`
- transport metadata including status codes and headers
- interceptor chains for auth, logging, and caching
- mountable onto Spring WebFlux on JVM or Cloudflare Workers on JS

### Navigation

Navigation is graph-native:

- `RouteNode` defines URL-like patterns such as `/chats/{id}`
- `NavigationEdge` connects routes with typed payloads
- `ContainerNode` manages nested graphs
- cross-graph navigation bubbles up automatically to find the right container
- observable stack state is exposed to UI layers

### Cloudflare and realtime

`reaktor-cloudflare` provides typed Kotlin/JS access to the Cloudflare platform:

- Workers with Hono routing and typed binding resolution
- D1 SQLite integration
- R2 object storage
- Durable Objects
- PartyKit for realtime WebSocket rooms
- service bindings
- vector and worker-platform integration points

### Native interop

Reaktor ships a unified native toolchain path:

- Android via JNI and FBJNI
- iOS via cinterop and CMake tasks
- Hermes integration for native code execution
- FlexBuffers for efficient Kotlin/C++ payload exchange
- an FFI protocol built around module, function, sequence, and encoded arguments

### Background work

`reaktor-work` abstracts platform-native task schedulers behind a unified API for sync, token refresh, analytics upload, media upload, maintenance, and related background jobs.

### Telemetry

`reaktor-telemetry` integrates:
- OpenTelemetry for distributed tracing
- Firebase Analytics for event logging
- Firebase Crashlytics for crash reporting

## Build model

Reaktor is a composite Gradle build and provides its own internal plugins from `dependeasy`.

Important build characteristics:
- Kotlin Multiplatform is the default for all modules
- native dependencies such as Hermes and FlatBuffers are bootstrapped into `.github_modules`
- generated JS exports live under `*/ts/export`
- consumer repos such as BestBuds use `includeBuild("../reaktor")`
- KSP is used for compile-time code generation

That means changes in `reaktor` are immediately visible to product repos using the composite build. Treat framework changes as high-leverage changes.

## Quick start

### Prerequisites
- Java 21+
- Android SDK
- Xcode + iOS platform for Darwin targets
- CMake and Ninja for native modules
- CocoaPods for iOS dependencies

Detailed setup:
- [SETUP.md](/Users/ovd/dev/reaktor/SETUP.md)

### Build the framework

```bash
./gradlew build
```

### Useful targets

```bash
./gradlew :reaktor-graph:allTests
./gradlew :reaktor-graph-port:allTests
./gradlew :compose-flow:reportParity
./gradlew :reaktor-flow:jvmTest
./gradlew :reaktor-ffi:assembleDebug
./gradlew :reaktor-flexbuffer:assembleDebug
./gradlew :reaktor-ffi:iphoneosCMake
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

| Document | Purpose |
| --- | --- |
| [SETUP.md](/Users/ovd/dev/reaktor/SETUP.md) | local machine setup and build prerequisites |
| [LLM_CONTEXT.md](/Users/ovd/dev/reaktor/LLM_CONTEXT.md) | architecture context for AI assistants |
| [reaktor-core](/Users/ovd/dev/reaktor/reaktor-core/README.md) | core runtime layer |
| [reaktor-graph](/Users/ovd/dev/reaktor/reaktor-graph/README.md) | graph runtime |
| [compose-flow](/Users/ovd/dev/reaktor/compose-flow/README.md) | generic flow substrate |
| [reaktor-flow](/Users/ovd/dev/reaktor/reaktor-flow/README.md) | Reaktor graph scene/editor layer |
| [reaktor-auth](/Users/ovd/dev/reaktor/reaktor-auth/README.md) | authentication and RBAC |
| [reaktor-auth/TECHNICAL_README.md](/Users/ovd/dev/reaktor/reaktor-auth/TECHNICAL_README.md) | auth implementation details |
| [reaktor-auth/WEB_IMPLEMENTATION_SUMMARY.md](/Users/ovd/dev/reaktor/reaktor-auth/WEB_IMPLEMENTATION_SUMMARY.md) | web auth specifics |
| [reaktor-db](/Users/ovd/dev/reaktor/reaktor-db/README.md) | database and persistence |
| [reaktor-cloudflare](/Users/ovd/dev/reaktor/reaktor-cloudflare/README.md) | Cloudflare Workers integration |
| [reaktor-google](/Users/ovd/dev/reaktor/reaktor-google/README.md) | Google Cloud Pub/Sub |
| [reaktor-ffi](/Users/ovd/dev/reaktor/reaktor-ffi/README.md) | native bridge layer |
| [reaktor-flexbuffer](/Users/ovd/dev/reaktor/reaktor-flexbuffer/README.md) | FlexBuffers serialization |
| [reaktor-work](/Users/ovd/dev/reaktor/reaktor-work/README.md) | background task orchestration |
| [tools/maestro](/Users/ovd/dev/reaktor/tools/maestro/README.md) | mobile E2E testing |

## Technology stack

| Aspect | Technologies |
| --- | --- |
| **Primary language** | Kotlin Multiplatform |
| **Secondary languages** | C++, TypeScript |
| **UI** | Jetpack Compose |
| **Server** | Spring WebFlux, Cloudflare Workers, Hono |
| **Database** | SQLite, PostgreSQL, D1, Neo4j/Memgraph-adjacent graph usage |
| **Auth** | Google Sign-In, Apple Sign-In, JWT, Spring Security integration points |
| **Serialization** | kotlinx.serialization, FlexBuffers, FlatBuffers |
| **Networking** | Ktor, WebSocket, PartyKit |
| **Native** | JNI, FBJNI, Hermes, CMake, cinterop |
| **Observability** | OpenTelemetry, Firebase Analytics, Firebase Crashlytics |
| **Build** | Gradle, dependeasy, KSP |
| **Testing** | Maestro, Kotlin Test |

## Status

Reaktor is not a polished general-purpose public framework yet. It is an active product-backed runtime.

Most mature:
- graph runtime and typed ports
- service contracts
- authentication, JWT verification, and RBAC model
- database and offline-first persistence
- Cloudflare worker abstractions
- build tooling and composite-build orchestration
- the `compose-flow` / `reaktor-flow` split for graph editing
- mobile testing via Maestro

Still evolving:
- React Flow parity depth in `compose-flow`
- web-side migration to the newer graph stack
- some platform modules and older bridge surfaces
- thin or brainstorming modules such as notification, tactile, MCP, and web

## Guidance for contributors

Use these rules to keep the system coherent:

- add reusable graph/runtime abstractions in `reaktor`
- keep `compose-flow` generic
- keep Reaktor graph semantics in `reaktor-flow`
- keep product shell code in BestBuds or another consumer repo
- prefer one obvious tuning surface over multiple overlapping token systems
- when fixing graph readability, check measurement and layout before tweaking render modifiers
