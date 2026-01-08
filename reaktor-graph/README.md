# Reaktor Graph

**Reaktor Graph** is a Kotlin Multiplatform graph + navigation runtime:

* A **graph of nodes** (actors) with well-defined lifecycles and concurrency.
* A **port / edge** system for type-safe wiring between nodes.
* Pluggable **dependency injection scopes** (Koin, Spring).
* **UI integration** for Compose (Android/iOS/JVM) and React (JS).
* A small **service layer** for type-safe HTTP clients/servers (Ktor-based).
* Multiplatform packaging for **Android, iOS (CocoaPods), JVM, JS/React, TypeScript**.

This module, `reaktor-graph`, provides the core runtime (`Graph`, `Node`, `Port`) and a concrete navigation system (`Navigator`, `ComposeNode`, `ReactNode`) built on top of it.

> ⚠️ **Status:** Experimental / WIP. APIs may change.

-----

## High-level Navigation Vocabulary

Original mental model:

* **Screen**
  Renders a `Composable` (or React component) at a unique path.

* **Switch**
  Groups together Screens. Nestable. Think “router outlet” or “child navigator”.

* **Container**
  Owns a `Switch`, one or more stacks and container UI (scaffold, tab bar, etc.)
  Responsible for navigation *within* the container.

* **Navigator**
  Receives all push/pop commands and decides which Container & Stack to affect.
  Handles cross-container navigation and back stack behavior.

This model is implemented *on top of* the core graph primitives:

* Screens ≈ `StatefulNode<State, Binding>` with `ComposeContent` / `ReactContent`.
* Switch/Container ≈ Nodes that own one or more `ObservableStack`s and render graphs.
* Navigator ≈ A `Graph` that implements `NavigationCapability` (or a `LogicNode`) that interprets `NavCommand` and mutates stacks.

-----

## Core Concepts

### Graph

```kotlin
@JsExport
open class Graph(
    parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    dependencyAdapter: DependencyAdapter<*>,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    val configureDependencies: (DependencyAdapter.ScopeBuilder.() -> Unit) = {},
    builder: Graph.() -> Unit = {}
): NavigationCapability by NavigationCapabilityImpl()
```

A `Graph`:

* Is a **scope** for:
    * Nodes (navigation, logic, UI, services…)
    * Dependency injection
    * Concurrency (coroutine scope)
    * Lifecycle
* Implements `NavigationCapability`, providing a `dispatch(NavCommand)` method and an `activeStack`.
* Owns a `linkedMapOf<Uuid, Node>` and attaches/detaches nodes with lifecycle transitions:
    * `Created → Restoring → Attaching → Saving → Destroying`

Helper DSL (from `util.kt`):

```kotlin
@JsExport fun Graph.graph(graph: Graph) = GraphNode(graph, this)
@JsExport fun Graph.logic(fn: Graph.(LogicNode) -> Unit) = LogicNode(this) { fn(it) }
@JsExport fun<Props: Props> Graph.route(pattern: String, initialProps: Props) =
    // This is a simplified helper; the real RouteNode is abstract
    RouteNode(this, pattern.toRoutePattern(), initialProps)
```

-----

### Node = Actor

```kotlin
@JsExport
sealed class Node(
    val graph: Graph,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
):
    Unique, Visitable,
    LifecycleCapability by LifecycleCapabilityImpl(),
    PortCapability by PortCapabilityImpl(graph.coroutineScope.coroutineContext),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(graph.coroutineScope.coroutineContext, dispatcher)
```

Node types:

* `LogicNode`
  Stateless behavior (navigation, orchestration, controllers).

* `StatefulNode<State, Binding: RouteBinding<out Props>>`
  Holds a `MutableStateFlow<State>` and requires a `RouteBinding`.

* `RouteNode<P: Props, Binding: RouteBinding<P>>`
  Binds a route pattern (`RoutePattern`) to `props` (via its `Binding`).
  Provides a `NavBinding<P>` for `NavigationEdge`s to connect to.
  Think: “route definition” that other nodes consume.

