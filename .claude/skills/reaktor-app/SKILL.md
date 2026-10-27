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

### 1. A port finds its provider by key first, then by type

A port's key defaults to the property name. A consumer connects, in this order:

1. to the provider with the same key, in its own graph and then its parents;
2. failing that, to the **only** provider of its type in the nearest graph that has one: its own
   nodes, then the graphs it `dependsOn`, then its parents. `wire()` also looks across sibling
   graphs of the tree it wires.

So `consumes<T>()` just works when one provider of `T` is in reach, in the same graph or above it:

```kotlin
private val interactor by consumes<TodayInteractor>()
private val programs by consumes<ProgramRepository>()   // provided by the root graph
```

Write a key only to choose between several providers of one type. The consumer's key must then
equal the provider's:

```kotlin
private val primary by consumes<HttpClient>("primary")
```

Two providers of one type at the same distance, with no key between them, fail with a message
that names both: `X can take T from A, B`. A port nothing provides fails with
`IllegalStateException: Can't invoke functions through unconnected ports`.

A key is a property *name*, so minification can rename it. Ports that wire by type are unaffected;
ports that rely on a written key need the keep rules in `reaktor-ship`.

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

**A route lives in one graph; navigation can cross graphs.** Pushing a route that lives in another
tab's graph switches the container to that tab, and back returns to the tab it came from. Give a
screen its own node *and* route in a second tab only when its *state* belongs to that tab — the
second instance has its own cursor state, and both write through ports to the same repository.

```kotlin
// In TodayGraph — its own instance, its own route, so back lands on Today
Node(::ImportInteractor)
val importRoute = Route("/today/import", ::ImportBinding, ::ImportSplitScreen)
val route = Route("/today", ::TodayBinding, ::TodayScreen)
```

If the screen is genuinely global, it belongs in the root graph.

## Node types

| Type | Use for |
|---|---|
| `BasicNode` | Plain logic, no state to publish. Services, repositories. |
| `StartableInteractor<S>` | Screen state. Holds a `StateFlow<S>`, has `onStart()`, `reduce {}`. |
| `StatelessComposeNode` | A screen. Consumes its `RouteBinding` and its interactor. |
| `ContainerNode` | Holds sub-graphs — a bottom-nav container over tab graphs. |
| `RouteNode` | A navigation destination. `Route(pattern, ::Binding, ::Screen)` creates it with its screen; `Route(pattern) { Binding() }` creates it alone. |

A screen consumes its binding and its interactor by type:

```kotlin
class TodayScreen(graph: Graph) : StatelessComposeNode(graph) {
    override val routeBinding by consumes<TodayBinding>()
    private val interactor by consumes<TodayInteractor>()

    @Composable
    override fun Content() {
        val today = interactor()
        LaunchedEffect(Unit) { today.start() }
        val state by today.state.collectAsState()
        ...
    }
}
```

A binding declares its screen's exits by destination binding type, and `autoWire` connects each
exit to the one route bound by that type — in the graph, in the graphs passed to `autoWire`, or in
a parent. A container is reached by its class, e.g. `exit<SessionSlot<*>>()`.

```kotlin
class TodayBinding : RouteBinding<Payload>(Payload()) {
    val workout by exit<WorkoutBinding>()
    val importer by exit<ImportBinding>()
}

// in Content(): routeBinding { workout.push(Payload()) }
// in the parent, after building the tabs: today.autoWire(workouts)
```

A route that carries data declares a `@Serializable` route class beside its binding. The exit
pushes it and the screen reads it back; its fields travel in the payload's string map, so deep
links and route patterns such as `/workouts/{id}` keep working:

```kotlin
@Serializable
data class WorkoutRoute(val id: String, val resume: Boolean = false)

// pushing: routeBinding { workout.push(WorkoutRoute(id)) }
// reading: val (id, resume) = routeBinding { toRoute<WorkoutRoute>() } ?: return
```

Keep route classes flat (strings, numbers, booleans, enums). A nested object has no place in a URL.

A graph's first screen is declared where it is created: `Root(pattern, ::Binding, ::Screen)` for a
route, `Root { HomeTabs(it, …) }` for a container. From the graph itself, `replaceWith(route)`,
`popToRoot()` and `currentRoute` cover what needs no exit.

