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

### 1. A port finds its provider by key first, then by type, and stops at a graph's edge

A port's key defaults to the property name. A consumer looks in its own graph and then in each
parent in turn. In each graph it sees that graph's nodes and the outputs of the graphs directly
inside it, and it connects, in this order:

1. to the provider with the same key, in the nearest graph that has one;
2. failing that, to the **only** provider of its type in the nearest graph that has one.

A node inside a child graph stays hidden from the rest of the app until that graph offers it. A
graph offers a node, a port or a typed service with `by provides`, and the property names the
output:

```kotlin
class ConfigGraph(parent: Graph) : Graph(parent, label = "config") {
    val storage by provides(Node(::AppStorage))
    private val deployment by provides(provide(AppEnvironment.current))
}
```

A graph that needs a provider while it builds reads it with `input<T>()`, or as a property with
`private val backend by input<Backend>()`. The read follows the same walk, and when the provider
sits outside the graph it leaves a wire on the map.

So `consumes<T>()` just works when one provider of `T` is in reach: in the same graph, above it,
or offered by a graph directly inside one of those:

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
`IllegalStateException: Can't invoke functions through unconnected ports`. When the provider
exists but sits inside a graph that does not offer it, `requireFullyWired` names that graph:
``offer it with `by provides` from 'profile'``.

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

## A scope that outlives every screen

