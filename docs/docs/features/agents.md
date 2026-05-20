# Agentic Graph Operations: Agents as First-Class Citizens

In Reaktor, AI agents are not just external tools; they are full participants in the graph runtime. This "Agent-as-Node" architecture allows for a more powerful, granular, and secure agent runtime than standard Model Context Protocol (MCP) integrations.

## Agent-as-Node Architecture

An agent in Reaktor is a specialized graph node (`AgentOperatorNode`) that possesses several key characteristics:

-   **An Identity**: Every agent is bound to a Reaktor Auth principal (service account) with specific RBAC roles and permissions.
-   **Typed Port Access**: The agent's `ConsumerPorts` and `ProviderPorts` define exactly what data it can read and write, which is enforced at the graph level.
-   **An Operation Log**: Every port invocation, state mutation, and database query is traced and visible in the Blueprint editor in real-time.
-   **Rollback Capability**: Agent operations can be wrapped in a transaction scope, allowing for automatic rollback if a multi-step plan fails.
-   **Scoped Context Window**: The agent's LLM context is populated directly from the graph—the topology of reachable nodes, the schemas of accessible ports, and the recent telemetry of connected edges.

## Auth-Governed Agent Operations

Every agent operation is governed by Reaktor Auth's granular RBAC system. An agent can only access ports and nodes for which it is authorized.

-   **Database Queries**: Access `RepositoryNode`'s `ProviderPorts` with `db:read` and `db:write` permissions.
-   **Graph Traversal**: Uses the `GraphInspector` interface for read-only traversal with `graph:read` permissions.
-   **Graph Mutation**: Add nodes, wire ports, and hot-swap implementations with `graph:write` and `graph:deploy` permissions.
-   **Service Invocation**: Call external APIs through a `ServiceNode` with `service:invoke` permissions.
-   **Deployment**: Push JS bundles to R2 and trigger k3s rolling updates with `deploy:js_mobile` or `deploy:server` permissions.

## Why This Is Better Than MCP

While Reaktor supports MCP for external interoperability, its graph-native agent model solves several fundamental limitations:

-   **Relationship-Aware**: Agents operate on a graph with typed edges, allowing them to understand relationships and dependency ordering.
-   **Built-in Auth**: Agents have RBAC identities, ensuring secure tool access.
-   **Transaction Support**: Multi-step sequences can fail with an automatic rollback.
-   **Observability**: Every operation flows through Reaktor telemetry and is visible in a unified trace.
-   **Context-Rich**: The agent's context is populated from the graph itself, which acts as the tool documentation.
