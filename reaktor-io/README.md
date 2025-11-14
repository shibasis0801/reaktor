# Reaktor Graph

**Reaktor Graph** is a Kotlin Multiplatform graph + navigation runtime:

* A **graph of nodes** (actors) with well-defined lifecycles and concurrency.
* A **port / edge** system for type-safe wiring between nodes.
* Pluggable **dependency injection scopes** (Koin, Spring).
* **UI integration** for Compose (Android/iOS/JVM) and React (JS).
* A small **service layer** for type-safe HTTP clients/servers (Ktor-based).
* Multiplatform packaging for **Android, iOS (CocoaPods), JVM, JS/React, TypeScript**.

This module is the core that powers `navigation` (Compose + React navigation) and `graph` (general graph runtime).

> ⚠️ **Status:** Experimental / WIP. APIs may change.

---

## High-level Navigation Vocabulary

Original mental model (from the rough README):

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

* Screens ≈ `StatefulNode<Props, State>` with `ComposeContent` / `ReactContent`.
* Switch/Container ≈ Nodes that own one or more `ObservableStack`s and render graphs.
* Navigator ≈ a `LogicNode` that interprets `NavCommand` and mutates stacks.

---

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
)
```

A `Graph`:

* Is a **scope** for:

    * Nodes (navigation, logic, UI, services…)
    * Dependency injection
    * Concurrency (coroutine scope)
    * Lifecycle
* Owns a `linkedMapOf<Uuid, Node>` and attaches/detaches nodes with lifecycle transitions:

    * `Created → Restoring → Attaching → Saving → Destroying`

Helper DSL:

```kotlin
@JsExport fun Graph.graph(graph: Graph) = GraphNode(graph, this)
@JsExport fun Graph.logic(fn: Graph.() -> LogicNode) = fn()
@JsExport fun <Props: Parameters> Graph.route(pattern: String, initialProps: Props) =
    RouteNode(this, pattern.toRoutePattern(), initialProps)
@JsExport fun <Props: Parameters, State> Graph.node(fn: Graph.() -> StatefulNode<Props, State>) = fn()
```

---

### Node = Actor

```kotlin
@JsExport
sealed class Node(
    val graph: Graph,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
) :
    Unique,
    Visitable,
    LifecycleCapability by LifecycleCapabilityImpl(),
    PortCapability by PortCapabilityImpl(graph.coroutineScope.coroutineContext),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(graph.coroutineScope.coroutineContext, dispatcher)
```

Node types:

* `LogicNode`
  Stateless behavior (navigation, orchestration, controllers).

* `StatefulNode<Props : Parameters, State>`
  Holds a `MutableStateFlow<State>` and a `RouteBinding<Props>`.

* `RouteNode<Props : Parameters>`
  Binds a route pattern (`RoutePattern`) to `props: StateFlow<Props>`.
  Think: “route definition” that other nodes consume.

* `GraphNode`
  Embeds a child `Graph` inside a parent graph (nesting).

> Comment in code: *“A node is an Actor.”*
> They have their own concurrency context and lifecycle, and they communicate via ports.

---

### Parameters & NavCommand

```kotlin
@JsExport
@Serializable
open class Parameters(
    val routeParams: HashMap<String, String> = hashMapOf()
)

@JsExport
sealed class NavCommand {
    data class Push(val target: RouteNode<out Parameters>, val props: Parameters) : NavCommand()
    data class Replace(val target: RouteNode<out Parameters>, val props: Parameters) : NavCommand()
    object Pop : NavCommand()
    object PopToRoot : NavCommand()
}
```

This is the minimal navigation contract:

* Every route carries typed `Parameters`.
* Navigation is expressed as `NavCommand` (push/replace/pop/popToRoot).
* A future `Navigator` node interprets these commands and manipulates stacks (`ObservableStack`).

---

## Ports & Edges (Contracts Between Nodes)

Ports are how nodes talk to each other via typed contracts.

```kotlin
@JsExport data class Key(val key: String)
@JsExport data class Type(val type: String, val kClass: KClass<*>? = null)

@JsExport
data class KeyType(val key: Key, val type: Type)

@JsExport
sealed class Port<Functionality : Any>(
    val owner: PortCapability,
    val key: Key,
    val type: Type
) : Visitable {
    val node: Node get() = owner as Node
}
```

Two flavors:

```kotlin
@JsExport
class ProviderPort<Functionality : Any>(/* … */) : Port<Functionality>(/* … */) {
    val impl: Functionality
    override fun isConnected() = edges.isNotEmpty()
}