A player that keeps playing through navigation, a socket that must not drop on rotation, a download
that finishes after the user left: none of these can live in a route's graph, and on Android none
can live in an Activity. Three apps hand-rolled a holder for this (GymBuddy's `object` graph, Cairn's
`CairnApp`, Lector's reader kept alive by a blanket `configChanges`). Use `ProcessGraph` instead:

```kotlin
object Hearth {
    val session = ProcessGraph { HearthSession() }              // no routes: player, storage, sync
    val ui = ProcessGraph { HearthUi(session.get()) }           // routes; a child of the session
}
```

- **Long-lived nodes go in a graph with no routes; every UI graph is its child.** DI chains upward
  only, so a screen can `consumes<Playback>("playback")` from the session and the session can never
  hold a screen. Building the child through `session.get()` also orders the wiring: the parent is
  exposed before the child's `autoWire()`.
- **Install the adapters from the earliest entry point, with the application's context.** On
  Android that is `Application.onCreate`: the system can start a `Service` (a media button, a sync)
  with no Activity, and it will ask for the session. `AndroidFileAdapter` and `AndroidSqlAdapter`
  take any `Context` and keep the application's; an adapter holds its controller *weakly*, so one
  built from an Activity silently goes null once that Activity is collected.
- **A platform object that registers global callbacks is built once per process.** iOS remote
  commands registered from a view-controller factory are registered again every time SwiftUI
  rebuilds the view, and every command then fires twice. Guard the install, not the caller.
- **Anything drawn over every route — a mini player, a banner — goes around `GraphContent`, not in
  a route**, and reads the session directly. A route-held copy is rebuilt by every navigation.
- **Screen state the user expects back (a list's scroll position) lives in the screen node**, which
  lasts as long as its graph — `LazyListState()` constructed in the node, not
  `rememberLazyListState()`, which ends when a deeper route covers the screen.

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

- **An exit into another graph** resolves only when that graph offers the target route:
  `val chat by provides(Route("/chats/{id}", ::ChatBinding, ::ChatScreen))`. `requireFullyWired`
  names the route that still has an unwired exit and the graph that must offer its target.
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

## Motion and touch that respect the person's settings

**Reduce motion is the app's job.** No platform applies it to a Compose `tween`: Android only scales
animator durations, and iOS and the browser only report the setting. Provide reaktor-ui's answer once
at the theme root, then read it in every motion helper:

```kotlin
CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) { content() }
```

Test it with frames rendered every 16 ms. An `ImageComposeScene` sampled with one `render` per
assertion advances an animation by one frame each time, and makes a looping pulse look still.

**Haptics go through Compose, not reaktor-tactile, for UI feedback.**
`LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.Confirm | Reject | VirtualKey)`
is common code. On Android it is the view's `performHapticFeedback`: it follows the system's
touch-feedback switch, needs no permission, and does nothing on the desktop or in a browser.
reaktor-tactile drives the vibrator directly. That merges `VIBRATE`, ignores the switch, and exists
only on Android and iOS, so keep it for alarms that must carry across a room.

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

### Activity-bound slots and process-wide ones (reaktor-io's file slots)

The system's file dialogs answer through `registerForActivityResult`, which only works while an
Activity is being created. So `DocumentPicker`, `DocumentSaver` and `FolderChooser` (and `Share`)
are built and installed in `onCreate`, again by each new Activity. Anything that must work with no
screen open goes in `Application.onCreate` instead: `FolderWriter` writes into a folder chosen once
(its grant is taken as persistable), so a weekly backup runs whenever the process is alive.
Coffer's `CofferActivity` and `CofferApplication` are the worked example.

Android's own backup runs in a restricted process **without your `Application` subclass**, so a
`BackupAgent` cannot reach the graph. Mirror the one setting it needs into `SharedPreferences` from
the app, and read it there (Coffer's `CofferBackupAgent`).

### `Feature.X` slots are extension properties: import each one

`Feature.Share`, `Feature.DocumentPicker`, `Feature.NotificationListener` and the rest are declared
as `var Feature.X by CreateSlot<X>()` beside their interface, so each is an extension property that
needs its own import (`import dev.shibasis.reaktor.io.adapters.Share`). An import cleanup that
looks only for the type name drops it, and the compiler then reports `Unresolved reference
'shareFile' on receiver of type 'Any'`, far from the cause. Keep the import even when the type
itself is never named in the file.

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
socket drop a connection that has heard nothing since:

```kotlin
webSocketOptions = WebSocketOptions(
    eager = false,
    heartbeat = Heartbeat(
        every = 20.seconds,   // under the idle timeout of the NATs that forget soonest
        wait = 10.seconds,    // how long the answer may take before the connection is given up
        ping = "ping",        // what the socket sends; your server must answer it
        pong = "pong",        // the answer, kept out of receiver.textFrames
    ),
),
```

Any frame that arrives counts as an answer, so a busy connection is never dropped for a late pong.

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

**File bytes do not go in an `ObjectDatabase` row.** On Android, `SqliteObjectDatabase` reads rows
through a 2 MB `CursorWindow`. A row bigger than that — a 1.5 MB file is enough once it is base64
inside JSON — is written without complaint and cannot be read back, and `getAll` on its store then
fails for every row in it. Before that limit, the cost is time: Cairn decoded a week of files on
every launch before showing a list, 72 s on a busy emulator. Keep metadata in rows and bytes in
files through `Feature.File` (`DesktopFileAdapter(rootDirectory = …)` on the desktop — its default
root is `~/.reaktor`), read the bytes after the list is on screen, and check a file's length on
read: `writeBinaryFile` is not atomic, so a kill mid-write leaves a short file that looks whole.

**A secret that opens something later must outlive the process.** Cairn kept each blob's content
key in a `HashMap` filled as envelopes were opened, so a file that had arrived but not been
downloaded could not be downloaded after a restart. Anything a later action needs goes where the
row that offers the action goes.

**A send can fail after the state said `Open`.** The connection can die between the check and
the frame: `sender.send` returns a failed `Result` then, and must be handled as one. Before this was
true it threw the transport's `EOFException`, and anything sending from a coroutine of its own took
the Android process with it whenever the server restarted. Anything you launch that touches a
socket gets a `try` around each iteration, so one bad round does not end the loop.

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

## What only iOS will show you

A screen that works on Android has not been tried on iOS until you open it there. Hearth's Phase 1
ran clean on Android and launched to an empty library on iOS. Its second screen crashed on the
first frame.

- **A composable fun interface written as a lambda can fail to link.** `BasicTextField(decorator =
  { inner -> … })` threw `IrLinkageError: … TextFieldDecorator.Decoration(Function2)` on iOS (Kotlin
  2.3.0, Compose 1.11.0-alpha01): the call site wanted a `Decoration` without the `Composer`
  parameters, which the library does not have. The build was green and Android was fine. Write an
  `object : TextFieldDecorator { @Composable override fun Decoration(…) }` instead, and do the same
  for any interface whose single method is `@Composable`. The crash only prints to stdout, so launch
  with `xcrun simctl launch --console-pty <device> <bundle id>` to read it. The `.ips` report shows
  only `MetalRedrawer.draw`.
- **An Objective-C category's members need their own import.** `UIColor.systemBackgroundColor`
  is `Unresolved reference` until `import platform.UIKit.systemBackgroundColor` is added beside
  `import platform.UIKit.UIColor`: system colours live in a category, and Kotlin exposes each
  category member as an extension. Android Studio adds the import; a hand-written file doesn't have it.
- **Text fields autocorrect unless told not to.** iOS rewrote "darknet diaries" as "Darnley
  diaries" before the search ran. Search, address, code and name fields need
  `KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false)`.
- **Hold an editable field's text in a `TextFieldState` owned by the node.** Do not mirror it
  through a `StateFlow` and back. That round trip drops keystrokes when typing is fast ("daark").
- **An unsigned simulator build gets no background `URLSession`.** The Lector template sets
  `CODE_SIGNING_ALLOWED = NO` for the simulator. `nsurlsessiond` then rejects the app ("Process …
  does not have a bundle ID, rejecting connection"), and every `DarwinBackgroundDownloads` transfer
  fails with `NSURLErrorDomain -1`. Device builds are signed, so they are unaffected. On the
  simulator, background downloads need the build signed "to run locally" (ad hoc). That is the
  owner's call, so do not switch it on yourself.

## Syncing an app's data between devices

Use `reaktor-db`'s `Sync` (`dev.shibasis.reaktor.db.sync`). Do not use `SyncAdapter`, which uploads
and restores the whole database. The server half is `reaktor-cloudflare`'s `D1SyncLog` and
`WorkerAuthService`. Hearth is the worked example (`hearth-worker/`, `session/Sync.kt`).

- **Write through `SyncedStore`, from one writer.** Each write takes the next clock reading as it
  enters the engine. Two writes launched in separate coroutines can enter in either order, and
  then the older one wins on every other device. Put writes through a `Channel` drained by one
  coroutine.
- **Sync what one person sets, not what a device computes.** Fetch timestamps, ETags and counts
  change on every device and overwrite each other. Give the user's choice its own small store
  (Hearth's `Follow`) and keep the bookkeeping local.
- **Subscribe to `SyncedStore.remote` before the engine connects.** Use
  `launch(start = CoroutineStart.UNDISPATCHED)` in the node's `start()`, and start the sync node
  last. A change applied before anyone was collecting is in the database but missing from the
  in-memory state until the next launch.
- **A key that carries an address diverges across devices; merge on read, don't migrate.** Hearth's
  episode keys are `feedUrl|guid`. One show followed by search on one phone and by OPML on another
  has two addresses, so two keys, and positions never meet. Rewriting keys to agree would mean
  migrating positions, downloads and the queue on every device. Instead, find the same thing by an
  identity both copies share (the feed's `podcast:guid`), map the other device's address to this
  one's, and read rows under either as one, newest first (Hearth's `Library.elsewhere`,
  `History.merged`). Deleting it must delete it under every address.
- **Read the session token from `AuthObjectStore`, not from `Feature.Auth`.** The adapter belongs
  to an Activity or view controller. The session graph outlives both and only needs the cached
  token.
- **A test with two devices needs two databases.** A node that looks up `database` (the global
  slot) at runtime reads whichever database was installed last. Take stores, or the database
  itself, at construction.
- **An app on reaktor-auth lists `pod 'GoogleSignIn'` in its own Podfile.** The framework references
  it but does not carry it, and the iOS link fails with `_OBJC_CLASS_$_GIDSignIn` undefined.
- **Give each app's Worker its own dev port.** Cairn uses 8787, and a Cairn build on the shared
  emulator polls it, so a second Worker on 8787 answers someone else's app. Hearth uses 8797.
- **Node tests in a module that reaches Compose** (through reaktor-auth → reaktor-ui) fail to
  load `skiko.mjs`. An empty `export const skikoApi = {};` in `src/jsTest/resources/skiko.mjs`
  satisfies the import (see reaktor-cloudflare).

## Bundling a typeface

Put the font files in the app module's `src/commonMain/composeResources/font/` (lower-case names,
`ibm_plex_sans_regular.ttf`), add `ComposeLibraries.Resources`, and set the `Res` package with
`ResourcesExtension.packageOfResClass`. The licence goes beside them under `files/`. On iOS the
CocoaPods podspec dependeasy generates already lists `build/compose/cocoapods/compose-resources`,
so the files reach the app bundle with no Xcode change.

`org.jetbrains.compose.resources.Font` is `@Composable`, and loads at once on Android but a moment
later on iOS. So a type scale cannot be plain `val TextStyle`s with the family baked in: give the
family a `staticCompositionLocalOf`, fill it in the app's theme, and make each style a
`@Composable @ReadOnlyComposable get()` that reads it. Text then sets itself again when the font
arrives. Pass the same family to `MaterialTheme(typography = …)` or the platform dialogs keep the
system face. Coffer's `design/Tokens.kt` and `Theme.kt` are the worked example.

## Forms: the form's check, and the Done key

`FormField.validate()` returns `true` or `false`. A whole form's check is `Form.firstInvalid()`:
the first field that failed, to focus, or `null`. It used to be called `Form.validate()` too, and
`if (field.validate() == null)` compiled, was always false, and left Coffer's budget dialog doing
nothing for weeks; that name is deprecated now and the compiler offers the replacement. For one
field write `if (field.validate())`; for a form, `if (form.firstInvalid() != null) return`.
A zero amount needs `moneyReader(..., positive = false)`, or the field rejects it.

`FormTextField(onDone = { … })` is what the keyboard's action key does: save the dialog, add the
tag. Giving it makes the key read Done. A dialog with one field should always pass it, or the key
only hides the keyboard and the person has to find the button.

## A table to share: `Xlsx.write`

`reaktor-io` writes `.xlsx` as well as reading it: `Xlsx.write(XlsxSheet(name, header, rows))`
gives the bytes, shared under `Xlsx.MIME`. Use `XlsxCell.Number(unscaled, scale)` for money (minor
units and the currency's decimals, never a `Double`), `XlsxCell.Date` for dates, and
`XlsxCell.Text` for anything with digits that isn't a quantity (a reference, an account's last
four), or Excel shows it as 4.31E+11. On a phone an `.xlsx` opens as a table; a `.csv` often opens
as text, so prefer it for anything a person will look at. `ZipWriter` is there for other zips.

## Locking an app: `reaktor-unlock`

`Feature.Unlock` is the phone's own unlock (fingerprint, face, or the screen lock), and `AppLock`
is when to ask. Coffer is the worked example (`CofferRoot.kt`, `LockScreen.kt`).

- **One `AppLock` per process**, in whatever holds the session, not in a screen: the Activity comes
  and goes while the lock's state carries on.
- **Don't draw the app under a cover.** While `locked` is true, don't compose the screens at all,
  or a screen reader still reads them. While it is `null` (settings not read yet) draw nothing.
- **Tell it about the screen** with `LifecycleStartEffect { lock.returned(); onStopOrDispose { lock.left() } }`.
- **Ask from `LifecycleStartEffect`, not `LaunchedEffect` or resume.** On Android 8 and 9 the
  prompt is a screen of its own, so backing out of it "returns" to the app: guard the automatic ask
  with the time the last prompt ended, or it loops.
- **On Android, install `AndroidDeviceUnlock(this)` in `onCreate`** with the other Activity-bound
  adapters. On iOS add `NSFaceIDUsageDescription` to Info.plist; iOS ends an app that asks without it.
- **Testing on an emulator:** `adb shell locksettings set-pin 1234`, then `adb shell input text 1234`
  and `input keyevent 66` at the prompt. The prompt is a secure window, so a screenshot of it is
  black: read it with `uiautomator dump` instead. Clear the PIN after (`locksettings clear --old 1234`).

## Reminders: local notifications an app schedules for itself

`reaktor-notification` posts and schedules local notifications and links no cryptography or push
transport (those are `reaktor-notification-sealed` and `reaktor-notification-fcm`). It merges
`POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`. Coffer's `remind/Reminders.kt` is a small worked
example; GymBuddy's `ReminderService` a larger one.

- **Two clients on Android.** Scheduling needs only a `Context`; asking for the permission needs an
  Activity. If anything schedules with no screen open, install
  `AndroidNotificationsClient(application, config)` in `Application.onCreate` and replace it with
  `AndroidNotificationsClient(activity, config)` in each `Activity.onCreate`. With only the
  Activity's, a background schedule finds an empty slot; with only the Application's,
  `requestPermissions()` returns without asking.
- **Ask at the switch.** Call `requestPermissions()` when the person turns a reminder on, not at
  launch, and say what to do when it comes back refused.
- **`NotificationPrecision.Approximate` is a one-hour window on Android.** It needs no exact-alarm
  permission, and it can arrive up to an hour late: say "around 9", not "at 9". The default,
  `Exact`, is for a time a person set on a clock face.
- **Cancel by id.** `cancelScheduled()` with no ids clears what this run of the process scheduled,
  which isn't everything a previous run did.
- **The small icon is found by name** (`AndroidNotificationsConfig(defaultSmallIconName = …)`), so
  the resource shrinker drops it from a release build unless `res/raw/keep.xml` names it. Recent
  Androids show the app's launcher icon in the shade and the small icon only in the status bar.
- **Seeing one fire on an emulator** without waiting: `adb shell cmd time_detector
  set_auto_detection_enabled false`, then `adb shell cmd alarm set-time <epoch ms>` (no root
  needed), to the *end* of the window for an approximate one. Read the result with `adb shell
  dumpsys notification --noredact`, and put the clock back (`set-time` to now, detection on).
  `adb shell dumpsys alarm | grep -A8 <package>` shows what is scheduled, with its window.

## A photo from the person: `PhotoPicker`, `CameraCapture`, `downscalePicture`

`reaktor-io` has the system's own photo chooser and camera, and neither needs a permission:
Android's Photo Picker and camera app, iOS's `PHPickerViewController` and camera sheet. Install
`AndroidPhotos(this)` / `DarwinPhotos()` in `Feature.PhotoPicker` and `Feature.Camera` beside the
document picker. Coffer's payment page is the worked example (`EntryScreen.attach`, `PhotoViews.kt`).

- **Bring it down before keeping it.** `downscalePicture(bytes, maxSide, quality)` gives an
  upright JPEG: a phone's 4 MB photo is a few hundred KB at 1,400 px. Never keep the raw bytes.
- **Don't declare `CAMERA` for this.** An Android app that declares it and hasn't been granted it
  is refused the camera app too. Check the merged manifest after adding any library.
- **iOS needs `NSCameraUsageDescription`** in Info.plist before the camera opens, or iOS ends the app.
- **Run the pick in the node's scope, not the screen's.** The camera is another app; by the time it
  returns, the screen may have been taken down (a lock after a minute away). The node outlives it.
- **A screen keyed on a `StateFlow` won't reload for a change that leaves the flow's value equal.**
  Replacing one photo with another left the payment's fields (and so the month's value) the same,
  and the screen kept showing the old photo. Hand the new value to the screen directly, or key the
  reload on something that changes.
- **The Photo Picker on recent Androids may ask for Done** even for a single photo, and shows
  "Coffer will only have access to the photos you select". To test, push a picture with
  `adb push x.jpg /sdcard/Pictures/` and it appears there.

## Files people bring from other apps

`reaktor-io`'s `archive` package reads them in common code: `Zip` (stored and deflated entries),
`Inflate` (raw DEFLATE) and `Xlsx.firstSheet` (an Excel workbook's first sheet as rows of text;
`Xlsx.excelDate` for its day-number dates). Money apps export Excel more often than CSV. Old
binary `.xls` (it starts D0 CF 11 E0) isn't read: say so and ask for `.xlsx` or CSV.

## Writing many rows: one transaction

A loop of `put` or `delete` on a SQLite-backed store is one commit per row. Restoring 30,000 rows
that way took 4.4 s on a laptop and 15.5 s on an emulator. `ObjectDatabase.importRaw` and
`deleteAll` run in one transaction (0.3 s and 1.6 s), and are all-or-nothing as well. Use them
for seeding, restoring, importing and undoing an import; serialize with the database's own
`objectSerializer` to build the `RawObject`s.

## Reading other apps' notifications

`reaktor-notification-listener` (Coffer is the worked example, `capture/Capture.kt`).

- **Install the listener and build the session in `Application.onCreate`.** The system binds the
  listener service on its own and can start the process for it with no screen; whatever collects
  `posted` must already exist. Notifications that arrive before a collector are queued, not lost.
- **Set the filter before anything can arrive, and keep it narrow.** The module reads only the
  package name of a notification outside the filter. Do not widen it to "every app" for convenience:
  the system's grant dialog already tells the person the app can read everything.
- **On every connect, read `active()` and expect repeats.** A reconnect hands over what is still in
  the shade. Remember each notification by its identity (app, key, post time) so a repeat is
  skipped, and never by a hash of its text when the text is a secret: a hash of a six-digit OTP is
  the OTP.
- **Test it on an emulator from the shell.** `cmd notification post` posts as `com.android.shell`,
  so a debug build adds the shell to its filter. `cmd notification allow_listener <pkg>/<service>`
  grants access without Settings, and `disallow_listener` then `allow_listener` is a reconnect.
  `cmd locale set-app-locales <pkg> --locales de-DE` changes one app's locale for a locale check.
- **A stopped listener looks exactly like a quiet month.** Phone makers kill background apps
  whatever Android's own setting says. Show `batteryRestricted()` where the person can act on it,
  offer `openKeepAliveSettings()` (the maker's autostart screen first, then Android's battery
  list), and watch each source's rhythm so a sender that goes quiet is said, not assumed (Coffer's
  `ledger/Health.kt`). `<queries>` for the makers' packages is already in the module's manifest.

## Hearing TalkBack on an emulator

TalkBack ships on Play images. Switch it on with `settings put secure
enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`
and `accessibility_enabled 1`. To hear it from adb, set TalkBack's Developer settings, Log output
level to VERBOSE first: each spoken word is logged as `talkback: … speak word = <word>`. Don't
trust it as a walk: taps and swipes sent with `adb shell input` under TalkBack sometimes activate
instead of focusing, which changes the app under you. It finds wrong labels; a person does AC4.
Put both settings back after.

## Running iOS simulator tests on `shibasis/newDesign`

dependeasy switches the simulator test chain off on this branch (`PhysicalDevices.kt`: "builds never
boot simulators"), so `iosSimulatorArm64Test` is skipped and reports nothing. To run a module's
common tests on the simulator anyway, without changing the branch, use an init script that turns
the three tasks back on, and run each task in its own Gradle invocation: the plugin cleared their
dependencies, and naming all three in one run fails Gradle's implicit-dependency validation.

```groovy
// ios-sim-tests.init.gradle
gradle.taskGraph.whenReady { graph ->
    graph.allTasks.each { task ->
        if (task.name in ['compileTestKotlinIosSimulatorArm64', 'linkDebugTestIosSimulatorArm64', 'iosSimulatorArm64Test']) {
            task.enabled = true
            if (task.name == 'iosSimulatorArm64Test') task.device.set('iPhone 17 Pro')
        }
    }
}
```

```bash
for t in compileTestKotlinIosSimulatorArm64 linkDebugTestIosSimulatorArm64 iosSimulatorArm64Test; do
  ./gradlew -I ios-sim-tests.init.gradle :reaktor-<module>:$t --tests '<package>.*'; done
```

A test that builds a graph needs `Feature.Dependency = KoinDependencyAdapter(koinApplication { })`
as well as a database, and both put back to null after.

**A new module with a JS target changes reaktor's shared pnpm lockfile** (the generated workspace
lists every module's package), and the JS build then fails with `ERR_PNPM_PACKAGE_MANAGER_NO_IMPORTER`
until the lockfile is regenerated. Give a module a web target only when something on the web uses
it, and when it must have one, regenerate and commit the lockfile in the same change.

## Moving an app's UI onto Surface without changing a pixel

Lector and Coffer both moved off Material onto reaktor Surface so Hangar's Studio can show and edit
them. Coffer's way kept every screen identical; its files are the ones to copy.

- **Baseline first, then one step at a time.** Capture every state on the emulator and simulator
  before changing anything, and diff each step's captures against them (more than 8 per channel
  counts). A state that drifts run to run (a fling, a cursor) needs a second baseline run to tell
  noise from a change. A state the baseline lacks can be compared against a build of the starting
  commit in a temporary `git worktree` beside the app, so `../reaktor` still resolves.
- **Appearances at the root carry the look.** `SurfaceTheme(snapshot, Appearances(...))` inside the
  app's theme, with its type scale as a `TextAppearance` and its palette as an `InkRole` enum
  (Coffer's `design/Surfaces.kt`). Surface `Text` shows **one line with an ellipsis** by default
  where Material showed every line: pass `lines = Int.MAX_VALUE, overflow = Clip` when swapping.
  Surface `Icon` has no `contentDescription` and no default size: give it `Modifier.semantics`.
- **Copy Material's numbers, don't guess them.** Its tokens and private constants read out of the
  Material jar by reflection (`DumpStatics`, `DumpTokens` on `material3-desktop`), its layouts from
  `javap -c` or the JetBrains fork's sources on GitHub. Defaults the app never set still show:
  Coffer's date picker sits on Material's `surfaceContainerHigh`, so that colour is carried over.
- **What Surface already has for a phone app:** `NavigationBar` (with `NavigationItem`), `DatePicker`
  with `CalendarText` (the platform's own date words, as Material reads them), Button, Text, Icon,
  Dialog. Coffer's `design/Navigation.kt` and `design/DatePicker.kt` are Material 3's bar and
  picker as Surface appearances, measure for measure.
- **Own components become Studio-visible with `Component(contract, modifier, values) { edits -> }`.**
  It does nothing outside Studio. Its body is a lambda: a bare `return` in the old function body
  becomes `return@Component` (but not inside a local `fun`).
- **Keep the platform dialog window** for alert and picker dialogs (compose-ui `Dialog`) where the
  app had Material's: Surface's `Dialog` is an overlay in the app's window and dismisses
  differently. Surface's `Tooltip` needs the overlay host from `SurfaceEnvironmentProvider`, which
  a platform dialog's window doesn't have.
- **Ripples too.** reaktor-ui's `materialRipple(color)` is Material's own ripple primitive (the
  platform's ripple on Android) without a Material import in the app; a Surface appearance gets
  its control's presses from `LocalPressInteractions`. Use Material's colour for each control (it
  reads `LocalContentColor` where the ripple sits: often not the label's colour), clip to the same
  shape, and map press positions when the ripple is on part of the control (Coffer's
  `design/Ripples.kt`). For a control whose colour follows its state, pass a `ColorProducer` and
  `remember` one ripple: a new ripple drops the next press. Hold each control down on the old and
  new builds and compare mid-press.

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
- [ ] Every function returning `Result` returns one on every path — nothing below it throws through.
- [ ] Every long-lived `collect { }` survives its own body throwing.
- [ ] Run it once with no network from a fresh install, and use it.
- [ ] Open every screen on iOS, not only the first. Some link errors only appear when a screen draws.
- [ ] Run it. These failures are all runtime, so a green build proves nothing about wiring. Run the
      **minified** build too: renamed properties break wiring there and nowhere else.
