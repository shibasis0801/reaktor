# The Headless Engine: React Native, Flutter, and Beyond

Reaktor is a headless service engine that works with any pluggable UI frontend. The core runtime, including typed ports, auth, database, telemetry, deployment, and agent infrastructure, is UI-independent.

## Architecture: Service Layer + UI Bridge

The architecture is split into two layers:

1.  **Reaktor Service Layer**: Runs natively on the device (Kotlin/JVM on Android, Kotlin/Native on iOS), containing all business logic, data, and agent nodes.
2.  **UI Bridge Layer**: A thin, framework-specific bridge that translates between Reaktor's `ProviderPorts` and the UI framework's idioms. It uses FlexBuffer for zero-copy data transfer.

## Integration Strategies

### 1. React Native Integration
Reaktor provides a native module that embeds the graph runtime. Developers can use React hooks to connect to Reaktor services:

-   `useReaktorService()`: Connects a React hook to a Reaktor service node.
-   `useReaktorAuth()`: Wraps `reaktor-auth` for login, logout, and RBAC checks.
-   `useReaktorStore()`: Provides an observable key-value store with offline-first semantics.
-   `useReaktorQuery()`: Reactive database queries through `RepositoryNode`.

### 2. Flutter Integration
Flutter uses Dart, which has no shared runtime with Kotlin. The bridge uses platform channels to send FlexBuffer-encoded binary messages:

-   `reaktor_flutter`: A plugin that wraps the Reaktor native runtime.
-   `ReaktorService.call()`: A Dart method that sends a request and returns a typed Dart object.
-   `ReaktorAuth`: A Dart class wrapping `reaktor-auth` with a `userStream`.
-   `ReaktorStore`: A `ValueNotifier`-based reactive store.

### 3. SwiftUI / UIKit Integration
For iOS teams wanting native Swift UI, the bridge is Kotlin/Native's cinterop. The Schema DSL generates Swift structs with `Codable` conformance, and the FlexBuffer C library provides the serialization layer.

## Cross-Platform Navigation

Reaktor separates navigation state from UI presentation:

-   **Reaktor owns navigation state**: What screen, what data, and what stack (modeled as `RouteNodes` and `NavigationEdges`).
-   **UI framework owns navigation presentation**: How it looks, how it animates, and how it responds to gestures.

This approach provides cross-platform navigation consistency, simulated user journeys in the Blueprint editor, and even agent-driven navigation where an AI agent can fire `NavCommand`s to walk a user through a tutorial or an onboarding flow.
