# Graphs with ports

*Proposal of 5 November 2026, built in the early hours of 6 November with route exits as outputs (open decision 1, option 2). It replaces the Scope proposal from earlier on 5 November. The diffs in section 3 are against the `BestBudsApp.kt` and `Backend.kt` of 5 November. Nothing is committed yet.*

## 1. Answer

Yes, we can keep the name `Graph`, and the change is now built. The Scope from the v2 paper becomes two changes to the existing class, and no new class is added.

1. **A graph has ports.** `Graph` implements `PortCapability`, as `Node` already does.
   - A graph offers something to the rest of the app with the delegate a node uses to offer itself, `val x by provides(...)`.
   - It reads what it needs while it builds with `input<T>()`, as it does today. When the read reaches outside the graph it now leaves a wire, and it can be written as a delegate, `val x by input<T>()`.
   - Every other input is a wire from one of its nodes to something outside the graph. The runtime already records those wires.
   - An output is an alias of a port inside the graph, and the lookup sees outputs from below and providers from above. So no graph needs a relay to bring a port in or to pass one on.
2. **Wiring stops at a graph's edge.** A port looks for a provider in its own graph, then in each enclosing graph in turn. At each level it sees two things: that graph's own nodes, and what that graph's child graphs offer. It never sees inside a child graph.

No new function name is added either.
- `provides` gains overloads on `Graph`.
- `input<T>()` records a read that reaches outside the graph as a wire.
- The lookup in `wire()` and `findProvider` changes.
- `dependsOn`, `Relay` and `relay(...)` are deleted.

In BestBuds, no node class, route class or binding changes. The graph files lose all 38 relays, the three `dependsOn` calls and every constructor parameter that is a graph. The graphs mark with `by provides` the sixteen nodes and the nine routes that other graphs use. `Build` offers its eighteen services and itself the same way.

## 2. The two changes

### A graph has ports

`YouGraph` shows the whole change in one graph. Here it is today:

```kotlin
class YouGraph(parent: Graph, backend: Backend) : Graph(parent, label = "profile") {
    val service = relay(backend.profile)
    val profile = Node(::You)
    val invites = Node(::Invites)
    val developer = Route("/settings/developer", ::DeveloperBinding) { DeveloperScreen(it, backend) }
    val verify = Route("/you/verify", ::VerifyBinding, ::VerifyScreen)
    val invitation = Route("/invite/{code}", ::InviteBinding, ::InviteScreen)
    val settings = Route("/settings", ::SettingsBinding, ::SettingsScreen)
    val editor = Route("/profile/edit", ::EditProfileBinding, ::EditProfileScreen)
    val route = Root("/you", ::ProfileHomeBinding, ::ProfileHomeScreen)

    init {
        relay(backend.invites)
        relay(backend.verification)
        relay(backend.media)
        Node(::InviteDetail)
        Node(::Verify)
        Node(::ProfileEditor)
    }
}
```

After the change:

```kotlin
class YouGraph(parent: Graph) : Graph(parent, label = "profile") {
    private val backend by input<Backend>()
    val profile = Node(::You)
    val invites by provides(Node(::Invites))
    val developer = Route("/settings/developer", ::DeveloperBinding) { DeveloperScreen(it, backend) }
    val verify = Route("/you/verify", ::VerifyBinding, ::VerifyScreen)
    val invitation = Route("/invite/{code}", ::InviteBinding, ::InviteScreen)
    val settings = Route("/settings", ::SettingsBinding, ::SettingsScreen)
    val editor = Route("/profile/edit", ::EditProfileBinding, ::EditProfileScreen)
    val route = Root("/you", ::ProfileHomeBinding, ::ProfileHomeScreen)

    init {
        Node(::InviteDetail)
        Node(::Verify)
        Node(::ProfileEditor)
    }
}
```

There are three differences.
- **The backend parameter becomes an input.** `input<Backend>()` finds the backend that `Build` offers at the Session level. The constructor now carries only the parent. Other constructors carry the parent and plain values such as `me` and `voice`.
- **`invites` becomes an output.** Today `CirclesHomeScreen` and `ConversationsScreen` reach inside this graph to find `Invites`. After the change they find it through this output. The property is still the `Invites` node, so code that reads `profile.invites` does not change.
- **The four relays go.** The services they relayed are outputs of `Build`. The lookup finds them one level up, in Session.

`provides` on a graph takes one of three things.
- **A node or a service.** `val x by provides(node)` offers the node's ports at the graph's edge, and the property is the node. A service handle such as `Provides<MessagingApi>` works the same way and offers the ports of the node behind it. The node can sit anywhere inside the graph, including inside a child graph. Every node class that other graphs use already offers itself with `by provides<T>(this)`, so this form covers most cases.
- **A port.** `val x by provides(port)` offers one port, such as the result of `provide(value)`. When the port is null it offers nothing. The property is the port.
- **A value.** `val x by provides<T>(value)` creates a port on the graph itself, exactly as the same call does on a node. `Build` uses this form to offer itself as a `Backend`.

