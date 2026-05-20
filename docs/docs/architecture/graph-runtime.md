# The Graph Runtime

Reaktor's graph-first architecture is its strongest differentiator. No other KMP framework treats the entire application as a directed graph of typed nodes. The graph is the runtime, not just a visualization.

## Node Type Hierarchy

The following node types are core to the Reaktor runtime:

| Node Type | Purpose | Key Capabilities |
| --- | --- | --- |
| **BasicNode** | Simple stateless logic unit. | Lifecycle, DI, and concurrency scope. |
| **ControllerNode** | Stateful node (equivalent to a ViewModel). | `MutableStateFlow` and observable state. |
| **ComposeNode** | Controller node with UI capability. | Jetpack Compose and React integration. |
| **ContainerNode** | Holds and manages sub-graphs. | Child graph management and nested scopes. |
| **RouteNode** | Navigation destination. | URL-like path patterns and deep linking. |
| **ActorNode** | Message-passing concurrency unit. | Mailboxes (Channels) and supervision. |
| **AgentOperatorNode** | AI agent node. | LLM access, planning loops, and auth governance. |

## Why a Graph Runtime?

- **Introspection**: The running graph can be serialized, visualized, and debugged at runtime via the `GraphInspector`.
- **Hot-swapping**: Individual nodes can be replaced at runtime without restarting the graph. This is the foundation for Reaktor's "CodePush" capability.
- **Fractal Composition**: `ContainerNode`s enable infinite nesting. A feature module is a graph; a microservice is a graph. The entire distributed system is a "Graph of Graphs."
- **Automatic Dependency Resolution**: `autoWire()` connects `ProviderPort`s to `ConsumerPort`s by type and key, eliminating boilerplate DI configuration while preserving compile-time type safety.
- **Scoped DI**: Each graph instance creates its own Koin scope. Dependencies are singletons within a graph but are isolated across different graphs.
