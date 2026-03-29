# Reaktor

Reaktor is a Kotlin Multiplatform application framework for building graph-structured apps and services across Android, iOS, JVM, JS, Cloudflare Workers, and native C++ interop.

It is the shared runtime used by:
- [BestBuds](https://github.com/shibasis0801/bestbuds/blob/main/README.md)
- `Manna`

## What Reaktor is for

Reaktor is built around a few stable ideas:
- graph-first composition: apps and services are assembled as graphs of nodes
- typed ports and edges: features talk through explicit contracts instead of ad-hoc globals
- capability composition: lifecycle, concurrency, DI, navigation, storage, auth, telemetry
- shared service contracts: the same request/response types can back clients and servers
- platform adapters: Android, iOS, JVM, JS, Cloudflare, Google, native C++

## Core modules

| Module | Purpose |
| --- | --- |
| `reaktor-core` | adapters, feature registry, capabilities, common runtime primitives |
| `reaktor-graph-port` | typed provider/consumer ports and edges |
| `reaktor-graph` | graph runtime, navigation, node lifecycle, service integration |
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

## Architecture

### Graph runtime

Reaktor graphs are assembled from:
- `Graph`: a scoped runtime containing nodes, navigation, DI, coroutine scope, and lifecycle
- `Node`: the unit of behavior; logic, route, container, controller, or actor-like node
- `ProviderPort<T>` / `ConsumerPort<T>`: typed contracts between nodes
- `Edge<T>`: validated connection between provider and consumer

This model is what BestBuds uses for screen graphs, navigation, repositories, and service composition.

### Adapters and features

Platform-specific capabilities are exposed through adapters registered in the global `Feature` registry. Examples:
- `Feature.Auth`
- `Feature.Database`
- `Feature.Sql`
- `Feature.Storage`
- `Feature.Permission`
- `Feature.GooglePubSub`

The adapter pattern keeps the framework code platform-neutral while allowing Android, iOS, JVM, and JS controllers underneath.

### Service model

Reaktor services are defined once and reused across client and server code.

The current model supports:
- typed `Request` / `Response`
- route-aware handlers like `GetHandler`, `PostHandler`, `PutHandler`, `DeleteHandler`
- transport metadata such as status code and headers
- interceptor/policy hooks
- mounting onto server runtimes such as Spring or Cloudflare Workers

### Cloudflare and realtime

`reaktor-cloudflare` now covers:
- Workers
- D1
- R2
- Durable Objects
- service bindings
- PartyKit room/server wrappers

This is what BestBuds uses for its worker and realtime deployment surface.

### Native interop

Reaktor also ships a unified native toolchain path via `dependeasy`:
- Android native builds through CMake without AGP `externalNativeBuild`
- Darwin native builds through generated cinterop + CMake tasks
- Hermes and FlexBuffers integration for runtime native execution

## Build model

Reaktor is a composite Gradle build and provides its own internal plugins from `dependeasy`.

Important build characteristics:
- Kotlin Multiplatform is the default
- native dependencies such as Hermes and FlatBuffers are bootstrapped into `.github_modules`
- generated JS exports live under `*/ts/export`
- consumer repos such as BestBuds use `includeBuild("../reaktor")`

## Quick start

### Prerequisites
- Java 21+
- Android SDK
- Xcode + iOS platform if building Darwin targets
- CMake and Ninja for native modules
- CocoaPods for iOS dependencies

Detailed setup: [SETUP.md](./SETUP.md)

### Build the framework

```bash
./gradlew build
```

### Useful targets

```bash
./gradlew :reaktor-graph:allTests
./gradlew :reaktor-graph-port:allTests
./gradlew :reaktor-ffi:assembleDebug
./gradlew :reaktor-flexbuffer:iphoneosCMake
```

## Documentation map

- [SETUP.md](./SETUP.md): local machine setup and build prerequisites
- [reaktor-core](./reaktor-core/README.md)
- [reaktor-graph](./reaktor-graph/README.md)
- [reaktor-auth](./reaktor-auth/README.md)
- [reaktor-db](./reaktor-db/README.md)
- [reaktor-cloudflare](./reaktor-cloudflare/README.md)
- [reaktor-ffi](./reaktor-ffi/README.md)
- [reaktor-flexbuffer](./reaktor-flexbuffer/README.md)
- [tools/maestro](./tools/maestro/README.md)

## Status

Reaktor is not a polished general-purpose public framework yet. It is an active product-backed runtime. The important parts are real and in production use:
- graph runtime
- service contracts
- auth
- Cloudflare workers
- build tooling
- mobile testing tooling
- native bridge path

Some modules are intentionally thinner or still evolving.