The first two forms are aliases, so a consumer outside the graph connects to the inner port itself. This is the job relays did by copying a port into another graph, which is why no relay is left.

Two things keep their present meaning.
- A plain `val` or getter is a handle for code. Examples are `app.world`, `identity.users.logout()` and `messaging.sessions`. Only a `by provides` property is a port.
- Kotlin visibility only controls which code can read a property. A `private val ... by provides` is still an output, even though code outside the graph cannot read it.

### Inputs: `input` and `consumes`

The two verbs bind at different times, and that is why both remain.
- **`consumes` binds when the graph wires.** A node uses the port while it runs. It is never read during construction, because the provider may be a node declared later or one in a graph built later.
- **`input` binds at the moment it is called.** A graph uses the value while it builds its nodes. Examples are passing `backend` into `DeveloperScreen`, reading a variant's clock, or deciding whether to build the secure chat nodes. It can reach only what is already built: its ancestors' nodes and the outputs of graphs built before it. Graphs build parent first and in declaration order, so `Build` exists before the features read it.

A graph's runtime needs belong to its nodes as `consumes`, and the wires those nodes make become the graph's inputs. So a graph needs `input` only for what it uses while building. It declares no `consumes` of its own, which leaves `consumes` on a graph free for declared inputs later (open decision 2).

`val x by input<T>()` is the delegate form, and `input<T>().impl` still works where no property is involved, as in Messaging's `chatSecurity`. Both forms leave the same wire.

### Wiring stops at a graph's edge

A consumer in graph G binds to the first match it finds. It searches G first, then G's parent, and so on up to the root. At each level the candidates are that graph's nodes and the outputs of its child graphs. Within a level, matching works as it does today: a provider with the consumer's key wins; if none has that key, the only provider of the consumer's type wins. Koin resolution through the DI adapter is unchanged.

Today a port can bind by five routes:
1. a local provider;
2. a `dependsOn` import;
3. the parent chain in `findProvider`;
4. Koin;
5. the whole-subtree search in `wire()` (`Graph.kt:396`).

Routes 2 and 5 reach inside other graphs.
- Through route 2, `dependsOn(build)` lets every node under Session take the relays inside `Build`.
- Through route 5, `CircleOutcomes` takes `ChatContentSecurity` from inside `MessagingGraph`, and `CirclesHomeScreen` takes `Invites` from inside `YouGraph`.

After the change there is one walk plus Koin.

Route exits take the same walk. An exit binds to its target route only when that route is in reach, so a graph offers every route that another graph opens, with the form it uses for a node:

```kotlin
val chat by provides(Route("/chats/{id}", ::ChatBinding, ::ChatScreen))
```

BestBuds offers nine routes this way, and cross-graph navigation now shows on the graph cards.

Explicit connections keep today's behaviour. `connect(...)` still connects the ports it names. A connection should stay inside the graph that makes it and that graph's descendants, which is the paper's rule W7. The same rule bounds aliases: a graph offers only ports from its own subtree. Today `Build`'s relays reach into `Remote` and `Mocked`, which are its siblings under Session. After the change `Build` mounts both variants itself and offers their ports directly.

### Against the v2 paper

| This proposal | v2 paper |
|---|---|
| `Graph` | the Scope node kind |
| `by provides` on a graph | Export |
| `input<T>()` and the wires that leave a graph | boundary ports of require polarity, here derived from wires |
| the walk | composition of open graphs along their boundaries, with child interiors hidden |
| explicit connections stay inside the graph's subtree | W7 |

## 3. BestBuds, graph by graph

### App root: two lines go

`dependsOn(config)` and `dependsOn(identity)` existed so that `input<T>()` and `autoWire` could find Config's and Identity's providers. The walk now finds their outputs at the app level.

```diff
 open class BestBudsApplication(id: Uuid) : Graph(id = id) {
     val config = Graph(::ConfigGraph)
-    init { dependsOn(config) }
 
     internal val language get() = config.language
@@
     init {
-        dependsOn(identity)
         remote(AnalyticsApi() over renewingLink(AppEnvironment.current.analyticsServer))
         remote(AnonymousAnalyticsApi() over renewingLink(AppEnvironment.current.analyticsServer))
```

The getters `language`, `world`, `wires` and `foreground` stay, because code reads them.

### Config: the environment becomes eight outputs

These eight are the app's environment. `Build`, `Remote` and Messaging read some of them while they build, and nodes across the app consume them. `deployment` and `authentication` stay private to code and become outputs for ports.

