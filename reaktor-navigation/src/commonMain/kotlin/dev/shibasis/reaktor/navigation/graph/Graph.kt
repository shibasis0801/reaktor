package dev.shibasis.reaktor.navigation.graph

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.navigation.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.navigation.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.DependencyCapability
import dev.shibasis.reaktor.navigation.capabilities.DependencyCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.Lifecycle
import dev.shibasis.reaktor.navigation.capabilities.LifecycleCapability
import dev.shibasis.reaktor.navigation.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.ReaktorScope
import dev.shibasis.reaktor.navigation.capabilities.ScopedDependency
import dev.shibasis.reaktor.navigation.capabilities.Unique
import dev.shibasis.reaktor.navigation.capabilities.invoke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.js.JsExport
import kotlin.uuid.Uuid

/*

A reactive graph library for application nodes inspired from Uber/RIBs and Actor frameworks like Akka/Erlang.
Large applications for mobile and servers can be decomposed into fully functional modules and visitors can orchestrate stuff on it.


Graph, Node, Edge (UI & Server)
-> dependencies & structured concurrency
-> observable, reactive graph state reconciliation
-> visitor pattern
-> cycle detection
-> unreachable detection
-> DAG validation & topological sort
-> Visitor for JSON / React-flow

RouteNode
-> Route (pattern, input, output)
-> View, Interaction used within - platform
(no containers, just nested views) - independent
-> Navigation Edges for visual, auth checks
-> Navigation Visitor
-> Feature Toggles / guards / etc

ApiNode
-> App (input, output)
-> Resilience4j - circuit breaker, retry, bulkhead, etc
-> auth checks
-> Execution Visitor

This needs to be concurrent to make use of all cores properly.


Sample usage

/

/profile
/profile/edit
/profile/create

/home/chats
/home/chats/{id}

/home/campaigns
/home/campaigns/current
/home/campaigns/discover

/home/events
/home/events/private
/home/events/public
/home/events/create

/home/friends

val BestBudsSwitch = container<BlankContainer> {
    errorScreen(appErrorScreen)

    startEntry {
        startScreen(startScreen)
    }

    entry("profile") {
        errorScreen(profileErrorScreen)
        startScreen("edit", editProfileScreen)
        screen("create", createProfileScreen)
    }

    container<BottomBarContainer>("home") {
        startEntry("chats") {
            icon(Icons.AutoMirrored.Filled.Chat)
            startScreen(chatsScreen)
            container<SingleScreenContainer>("{id}") {
                clearTop = true
                startScreen(chatScreen)
            }
        }

        entry("campaigns") {
            icon(Icons.Filled.Campaign)
            container<TabbedContainer> {
                startEntry("current") {
                    startScreen(currentScreen)
                }
                entry("discover") {
                    startScreen(discoverScreen)
                }
            }
        }

        entry("events") {
            icon(Icons.Filled.Event)
            container<TabbedContainer> {
                startEntry("private") {
                    startScreen(privateEventsScreen)
                }
                entry("public") {
                    startScreen(publicEventsScreen)
                }
                entry("create") {
                    startScreen(createEventScreen)
                }
            }
        }

        entry("friends") {
            icon(Icons.Filled.People)
            startScreen(friendsScreen)
            screen()
        }
    }
}

Requirements:
1. Support compose and react (native too)
2. Handle Adaptive Layouts
3. Allow modularity by grouping multiple screens together - Switch
4. A chrome-less (no UI of its own) Stack responsible for only navigating front and back.
5. A container which can hold one or more stacks
6. Loading mechanism to gracefully handle APIs
7. First class integration with Interactors which handle state + actions within, and provide a mechanism.
8. Container Interactors ? to be able to do something in the container (example bottom nav change should do something in appbar)
9. deep links, unified across web and app.
10. One Navigator
11. Event bubbling
12. Make it closer to the theory (i.e. PushdownAutomata) and make everything testable.
13. We need to build the navigation graph, and it should be possible to see it and interactions using react flow, etc.
14. As far as possible, use interfaces instead of classes.
15. Containers and Switches can contain containers and switches respectively.
16. At a single time, there must be one Container occupying the entire screen.
17. Handle when a screen pop happens, you may need to refresh.
18. LayoutParams somewhere
19. The nav-graph and back-stack need to be serializable so that we can restore.
20. Building it in compose runtime, we can use custom appliers with a input mutable state.
    deep-links would be trivial by just setting the mutable state and the entire tree would recompose.
    we should also give functionality to add more root states / ability to recompose part of the tree.
21. Minimise number of concepts needed for a developer to build apps with this
22. Think something like Scenes abstraction from compose jetpack navigation 3
23. Transitions / Animations
24. A Pod gives you a Switch
25. Basic container will have basic entry/stack addition logic. You need to implement methods for adding icons, etc through your EntryBuilder lambda.
26. Lifecycles ? what if another screen is rendered ?
27. Restoration ?
28. Uber RIBs, Permission and other guards,


Example Containers:

Bottom Navigation
Tabbed Navigation
Chrome Tab Style
Single Container
Drawer Container
Modal Container (Bottom / AlertDialog)
Simultaneous (example: List Detail)
Adaptive Containers

It should be very easy to make any containers.

I am building a comprehensive navigation framework that should work with both compose and react.

My idea was to wrap UI in a Screen, but without a Render method.

ComposeScreens and ReactScreens will instead rely on the MutableStateFlow to render when data is updated.

A switch is just a group of routes, flattened for efficiency

A stack is the primitive to be used along with switch by a Container.

The container is the orchestrator and will be responsible for all sorts of layouts.

There will be only one Navigator for the entire app which must leverage other primitives
to perform proper navigation

The Renderer will be similar to Screen from the perspective of having one implementation
for React and one for Compose.

The design/implementation is incomplete, and before writing code I need guidance.

The framework needs to grow into something production ready for scalable apps
but requiring a single mental model so that we can replace react-router and compose-navigation
with this one unified framework

*/



@JsExport
open class Graph(
    parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    override val id: Uuid = Uuid.random(),
    val dependency: ScopedDependency = {}
):
    Unique,
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(
        id.toString(),
        ReaktorScope.Graph,
        parentGraph?.koinScope,
        dependency
    ),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        parentGraph?.coroutineScope?.coroutineContext,
        dispatcher
    )
{
    private val children = linkedMapOf<Uuid, Node>()

    fun <N : Node> attach(builder: (parent: Graph) -> N): N {
        val node = builder(this)
        if (children.containsKey(node.id)) {
            Logger.w("Node ${node.id} is already attached. Ignoring.")
            return children[node.id] as N
        }

        Logger.v("Attaching Node: ${node::class.simpleName} (${node.id})")
        children[node.id] = node
        node.transition(Lifecycle.Restoring)
        node.restore()
        node.transition(Lifecycle.Attached)
        return node
    }

    fun detach(node: Node) {
        children.remove(node.id)?.let {
            Logger.v("Detaching Node: ${it::class.simpleName} (${it.id})")
            it.transition(Lifecycle.Saving)
            it.save()
            it.transition(Lifecycle.Destroyed)
            it.close()
        }
    }

    override fun close() {
        Logger.v("Closing Graph: ${this::class.simpleName} ($id)")
        children.values.toList().forEach { detach(it) } // Detach all children
        invoke<LifecycleCapability> { close() }
        invoke<DependencyCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
    }
}
