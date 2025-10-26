package dev.shibasis.reaktor.navigation.graph

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.DependencyCapability
import dev.shibasis.reaktor.navigation.capabilities.DependencyCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.Lifecycle
import dev.shibasis.reaktor.navigation.capabilities.LifecycleCapability
import dev.shibasis.reaktor.navigation.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.ReaktorScope
import dev.shibasis.reaktor.navigation.capabilities.ScopedDependency
import dev.shibasis.reaktor.navigation.capabilities.Unique
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
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

/profiles
/profiles/edit
/profiles/create
/profiles/{id}

/home/chats

/home/campaigns
/home/campaigns/current
/home/campaigns/discover

/home/events
/home/events/private
/home/events/public
/home/events/create

/home/friends


/chats/{id}
/events/{id}
/friends/{id}
/campaigns/{id}

// special routes, etc
/modal

val BestBuds = Graph { it ->
    route("/") connect LoginView
    route("/profile") connect ProfileView



}



Requirements:
1. Support compose and react (native too)
2. Handle Adaptive Layouts
3. Allow modularity by grouping multiple screens together
6. Loading mechanism to gracefully handle APIs
7. First class integration with Interactors which handle state + actions within, and provide a mechanism.
9. deep links, unified across web and app.
10. One Navigator
11. Event bubbling
12. Make it closer to the theory (i.e. PushdownAutomata) and make everything testable.
13. We need to build the navigation graph, and it should be possible to see it and interactions using react flow, etc.
14. As far as possible, use interfaces instead of classes.
17. Handle when a screen pop happens, you may need to refresh.
19. need to be serializable so that we can restore.
20. Building it in compose runtime, we can use custom appliers with a input mutable state.
    deep-links would be trivial by just setting the mutable state and the entire tree would recompose.
    we should also give functionality to add more root states / ability to recompose part of the tree.
21. Minimise number of concepts needed for a developer to build apps with this
22. Think something like Scenes abstraction from compose jetpack navigation 3
23. Transitions / Animations
27. Restoration ?
28. Uber RIBs, Permission and other guards,


Example Layouts:

Bottom Navigation
Tabbed Navigation
Chrome Tab Style
Single Container
Drawer Container
Modal Container (Bottom / AlertDialog)
Simultaneous (example: List Detail)
Adaptive Containers
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

    fun attach(node: Node): Result<Unit> {
        if (children.containsKey(node.id)) {
            Logger.w("Node ${node.id} is already attached. Ignoring.")
            return fail(ConcurrentModificationException())
        }

        children[node.id] = node

        node.transition(Lifecycle.Restoring)
        node.restore()
        node.transition(Lifecycle.Attached)

        return succeed(Unit)
    }

    fun detach(node: Node) {
        children.remove(node.id)?.let {
            it.transition(Lifecycle.Saving)
            it.save()
            it.transition(Lifecycle.Destroyed)
            it.close()
        }
    }

    override fun close() {
        children.values.toList().forEach { detach(it) }
        invoke<LifecycleCapability> { close() }
        invoke<DependencyCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
    }
}