```diff
 class ConfigGraph(parent: Graph) : Graph(parent, label = "config") {
-    private val deployment = provide(AppEnvironment.current)
-    private val authentication = Feature.Auth?.let { provide<AuthAdapter<*>>(it) }
-    val language = Node { Language(it, HumanSignalMode.entries.firstOrNull { mode -> mode.pathSegment == SurfaceAppLaunch.language }) }
-    val world = Node { MockWorld(it, Scenario.from(SurfaceAppLaunch.scenario)) }
-    val wires = Node { Wires(it, SurfaceAppLaunch.backends) }
-    val storage = Node(::AppStorage)
-    val devices = Node(::DeviceCapabilities)
-    val foreground = Node(::Foreground)
+    private val deployment by provides(provide(AppEnvironment.current))
+    private val authentication by provides(Feature.Auth?.let { provide<AuthAdapter<*>>(it) })
+    val language by provides(Node { Language(it, HumanSignalMode.entries.firstOrNull { mode -> mode.pathSegment == SurfaceAppLaunch.language }) })
+    val world by provides(Node { MockWorld(it, Scenario.from(SurfaceAppLaunch.scenario)) })
+    val wires by provides(Node { Wires(it, SurfaceAppLaunch.backends) })
+    val storage by provides(Node(::AppStorage))
+    val devices by provides(Node(::DeviceCapabilities))
+    val foreground by provides(Node(::Foreground))
 
     init {
```

### Identity: four outputs

Nodes in three places use these outputs: `SessionState` and `AnalyticsDeliveryNode` at the app level, `SignalOnboarding` and `Circles` in Circles, and `SettingsScreen` and `DeveloperScreen` in You. `DeveloperScreen` also calls the notification contract, so `notifications` is an output too.

```diff
     private val service = provide(UserService())
-    val notifications = NotificationServiceNode()
+    val notifications by provides(NotificationServiceNode())
-    val users = Node(::UserRepository)
-    val hydration = Node(::SessionHydrationInteractor)
-    val account = Node(::Account)
+    val users by provides(Node(::UserRepository))
+    val hydration by provides(Node(::SessionHydrationInteractor))
+    val account by provides(Node(::Account))
     val celebration = Route("/verified", ::VerifiedCelebrationBinding, ::VerifiedMomentScreen)
```

`notifications`, `MeServiceNode` and `TrustVerificationServiceNode` also keep publishing into Koin.

### Session: features take no arguments, and Build owns its variants

Session stops building `Remote` and `Mocked`, stops importing `build`, and stops relaying the user stream. The features now reach the stream as an output of `Build`.

```diff
 class Session(parent: Graph, user: User, wiring: Wiring) : Graph(parent, label = "session") {
-    private val remote = Graph { Remote(it, user, input<AppEnvironment>().impl) }
-    private val mocked = Graph { Mocked(it, user, input<MockWorld>().impl) }
-    val build = runCatching { Graph { Build(it, user, wiring, remote, mocked) } }.getOrElse { close(); throw it }
-    init { dependsOn(build) }
+    val build = runCatching { Graph { Build(it, user, wiring) } }.getOrElse { close(); throw it }
 
     private val chats = build.origin(Backend::messaging)
-    private val stream = relay(build.userStream)
     private val freshness = Node(::Freshness)
     private val snapshots = Node { Snapshots(it, "${user.appId}/${user.id}/${chats.label}", persistent = chats.live) }
 
-    val circles = Graph { CirclesGraph(it, build) }
-    val messaging = Graph { MessagingGraph(it, build) }
-    val discovery = Graph { DiscoveryGraph(it, build) }
-    val profile = Graph { YouGraph(it, build) }
+    val circles = Graph(::CirclesGraph)
+    val messaging = Graph(::MessagingGraph)
+    val discovery = Graph(::DiscoveryGraph)
+    val profile = Graph(::YouGraph)
     val home = Root { HomeTabs(it, mapOf(SignalTab.Discover to discovery, SignalTab.Chats to messaging, SignalTab.Circles to circles, SignalTab.You to profile), SignalTab.Circles) }
```

### Build: services become outputs, and Build offers itself

Each service line changes from `= relay(...)` to `by choose(...)`. The private helper keeps its bookkeeping and now offers the chosen variant's service as it is, with no relay node in between. A feature that consumes `MessagingApi` therefore connects straight to the contract node inside `Remote` or `Mocked`. `backend` lets the features read `origin(...)` and lets `CirclesStart` and `DeveloperScreen` keep taking the whole backend.

```diff
-class Build(parent: Graph, user: User, private val wiring: Wiring, private val remote: Remote, mocked: Mocked) : Backend(parent, "build", user) {
+class Build(parent: Graph, user: User, private val wiring: Wiring) : Backend(parent, "build", user) {
+    val remote = Graph { Remote(it, user, input<AppEnvironment>().impl) }
+    val mocked = Graph { Mocked(it, user, input<MockWorld>().impl) }
     val variants: List<Variant> = listOf(remote, mocked)
     private val chosen = LinkedHashMap<String, Variant>()
     private val rewirable = mutableListOf<String>()
+    val backend by provides<Backend>(this)
 
-    override val messaging = relay(Backend::messaging)
-    override val chatStream = relay(Backend::chatStream, with = Backend::messaging)
-    override val userStream = relay(Backend::userStream, with = Backend::messaging)
-    override val media = relay(Backend::media)
+    override val messaging by choose(Backend::messaging)
+    override val chatStream by choose(Backend::chatStream, with = Backend::messaging)
+    override val userStream by choose(Backend::userStream, with = Backend::messaging)
+    override val media by choose(Backend::media)
@@
-    private fun <T : Any> relay(service: KProperty1<Backend, Provides<T>>, with: KProperty1<Backend, Provides<*>>? = null): Relay<T> {
+    private fun <T : Any> choose(service: KProperty1<Backend, Provides<T>>, with: KProperty1<Backend, Provides<*>>? = null) = provides(run {
         val variant = with?.let(::origin) ?: pick(service.name).also { rewirable += service.name }
         chosen[service.name] = variant
-        return Node { Relay(it, service.get(variant)) }
-    }
+        service.get(variant)
+    })
```

