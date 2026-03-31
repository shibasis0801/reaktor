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
| `ServiceNode` | Wraps a `Service`, exposing each handler as a typed `ProviderPort` |

### Services

Services are HTTP/RPC abstractions that work identically on client and server:

- `Service` - abstract base with a list of `RequestHandler` instances
- `GetHandler`, `PostHandler`, `PutHandler`, `DeleteHandler` - typed route handlers
- `ServiceEndpoint` - URL, operation, and transport type
- Interceptor chains for auth, logging, caching, error handling
- `ServiceNode` wraps services as graph nodes for port-based wiring

### Navigation

- `RouteNode` defines URL-like patterns (`/chats`, `/chats/{id}`)
- `NavigationEdge` connects routes with typed payloads
- `NavCommand` variants: `Push`, `Pop`, `Replace`, `Return`
- `BackStack` with observable state via `StateFlow`
- Cross-graph navigation automatically bubbles up to find containers

## Core ideas

### Graph as runtime scope

A graph owns nodes, DI scope, lifecycle state, coroutine scope, navigation state, local ports, and auto-wiring. It is the fundamental unit of application composition.

### Nodes communicate through typed ports

Nodes never call each other directly. They communicate through `ProviderPort<T>` and `ConsumerPort<T>` wired by explicit edges or `autoWire()`.

### Navigation is graph-native

Navigation is part of the graph runtime through route bindings, navigation edges, and container nodes. It is not bolted on as a separate router.

### Services are part of the same model

Service contracts sit inside the graph model. A `ServiceNode` can be swapped transparently between a real server implementation and an HTTP client proxy.

## Dependencies

- `reaktor-graph-port` - Port abstraction layer
- `reaktor-ui` - Compose integration
- `reaktor-db` - Database layer
- Arrow - Functional programming utilities
- Koin - Dependency injection
- Spring WebFlux, Exposed ORM, PostgreSQL (server only)
