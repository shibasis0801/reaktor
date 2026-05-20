# The Blueprint Editor: A Live Runtime Control Plane

The Reaktor Blueprint editor is not a static graph visualizer. It is a fundamental architectural layer that imports and runs the actual Reaktor graph in-process, acting as a "live control plane" for application development.

## Live Runtime Inspection

The Blueprint editor provides real-time visibility into every observable primitive in Reaktor:

-   **Node State**: Every `ControllerNode`'s `MutableStateFlow` is observed in real-time.
-   **Port Data Flow**: Every `ProviderPort` emission and `ConsumerPort` consumption is intercepted and displayed.
-   **Lifecycle States**: Each node's lifecycle phase (Created, Restoring, Attaching, Destroying) is visible and color-coded.
-   **Edge Invocations**: Every port call through an edge is logged with timestamps, arguments, and return values.
-   **Coroutine Scope**: The `ConcurrencyCapability`'s coroutine tree is inspectable, including active jobs and cancellation states.

## Interactive Debugging Controls

Beyond observation, the Blueprint editor provides active debugging controls:

-   **State Injection**: Directly modify a `ControllerNode`'s `StateFlow` value to watch how downstream nodes and UI react.
-   **Port Data Injection**: Push synthetic data into any `ProviderPort` to test downstream consumer edge cases.
-   **Node Pause/Resume**: Suspend a node's coroutine scope to simulate latency or unresponsive services.
-   **Node Kill**: Force-destroy a node to test error recovery and lifecycle management under failure.
-   **Navigation Triggers**: Emit `NavCommand`s (Push, Pop, Replace) to walk through the app's navigation flow.
-   **Edge Breakpoints**: Set a breakpoint on any edge. When data flows through it, the graph pauses, displaying the payload.

## Bidirectional Code Generation

Following the Unreal Engine Blueprints model, the Reaktor Blueprint editor is bidirectional:

-   **Deterministic Operations (~80% of edits)**: Wiring a `ConsumerPort` to a `ProviderPort`, adding child nodes, and creating route bindings. These patterns are translated back into Kotlin source code for persistence.
-   **AI-Assisted Operations (~20% of edits)**: Implementing new node business logic, designing schemas, or writing custom port types. These require AI assistance via the `Blueprint Copilot` to generate skeleton implementations.
-   **The Live Edit Loop**: Rewiring a port in the editor results in immediate graph mutation via `connect()` and `autoWire()`, while source code changes are generated in the background for persistence.