The other fourteen service lines change the same way. `remote` and `mocked` are public, so `BuildGraphTest` reads the variants from the build it made. The refusal for an unknown service now says "this build chooses". `Backend`, `Variant`, `Remote` and `Mocked` do not change, and their services stay `by lazy`, so an unchosen service is still never built. `Remote` still reads `input<Foreground>()`, and the walk now finds it as an output of Config.

On the map, `Build` collapses to one card with a row per service. Expanded, it shows the two variants with only the chosen services inside them. The relay cards are gone; the blueprint review had flagged them as noise, because each one printed every port twice.

### Circles: the backend becomes an input, six relays go, and three routes become outputs

```diff
-class CirclesGraph(parent: Graph, backend: Backend) : Graph(parent, label = "circles") {
+class CirclesGraph(parent: Graph) : Graph(parent, label = "circles") {
+    private val backend by input<Backend>()
     private val planClock = backend.origin(Backend::plans).clock
-    val service = relay(backend.circles)
-    val rounds = relay(backend.rounds)
     val circles = Node { Circles(it, backend.user.name) }
     val signal = Node(::SignalOnboarding)
@@
-    val welcome = Route("/circles/{id}/welcome", ::CircleWelcomeBinding, ::CircleWelcomeScreen)
-    val people = Route("/circles/{id}/people", ::CirclePeopleBinding, ::CirclePeopleScreen)
+    val welcome by provides(Route("/circles/{id}/welcome", ::CircleWelcomeBinding, ::CircleWelcomeScreen))
+    val people by provides(Route("/circles/{id}/people", ::CirclePeopleBinding, ::CirclePeopleScreen))
@@
-    val createPlan = Route("/plans/new", ::PlanComposerBinding, ::PlanComposerScreen)
+    val createPlan by provides(Route("/plans/new", ::PlanComposerBinding, ::PlanComposerScreen))
@@
     init {
-        relay(backend.onboarding)
-        relay(backend.matching)
-        relay(backend.plans)
-        relay(backend.inbox)
         Node(::CircleWelcome)
         Node(::PeopleOfCircle)
```

The clock reads and `CirclesStart(it, backend)` stay as they are. The six relays only brought services into this graph. The nodes here now find those services as `Build`'s outputs, one level up. Discovery opens `welcome`, and Messaging's chat room opens `people` and `createPlan`.

### Messaging: two node outputs, four route outputs, and the security branch moves into a function

Circles' `CircleOutcomes` uses `security`, and You's `DeveloperScreen` uses `pages`. Circles opens `chat` and `person`, and You opens `blocked` and `buds`. The branch that chooses the security node becomes a function that returns the node, so the output names the node it offers. The function returns `Node`, because a value typed as the `ChatContentSecurity` interface would pick the value form of `provides` and add a second port on the graph.