* `GraphNode`
  Embeds a child `Graph` inside a parent graph (nesting).

> A node is an Actor. They have their own concurrency context and lifecycle, and they communicate via ports.

-----

### Navigation Primitives (Props & NavCommand)

This is the core navigation contract, defined in `Navigator.kt`.

```kotlin
@JsExport
@Serializable
open class Props(
    val routeParams: HashMap<String, String> = hashMapOf()
)

@JsExport
data class BackStackEntry<P: Props, R>(
    val edge: NavigationEdge<P>,
    val props: P,
    val result: CompleTableDeferred<R> = CompletableDeferred()
): Unique by UniqueImpl()

@JsExport
sealed interface NavCommand {
    sealed interface Forward<P: Props, R>: NavCommand {
        val entry: BackStackEntry<P, R>
    }

    sealed interface Back<R>: NavCommand {
        val value: R
    }

    class Push<P: Props, R>(
        override val entry: BackStackEntry<P, R>
    ): Forward<P, R>

    class Replace<P: Props, R>(
        override val entry: BackStackEntry<P, R>
    ): Forward<P, R>

    class Return<R>(
        override val value: R
    ): Back<R>

    object Pop: Back<Unit> {
        override val value = Unit
    }
}
```

* Every route carries typed `Props`.
* Navigation is expressed as `NavCommand` (Push, Replace, Return, Pop).
* A `Graph` (or other `NavigationCapability`) interprets these commands and manipulates the `activeStack: ObservableStack<BackStackEntry<*, *>>`.
* `Push` and `Replace` can carry a `CompletableDeferred` for screen results, which is completed by `Return`.

-----

## Ports & Edges (Contracts Between Nodes)

Ports are how nodes talk to each other via typed contracts.

```kotlin
@JsExport data class Key(val key: String)
@JsExport data class Type(val type: String, val kClass: KClass<*>? = null)
@JsExport data class KeyType(val key: Key, val type: Type)

@JsExport
sealed class Port<Functionality : Any>(
    val owner: PortCapability,
    val key: Key,
    val type: Type
) : Visitable
```

Two flavors:

```kotlin
@JsExport
class ProviderPort<Functionality : Any>(/* … */) : Port<Functionality>(/* … */) {
    val impl: Functionality
}

@JsExport
class RequirerPort<Functionality : Any>(/* … */) : Port<Functionality>(/* … */) {
    val functionality: Functionality?
    inline operator fun <R> invoke(fn: Functionality.() -> R): R
}
```

Ports live on any `PortCapability` (all nodes):

```kotlin
inline fun <reified Functionality : Any> PortCapability.provides(impl: Functionality)
inline fun <reified Functionality : Any> PortCapability.requires()
```

### Edges

Edges connect compatible ports:

```kotlin
@JsExport
open class Edge<Contract : Any>(
    val source: PortCapability,
    val requirer: RequirerPort<Contract>,
    val destination: PortCapability,
    val provider: ProviderPort<Contract>
)
```

`NavigationEdge` is a specialized `Edge` for navigation:

```kotlin
@JsExport
class NavigationEdge<P: Props>(
    val start: RouteNode<*, *>,
    val end: RouteNode<P, out RouteBinding<P>>
): Edge<NavBinding<P>>(
    start,
    start.registerRequirer<NavBinding<P>>(end.id.toString()),
    end,
    end.navBinding
)
```

### Example: Navigation via Ports

This example from `Edge.kt` shows how a `HomeRoute` (a `RouteNode`) defines its navigation options as `NavigationEdge`s, which are then used by a `HomeNode` (a `StatefulNode`) to dispatch `NavCommand`s.

