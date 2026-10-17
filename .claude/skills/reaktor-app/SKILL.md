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

Because the key is a property *name*, **minification breaks wiring**. R8 renames fields, so a
release build raises this same unconnected-port error on its first launch while every debug build
is fine. The keep rules that prevent it are in `reaktor-ship`.

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

**And give it a heartbeat.** The supervisor above only acts on a socket that knows it is broken.
A connection can die without closing — a carrier NAT forgets an idle mapping in minutes, a phone
changes networks — and then the socket reports `Open` forever while everything sent into it is
lost and nothing comes out. Cairn's phone sat like that, showing itself online; the relay, asked
directly, did not have it. Send something on an interval that your server answers, and let the
socket drop a connection that has heard nothing:

```kotlin
webSocketOptions = WebSocketOptions(
    eager = false,
    heartbeat = Heartbeat(
        interval = 20.seconds,   // under the idle timeout of the NATs that forget soonest
        silence = 50.seconds,    // two missed answers and a margin, so congestion is not death
        message = { json.encodeToString(ClientMessage.serializer(), ClientMessage.Ping(epochTime())) },
    ),
),
```

**Sending is not delivering.** A frame written to a socket that closed a moment later is gone,
and so is one written before the socket finished opening. If the app promises that something sent
arrives, keep it — sealed, exactly as sent — until the server says it has it, and send it again on
every reconnect. Make the server's acknowledgement a message, and also resend when the server
welcomes a new connection: a reconnect quick enough that the status went `Online` → `Online` never
shows up in a `StateFlow`, because the in-between value was conflated away. Cairn's comment said
"the room will get it on reconnect"; reconnecting only pulled, and drops sent offline reached
nobody, ever.

**A node that reads its state back and also listens to a port has a race in its `init`.**
`launch { hydrate() }` and `launchWhenWired(room) { … }` start together, and nothing orders them.
Cairn's store caught up from the cursor it held — which, until the database answered, was zero; the
room usually opened first, so most launches asked the server for the whole week. Gate the port
work on a `CompletableDeferred` that `hydrate` completes in a `finally`. For a `SharedFlow`,
subscribe first and await inside the collector: awaiting before subscribing loses whatever arrived
in between. Test it against a database that is slow to read — an in-memory one answers before
anything else runs and hides the race.

**Deleting what a server can send again needs a note that it was deleted.** If the server keeps a
history and replays it on catch-up, or passes on a client's resend, a row removed locally is a row
never seen. Cairn forgot a drop and had it back four days later. Keep the deleted id — persisted —
for as long as the server could still send it, and skip it on arrival.

**A `ConcurrencyCapabilityImpl` dispatcher is passed by name.** Its first parameter is a context;
a dispatcher passed there positionally is silently replaced by `Dispatchers.Default`. That is how
`WebSocket` — documented as single-threaded — ran on the whole pool for its entire life.

```kotlin
ConcurrencyCapabilityImpl(coroutineDispatcher = dispatcher.limitedParallelism(1))
```

## A function that returns `Result` must not throw

Ports here return `Result` so the screen can say what went wrong. That is a promise about every
path through the function, including the dependency three calls down that signals failure with
`error(...)`. Cairn's `send` returned `Result<Drop>` and called a sealer that threw when the device
had no key yet. What the throw did depended on who was calling:

- **From the UI** — `scope.launch { port().send(x).onFailure { toast(it) } }` in a
  `rememberCoroutineScope`. The `onFailure` never runs; the exception leaves the composition's
  scope and the app closes. `FATAL EXCEPTION: main` on Android.
- **From a collector** — `flow.collect { share -> feed(share) }`. The exception ends the
  collection. Under a `SupervisorJob` nothing reports it, and the receiver goes on looking alive
  while dropping everything for the rest of the session.

So: catch at the boundary where the `Result` is made, rethrow `CancellationException`, and give
expected refusals their own exception type with a message written for a person — it will be shown
verbatim. And in a long-lived `collect`, guard the whole body, not just the half that looked
risky.

```kotlin
private suspend fun seal(payload: Payload): Result<Envelope> =
    try { Result.success(sealer().seal(payload)) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }
```

States worth exercising because they are ordinary and rarely run: first launch with no network,
and whatever sits between "has an identity" and "has been let in".

## Testing a graph: wait for what you assert

Nodes do their work on their own dispatcher, so a test waits for it. Two ways that goes wrong,
both of which pass on a quiet machine and fail one run in four on a busy one:

- **`runTest` makes `withTimeout` virtual.** The work is on real threads; the test scheduler is
  idle, skips to the deadline, and the wait "times out" instantly. Use `runBlocking` when the thing
  waited for does not run on the test dispatcher.
- **Waiting for one thing and asserting another.** A source appears in the list when it is
  registered; its document arrives when the poll that registering starts has finished. Wait on the
  flow that carries the fact you are about to assert.

**A test that fails in a different place each time is one bug.** One run in six, somewhere in
Cairn's suite, a test died with `Already resumed`. Each time it looked like that test's flake, and
rerunning the test alone passed. It was `launchWhenWired` — the node's thread and the wiring
thread noticing the same connection — and every graph any test built was a chance to hit it. Run
the *whole* suite several times and read the stack, not the test's name. Then reproduce it on
purpose: release both sides from a barrier a few thousand times and the window that opened once in
six runs opens in under a second.

And a slot set in one test is set in the next: `Feature.Database = …` needs an `@AfterTest` that
puts it back to `null`.

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
- [ ] Any socket meant to stay open: `maxRetries` raised, and something re-opens a `Failed` one.
- [ ] Every function returning `Result` returns one on every path — nothing below it throws through.
- [ ] Every long-lived `collect { }` survives its own body throwing.
- [ ] Run it once with no network from a fresh install, and use it.
- [ ] Run it. These failures are all runtime, so a green build proves nothing about wiring. Run the
      **minified** build too: renamed properties break wiring there and nowhere else.
