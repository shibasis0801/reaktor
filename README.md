# Reaktor Framework (reaktor)

Reaktor (`reaktor`) is a foundational Kotlin Multiplatform (KMP) framework ecosystem designed for graph-first app composition, flow-based edge wiring, shared contract surfaces across Kotlin/JVM/JS/native, and multi-tenant applications. 

It acts as the runtime platform layer for dependent products (like `bestbuds` and `Manna`).

## Core Architecture

The framework is built around a directed graph architecture where functionality is encapsulated in **Nodes**, which communicate exclusively over **Edges** via typed **Ports**.

### 1. The Graph Substrate
* **`Graph`**: The root container and lifecycle manager. It manages a collection of `Node`s, coordinates lifecycle transitions, and provides isolated scopes for dependency injection and concurrency.
* **`Node`**: The fundamental unit of logic.
  * `BasicNode`: A simple logic execution unit.
  * `ControllerNode`: A stateful node (ViewModel equivalent), holding a `MutableStateFlow`.
  * `ContainerNode`: Manages sub-graphs (e.g., `BottomNavigationContainer`) and cross-graph navigation.
  * `RouteNode`: Defines navigation paths, payload structures, and routing destinations.
  * `ActorNode`: Experimental single-threaded channel pattern.

### 2. Communication (Ports & Edges)
Nodes are completely decoupled. They communicate through strict typed ports:
* **`ProviderPort<T>`**: Exposes functionality (an implementation of type `T`).
* **`ConsumerPort<T>`**: Consumes functionality. It remains inactive until connected to a `ProviderPort`.
* **`Edge<T>`**: Validates connectivity rules and binds a consumer to a provider.
* **`autoWire()`**: An algorithm invoked on the `Graph` that automatically binds matching local provider ports to unconnected consumer ports, falling back to DI lookup if necessary.

### 3. Capabilities System
Capabilities compose behavior into classes using Kotlin delegation, avoiding deep inheritance hierarchies:
* `LifecycleCapability`: Manages state machine transitions (`Created` -> `Restoring` -> `Attaching` -> `Saving` -> `Destroying`).
* `ConcurrencyCapability`: Manages `CoroutineScope` and custom dispatchers.
* `DependencyCapability`: Connects nodes and graphs to the scoped DI system.
* `NavigationCapability`: Manages the back stack, observable history, and navigation commands (`Push`, `Pop`, `Replace`).

### 4. Adapter Pattern & Feature Slots
Adapters bridge generic framework components to platform-specific controllers without hard-coupling APIs:
* **`Adapter<Controller>`**: Stores platform controllers via `WeakRef` and provides `invoke {}` and `suspended {}` helpers to centralize null-handling.
* **`Feature` Registry**: A global dependency slot registry using `CreateSlot<T>`. Modules register adapters into slots (e.g., `Feature.Auth`, `Feature.Database`, `Feature.Telemetry`, `Feature.GooglePubSub`).

## Module Inventory

* **Core & Runtime**: `reaktor-core`, `reaktor-graph`, `reaktor-graph-port`
* **Data & Auth**: 
  * `reaktor-auth`: Multi-tenant scoped RBAC schema (App, Context, Role, Permission) with provider-agnostic JWT verification.
  * `reaktor-db`: Object DB abstraction (currently backed by `SqliteObjectDatabase`) emphasizing Offline-First, read-through caching via `RepositoryNode`.
  * `reaktor-io`: Core typed service abstractions, routing matchers, and Ktor client wrappers.
* **Interop**: `reaktor-flexbuffer` (C++ primitives), `reaktor-ffi` (Hermes/JSI integration)
* **Services**: 
  * `reaktor-cloudflare`: Typed Cloudflare Worker & Durable Object bindings (D1, R2, Vector).
  * `reaktor-google`: PubSub adapter abstractions.
  * `reaktor-ui`, `reaktor-media`, `reaktor-location`, `reaktor-work`, `reaktor-notification`, `reaktor-mcp`, `reaktor-react`, `reaktor-web`.
* **Observability**: `reaktor-telemetry` (Graph-aware tracing and analytics, distributing OpenTelemetry spans across KMP and Cloudflare Workers).
* **Tooling**: `dependeasy` (Gradle target DSL plugins), `reaktor-compiler`.

## Build System (Dependeasy, CMake, Karakum)
`reaktor` uses a custom build orchestrator called `dependeasy` (a composite build plugin) that standardizes Android, iOS (Darwin), Web, and Server target configurations. 

- **Native Toolchain**: The root `settings.gradle.kts` clones and bootstraps dependencies like FlatBuffers and Hermes. Android targets configure `externalNativeBuild` and iOS targets create custom `darwinCmake` tasks compiling C++ sources directly to static libs linked via cinterop.
- **TypeScript Interop (Karakum)**: Modules exporting JS binaries (`ts/export`) have wrapper packages (`ts/`) that leverage `karakum` to generate Kotlin external declarations back into `ts/import`. This provides a bidirectional bridge for TS/Kotlin.

## Setup & Building

To build Reaktor from scratch on macOS (Apple Silicon):

1. **Xcode**: Install Xcode 16+ and the iOS platform (`xcodebuild -downloadPlatform iOS`).
2. **Java 21**: JDK 21 is required.
3. **CMake & Ninja**: Required for native C++ dependencies.
4. **CocoaPods**: Required for iOS dependencies.
5. **Android SDK**: Install Android Studio and the SDK.

Create a `local.properties` file in the project root:
```properties
sdk.dir=/Users/<username>/Library/Android/sdk
kotlin.apple.cocoapods.bin=/Users/<username>/.rbenv/shims/pod
```

Run the build:
```bash
./gradlew build
```
*(Note: The first build clones and compiles native dependencies like flatbuffers, which takes several minutes.)*

For detailed setup instructions, see [SETUP.md](./SETUP.md).
For deeper LLM architectural context, see [LLM_CONTEXT.md](./LLM_CONTEXT.md) and the root `AGENTS.md` file.