```kotlin
// 1. Define bindings and route nodes
interface HomeBinding: RouteBinding<HomeProps> {
    val chatEdge: NavigationEdge<ChatProps>
    val onboardingEdge: NavigationEdge<OnboardingProps>
}

class HomeRoute(
    graph: Graph,
    chatRoute: RouteNode<ChatProps, *>,
    onboardingRoute: RouteNode<OnboardingProps, *>
): RouteNode<HomeProps, HomeBinding>(graph, "/home"), HomeBinding {
    override val props = MutableStateFlow(HomeProps())
    override val chatEdge = navigationEdge(chatRoute)
    override val onboardingEdge = navigationEdge(onboardingRoute)
    override val routeBinding by provides<HomeBinding>(this)
}

// 2. Define a screen node that consumes the binding
class HomeNode(graph: Graph): StatefulNode<Unit, HomeBinding>(graph) {
    override val state = MutableStateFlow(Unit)
    override val routeBinding by requires<HomeBinding>()

    init {
        // 3. Connect HomeNode and HomeRoute (e.g., in the Graph builder)
        // connect(this, homeRouteNode)

        // 4. Use the binding to navigate
        routeBinding {
            // Get a deferred for the result of onboarding
            val result = CompletableDeferred<String>()
            navigate(NavCommand.Push(onboardingEdge, OnboardingProps(), result))

            // Navigate to chat (fire and forget)
            navigate(NavCommand.Push(chatEdge, ChatProps()))

            // Wait for the onboarding result
            launch {
                val onboardingResult = result.await()
                // ... do something with result
            }
        }
    }
}
```

-----

## Lifecycle

```kotlin
sealed class Lifecycle {
    object Created
    object Restoring
    object Attaching
    object Saving
    object Destroying
}
```

`LifecycleCapability`:

* Enforces allowed transitions (`validTransitions`).
* Propagates lifecycle changes from `Graph` → all attached nodes.
* Gives hooks for “on attach”, “on save”, “on destroy”.

`Graph` drives this by calling `transition(next)` on nodes when its own lifecycle changes.

-----

## Dependency Injection Scopes

All DI is abstracted through `DependencyAdapter`.

```kotlin
abstract class DependencyAdapter<Controller>(controller: Controller) {
    interface ScopeBuilder {
        fun <T : Any> factory(type: KClass<T>, /* ... */)
        fun <T : Any> singleton(type: KClass<T>, /* ... */)
    }

    abstract fun createScope(/* ... */): DependencyScopeCapability
    abstract fun closeScope(scope: DependencyScopeCapability)
    abstract fun <T : Any> get(scope: DependencyScopeCapability, /* ... */): T
}
```

### Koin

```kotlin
class KoinDependencyAdapter(
    private val app: KoinApplication = startKoin {}
) : DependencyAdapter<KoinApplication>(app)
```

* Each `Graph` creates a **Koin scope** keyed by `graph.id`.
* `ScopeBuilder.singleton` / `factory` register scoped Koin definitions.
* Parent graphs link scopes for resolution fallback.

Entry point:

```kotlin
object Reaktor {
    fun start(featureInitializer: Feature.() -> Unit = {}) {
        Feature.Dependency = KoinDependencyAdapter()
        featureInitializer(Feature)
    }
}
```

### Spring (JVM only)

```kotlin
class SpringDependencyAdapter(
    private val ctx: ConfigurableApplicationContext
) : DependencyAdapter<ConfigurableApplicationContext>(ctx)
```

* Singleton/factory definitions become real Spring beans.
* Beans are dynamically registered and cleaned up when the graph scope closes.
* Plays nicely with `@Autowired` and normal Spring wiring.

-----

## UI Integration

### Compose

`ComposeNode` ties a `StatefulNode` to Compose:

```kotlin
abstract class ComposeNode<State, Binding: RouteBinding<out Props>>(
    graph: Graph
): StatefulNode<State, Binding>(graph), ComposeContent {
    @Composable
    abstract fun Content(content: @Composable () -> Unit = {})
}
```

Back handling per platform:

* `androidMain` → `BackHandler` from `activity-compose`.
* `iosMain` → edge-swipe to go back (via `detectHorizontalDragGestures`).
* `jvmMain` / `jsMain` → simple `Box` wrapper (no back).

<!-- end list -->

```kotlin
@Composable
expect fun BackHandlerContainer(
    modifier: Modifier,
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
)
```

### ObservableStack

Used for back stacks in containers:

```kotlin
class ObservableStack<T>(initialTop: T? = null) {
    val top = MutableStateFlow(initialTop)
    val size: Int
    val entries: List<T>

    fun push(value: T)
    fun replace(value: T)
    fun pop(): Boolean
    fun clear()
}
```

There are extensive tests in `ObservableStackTest.kt`.

### Bottom Navigation Container

`BottomNavigation` + `BottomNavigationStateful` implement a multi-stack container with a Material 3 bottom bar:

```kotlin
// LogicNode that holds the tab state
class BottomNavigation(
    graph: Graph,
    initialState: State
): LogicNode(graph) {
    interface Controller {
        fun select(key: String)
        val state: StateFlow<State>
    }

    @Serializable data class Metadata(val icon: String, val label: String)
    @Serializable data class State(
        var selected: String = "INVALID",
        val options: Map<String, Metadata> = emptyMap()
    )

    val controller by provides<Controller>(/* … */)
}

// ComposeNode that renders the Scaffold and tabs
open class BottomNavigationStateful<Props: Props>(
    graph: Graph,
    initialState: BottomNavigation.State
): ComposeNode<Props, BottomNavigation.State>(graph) {
    // Requires the controller from the LogicNode
    val controller by requires<BottomNavigation.Controller>()

    // Registers a requirer port for each tab
    init {
        initialState.options.forEach { (key, value) ->
            registerRequirer<Content>(key) // Content is a ComposeContent interface
        }
    }

    // Renders the Scaffold + NavigationBar
    @Composable
    override fun Content(content: @Composable (() -> Unit)) = themed {
        // ... renders Scaffold, NavigationBar
        // ... gets the currently selected tab's content
        val consumer = remember(navState) { getRequirer<Content>(navState.selected) }
        // ... renders the content
        consumer?.functionality?.Content {}
    }
}
```

-----

## React / JS Integration

`ReactNode` brings React and the graph together:

```kotlin
@JsExport
abstract class ReactNode<P: Props, State, Binding: RouteBinding<P>>(
    graph: Graph,
    val build: (node: ReactNode<P, State, Binding>) -> State,
    val render: (node: ReactNode<P, State, Binding>) -> ReactNode? // ReactNode from 'react'
): StatefulNode<State, Binding>(graph), ReactContent {

    override val state = MutableStateFlow(build(this))

    fun useNodeState(): StateInstance<State> = state.toReactState()

    override fun Content(children: ReactNode?): ReactNode? = render(this)
}
```

Helpers:

```kotlin
@JsExport
fun Logic(build: (logic: LogicNode) -> Unit) = { graph: Graph -> LogicNode(graph, build) }
```

`WindowSize` and `useWindowSize()` in JS give adaptive layout info (width/height classes) based on Material guidelines, using a `MutableStateFlow` bridged to a React state.

### TypeScript / Karakum bridge

* `ts/package.json` + `ts/tsconfig.json` + `ts/karakum.config.json` define a TS build.
* `karakum` generates Kotlin bindings for TS classes like `Greeter` (from `ts/karakum.ts`).
* `ts/index.ts` augments the exported `Node` prototype and re-exports everything from the JS library:
  ```typescript
  // ts/index.ts
  declare module "reaktor-reaktor-navigation" {
      interface Node {
          getContract<Contract>(keyType: KeyType): Optional<Contract>;
      }
  }

  Node.prototype.getContract = function<Contract>(keyType: KeyType) {
      return this.getConsumer<Contract>(keyType)?.contract;
  }
  ```

-----

## HTTP Service Layer

Located in `dev.shibasis.reaktor.graph.service`.

### Request / Response

```kotlin
@Serializable @JsExport
open class Request(
    @Transient open val headers: MutableMap<String, String> = mutableMapOf(),
    @Transient open val queryParams: MutableMap<String, String> = mutableMapOf(),
    @Transient open val pathParams: MutableMap<String, String> = mutableMapOf(),
    // ...
)

@Serializable @JsExport
open class Response(
    @Transient open val headers: MutableMap<String, String> = mutableMapOf(),
    @Transient open val statusCode: StatusCode = StatusCode.OK
)
```