```diff
-class MessagingGraph(parent: Graph, backend: Backend) : Graph(parent, label = "messaging") {
+class MessagingGraph(parent: Graph) : Graph(parent, label = "messaging") {
+    private val backend by input<Backend>()
     private val user = backend.user
     private val chats = backend.origin(Backend::messaging)
-    val service = relay(backend.messaging)
-    val stream = relay(backend.chatStream)
-    val peopleService = relay(backend.people)
-    val groupsService = relay(backend.groups)
-    val pages = Node(::ChatPages)
+    val pages by provides(Node(::ChatPages))
+    val security by provides(chatSecurity(user, chats.live))
     internal val chatRuntime = Graph { ChatGraph(it, user.id, !chats.live || FeatureFlags.bootValue(FeatureFlags.VOICE_MESSAGES)) }
     val sessions get() = chatRuntime.sessions
@@
-    val person = Route("/people/{id}", ::PersonBinding, ::PersonScreen)
+    val person by provides(Route("/people/{id}", ::PersonBinding, ::PersonScreen))
@@
-    val blocked = Route("/settings/blocked", ::BlockedBinding, ::BlockedPeopleScreen)
-    val buds = Route("/buds", ::BudsBinding, ::BudsScreen)
+    val blocked by provides(Route("/settings/blocked", ::BlockedBinding, ::BlockedPeopleScreen))
+    val buds by provides(Route("/buds", ::BudsBinding, ::BudsScreen))
@@
-    val chat = Route("/chats/{id}", ::ChatBinding, ::ChatScreen)
+    val chat by provides(Route("/chats/{id}", ::ChatBinding, ::ChatScreen))
@@
     init {
-        val policy = chatSecuritySession(user.appId, user.id)
-        if (chats.live && PlatformSecurity.available && policy != null) {
-            val resetIdentity = isDevToolsBuild && user.appId == AppEnvironment.DevAppId && SurfaceAppLaunch.resetDeviceIdentity
-            if (resetIdentity) SurfaceAppLaunch.resetDeviceIdentity = false
-            provide(policy.copy(resetIdentity = resetIdentity))
-            provide<PlatformSecretBox>(PlatformSecurity.secretBox("ai.bestbuds.chat.${user.appId}.${user.id}"))
-            provide(requireNotNull(input<AppStorage>().impl.store("Chat.Security")), "security-state")
-            remote(SecureDirectoryApi() over userLink(Servers.MessagingServer, user))
-            Node { SecurityNode(it, input<AnalyticsDeliveryNode>().impl.securitySpans) }
-            Node { ChatSecurity(it, user.id, input<DeviceCapabilities>().impl.notifications?.sealedPreviews, policy) }
-        } else {
-            Node(::UnencryptedChatSecurity)
-        }
-        relay(backend.media)
-        relay(backend.stickers)
         Node(::People)
         Node(::Groups)
@@
 }
 
+private fun Graph.chatSecurity(user: User, live: Boolean): Node {
+    val policy = chatSecuritySession(user.appId, user.id)
+    if (!live || !PlatformSecurity.available || policy == null) return Node(::UnencryptedChatSecurity)
+    val resetIdentity = isDevToolsBuild && user.appId == AppEnvironment.DevAppId && SurfaceAppLaunch.resetDeviceIdentity
+    if (resetIdentity) SurfaceAppLaunch.resetDeviceIdentity = false
+    provide(policy.copy(resetIdentity = resetIdentity))
+    provide<PlatformSecretBox>(PlatformSecurity.secretBox("ai.bestbuds.chat.${user.appId}.${user.id}"))
+    provide(requireNotNull(input<AppStorage>().impl.store("Chat.Security")), "security-state")
+    remote(SecureDirectoryApi() over userLink(Servers.MessagingServer, user))
+    Node { SecurityNode(it, input<AnalyticsDeliveryNode>().impl.securitySpans) }
+    return Node { ChatSecurity(it, user.id, input<DeviceCapabilities>().impl.notifications?.sealedPreviews, policy) }
+}
```

`sessions` stays a getter, because only code reads it. Messaging's own nodes see Chat's output directly, since Chat is one of Messaging's children.

### Chat: one output

```diff
 internal class ChatGraph(parent: Graph, me: String, voice: Boolean) : Graph(parent, label = "chat") {
-    val sessions = Node { ChatSessions(it, me, voice) }
+    val sessions by provides(Node { ChatSessions(it, me, voice) })
```

Chat's nodes find `ChatPages` and `ChatContentSecurity` among Messaging's nodes. They find the messaging, circles and rounds services as outputs of `Build`.

### Discovery: three relays and the parameter go, and one route becomes an output

You opens `compass`.

```diff
-class DiscoveryGraph(parent: Graph, backend: Backend) : Graph(parent, label = "discovery") {
-    val service = relay(backend.trials)
-    val compassService = relay(backend.compass)
-    val matchingService = relay(backend.matching)
+class DiscoveryGraph(parent: Graph) : Graph(parent, label = "discovery") {
     val discovery = Node(::CircleDiscover)
     val chooser = Node(::Discover)
@@
-    val compass = Route("/you/compass", ::CompassBinding, ::CompassScreen)
+    val compass by provides(Route("/you/compass", ::CompassBinding, ::CompassScreen))
```

### Gallery: its root becomes an output

The sign-in screen in Identity opens the developer gallery.

```diff
 class GalleryGraph(parent: Graph) : Graph(parent, label = "gallery") {
-    val route = Root("/dev/gallery", ::SurfaceGalleryBinding, ::SurfaceGalleryScreen)
+    val route by provides(Root("/dev/gallery", ::SurfaceGalleryBinding, ::SurfaceGalleryScreen))
 }
```

### What stays as it is

- Every node class. A consumer such as `private val invites by consumes<Invites>()` in `CirclesHomeScreen` keeps its declaration; only the provider it binds to changes. `CirclesStart(graph, backend)` and `DeveloperScreen(graph, backend)` keep their constructors.
- Every route class, binding and container, `SessionSlot`, the actors, `Graph { }`, `wire()`, `remote`, `serve`, `provide`, `Backend`, `Variant`, `Remote`, `Mocked`, and Koin.

### Totals

`BestBudsApp.kt` loses 72 lines and gains 52, going from 326 to 306 lines. `Backend.kt` loses 24 and gains 28, going from 195 to 199 lines. The counts include the import changes. Neither file makes a relay any more.

## 4. Runtime changes in reaktor-graph-runtime

**`Graph` takes ports the way `Node` does.** It also overrides the two `KeyType` registrations, as `PortNode` does, so a port the graph registers names the graph as its owner. `close()` closes the graph's own ports and forgets its outputs.

