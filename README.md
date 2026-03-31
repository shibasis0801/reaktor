# Reaktor

Reaktor is a **Kotlin Multiplatform application framework** for building graph-structured apps and services across Android, iOS, JVM, JavaScript, Cloudflare Workers, and native C++ interop.

It is the shared runtime used by:
- [BestBuds](https://github.com/shibasis0801/bestbuds/blob/main/README.md)
- `Manna`

## Graph Blueprint

The graph blueprint is a live visualization of how a Reaktor application is assembled. Every screen, service, repository, and navigation binding is a node in the graph, wired together through typed ports and edges.

![Reaktor Graph Blueprint - BestBuds application graph showing nodes, routes, containers, services, and navigation wires](https://media.licdn.com/dms/image/v2/D5622AQEjWgnHCS6qVQ/feedshare-shrink_800/B56Z1DUxzQGYAc-/0/1774951013900?e=2147483647&v=beta&t=78ucaCV3dKt5uHDhILM6wr8WlGcwEbZYmm0YC-Ah2i4)

*BestBuds running on Reaktor: screens (green), routes (blue), containers (yellow), services/data (orange), with navigation wires (dark blue) and data wires (green lines) connecting them.*

## What Reaktor is for

Reaktor is built around a few stable ideas:
- **Graph-first composition**: apps and services are assembled as directed graphs of nodes
- **Typed ports and edges**: features communicate through explicit contracts, not ad-hoc globals
- **Capability composition**: lifecycle, concurrency, DI, navigation, storage, auth, telemetry are all composable mixins
- **Shared service contracts**: the same request/response types back both clients and servers
- **Platform adapters**: Android, iOS, JVM, JS, Cloudflare, Google Cloud, native C++

---

## Module Overview

Every module has a **stability level** indicating its maturity:

| Level | Meaning |
|---|---|
| **Stable** | Production-tested, API unlikely to change. Used by shipping products. |
| **Experimental** | Integrated and functional, but API may evolve. Used in production with care. |
| **Early** | Partial implementation. Functional for its current scope but not feature-complete. |
| **Brainstorming** | Placeholder or minimal skeleton. Reserved for future development. |
| **Paused** | Previously active, now on hold. Code preserved but not recommended for new work. |

---

## Modules

### Foundation

<table>
<tr><th>Module</th><th>Stability</th><th>Platforms</th><th>Description</th></tr>
<tr>
  <td><a href="./reaktor-core/README.md"><code>reaktor-core</code></a></td>
  <td><strong>Stable</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>Adapters, feature registry, capabilities, cross-platform runtime primitives</td>
</tr>
<tr>
  <td><a href="./reaktor-graph-port"><code>reaktor-graph-port</code></a></td>
  <td><strong>Stable</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>Typed provider/consumer ports, edges, and port wiring</td>
</tr>
<tr>
  <td><a href="./reaktor-graph/README.md"><code>reaktor-graph</code></a></td>
  <td><strong>Stable</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>Graph runtime, node lifecycle, navigation, DI, services</td>
</tr>
<tr>
  <td><a href="./reaktor-io/README.md"><code>reaktor-io</code></a></td>
  <td><strong>Stable</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>Route patterns, request/response shapes, HTTP/WebSocket transport</td>
</tr>
</table>

### Identity and Data

<table>
<tr><th>Module</th><th>Stability</th><th>Platforms</th><th>Description</th></tr>
<tr>
  <td><a href="./reaktor-auth/README.md"><code>reaktor-auth</code></a></td>
  <td><strong>Stable</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>OAuth2/OIDC social login (Google, Apple), JWT verification, RBAC</td>
</tr>
<tr>
  <td><a href="./reaktor-db/README.md"><code>reaktor-db</code></a></td>
  <td><strong>Stable</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>Object database, observable stores, cache policies, offline-first repositories</td>
</tr>
</table>

### Server and Cloud

<table>
<tr><th>Module</th><th>Stability</th><th>Platforms</th><th>Description</th></tr>
<tr>
  <td><a href="./reaktor-cloudflare/README.md"><code>reaktor-cloudflare</code></a></td>
  <td><strong>Experimental</strong></td>
  <td>JS (Cloudflare Workers)</td>
  <td>Workers, D1, R2, Durable Objects, PartyKit, Hono, service bindings</td>
</tr>
<tr>
  <td><a href="./reaktor-google/README.md"><code>reaktor-google</code></a></td>
  <td><strong>Experimental</strong></td>
  <td>JVM, JS, Android, iOS</td>
  <td>Google Cloud Pub/Sub adapters (publish, subscribe, pull, ack)</td>
</tr>
<tr>
  <td><a href="./reaktor-work/README.md"><code>reaktor-work</code></a></td>
  <td><strong>Experimental</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>Background task orchestration with platform-native schedulers</td>
</tr>
</table>

### Native Interop

<table>
<tr><th>Module</th><th>Stability</th><th>Platforms</th><th>Description</th></tr>
<tr>
  <td><a href="./reaktor-ffi/README.md"><code>reaktor-ffi</code></a></td>
  <td><strong>Experimental</strong></td>
  <td>Android (JNI), iOS (cinterop)</td>
  <td>Native bridge layer with Hermes JS engine integration</td>
</tr>
<tr>
  <td><a href="./reaktor-flexbuffer/README.md"><code>reaktor-flexbuffer</code></a></td>
  <td><strong>Experimental</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>FlexBuffers serialization with native C++ utility layer</td>
</tr>
</table>

### UI and Presentation

<table>
<tr><th>Module</th><th>Stability</th><th>Platforms</th><th>Description</th></tr>
<tr>
  <td><a href="./reaktor-ui/README.md"><code>reaktor-ui</code></a></td>
  <td><strong>Early</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>Design tokens, Compose-first components, cross-platform theming</td>
</tr>
<tr>
  <td><a href="./reaktor-media/README.md"><code>reaktor-media</code></a></td>
  <td><strong>Early</strong></td>
  <td>Android, iOS</td>
  <td>Camera, gallery, image caching, speech recognition/synthesis adapters</td>
</tr>
<tr>
  <td><a href="./reaktor-web"><code>reaktor-web</code></a></td>
  <td><strong>Brainstorming</strong></td>
  <td>Android, iOS, JS</td>
  <td>WebView abstraction for embedding web content in native apps</td>
</tr>
</table>

### Platform Services

<table>
<tr><th>Module</th><th>Stability</th><th>Platforms</th><th>Description</th></tr>
<tr>
  <td><a href="./reaktor-location/README.md"><code>reaktor-location</code></a></td>
  <td><strong>Early</strong></td>
  <td>Android, iOS</td>
  <td>Cross-platform location adapters (GPS, map interface)</td>
</tr>
<tr>
  <td><a href="./reaktor-telemetry"><code>reaktor-telemetry</code></a></td>
  <td><strong>Early</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>OpenTelemetry tracing, Firebase Analytics and Crashlytics adapters</td>
</tr>
<tr>
  <td><a href="./reaktor-notification/README.md"><code>reaktor-notification</code></a></td>
  <td><strong>Brainstorming</strong></td>
  <td>Android, iOS, JVM, JS</td>
  <td>Notification delivery and registration adapter surface</td>
</tr>
<tr>
  <td><a href="./reaktor-tactile/README.md"><code>reaktor-tactile</code></a></td>
  <td><strong>Brainstorming</strong></td>
  <td>-</td>
  <td>Reserved for touch and haptics APIs. Not built out yet.</td>
</tr>
</table>

### Tooling and Codegen

<table>
<tr><th>Module</th><th>Stability</th><th>Platforms</th><th>Description</th></tr>
<tr>
  <td><a href="./dependeasy"><code>dependeasy</code></a></td>
  <td><strong>Stable</strong></td>
  <td>Gradle plugin</td>
  <td>Internal Gradle plugin for multiplatform target orchestration and native builds</td>
</tr>
<tr>
  <td><a href="./reaktor-compiler"><code>reaktor-compiler</code></a></td>
  <td><strong>Early</strong></td>
  <td>JVM (build-time)</td>
  <td>KSP processor for JS Promise wrappers of suspend functions</td>
</tr>
<tr>
  <td><a href="./reaktor-mcp"><code>reaktor-mcp</code></a></td>
  <td><strong>Brainstorming</strong></td>
  <td>JVM</td>
  <td>Model Context Protocol client and server stubs</td>
</tr>
<tr>
  <td><a href="./reaktor-react/README.md"><code>reaktor-react</code></a></td>
  <td><strong>Paused</strong></td>
  <td>Android, iOS</td>
  <td>React Native JSI bridge. Not actively maintained.</td>
</tr>
</table>

---

## Architecture

### Graph runtime

Reaktor applications are assembled from directed graphs:

- **`Graph`** is a scoped runtime container owning nodes, DI scope, lifecycle state, coroutine scope, and navigation state.
- **`Node`** is the unit of behavior. Variants include `BasicNode`, `ControllerNode` (stateful, ViewModel-like), `RouteNode` (navigation destination), `ContainerNode` (nested sub-graphs), and `ServiceNode` (HTTP/RPC service wrapper).
- **`ProviderPort<T>` / `ConsumerPort<T>`** are typed contracts. Nodes never call each other directly; they communicate through ports.
- **`Edge<T>`** connects a consumer port to a provider port. Validated at connection time with type and key matching.
- **`autoWire()`** automatically matches unconnected consumer ports to provider ports by type and key within a graph.

### Capabilities (mixins)

Behavior is composed into graphs and nodes through delegation, not deep inheritance:

| Capability | What it provides |
|---|---|
| `LifecycleCapability` | State machine: Created, Restoring, Attaching, Saving, Destroying |
| `ConcurrencyCapability` | Owned `CoroutineScope`, dispatcher, structured concurrency helpers |
| `DependencyCapability` | Scoped DI via Koin with parent/child scope hierarchy |
| `NavigationCapability` | Back stack, `Push`/`Pop`/`Replace`/`Return` commands, cross-graph routing |

### Adapters and features

Platform-specific capabilities are installed through adapters registered in the global `Feature` registry:

```kotlin
// At app startup
Feature.Auth = AndroidAuthAdapter(activity)
Feature.Database = SqliteObjectDatabase(context)
Feature.Telemetry = TelemetryAdapter(activity, createOpenTelemetry { ... })

// In shared code (any platform)
val user = Feature.Auth?.login(appId, environment, UserProvider.GOOGLE)
```

### Service model

Services are defined once and reused across client and server:

- Typed `Request` / `Response` with kotlinx.serialization
- Route-aware handlers: `GetHandler`, `PostHandler`, `PutHandler`, `DeleteHandler`
- Transport metadata (status codes, headers)
- Interceptor chains for auth, logging, caching
- Mountable onto Spring WebFlux (JVM) or Cloudflare Workers (JS)
- `ServiceNode` wraps services as graph nodes, exposing handlers as typed ports

### Navigation

Navigation is graph-native, not bolted on:

- `RouteNode` defines URL-like patterns (e.g., `/chats/{id}`)
- `NavigationEdge` connects routes with typed payloads
- `ContainerNode` manages nested graphs (e.g., bottom navigation tabs)
- Cross-graph navigation bubbles up automatically to find the right container
- `ObservableStack` backs the navigation state, observable by the UI

### Cloudflare and realtime

`reaktor-cloudflare` provides typed Kotlin/JS access to the full Cloudflare platform:

- **Workers** with Hono routing and typed binding resolution
- **D1** SQLite with query builders and typed row mapping
- **R2** object storage with streaming and JSON support
- **Durable Objects** for persistent actor-like computation
- **PartyKit** for real-time WebSocket rooms with hibernation support
- **Service bindings** for worker-to-worker calls
- **Vectorize** for embedding/vector database queries

### Native interop

Reaktor ships a unified native toolchain path:

- **Android**: JNI bridge via FBJNI with `JAVA_DESCRIPTOR(...)` macro for readable descriptors
- **iOS**: cinterop + CMake tasks for Darwin native builds
- **Hermes**: Facebook's JS engine integrated for native code execution
- **FlexBuffers**: Binary serialization for efficient Kotlin-C++ payload exchange
- **FFI protocol**: Module name, function name, sequence number, and arguments encoded as a FlexBuffer vector

### Background work

`reaktor-work` abstracts platform-native task schedulers (WorkManager on Android, BGTaskScheduler on iOS, schedulers on JVM/JS) behind a unified API:

Built-in worker shapes: sync, token refresh, analytics upload, media upload, database maintenance, cache cleanup, notification sync, heartbeat, prefetch, log upload.

### Telemetry

`reaktor-telemetry` integrates:

- **OpenTelemetry Kotlin** for distributed tracing (noop by default, zero overhead)
- **Firebase Analytics** for event logging via GitLive SDK
- **Firebase Crashlytics** for crash reporting on Android and iOS

---

## Build model

Reaktor is a composite Gradle build with its own internal plugins from `dependeasy`.

- Kotlin Multiplatform is the default for all modules
- Native dependencies (Hermes, FlatBuffers) are bootstrapped into `.github_modules`
- Generated JS exports live under `*/ts/export`
- Consumer repos use `includeBuild("../reaktor")` for immediate change propagation
- KSP is used for compile-time code generation (Promise wrappers, annotations)

## Quick start

### Prerequisites
- Java 21+
- Android SDK
- Xcode + iOS platform (for Darwin targets)
- CMake and Ninja (for native modules)
- CocoaPods (for iOS dependencies)

Detailed setup: [SETUP.md](./SETUP.md)

### Build the framework

```bash
./gradlew build
```

### Run tests

```bash
# Graph runtime tests
./gradlew :reaktor-graph:allTests
./gradlew :reaktor-graph-port:allTests

# Native bridge (Android)
./gradlew :reaktor-ffi:assembleDebug
./gradlew :reaktor-flexbuffer:assembleDebug

# Native bridge (iOS)
./gradlew :reaktor-ffi:iphoneosCMake
./gradlew :reaktor-flexbuffer:iphoneosCMake
```

## Documentation map

| Document | Purpose |
|---|---|
| [SETUP.md](./SETUP.md) | Local machine setup and build prerequisites |
| [LLM_CONTEXT.md](./LLM_CONTEXT.md) | Architecture context for AI assistants |
| [reaktor-core](./reaktor-core/README.md) | Core runtime layer |
| [reaktor-graph](./reaktor-graph/README.md) | Graph runtime |
| [reaktor-auth](./reaktor-auth/README.md) | Authentication and RBAC |
| [reaktor-auth/TECHNICAL_README.md](./reaktor-auth/TECHNICAL_README.md) | Auth implementation details |
| [reaktor-auth/WEB_IMPLEMENTATION_SUMMARY.md](./reaktor-auth/WEB_IMPLEMENTATION_SUMMARY.md) | Web auth specifics |
| [reaktor-db](./reaktor-db/README.md) | Database and persistence |
| [reaktor-cloudflare](./reaktor-cloudflare/README.md) | Cloudflare Workers integration |
| [reaktor-google](./reaktor-google/README.md) | Google Cloud Pub/Sub |
| [reaktor-ffi](./reaktor-ffi/README.md) | Native bridge layer |
| [reaktor-flexbuffer](./reaktor-flexbuffer/README.md) | FlexBuffers serialization |
| [reaktor-work](./reaktor-work/README.md) | Background task orchestration |
| [tools/maestro](./tools/maestro/README.md) | Mobile E2E testing |

## Technology stack

| Aspect | Technologies |
|---|---|
| **Primary language** | Kotlin (Multiplatform) |
| **Secondary languages** | C++ (native interop), TypeScript (JS platform exports) |
| **UI** | Jetpack Compose (Android, iOS, Desktop) |
| **Server** | Spring WebFlux, Cloudflare Workers (Hono) |
| **Database** | SQLite (SQLDelight), PostgreSQL (Exposed ORM), D1, Neo4j |
| **Auth** | Google Sign-In, Apple Sign-In, JWT, Spring Security |
| **Serialization** | kotlinx.serialization, FlexBuffers, FlatBuffers |
| **Networking** | Ktor, WebSocket, PartyKit |
| **Native** | JNI/FBJNI, Hermes, CMake, cinterop |
| **Observability** | OpenTelemetry, Firebase Analytics, Firebase Crashlytics |
| **Build** | Gradle, dependeasy (custom plugin), KSP |
| **Testing** | Maestro (mobile E2E), Kotlin Test |

## Status

Reaktor is not a polished general-purpose public framework yet. It is an **active product-backed runtime**. The important parts are real and in production use:

- Graph runtime and navigation
- Service contracts (client + server)
- Authentication (Google, Apple, JWT, RBAC)
- Database and offline-first persistence
- Cloudflare Workers deployment
- Native bridge (Hermes + FlexBuffers)
- Build tooling (dependeasy)
- Mobile testing (Maestro)
- Background work scheduling
- Google Pub/Sub integration

Some modules (notification, tactile, MCP, web) are intentionally thin or still at the brainstorming stage.