@JsExport
class RequirerPort<Functionality : Any>(/* … */) : Port<Functionality>(/* … */) {
    val functionality: Functionality?
        get() = edge?.provider?.impl

    inline operator fun <R> invoke(fn: Functionality.() -> R): R
}
```

Ports live on any `PortCapability` (all nodes):

```kotlin
inline fun <reified Functionality : Any> PortCapability.provides(impl: Functionality)
inline fun <reified Functionality : Any> PortCapability.requires()
```

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

Connect helpers:

```kotlin
fun <C : Any> connect(requirerPort: RequirerPort<C>, providerPort: ProviderPort<C>): Result<Unit>

@JsExport @JsName("connectPort")
fun connectPort(requirerPort: RequirerPort<Any>, providerPort: ProviderPort<Any>)
```

> Use case:
>
> * View nodes require `BottomNavigation.Controller`.
> * A logic node provides it.
> * `connect` wires them at runtime, and the consumer calls `controller { select("home") }`.

---

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

---

## Dependency Injection Scopes

All DI is abstracted through `DependencyAdapter`.

```kotlin
abstract class DependencyAdapter<Controller>(controller: Controller) :
    Adapter<Controller>(controller) {

    interface ScopeBuilder {
        fun <T : Any> factory(type: KClass<T>, qualifier: String? = null, definition: DependencyScopeCapability.() -> T)
        fun <T : Any> singleton(type: KClass<T>, qualifier: String? = null, definition: DependencyScopeCapability.() -> T)
    }

    abstract fun createScope(id: String, parent: DependencyScopeCapability? = null, configure: (ScopeBuilder.() -> Unit) = {}): DependencyScopeCapability
    abstract fun closeScope(scope: DependencyScopeCapability)
    abstract fun <T : Any> get(scope: DependencyScopeCapability, type: KClass<T>, qualifier: String? = null, parameters: Map<String, Any?> = emptyMap()): T
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

---

## UI Integration

### Compose

`ComposeNode<Props, State>` ties a `StatefulNode` to Compose:

```kotlin
abstract class ComposeNode<Props : Parameters, State>(
    graph: Graph
) : StatefulNode<Props, State>(graph), ComposeContent {
    @Composable
    abstract fun Content(content: @Composable () -> Unit = {})
}
```

Back handling per platform:

* `androidMain` → `BackHandler` from `activity-compose`.
* `iosMain` → edge-swipe to go back.
* `jvmMain` / `jsMain` → simple `Box` wrapper (no back).

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
    private val stack = ArrayDeque<T>()

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
class BottomNavigation(
    graph: Graph,
    initialState: State
) : LogicNode(graph) {

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
```

A `ComposeNode` consumes the controller and renders `Scaffold + NavigationBar`, wiring each tab to a child `Content` via ports.

---

## React / JS Integration

`ReactNode` brings React and the graph together:

```kotlin
@JsExport
open class ReactNode<Props : Parameters, State>(
    graph: Graph,
    val build: (node: ReactNode<Props, State>) -> State,
    val render: (node: ReactNode<Props, State>) -> ReactNode?
) : StatefulNode<Props, State>(graph), ReactContent {
    override val state = MutableStateFlow(build(this))

    fun useNodeState(): StateInstance<State> = state.toReactState()

    override fun Content(children: ReactNode?): ReactNode? = render(this)
}
```

Helpers:

```kotlin
@JsExport
fun <Props : Parameters, State> ViewNode(
    build: (node: ReactNode<Props, State>) -> State,
    render: (node: ReactNode<Props, State>) -> ReactNode?
) = { graph: Graph -> ReactNode(graph, build, render) }

@JsExport
fun Logic(build: (logic: LogicNode) -> Unit) = { graph: Graph -> LogicNode(graph, build) }
```

`WindowSize` and `useWindowSize()` in JS give adaptive layout info (width/height classes) based on Material guidelines.

### TypeScript / Karakum bridge

* `ts/package.json` + `ts/tsconfig.json` + `ts/karakum.config.json` define a TS build.

* `karakum` generates Kotlin bindings for TS classes like `Greeter`:

    * TS: `ts/karakum.ts`
    * Generated Kotlin: `ts/import/karakum.kt`

* `ts/index.ts` augments `Node` with `getContract` and re-exports everything from the JS library.

---

## HTTP Service Layer

Located in `dev.shibasis.reaktor.graph.service`.

### Request / Response

```kotlin
@Serializable @JsExport
open class Request(
    @Transient open val headers: MutableMap<String, String> = mutableMapOf(),
    @Transient open val queryParams: MutableMap<String, String> = mutableMapOf(),
    @Transient open val pathParams: MutableMap<String, String> = mutableMapOf(),
    @Transient open val environment: Environment = Environment.STAGE
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

    inline fun url(request: In, vararg extraPathParams: Pair<String, String>): String
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
    val handlers = arrayListOf<RequestHandler<*, *>>()
    val baseUrl: String = baseUrl.trimEnd('/')

    fun <In : Request, Out : Response> server(
        factory: RequestHandler.Factory,
        endpoint: String,
        requestSerializer: KSerializer<In>,
        responseSerializer: KSerializer<Out>,
        block: RequestHandlerBlock<In, Out>
    )

    fun <In : Request, Out : Response> client(
        factory: RequestHandler.Factory,
        route: String,
        requestSerializer: KSerializer<In>,
        responseSerializer: KSerializer<Out>
    ): RequestHandler<In, Out>
}
```

Kotlin helpers:

```kotlin
inline fun <reified In : Request, reified Out : Response> Service.GetHandler(endpoint: String)
inline fun <reified In : Request, reified Out : Response> Service.PostHandler(endpoint: String)
// … Put/Delete variants
```

JS helpers wrap handlers into `Promise`s:

```kotlin
fun <In : Request, Out : Response> RequestHandler<In, Out>.promised(request: In): Promise<Out>

fun <In : Request, Out : Response> handler(
    method: HttpMethod,
    route: String,
    requestSerializer: KSerializer<In>,
    responseSerializer: KSerializer<Out>,
    handler: (In) -> Promise<Out>
): RequestHandler<In, Out>
```

This allows *the same* service definition to be reused:

* On the **server** (e.g., Ktor routing).
* On the **client** (Ktor / JS fetch / etc.).
* In **JS/TS** through promises.

---

## Traversal & Visualization

The `visitor` package lets you traverse graphs for introspection, tooling, or visualization.

* `Selector`: decides adjacency (structural, routing, connectivity).
* `DepthFirstTraverser`, `BreadthFirstTraverser`.
* `HierarchyVisitor`: turns a graph into a nested `Map<String, Any>` with `"element"` and `"children"` entries (easy to convert to JSON or feed into React Flow).

You can build:

* Navigation maps.
* Dependency graphs.
* Visualization for debugging or UE5-style blueprint editors.

---

## Roadmap / Alignment with Requirements

From the original requirements (and partially implemented):

1. **Navigation Graph**

    * Graph + RouteNode + StatefulNode + Ports + Visitor.

2. **Back Stack**

    * `ObservableStack`, containers (e.g. `BottomNavigation`), and future `Navigator` logic.

3. **Deep Linking**

    * `RoutePattern` + `Parameters` + `NavCommand.Push/Replace`.
    * URL parsing & integration sits on top of this.

4. **Cross-Platform Views**

    * Compose: Android / iOS / JVM.
    * React: JS/TS bindings and ReactNode.

5. **Cross-Platform Screens**

    * Same node graph drives both Compose and React with shared business logic & DI.

6. **Back Handler**

    * Implemented per platform via `BackHandlerContainer` + `ObservableStack`.

---

## CocoaPods / Mobile Integration

Two pods are exposed:

* `reaktor_graph`
* `reaktor_navigation`

They ship KMP frameworks through:

```ruby
spec.vendored_frameworks = 'build/cocoapods/framework/reaktor_graph.framework'
spec.libraries           = 'c++'
spec.ios.deployment_target = '13'
spec.script_phases = [ /* Gradle syncFramework invocation */ ]
spec.resources = ['build/compose/cocoapods/compose-resources']
```

The script phases call:

```sh
"$REPO_ROOT/../gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:syncFramework \
  -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
  -Pkotlin.native.cocoapods.archs="$ARCHS" \
  -Pkotlin.native.cocoapods.configuration="$CONFIGURATION"
```

This keeps the frameworks up to date during `pod install` / Xcode builds.

---

## Testing

Currently:

* `ObservableStackTest` validates stack semantics and `top` flow behavior.
* More tests are expected around navigation graphs, DI scopes, and service wiring.

---

## License

> TODO: add license information.

---

You can tweak tone/details, but this should be a solid, code-accurate README shaped by your original Screen/Switch/Container/Navigator model.
