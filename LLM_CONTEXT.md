# Reaktor Framework Architecture

## 1. Core Architecture: The Graph
The foundation is a directed graph where functionality is composed of **Nodes** connected by **Edges** via **Ports**.

*   **`Graph`**: The root container. It manages a collection of `Node`s, handles lifecycle transitions, and provides a scope for dependency injection and concurrency. It is itself a `Visitable` structure.
*   **`Node`**: The fundamental unit of logic.
    *   **`BasicNode`**: A simple logic unit.
    *   **`ControllerNode`**: A stateful node, likely the ViewModel equivalent, holding a `MutableStateFlow`.
    *   **`ActorNode`**: Designed for actor-model concurrency (message passing via channels), though noted as "incomplete".
    *   **`ContainerNode`**: A node that contains other sub-graphs (e.g., `BottomNavigationContainer`).
    *   **`RouteNode`**: Represents a navigation destination or path.

## 2. Communication: Ports & Edges
Nodes do not call each other directly; they communicate through typed ports.

*   **`PortCapability`**: A mixin that gives a class the ability to have ports.
*   **`ProviderPort<T>`**: Exposes functionality (an implementation of type `T`).
*   **`ConsumerPort<T>`**: Consumes functionality. It must be connected to a `ProviderPort` via an `Edge` to work.
*   **`Edge<T>`**: The connection between a Consumer and a Provider. It allows the consumer to invoke functions on the provider.
*   **`connect()` / `autoWire()`**: Utilities to manually connect ports or automatically wire them based on types and keys.

## 3. Capabilities System (Mixins)
Delegation is used heavily to compose behavior into classes without deep inheritance hierarchies.

*   **`Capability`**: The base interface (AutoCloseable).
*   **`ConcurrencyCapability`**: Provides `CoroutineScope` and `Dispatcher` management.
*   **`LifecycleCapability`**: Manages states like `Created`, `Restoring`, `Attaching`, `Destroying`.
*   **`DependencyCapability`**: Provides access to the DI system.
*   **`NavigationCapability`**: Manages the back stack and navigation commands.

## 4. Navigation
Navigation is modeled as graph traversals and state changes.
*   **`RouteNode`**: Defines a route (URL-like pattern).
*   **`NavigationEdge`**: A specialized edge connecting two `RouteNode`s.
*   **`NavCommand`**: Commands like `Push`, `Pop`, `Replace`.
*   **`Payload`**: Data passed during navigation.
*   **`ObservableStack`**: The backing data structure for the navigation state, observable by the UI.

## 5. Dependency Injection
A custom abstraction layer over Koin.
*   **`DependencyAdapter`**: An abstract base for DI adapters.
*   **`KoinDependencyAdapter`**: The concrete implementation wrapping Koin.
*   **`Graph` Scopes**: Each `Graph` instance creates its own Koin scope, allowing for scoped dependencies (singletons within a graph).

## 6. UI Integration (Compose)
*   **`ComposeNode`**: A `ControllerNode` that also implements `ComposeContent`, bridging the graph logic with Jetpack Compose UI.
*   **`BottomNavigationContainer`**: A specific `ContainerNode` that manages multiple child graphs and renders a bottom bar.

## Observations
*   **Decoupling**: The strict separation of `Consumer` and `Provider` ports allows for very loose coupling. Nodes don't know who they are talking to, only the contract (interface).
*   **Fractal Structure**: `ContainerNode` holding `Graph`s allows for infinite nesting of sub-applications.
*   **KMP Ready**: The use of `expect`/`actual` (implied by `commonMain`) and `JsExport` suggests this runs on Android, iOS, and Web.

# BestBuds App Architecture

## 1. Overview
BestBuds is a Kotlin Multiplatform (KMP) application built on top of the `reaktor` framework. It appears to be a social/messaging app with features like chats, user profiles, and authentication.

## 2. Key Components

### App Entry Point
*   **`BestBuds` Object**: The main `Graph` for the application.
    *   Initializes core services: `UserService`, `MessageRepository`, `UserRepository`, `ChatInteractor`.
    *   Sets up the navigation structure with `ChatGraph` and `StartScreen`.
    *   Uses `autoWire()` to connect ports automatically.

### Screens & UI
*   **`StartScreen`**: The initial onboarding/login screen.
    *   Handles user login via Apple, Google, or impersonation.
    *   Navigates to the chat list upon successful login.
*   **`ChatListScreen`**: Displays a list of chats.
    *   Fetches chats via `ChatInteractor`.
    *   Navigates to individual `ChatScreen`s.
*   **`ChatScreen`**: The individual chat view.
    *   Fetches messages via `MessagingService`.
    *   Handles sending messages (though some logic is commented out).
    *   Uses `PartySocket` for real-time updates (commented out).

### Data Layer
*   **Repositories**:
    *   **`UserRepository`**: Manages user data and authentication state. Extends `RepositoryNode` to use `ObjectStore` for caching.
    *   **`MessageRepository`**: Fetches top messages (conversations).
*   **Interactors**:
    *   **`ChatInteractor`**: Orchestrates fetching chats and sending messages, combining data from repositories.
*   **Services**:
    *   **`MessagingService`**: Handles HTTP requests for messages and chat operations.
    *   **`UserService`**: Fetches user profiles.
    *   **`Servers`**: Configuration object for API endpoints.

### Database & Caching
*   **`ObjectStore`**: A key-value store backed by SQLite (`SqliteObjectDatabase`).
*   **`CachePolicyLRU`**: Implements an LRU cache with time-based expiration (TTL).
*   **`RepositoryNode`**: A base class for repositories that provides easy access to `ObjectStore` and `writeAndGet` patterns for read-through caching.

## 3. Navigation Structure
*   **Routes**:
    *   `/`: Start Screen.
    *   `/chats`: Chat List.
    *   `/chats/{id}`: Individual Chat.
*   **Bindings**:
    *   `StartBinding`: Connects Start Screen to Chat List.
    *   `ChatListBinding`: Connects Chat List to Chat Screen.
    *   `ChatBinding`: Payload for Chat Screen (Chat object, Chat ID, User).

## 4. Observations
*   **Graph-Based DI**: The app heavily relies on the `reaktor` graph for dependency injection (`consumes`, `provides`, `exposePort`).
*   **Reactive UI**: Uses `MutableStateFlow` and Compose for reactive UI updates.
*   **Offline-First**: The `RepositoryNode` and `ObjectStore` architecture suggests a strong focus on caching and offline capabilities.