### RequestHandlers

```kotlin
typealias RequestHandlerBlock<In, Out> =
    suspend RequestHandler<In, Out>.(In) -> Out

@JsExport
sealed class RequestHandler<In : Request, Out : Response>(/* … */) {
    val routePattern = RoutePattern.from(route)
    inline fun url(request: In, /* ... */): String
}
```

Concrete methods: `GetHandler`, `PostHandler`, `PutHandler`, `DeleteHandler`.

### Service

```kotlin
@JsExport
abstract class Service(
    baseUrl: String = "",
    val httpClient: HttpClient = http
) {
    // Defines a server-side endpoint
    fun <In : Request, Out : Response> server(
        factory: RequestHandler.Factory,
        endpoint: String,
        // ... serializers
        block: RequestHandlerBlock<In, Out>
    )

    // Defines a client-side implementation
    fun <In : Request, Out : Response> client(
        factory: RequestHandler.Factory,
        route: String,
        // ... serializers
    ): RequestHandler<In, Out>
}
```

This allows *the same* service definition to be reused:

* On the **server** (e.g., Ktor routing).
* On the **client** (Ktor / JS fetch / etc.).
* In **JS/TS** through promise-based helpers (`promised`, `handler`).

-----

## Traversal & Visualization

The `visitor` package lets you traverse graphs for introspection, tooling, or visualization.

* `Selector`: decides adjacency (Structural, Routing, Connectivity).
* `DepthFirstTraverser`, `BreadthFirstTraverser`.
* `HierarchyVisitor`: turns a graph into a nested `Map<String, Any>` with `"element"` and `"children"` entries (easy to convert to JSON or feed into React Flow).

You can build:

* Navigation maps.
* Dependency graphs.
* Visualization for debugging or UE5-style blueprint editors.

-----

## Roadmap / Alignment with Requirements

1.  **Navigation Graph**

    * `Graph` + `RouteNode` + `StatefulNode` + `Ports` + `Visitor`.

2.  **Back Stack**

    * `ObservableStack`, containers (e.g. `BottomNavigation`), and `NavigationCapability` logic.

3.  **Deep Linking**

    * `RoutePattern` + `Props` + `NavCommand.Push`.
    * URL parsing & integration sits on top of this.

4.  **Cross-Platform Views**

    * Compose: Android / iOS / JVM.
    * React: JS/TS bindings and ReactNode.

5.  **Cross-Platform Screens**

    * Same node graph drives both Compose and React with shared business logic & DI.

6.  **Back Handler**

    * Implemented per platform via `BackHandlerContainer` + `ObservableStack`.

-----

## CocoaPods / Mobile Integration

Two pods are exposed, `reaktor_graph` and `reaktor_navigation`, from their respective `.podspec` files. They ship KMP frameworks through a standard configuration.

Example (`reaktor_graph.podspec`):

```ruby
Pod::Spec.new do |spec|
    spec.name               = 'reaktor_graph'
    spec.version            = '1.0'
    # ...
    spec.vendored_frameworks = 'build/cocoapods/framework/reaktor_graph.framework'
    spec.libraries           = 'c++'
    spec.ios.deployment_target = '13'
    # ...
    spec.pod_target_xcconfig = {
        'KOTLIN_PROJECT_PATH' => ':reaktor-graph',
        'PRODUCT_MODULE_NAME' => 'reaktor_graph',
    }
    spec.script_phases = [
        {
            :name => 'Build reaktor_graph',
            :execution_position => :before_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                # ... calls ../gradlew :reaktor-graph:syncFramework
            SCRIPT
        }
    ]
    spec.resources = ['build/compose/cocoapods/compose-resources']
end
```

The `reaktor_navigation.podspec` file is identical, targeting the `:reaktor-navigation` Gradle project.

This keeps the frameworks up to date during `pod install` / Xcode builds.

-----

## Testing

Currently:

* `ObservableStackTest` validates stack semantics and `top` flow behavior.
* More tests are expected around navigation graphs, DI scopes, and service wiring.

-----

## License

> TODO: add license information.