```kotlin
open class Graph(
    val parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = parentGraph?.coroutineDispatcher ?: reaktorDefaultDispatcher,
    dependencyAdapter: DependencyAdapter<*> = parentGraph?.diAdapter ?: Feature.Dependency ?: throw DependencyException,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    val dependencies: (DependencyAdapter.ScopeBuilder.() -> Unit) = {},
    builder: Graph.() -> Unit = {}
): PortGraph<Graph, Node>(id, label),
    PortCapability by PortCapabilityImpl(),
    LifecycleCapability,
    DependencyCapability,
    ConcurrencyCapability,
    NavigationCapability
```

**`provides` overloads put inner ports at the edge.** They live in the new `GraphPorts.kt`. The value form is the existing `PortCapability.provides`, which needs no change.

```kotlin
class GraphOutput(val name: String, val ports: List<ProviderPort<Any>>)

fun <N : Node> Graph.provides(node: N) = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, N>> { graph, property ->
    graph.offer(property.name, node, node.providerPorts.flattenedValues())
    ReadOnlyProperty { _, _ -> node }
}

fun <S : Provides<*>> Graph.provides(service: S) = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, S>> { graph, property ->
    val owner = service.port.owner
    graph.offer(property.name, owner, owner.providerPorts.flattenedValues())
    ReadOnlyProperty { _, _ -> service }
}

@JvmName("providesServiceNode")
fun <S> Graph.provides(service: S) where S : Node, S : Provides<*> = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, S>> { graph, property ->
    graph.offer(property.name, service, service.providerPorts.flattenedValues())
    ReadOnlyProperty { _, _ -> service }
}

fun <T : Any> Graph.provides(port: ProviderPort<T>) = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, ProviderPort<T>>> { graph, property ->
    graph.offer(property.name, port)
    ReadOnlyProperty { _, _ -> port }
}

@JvmName("providesOptional")
fun <T : Any> Graph.provides(port: ProviderPort<T>?) = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, ProviderPort<T>?>> { graph, property ->
    port?.let { graph.offer(property.name, it) }
    ReadOnlyProperty { _, _ -> port }
}

operator fun <T : Any> ProviderPort<T>.getValue(thisRef: Any?, property: KProperty<*>): T = impl
```

- **An output is an alias of the inner port.** A consumer outside the graph connects to the inner port itself, so `PortLink` and interceptors keep seeing the real provider.
- **`offer` enforces the subtree rule.** It refuses a port whose owner lies outside the graph's `tree()`, which is W7 for aliases.
- **Two pairs of overloads exist for Kotlin's overload ranking.** A node that is also a service matches the node and service forms, so a third form bounded by both resolves that case. A port has a non-null and a nullable form, because Kotlin cannot rank `ProviderPort<T>?` above the value form when the argument is not null.
- **`getValue`** makes `private val backend by input<Backend>()` work. A file that uses the delegate form imports it from `dev.shibasis.reaktor.graph.core`.

The graph gains three members:

```kotlin
val outputs: List<GraphOutput>
    get() = offered.value + providerPorts.flattenedValues().map { GraphOutput(it.key.key, listOf(it)) }

internal fun children(): List<Graph> = (ownedGraphs.values() + nodes.filterIsInstance<ContainerNode>().flatMap { it.graphs }).distinct()

internal fun visible(): List<ProviderPort<Any>> = (providers() + children().flatMap { child -> child.outputs.flatMap { it.ports } }).distinct()
```

- **`visible()` leaves out a graph's own outputs at its own level.** They are for the levels above, and the nodes inside see the inner ports directly, so nothing is found twice.
- **`children()`** is the owned graphs plus the graphs held by container nodes, the same set `tree()` walks.

**`Relay` and `relay(...)` are deleted.** `Relay.kt` is gone, and `Provides` moves to its own file in the same package, so no import changes. Outside BestBuds no main code called `relay(...)`. The only other reference was `PortLink`, which unwrapped relays to find the contract node:

```diff
-    private val provider: ContractNode<*>? get() = (port.edge?.provider?.owner as? Provides<*>)?.let { if (it is Relay<*>) it.origin else it } as? ContractNode<*>
+    private val provider: ContractNode<*>? get() = port.edge?.provider?.owner as? ContractNode<*>
```

**`input<T>()` leaves a wire when it reaches outside the graph.** The call sites and the return type stay the same.

```kotlin
inline fun <reified T : Any> Graph.input(key: String = ""): ProviderPort<T> = input(Type<T>(), Key(key))

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun <T : Any> Graph.input(type: Type, key: Key): ProviderPort<T> {
    val provider = requireNotNull(findProvider(type, key)) { "Graph '$label' requires a provider for $type / ${key.key}" } as ProviderPort<T>
    val home = (provider.owner as? Node)?.graph ?: provider.owner as? Graph
    if (home !in tree()) registerConsumer<T>(key, type).let { if (!it.isConnected()) connect(it, provider).getOrThrow() }
    return provider
}
```

A read that stays inside the graph leaves no input. `Wires`, for example, reads Config's own deployment, and Config shows no input for it.

**The lookups walk `visible()`.** A tie now names its candidates.

