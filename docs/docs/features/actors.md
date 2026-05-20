# Reaktor Actors: Distributed Actor Model

Reaktor Actors is an Erlang/Orleans-style distributed actor system. It unifies the graph runtime, structured concurrency from Kotlin coroutines, and the Reaktor Mesh into a single model for distributed computation.

## Core Design: Actor = Graph Node + Mailbox + Address

An `ActorNode` is a first-class graph node with a built-in mailbox:

-   **Node Lifecycle**: Activation, passivation, and garbage collection are managed automatically.
-   **Typed Ports**: Actors communicate via `ConsumerPort` (mailbox) and `ProviderPort` (reply channel), with Schema DSL-enforced message types.
-   **Structured Concurrency**: Each actor runs in its own coroutine scope, and an actor's crash cancels its scope but not those of its siblings.
-   **Mailbox**: Messages are processed one at a time via a coroutine select loop on its internal `Channel`.
-   **Address**: Globally unique addresses (e.g., `mesh://peer-id/graph/path/actor-name`) that are location-transparent.

## Virtual Actors (Orleans-Style Grains)

Virtual actors exist conceptually but are only active in memory when needed, making them the ideal model for entities with identity (e.g., users or chat rooms) that may be idle.

-   **Activation**: The first message to a virtual actor triggers its activation. The runtime picks a peer, creates the `ActorNode`, and rehydrates its state from `ObjectStore`.
-   **Passivation**: After a configurable idle timeout, the actor snapshots its state to `ObjectStore` and its `ActorNode` is destroyed.
-   **Migration**: If a peer is under load, actors can migrate by passivating on the current peer and reactivating on another.
-   **Affinity**: Actors can declare affinity rules (e.g., "activate near the user's data" or "activate on a peer with a GPU").

## Supervision Trees

Kotlin's structured concurrency maps naturally to Erlang-style supervision trees:

-   **Supervisor**: A `SupervisorNode` (a `ContainerNode` subtype) whose child scope contains `ActorNodes`. 
-   **Restart Strategies**: `one_for_one`, `one_for_all`, and `rest_for_one`.
-   **Restart Intensity**: Tracks restart counts within a window. If exceeded, the supervisor itself crashes.
-   **Let it crash**: Actors don't catch unexpected exceptions; they crash, and the supervisor handles the recovery.

## Distributed Computing on Consumer Devices

The ambitious "Unreal Engine" play of Reaktor is to treat the thousands of online user devices as a distributed compute cluster:

-   **Task Distribution**: A `CoordinatorActor` shards a compute job into chunks for `WorkerActors` running on consumer devices.
-   **MapReduce on the Mesh**: Map tasks run on devices, while reduce tasks run on servers.
-   **Federated Learning**: Each device trains a local model, and a server-side `AggregatorActor` averages the gradients.
