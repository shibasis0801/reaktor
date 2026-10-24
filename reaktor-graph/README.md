# reaktor-graph

> **Stability: Stable**

`reaktor-graph` is Reaktor's graph runtime. This is one of the most mature parts of Reaktor and is actively used by BestBuds in production.

## What it adds on top of `reaktor-core` and `reaktor-graph-port`

- Graph and node lifecycle management
- Navigation with back stack and cross-graph routing
- Scoped dependency injection (Koin)
- Coroutine scope ownership
- Route nodes, container nodes, and controller nodes
- Integrated service layer with interceptors
- JSON export helpers for graph inspection

## Platforms

Android (Compose), iOS (Compose), JVM (Spring WebFlux), JavaScript/Web

## Main runtime types

### Graph

`Graph` is the primary runtime container. It owns:
- **Nodes** - a collection of `Node` instances
- **DI scope** - a Koin scope with parent/child hierarchy
- **Lifecycle state** - coordinated state transitions across all nodes
- **Coroutine scope** - supervisor-based structured concurrency
- **Navigation state** - observable back stack with route matching
- **Port wiring** - `autoWire()` matches unconnected consumers to providers by type/key

### Nodes

| Type | Purpose |
|---|---|
| `BasicNode` | Simple logic unit, supports builder pattern |
| `ControllerNode<State>` | Stateful node (ViewModel equivalent) with `MutableStateFlow<State>` |
| `RouteNode<P, Binding>` | Navigation destination with URL-like route pattern |
| `ContainerNode` | Holds nested child `Graph` instances (e.g., bottom navigation tabs) |
| `ContractNode<Api>` | Serves or calls a contract; provides the whole API and one typed port per call |

### Services

Services are contracts: one Kotlin interface that both the client and the server implement, so the compiler enforces the boundary:

- `Contract<Api>` - the interface's companion; `call(Api::op, Get("/path"))` binds each operation to its route
- `graph.serve(Contract, implementation)` - a server node; `graph.remote(Contract, link)` - a client node over `HttpLink`, `LocalLink` or a socket
- `by uses(Contract)` and `by api(Contract.op)` - consumer ports that `autoWire()` connects to those nodes
- Interceptors installed on a graph reach every contract in it and its child graphs (telemetry, retry, security, application)

### Navigation

- `RouteNode` defines URL-like patterns (`/chats`, `/chats/{id}`)
- `NavigationEdge` connects routes with typed payloads
- `NavCommand` variants: `Push`, `Pop`, `Replace`, `Return`
- `BackStack` with observable state via `StateFlow`
- Cross-graph navigation automatically bubbles up to find containers

## Core ideas

### Graph as runtime scope

A graph owns nodes, DI scope, lifecycle state, coroutine scope, navigation state, local ports, and auto-wiring. `Graph { ... }` owns child scopes, including graphs without a UI container; closing the owner disposes them and `shape()` includes them. Child graphs inherit the owner's dependency adapter.

`dependsOn(config)` imports a graph's provider ports for this scope and its descendants. Imports borrow providers and do not transfer ownership. `autoWire(otherFeature)` adds providers for that wiring pass; sibling features remain isolated unless explicitly connected. Local providers take priority, followed by declared imports and the nearest ancestor scope.

`input<T>(key)` selects a scoped provider for construction. `Node(configurationPort, key, ::CatalogNode) { config -> link(config) }` constructs only the selected implementation and connects its configuration port. A matching typed provider in scope replaces the default factory and is connected as an additional input, keeping substitutions inspectable through ordinary graph edges.

### Nodes communicate through typed ports

Nodes never call each other directly. They communicate through `ProviderPort<T>` and `ConsumerPort<T>` wired by explicit edges or `autoWire()`.

### Navigation is graph-native

Navigation is part of the graph runtime through route bindings, navigation edges, and container nodes. It is not bolted on as a separate router.

### Services are part of the same model

Service contracts sit inside the graph model. A contract node can be swapped transparently between a served implementation and a remote client, because both provide the same interface.

## Dependencies

- `reaktor-graph-port` - Port abstraction layer
- `reaktor-ui` - Compose integration
- `reaktor-db` - Database layer
- Arrow - Functional programming utilities
- Koin - Dependency injection
- Spring WebFlux, Exposed ORM, PostgreSQL (server only)