```diff
 fun findProvider(type: Type, key: Key): ProviderPort<Any>? {
     var scope: Graph? = this
     while (scope != null) {
-        val local = scope.providers().filter { it.type == type }
-        val localMatches = if (key.key.isEmpty() && local.size == 1) local else local.filter { it.key == key }
-        require(localMatches.size <= 1) { "Graph '${scope.label}' has ambiguous providers for $type / $key" }
-        localMatches.singleOrNull()?.let { return it }
-        val imported = scope.importedGraphs.values().flatMap { it.providers() }.distinct().filter { it.type == type }
-        val matches = if (key.key.isEmpty() && imported.size == 1) imported else imported.filter { it.key == key }
-        require(matches.size <= 1) { "Graph '${scope.label}' has ambiguous imported providers for $type / $key" }
+        val matches = scope.visible().filter { it.type == type }.matching(key)
+        require(matches.size <= 1) { "Graph '${scope.label}' has ambiguous providers for $type / $key: ${matches.joinToString { it.owner.toString() }}" }
         matches.singleOrNull()?.let { return it }
         scope = scope.parentGraph
     }
     return null
 }
```

In `wire()`, the per-ancestor candidate list changes in one place. Exits go through the same line, so a target route must be an output.

```diff
-                    .map { scope -> reach.getOrPut(scope) { scope.tree().flatMap { it.providers() }.distinct() }.filter { it.type == consumer.type }.pick(consumer.key) }
+                    .map { scope -> reach.getOrPut(scope) { scope.visible() }.filter { it.type == consumer.type }.pick(consumer.key) }
```

`findOnlyProvider` walks `visible()` the same way. `dependsOn` and `importedGraphs` are deleted.

**A refusal names the graph that must offer.** When a port stays unconnected, `requireFullyWired` looks for providers of its type anywhere in the app. For each one it names the graphs that must offer it, from the inside out. This is the message from `GraphWireTest`, with node ids shortened:

```text
Graph 'feed' has unconnected consumer ports:
- key='clock' type='Clock' owner='[Reader] …'
  [Clock] … provides it inside 'profile'; offer it with `by provides` from 'profile'
```

**`GraphShape` carries outputs and inputs.** `ScopeShape` gains `outputs` and `inputs`, both lists of `ScopePortShape(name, type, node, port)`. An output row names the inner node and port it aliases. An input row names the outside node and port that `input<T>()` reached. The definition digest maps those node ids to definition ids, and a graph with no ports serializes as before, so its digest does not change.

## 5. What the map gets

A graph can now be drawn as a card with ports, which is the collapsed card the depth view needs.
- **Output rows** are the graph's `by provides` properties, named by the property, from `ScopeShape.outputs`.
- **Input rows** are its `input<T>()` reads that reach outside it, from `ScopeShape.inputs`, plus the wires that leave it, grouped by the graph they reach. The map derives the second kind from `wires`.

No author declares anything extra for the map. When the card expands, the same wires re-attach to the inner nodes. The earlier depth-view decisions still stand: island roots at depth 0, one row per outside port, and the opening depth.

## 6. How it was built and checked

The plan had a dry-run step that would report, under the old lookup, every binding the new walk would refuse. One throwaway report test did that job before the switch. It listed every binding whose provider sat outside the consumer's own chain of graphs.
- **The BestBuds app**, with mock backends and one session, had 319 such bindings. Each now goes through an output, or it went away with its relay. On the new runtime the same report finds 180 bindings that cross a graph edge, all through outputs.
- **Hangar's desktop graph** had 99. They are all explicit `connect(...)` calls from panes that take the kernel graph as a constructor argument. The walk does not touch explicit connections, and Hangar's code did not change.
- **Workers, Manna, reaktor-auth, reaktor-cluster and the server** build flat graphs or call only `autoWire`. With no outputs, each level's `visible()` is its own nodes, which is the old behaviour.

The report test was deleted afterwards. `requireFullyWired` now names the graph that must offer a hidden provider, so it does the same job from now on.

Tests changed with the runtime:
- `GraphWireTest`: the sibling test now offers `clock` and the profile route from a `ProfileGraph` class. A new test shows that what a graph does not offer stays inside, and the refusal names that graph. The tie test builds the tie from two offering graphs.
- `ContractNodeTest`: the relay test becomes two tests. One sibling graph calls a service that another graph offers, and the wire and the output row point at the served node. A service the graph does not offer stays unreachable.
- `GraphLifecycleIntegrationTest` builds its config as a small class that offers its provider, in place of `root.dependsOn(config)`. It also checks the new output and input rows.
- `WiringTest` gives identity and config small classes that offer `users` and `greeting`.

Tests changed in BestBuds:
- `FeatureGraphTest` changes the assertions shown below and two tests, and gains a one-line helper.
- `BuildGraphTest` builds each `Build` inside a graph that provides the deployment and the mock world, and reads `build.remote` and `build.mocked`.
- `SessionFixture` drops `graph.dependsOn(identity)`.
- `AppArchitectureVitalsTest` checks for `override val messaging by choose(Backend::messaging)` in place of `relay(backend.messaging)`.
- Hangar's kernel-client tests, eight places in seven files, put their pane inside the kernel services graph in place of `parent.dependsOn(services)`. Those services are made lazily, so they cannot be outputs, and the panes only need to call the kernel.
- `ChatRuntimeGraphTest` does not change.

