---
name: reaktor-app
description: Wiring an app on the reaktor framework — graphs, nodes, typed ports, routes, and platform slots. Use when adding or debugging a node, interactor, screen, repository, route or platform adapter in a project that depends on reaktor (dev.shibasis.reaktor), or when an app crashes with "unconnected ports", "FileAdapter not initialized", or a null adapter at launch.
---

# Wiring an app on reaktor

Reaktor composes an app as a graph of nodes that talk through typed ports. Almost everything it
gets wrong, it gets wrong **at runtime** — the compiler cannot see a port that is not connected,
an adapter installed in the wrong order, or an `actual` that was never written. This file is the
set of rules that turn those into things you do not hit.

Architecture reference: `LLM_CONTEXT.md` at the root of the reaktor checkout. Read it for the
type names; read this for the rules that bite.

## The failures worth knowing before you write anything

Three crashes account for most lost time on a new reaktor app. All three are silent until launch.

### 1. Port keys must match, and the rule differs by direction

A port's qualifier is `"Port:$key:$type"`, and **`key` defaults to the property name**. Get it
wrong and you get `IllegalStateException: Can't invoke functions through unconnected ports`.

**Same graph** — `autoWire()` matches by type *only when the consumer key is empty*:

```kotlin
// Right: empty string, so autoWire matches on type
private val interactor by consumes<TodayInteractor>("")

// Wrong: keys it "interactor" (the property name), which never matches
// the provider's key "todayInteractor"
private val interactor by consumes<TodayInteractor>()
```

**Across graphs** — resolves through DI by the **full qualifier**, so an empty key does *not*
work. The consumer key must equal the provider's key:

```kotlin
// Root graph: Node(::ProgramRepository).apply { exposePort(programRepository) }
// Child graph consumer must name it exactly:
private val programRepo by consumes<ProgramRepository>("programRepository")
```

A child graph's DI scope chains to its parent, so cross-graph sharing works as long as the keys
match and the provider is exposed **before** the child graph's `autoWire()` runs.

### 2. Never touch a port from `init`

Nodes are all constructed first; `autoWire()` runs afterwards. Reading a port during construction
crashes with the same unconnected-port error **even when the keys are right**.

Give the node an explicit `start()` and call it after `autoWire()`:

```kotlin
init {
    val reminders = Node(::ReminderService).apply { exposePort(reminderService) }
    autoWire()
    // Only safe once wired: this reads its repository through a port.
    reminders.start()
}
```

Repositories get away with touching things in `init` only because they touch the object store,
never a port.

### 3. Adapter install order

In the Android `Activity`'s `Reaktor.start { }` block, `File` **must** come before `Sql`.
`SqlAdapter.<init>` reads `Feature.File` as a default constructor argument and throws
`java.lang.Error: FileAdapter not initialized` when it is null.

Working order: **Theme, Dependency, File, Sql, Database.**

## Graphs, and what belongs in one

A graph is a scope: its own nodes, its own DI, its own navigation stack. In a tabbed app each tab
is a graph, over a root graph holding the repositories.

**Routes are per-graph.** A screen reachable from two tabs needs its own node *and* its own route
in each graph — there is no cross-graph push. Duplicating an interactor for this is the
established pattern, not a smell: the second instance has its own cursor state, which is usually
what you want, and both write through ports to the same shared repository.

```kotlin
// In TodayGraph — its own instance, its own route, so back lands on Today
Node(::ImportInteractor)
val importRoute = Route("/today/import") { ImportBinding() }
Node(::ImportSplitScreen)
val route = Route("/today") { TodayBinding(it.edge(workoutRoute), it.edge(importRoute)) }
```

Ask before duplicating: does this screen's *state* belong to this tab? If yes, duplicate. If the
screen is genuinely global, it belongs in the root graph.

## Node types

| Type | Use for |
|---|---|
| `BasicNode` | Plain logic, no state to publish. Services, repositories. |
| `StartableInteractor<S>` | Screen state. Holds a `StateFlow<S>`, has `onStart()`, `reduce {}`. |
| `StatelessComposeNode` | A screen. Consumes its `RouteBinding` and its interactor. |
| `ContainerNode` | Holds sub-graphs — a bottom-nav container over tab graphs. |
| `RouteNode` | A navigation destination. Created via `Route(pattern) { Binding() }`. |

A screen consumes its binding by type and its interactor with an **empty key**:

```kotlin
class TodayScreen(graph: Graph) : StatelessComposeNode(graph) {
    override val routeBinding by consumes<TodayBinding>()
    private val interactor by consumes<TodayInteractor>("")

    @Composable
    override fun Content() {
        val today = interactor()
        LaunchedEffect(Unit) { today.start() }
        val state by today.state.collectAsState()
        ...
    }
}
```

Navigation edges are declared on the binding and pushed from the screen:

```kotlin
class TodayBinding(
    val workoutEdge: NavigationEdge<Payload>,
) : RouteBinding<Payload>(Payload())

// in Content(): routeBinding { workoutEdge.push(Payload()) }
```

## Platform slots: two patterns, different jobs

Something with no shared implementation — a camera, a widget, a live activity — is a slot the
platform layer fills. Pick by whether **absence is a normal state**.

**`expect object` + `attach()`** — the platform always has one, and common code calls it freely:

```kotlin
// commonMain
expect object Widgets {
    fun publish(snapshot: WidgetSnapshot)
}
// androidMain: actual object Widgets { fun attach(context: Context) ... }
```

**`interface` + settable slot** — the capability may genuinely not exist, and callers must handle
null:

```kotlin
interface PhotoSource {
    val canCapture: Boolean          // a tablet without a camera cannot
    suspend fun capture(): ByteArray?
    suspend fun choose(): ByteArray?
}
object Photos { var source: PhotoSource? = null }
```

Register both from the platform entry point **before the graph starts**, so the first thing the
graph publishes has somewhere to land.

### Write every actual, including the ones you do not ship

An `expect` with no `actual` for jvm or js breaks those targets, and you will not notice until a
target you rarely build fails. Write no-op actuals for the platforms where the capability is
meaningless. This is cheap and it keeps the module compiling everywhere.

## Extending reaktor rather than working around it

When an app needs something reaktor does not have, the default is to add it to reaktor rather
than to grow app-local plumbing — a helper that lives in one app is a helper the next app
rewrites, and the second copy is the one that drifts.

Deciding *where* it goes — an existing module, a new one, or nowhere because the toolkit already
provides it — has its own rules and its own build mechanics. **Load the `reaktor-module` skill**
before adding a capability to the framework.

The short version, so this decision is never skipped: check the existing modules first, do not
wrap something Compose or kotlinx already exposes in `commonMain`, and only create a new module
when the capability would cost every other app a permission or a heavy SDK it did not ask for.

## Before you say it works

- [ ] Does any node read a port from `init`? Move it to `start()`, called after `autoWire()`.
- [ ] Same-graph consumers use `consumes<T>("")`; cross-graph ones name the provider's key.
- [ ] Adapters installed File-before-Sql.
- [ ] Every new `expect` has an `actual` on every target, no-ops included.
- [ ] Run it. These failures are all runtime, so a green build proves nothing about wiring.
