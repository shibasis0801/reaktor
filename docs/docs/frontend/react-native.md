# React Native Integration

Reaktor allows React Native developers to leverage its high-performance service layer with minimal Kotlin knowledge. By treating Reaktor as a "headless engine," React Native apps gain offline-first capabilities, real-time sync, and hot-updatable logic.

## Key Components

### `@reaktor/react-native` (npm)
A native module that embeds the Reaktor graph runtime. It auto-links via React Native's native module system, ensuring the Reaktor service layer is running alongside your JavaScript code.

### React Hooks
Reaktor provides a set of generic hooks for interacting with the service layer:

-   `useReaktorService<T>('ServiceName', 'MethodName', params)`: Connects to a `ProviderPort` on a Reaktor service node.
-   `useReaktorAuth()`: Exposes `login`, `logout`, `tokenState`, and RBAC role checks.
-   `useReaktorStore('KeyName')`: An observable key-value store with offline-first semantics and LRU cache.
-   `useReaktorQuery('RepositoryName', 'MethodName', params)`: Returns reactive data that updates automatically when the underlying `ObjectStore` changes.

## Developer Experience

The React Native developer defines their UI in React, calls Reaktor services through typed hooks, and gets all the benefits of the engine:

-   **Zero Kotlin required** for UI developers.
-   **Schema-generated types**: TypeScript interfaces are generated from annotated Kotlin schemas.
-   **Real-time sync** via Reaktor Mesh.
-   **OTA updates** via the JS Mobile layer on the Hermes engine.