The reaktor-app skill's lookup section now describes the walk, `by provides`, `input<T>()`, the refusal message and route outputs.

Results on 6 November:
- The reaktor-graph-runtime and reaktor-graph JVM tests pass.
- The BestBuds app's JVM tests pass 320 of 322. The two failures in `AppArchitectureVitalsTest` were there before this change: one reads `NotificationWorkerAuth.kt`, which an earlier commit deleted, and one checks the Worker auth sources.
- The app also compiles for JavaScript, which is what the Workers ship.
- In Hangar, `DesktopWorkbenchGraphTest` passes 19 of 19. A focused run of 112 tests that build Hangar's graphs passes 109. The three failures most likely come from other people's uncommitted work. `DesktopGraphCoverageTest`'s Cloud frame depends on the map's internal-wire filter, which has an uncommitted change in `reaktor-blueprint`'s `AppGraph.kt`. The two `ComputeWorkspaceTest` layout checks cover a Cloud pane that also has uncommitted changes.

`Build`'s services are typed `Provides<T>`, which has no `id`, so `FeatureGraphTest` gains a one-line helper that names the node behind a service:

```kotlin
private val Provides<*>.node get() = port.owner as Node
```

```diff
-        assertTrue(wires.any { it.provider == session.build.circles.id.toString() })
-        assertTrue(wires.any { it.provider == session.messaging.groupsService.id.toString() })
-        assertTrue(wires.any { it.provider == session.messaging.service.id.toString() })
+        assertTrue(wires.any { it.provider == session.build.circles.node.id.toString() })
+        assertTrue(wires.any { it.provider == session.build.groups.node.id.toString() })
+        assertTrue(wires.any { it.provider == session.build.messaging.node.id.toString() })
@@
-            it.edge?.provider?.owner === session.discovery.service
+            it.edge?.provider?.owner === session.build.trials.node
@@
-                assertTrue(snapshot.wires.any { it.provider == session.build.messaging.id.toString() && it.consumer == session.messaging.service.id.toString() })
+                assertTrue(snapshot.wires.any { it.provider == session.build.messaging.node.id.toString() && it.consumer == session.messaging.sessions.id.toString() })
+                assertTrue(snapshot.scopes.single { it.id == session.messaging.id.toString() }.outputs.any { it.name == "chat" && it.node == session.messaging.chat.id.toString() })
```

The tab test used to require a service node inside each feature graph. The services now live in `Build`'s variants, so the test checks that each feature calls one of them:

```diff
-    @Test fun tabGraphsOwnTheirApisStateScreensAndRoutes() = runGraphTest {
+    @Test fun tabGraphsOwnTheirStateScreensAndRoutesAndCallTheVariantsBuildChose() = runGraphTest {
@@
-            assertTrue(feature.nodes.any { it.kind == NodeKind.Service }, feature.label)
+            assertTrue(feature.nodes.any { node -> node.consumerPorts.flattenedValues().any { (it.edge?.provider?.owner as? Node)?.graph in session.build.variants } }, feature.label)
```

`aFeatureWiresStraightToOneVariant` can no longer hand Discovery a backend. Instead it mounts Discovery under the variant. This uses the paper's mount-anywhere property: the nearest provider wins, so the three trial operations bind to the mocked variant and the rest binds further up.

```diff
         val mocked = Mocked(session, testUser(), world)
-        val discovery = DiscoveryGraph(session, mocked)
+        val trials = mocked.trials
+        val discovery = mocked.Graph(::DiscoveryGraph)
         try {
             discovery.wire()
-            assertSame(mocked.trials, discovery.service.origin)
-            assertSame(mocked.trials.port.impl, discovery.service.port.impl)
+            assertEquals(3, discovery.chooser.consumerPorts.flattenedValues().count { it.edge?.provider?.owner === trials.node })
         } finally { discovery.close(); mocked.close() }
```

## 7. Decisions

1. **Route exits are outputs.** The owner chose this on 6 November. The nine routes are Messaging's `chat`, `person`, `blocked` and `buds`; Circles' `people`, `createPlan` and `welcome`; Discovery's `compass`; and Gallery's `route`.
2. **Declared inputs stay open.** Once a graph has ports, `val x by consumes<T>()` compiles on a graph.
   - Nothing uses that yet. A graph reads what it needs while building with `input`, and its other inputs come from its nodes' wires.
   - Graph-level `consumes` would be the spelling for declared inputs: a port its nodes bind to, bound outward when the graph wires. That is the paper's require port and the stricter option from the earlier Scope proposal. Its cost is a list of inputs at the top of each graph.
3. **The facade went first.** This is built on today's runtime. Kernel v0 can keep the same surface, `Graph`, `by provides` and `input<T>()`, when it arrives.