What fails at runtime, not compile time:

- **An exit into a sibling graph** resolves only when that graph is passed to `autoWire`.
  `requireFullyWired` names the route that still has an unwired exit.
- **Two routes bound by the same binding class** in one scope are ambiguous to an exit. Give every
  route its own binding class.
- **Reading an exit before wiring** throws `No X route is wired to y, an exit of Z`.

## Talking to a server: contracts

A service is one open root class. Its operations are declared once, the server overrides them,
and a client is the same class bound to a link, so a changed signature fails to compile on both ends:

```kotlin
open class PlansApi : Service("app.plans") {
    open val plans by get<PlansRequest.Browse, PlansResponse>("/plans")
}

class PlansServer : PlansApi() {
    override val plans = super.plans.handle { request -> PlansResponse(load(request)) }
}

graph.serve(PlansServer())                           // server node: Hono.mount / toRouter mount it
graph.remote(PlansApi() over HttpLink(baseUrl))      // client node
class PlansScreen(graph: Graph) : BasicNode(graph) {
    val plans by consumes(PlansApi())             // the whole service: plans.plans(r)
    val rsvp by consumes(PlansApi().rsvp)         // one operation, its own edge in the graph: rsvp(r)
}
```

A failure the caller should handle is a `reject(...)` on the server and an `Outcome.Rejection` from
`outcome { plans.plans(r) }` on the client, not a variant of the response type.

What the compiler does not catch:

- **Only `serve` makes a server.** `graph.remote(PlansApi() over LocalLink(served))` is still a
  client node; mounting skips it and graph interceptors run on its client side only.
- **The serial name is the wire.** Responses carry their class name as `type`. Moving or renaming a
  `@Serializable` request or response without a `@SerialName` breaks every installed client.
- **Overlapping routes.** `/circles/discover` and `/circles/{id}` both match `/circles/discover`;
  `Hono.mount` registers static segments first, but a raw route added before it still wins.
- **A member that calls `inProcess<S>()`** reaches the implementation only over a `LocalLink`; over
  HTTP it throws.

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

## A connection that is supposed to stay open

`WebSocket` and `PartySocket` reconnect through a `ReconnectionStrategy`, and the default
`ExponentialBackoffStrategy` stops after **ten attempts**. That is right for a request with a
caller waiting and wrong for a socket the app expects to hold: a laptop that slept for an hour, or
a phone that spent the commute underground, comes back to a socket that gave up long ago and now
reports itself as `Failed`. Say so explicitly:

```kotlin
PartySocketOptions(
    host = config.host,
    room = self.room,
    party = config.party,
    reconnectionStrategy = ExponentialBackoffStrategy(maxRetries = Int.MAX_VALUE),
    webSocketOptions = WebSocketOptions(eager = false),
)
```

Backoff only covers a connection that **drops**. Two states leave a socket parked with nobody
retrying: a first `connect()` that fails — which is what launching with no network looks like —
and a `Failed` reached after the strategy ran out. Both end in a device that is quietly not
connected, which is the worst failure this kind of app has, because everything looks fine and
nothing arrives. A supervising loop beside the socket costs one `delay` and closes both:

```kotlin
private suspend fun keepOpen(connection: PartySocket) {
    while (true) {
        delay(RETRY_MILLIS)
        if (socket !== connection) return   // a newer socket owns the room now
        when (connection.state.value) {
            is ConnectionState.Open, is ConnectionState.Connecting -> continue
            else -> {
                connection.reconnectionStrategy.reset()
                runCatching { connection.connect() }
                    .onFailure { Logger.w(it) { "Could not re-open." } }
            }
        }
    }
}
```

Pair it with `eager = false` and connect yourself, so nothing opens before there is a token to
open it with — and re-read the token in `queryProvider`, which runs on every reconnect, so one
refreshed while offline is picked up without tearing the socket down.

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
- [ ] A key is written only where one type has several providers in reach.
- [ ] Adapters installed File-before-Sql.
- [ ] Every new `expect` has an `actual` on every target, no-ops included.
- [ ] Any socket meant to stay open: `maxRetries` raised, and something re-opens a `Failed` one.
- [ ] Run it. These failures are all runtime, so a green build proves nothing about wiring. Run the
      **minified** build too: renamed properties break wiring there and nowhere else.
